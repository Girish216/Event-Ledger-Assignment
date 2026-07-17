package com.eventledger.eventgateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
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

import java.math.BigDecimal;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EventControllerWireMockTest {

    private static WireMockServer wireMock;

    @DynamicPropertySource
    static void accountServiceUrl(DynamicPropertyRegistry registry) {
        wireMock = new WireMockServer(0);
        wireMock.start();
        registry.add("account-service.base-url", () -> "http://localhost:" + wireMock.port());
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @LocalServerPort
    private int port;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void resetStubs() {
        wireMock.resetAll();
        wireMock.stubFor(WireMock.post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"eventId":"placeholder","accountId":"placeholder","type":"CREDIT",
                                 "amount":10.00,"currency":"USD","balance":10.00,"duplicate":false}
                                """)));
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private ResponseEntity<String> postEvent(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity(baseUrl() + "/events", new HttpEntity<>(json, headers), String.class);
    }

    private String eventJson(String eventId, String accountId, String type, String amount, String timestamp) {
        return """
                {"eventId":"%s","accountId":"%s","type":"%s","amount":%s,"currency":"USD","eventTimestamp":"%s"}
                """.formatted(eventId, accountId, type, amount, timestamp);
    }

    @Test
    void submitsNewEventAndAppliesItExactlyOnce() {
        String eventId = "evt-" + UUID.randomUUID();
        ResponseEntity<String> response = postEvent(
                eventJson(eventId, "acct-1", "CREDIT", "10.00", "2026-05-15T10:00:00Z"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        wireMock.verify(1, postRequestedFor(urlPathMatching("/accounts/.*/transactions")));
    }

    @Test
    void duplicateSubmissionDoesNotCallAccountServiceAgain() {
        String eventId = "evt-" + UUID.randomUUID();
        String body = eventJson(eventId, "acct-2", "CREDIT", "10.00", "2026-05-15T10:00:00Z");

        ResponseEntity<String> first = postEvent(body);
        ResponseEntity<String> second = postEvent(body);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        wireMock.verify(1, postRequestedFor(urlPathMatching("/accounts/.*/transactions")));
    }

    @Test
    void rejectsNonPositiveAmount() {
        ResponseEntity<String> response = postEvent(
                eventJson("evt-bad", "acct-3", "CREDIT", "-5.00", "2026-05-15T10:00:00Z"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        wireMock.verify(0, postRequestedFor(urlPathMatching("/accounts/.*/transactions")));
    }

    @Test
    void rejectsUnknownEventType() {
        ResponseEntity<String> response = postEvent(
                eventJson("evt-bad-type", "acct-3", "REFUND", "5.00", "2026-05-15T10:00:00Z"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsMissingAccountId() {
        String json = """
                {"eventId":"evt-missing","type":"CREDIT","amount":5.00,"currency":"USD","eventTimestamp":"2026-05-15T10:00:00Z"}
                """;
        ResponseEntity<String> response = postEvent(json);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void getUnknownEventReturns404() {
        ResponseEntity<String> response = restTemplate.getForEntity(baseUrl() + "/events/does-not-exist", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listsEventsInChronologicalOrderRegardlessOfSubmissionOrder() throws Exception {
        String accountId = "acct-" + UUID.randomUUID();
        postEvent(eventJson("evt-late", accountId, "CREDIT", "10.00", "2026-05-15T18:00:00Z"));
        postEvent(eventJson("evt-early", accountId, "CREDIT", "5.00", "2026-05-15T06:00:00Z"));

        ResponseEntity<String> response = restTemplate.getForEntity(
                baseUrl() + "/events?account=" + accountId, String.class);

        JsonNode events = mapper.readTree(response.getBody());
        assertThat(events.get(0).get("eventId").asText()).isEqualTo("evt-early");
        assertThat(events.get(1).get("eventId").asText()).isEqualTo("evt-late");
    }

    @Test
    void balanceProxyReturnsAccountServiceResponse() throws Exception {
        wireMock.stubFor(WireMock.get(urlPathMatching("/accounts/.*/balance"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"accountId":"acct-9","balance":42.50,"currency":"USD","asOf":"2026-05-15T10:00:00Z"}
                                """)));

        ResponseEntity<String> response = restTemplate.getForEntity(baseUrl() + "/accounts/acct-9/balance", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = mapper.readTree(response.getBody());
        assertThat(body.get("balance").decimalValue()).isEqualByComparingTo(new BigDecimal("42.50"));
    }
}
