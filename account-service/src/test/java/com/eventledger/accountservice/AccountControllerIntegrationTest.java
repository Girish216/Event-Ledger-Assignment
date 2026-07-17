package com.eventledger.accountservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountControllerIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @LocalServerPort
    private int port;

    private final ObjectMapper mapper = new ObjectMapper();

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private ResponseEntity<String> postTransaction(String accountId, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity(baseUrl() + "/accounts/" + accountId + "/transactions",
                new HttpEntity<>(json, headers), String.class);
    }

    private String transactionJson(String eventId, String type, String amount, String timestamp) {
        return """
                {"eventId":"%s","type":"%s","amount":%s,"currency":"USD","eventTimestamp":"%s"}
                """.formatted(eventId, type, amount, timestamp);
    }

    @Test
    void appliesNewTransactionAndUpdatesBalance() throws Exception {
        String accountId = "acct-" + UUID.randomUUID();

        ResponseEntity<String> response = postTransaction(accountId,
                transactionJson("evt-1", "CREDIT", "100.00", "2026-05-15T10:00:00Z"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = mapper.readTree(response.getBody());
        assertThat(body.get("duplicate").asBoolean()).isFalse();
        assertThat(body.get("balance").decimalValue()).isEqualByComparingTo("100.00");
    }

    @Test
    void duplicateEventIdDoesNotChangeBalanceAndReturns200() throws Exception {
        String accountId = "acct-" + UUID.randomUUID();
        postTransaction(accountId, transactionJson("evt-dup", "CREDIT", "50.00", "2026-05-15T10:00:00Z"));

        ResponseEntity<String> second = postTransaction(accountId,
                transactionJson("evt-dup", "CREDIT", "50.00", "2026-05-15T10:00:00Z"));

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = mapper.readTree(second.getBody());
        assertThat(body.get("duplicate").asBoolean()).isTrue();
        assertThat(body.get("balance").decimalValue()).isEqualByComparingTo("50.00");

        ResponseEntity<String> balance = restTemplate.getForEntity(
                baseUrl() + "/accounts/" + accountId + "/balance", String.class);
        JsonNode balanceBody = mapper.readTree(balance.getBody());
        assertThat(balanceBody.get("balance").decimalValue()).isEqualByComparingTo("50.00");
    }

    @Test
    void balanceIsCorrectRegardlessOfArrivalOrder() throws Exception {
        String accountId = "acct-" + UUID.randomUUID();

        // later event (by eventTimestamp) arrives first
        postTransaction(accountId, transactionJson("evt-later", "CREDIT", "200.00", "2026-05-15T14:00:00Z"));
        // earlier event arrives second
        postTransaction(accountId, transactionJson("evt-earlier", "DEBIT", "30.00", "2026-05-15T09:00:00Z"));

        ResponseEntity<String> balance = restTemplate.getForEntity(
                baseUrl() + "/accounts/" + accountId + "/balance", String.class);
        JsonNode body = mapper.readTree(balance.getBody());
        assertThat(body.get("balance").decimalValue()).isEqualByComparingTo("170.00");

        ResponseEntity<String> account = restTemplate.getForEntity(
                baseUrl() + "/accounts/" + accountId, String.class);
        JsonNode transactions = mapper.readTree(account.getBody()).get("recentTransactions");
        // most-recent-first: evt-later (14:00) should come before evt-earlier (09:00)
        assertThat(transactions.get(0).get("eventId").asText()).isEqualTo("evt-later");
        assertThat(transactions.get(1).get("eventId").asText()).isEqualTo("evt-earlier");
    }

    @Test
    void rejectsNonPositiveAmount() {
        ResponseEntity<String> response = postTransaction("acct-x",
                transactionJson("evt-bad-amount", "CREDIT", "0", "2026-05-15T10:00:00Z"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsUnknownTransactionType() {
        ResponseEntity<String> response = postTransaction("acct-x",
                transactionJson("evt-bad-type", "TRANSFER", "10.00", "2026-05-15T10:00:00Z"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsMissingRequiredField() {
        String json = """
                {"type":"CREDIT","amount":10.00,"currency":"USD","eventTimestamp":"2026-05-15T10:00:00Z"}
                """;
        ResponseEntity<String> response = postTransaction("acct-x", json);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void balanceForUnknownAccountReturns404() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                baseUrl() + "/accounts/does-not-exist/balance", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
