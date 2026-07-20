package com.eventledger.accountservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

    @Test
    void duplicateEventIdWithDifferentPayloadReturns409() throws Exception {
        String accountId = "acct-" + UUID.randomUUID();
        String eventId = "evt-" + UUID.randomUUID();

        ResponseEntity<String> first = postTransaction(accountId,
                transactionJson(eventId, "CREDIT", "100.00", "2026-05-15T10:00:00Z"));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> changedAmount = postTransaction(accountId,
                transactionJson(eventId, "CREDIT", "150.00", "2026-05-15T10:00:00Z"));
        assertThat(changedAmount.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<String> changedType = postTransaction(accountId,
                transactionJson(eventId, "DEBIT", "100.00", "2026-05-15T10:00:00Z"));
        assertThat(changedType.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // an exact resubmission of the original payload must still be treated as a true duplicate
        ResponseEntity<String> exactDuplicate = postTransaction(accountId,
                transactionJson(eventId, "CREDIT", "100.00", "2026-05-15T10:00:00Z"));
        assertThat(exactDuplicate.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void rejectsDifferentCurrencyForExistingAccount() throws Exception {
        String accountId = "acct-" + UUID.randomUUID();
        postTransaction(accountId, transactionJson("evt-" + UUID.randomUUID(), "CREDIT", "100.00", "2026-05-15T10:00:00Z"));

        String eurJson = """
                {"eventId":"%s","type":"CREDIT","amount":50.00,"currency":"EUR","eventTimestamp":"2026-05-15T11:00:00Z"}
                """.formatted("evt-" + UUID.randomUUID());
        ResponseEntity<String> response = postTransaction(accountId, eurJson);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"US", "Ud", "SD", "U$D", "usd", "XXXX"})
    void rejectsInvalidCurrencyFormat(String invalidCurrency) {
        String json = """
                {"eventId":"%s","type":"CREDIT","amount":10.00,"currency":"%s","eventTimestamp":"2026-05-15T10:00:00Z"}
                """.formatted("evt-" + UUID.randomUUID(), invalidCurrency);
        ResponseEntity<String> response = postTransaction("acct-invalid-currency", json);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsAmountWithMoreThanTwoDecimalPlaces() {
        ResponseEntity<String> response = postTransaction("acct-precision",
                transactionJson("evt-" + UUID.randomUUID(), "CREDIT", "0.001", "2026-05-15T10:00:00Z"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsAmountThatExceedsSupportedMaximum() {
        String hugeAmount = "9".repeat(20) + ".99";
        ResponseEntity<String> response = postTransaction("acct-oversized",
                transactionJson("evt-" + UUID.randomUUID(), "CREDIT", hugeAmount, "2026-05-15T10:00:00Z"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsMalformedTimestampWithMeaningfulMessage() throws Exception {
        String json = """
                {"eventId":"evt-bad-ts","type":"CREDIT","amount":10.00,"currency":"USD","eventTimestamp":"not-a-date"}
                """;
        ResponseEntity<String> response = postTransaction("acct-bad-ts", json);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = mapper.readTree(response.getBody());
        assertThat(body.get("message").asText()).contains("eventTimestamp");
    }

    @Test
    void concurrentTransactionsAgainstSameAccountDoNotLoseUpdatesOrFail() throws Exception {
        String accountId = "acct-" + UUID.randomUUID();
        int concurrentPosts = 25;
        BigDecimal amountEach = new BigDecimal("10.00");

        ExecutorService pool = Executors.newFixedThreadPool(concurrentPosts);
        try {
            List<Callable<ResponseEntity<String>>> tasks = new java.util.ArrayList<>();
            for (int i = 0; i < concurrentPosts; i++) {
                String eventId = "evt-concurrent-" + i + "-" + UUID.randomUUID();
                tasks.add(() -> postTransaction(accountId,
                        transactionJson(eventId, "CREDIT", amountEach.toPlainString(), "2026-05-15T10:00:00Z")));
            }

            List<Future<ResponseEntity<String>>> futures = pool.invokeAll(tasks);
            for (Future<ResponseEntity<String>> future : futures) {
                ResponseEntity<String> response = future.get(10, TimeUnit.SECONDS);
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            }
        } finally {
            pool.shutdown();
        }

        ResponseEntity<String> balance = restTemplate.getForEntity(baseUrl() + "/accounts/" + accountId + "/balance", String.class);
        JsonNode body = mapper.readTree(balance.getBody());
        BigDecimal expected = amountEach.multiply(new BigDecimal(concurrentPosts));
        assertThat(body.get("balance").decimalValue()).isEqualByComparingTo(expected);
    }
}
