package com.eventledger.eventgateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ResiliencyTest {

    private static WireMockServer wireMock;

    @DynamicPropertySource
    static void accountServiceUrl(DynamicPropertyRegistry registry) {
        wireMock = new WireMockServer(0);
        wireMock.start();
        registry.add("account-service.base-url", () -> "http://localhost:" + wireMock.port());
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @LocalServerPort
    private int port;

    @BeforeEach
    void resetState() {
        wireMock.resetAll();
        circuitBreakerRegistry.circuitBreaker("accountService").reset();
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private ResponseEntity<String> postEvent(String eventId, String accountId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String json = """
                {"eventId":"%s","accountId":"%s","type":"CREDIT","amount":10.00,"currency":"USD","eventTimestamp":"2026-05-15T10:00:00Z"}
                """.formatted(eventId, accountId);
        return restTemplate.postForEntity(baseUrl() + "/events", new HttpEntity<>(json, headers), String.class);
    }

    @Test
    void postEventsReturns503WhenAccountServiceIsDown() {
        wireMock.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(500)));

        ResponseEntity<String> response = postEvent("evt-" + UUID.randomUUID(), "acct-down");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void localReadsStillWorkWhenAccountServiceIsDown() {
        wireMock.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(500)));

        String eventId = "evt-" + UUID.randomUUID();
        postEvent(eventId, "acct-down-2");

        ResponseEntity<String> byId = restTemplate.getForEntity(baseUrl() + "/events/" + eventId, String.class);
        assertThat(byId.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(byId.getBody()).contains("\"status\":\"FAILED\"");

        ResponseEntity<String> byAccount = restTemplate.getForEntity(
                baseUrl() + "/events?account=acct-down-2", String.class);
        assertThat(byAccount.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(byAccount.getBody()).contains(eventId);
    }

    @Test
    void balanceQueryReturns503WhenAccountServiceIsDown() {
        wireMock.stubFor(WireMock.get(urlPathMatching("/accounts/.*/balance"))
                .willReturn(aResponse().withStatus(500)));

        ResponseEntity<String> response = restTemplate.getForEntity(
                baseUrl() + "/accounts/acct-down-3/balance", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void circuitBreakerOpensAndStopsCallingAFailingDownstream() {
        wireMock.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(500)));

        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker("accountService");

        // drive enough failures to exceed minimumNumberOfCalls / failureRateThreshold
        for (int i = 0; i < 6; i++) {
            postEvent("evt-cb-" + i, "acct-cb");
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int callsBeforeExtra = wireMock.getServeEvents().getServeEvents().size();

        // further calls should fail fast without reaching the (still failing) downstream
        ResponseEntity<String> response = postEvent("evt-cb-extra", "acct-cb");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        int callsAfterExtra = wireMock.getServeEvents().getServeEvents().size();
        assertThat(callsAfterExtra).isEqualTo(callsBeforeExtra);
    }
}
