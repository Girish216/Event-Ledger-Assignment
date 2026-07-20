package com.eventledger.eventgateway;

import com.eventledger.accountservice.AccountServiceApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the real Gateway -> Account Service call over HTTP, with no mocking on either
 * side: both Spring Boot applications are started as separate contexts, each against its
 * own in-memory database, exactly like they would run as separate processes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EndToEndIntegrationTest {

    private static ConfigurableApplicationContext accountServiceContext;

    @DynamicPropertySource
    static void startRealAccountService(DynamicPropertyRegistry registry) {
        accountServiceContext = new SpringApplicationBuilder(AccountServiceApplication.class)
                .properties(
                        "server.port=0",
                        "spring.datasource.url=jdbc:h2:mem:e2e-accountdb;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000"
                )
                .run();

        String port = accountServiceContext.getEnvironment().getProperty("local.server.port");
        registry.add("account-service.base-url", () -> "http://localhost:" + port);
    }

    @AfterAll
    static void stopAccountService() {
        if (accountServiceContext != null) {
            accountServiceContext.close();
        }
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @LocalServerPort
    private int gatewayPort;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void eventSubmittedToGatewayIsAppliedByTheRealAccountService() throws Exception {
        String eventId = "evt-" + UUID.randomUUID();
        String accountId = "acct-e2e";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String json = """
                {"eventId":"%s","accountId":"%s","type":"CREDIT","amount":88.00,"currency":"USD","eventTimestamp":"2026-05-15T10:00:00Z"}
                """.formatted(eventId, accountId);

        ResponseEntity<String> submitResponse = restTemplate.postForEntity(
                "http://localhost:" + gatewayPort + "/events", new HttpEntity<>(json, headers), String.class);

        assertThat(submitResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode submitted = mapper.readTree(submitResponse.getBody());
        assertThat(submitted.get("status").asText()).isEqualTo("PROCESSED");

        ResponseEntity<String> balanceResponse = restTemplate.getForEntity(
                "http://localhost:" + gatewayPort + "/accounts/" + accountId + "/balance", String.class);

        assertThat(balanceResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode balance = mapper.readTree(balanceResponse.getBody());
        assertThat(balance.get("balance").decimalValue()).isEqualByComparingTo("88.00");

        // duplicate resubmission must not double-apply on the real Account Service either
        ResponseEntity<String> duplicateResponse = restTemplate.postForEntity(
                "http://localhost:" + gatewayPort + "/events", new HttpEntity<>(json, headers), String.class);
        assertThat(duplicateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> balanceAfterDuplicate = restTemplate.getForEntity(
                "http://localhost:" + gatewayPort + "/accounts/" + accountId + "/balance", String.class);
        JsonNode balanceAfter = mapper.readTree(balanceAfterDuplicate.getBody());
        assertThat(balanceAfter.get("balance").decimalValue()).isEqualByComparingTo("88.00");
    }

    @Test
    void concurrentEventsAgainstTheSameAccountDoNotLoseUpdatesOrFail() throws Exception {
        String accountId = "acct-e2e-concurrent-" + UUID.randomUUID();
        int concurrentEvents = 25;
        BigDecimal amountEach = new BigDecimal("10.00");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ExecutorService pool = Executors.newFixedThreadPool(concurrentEvents);
        try {
            List<Callable<ResponseEntity<String>>> tasks = new ArrayList<>();
            for (int i = 0; i < concurrentEvents; i++) {
                String eventId = "evt-e2e-concurrent-" + i + "-" + UUID.randomUUID();
                String json = """
                        {"eventId":"%s","accountId":"%s","type":"CREDIT","amount":%s,"currency":"USD","eventTimestamp":"2026-05-15T10:00:00Z"}
                        """.formatted(eventId, accountId, amountEach.toPlainString());
                tasks.add(() -> restTemplate.postForEntity(
                        "http://localhost:" + gatewayPort + "/events", new HttpEntity<>(json, headers), String.class));
            }

            List<Future<ResponseEntity<String>>> futures = pool.invokeAll(tasks);
            for (Future<ResponseEntity<String>> future : futures) {
                ResponseEntity<String> response = future.get(15, TimeUnit.SECONDS);
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            }
        } finally {
            pool.shutdown();
        }

        ResponseEntity<String> balanceResponse = restTemplate.getForEntity(
                "http://localhost:" + gatewayPort + "/accounts/" + accountId + "/balance", String.class);
        JsonNode balance = mapper.readTree(balanceResponse.getBody());
        BigDecimal expected = amountEach.multiply(new BigDecimal(concurrentEvents));
        assertThat(balance.get("balance").decimalValue()).isEqualByComparingTo(expected);
    }
}
