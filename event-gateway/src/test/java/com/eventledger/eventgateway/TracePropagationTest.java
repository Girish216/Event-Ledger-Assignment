package com.eventledger.eventgateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A trace is generated at the Gateway for each request and must be propagated to the
 * Account Service over HTTP headers (Spring Boot's Brave-based tracing does this via
 * W3C traceparent/B3 headers automatically once micrometer-tracing-bridge-brave is on
 * the classpath).
 * <p>
 * Uses a bare JDK HttpServer rather than a stub framework so the request the Gateway
 * actually sent can be inspected with nothing else (no second Spring context, no
 * response-stubbing library) in between.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
class TracePropagationTest {

    private static HttpServer stubAccountService;
    private static final List<Set<String>> RECEIVED_HEADER_NAMES = new CopyOnWriteArrayList<>();

    @DynamicPropertySource
    static void accountServiceUrl(DynamicPropertyRegistry registry) throws Exception {
        stubAccountService = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        stubAccountService.createContext("/accounts", exchange -> {
            RECEIVED_HEADER_NAMES.add(exchange.getRequestHeaders().keySet());
            byte[] body = """
                    {"eventId":"placeholder","accountId":"placeholder","type":"CREDIT",
                     "amount":10.00,"currency":"USD","balance":10.00,"duplicate":false}
                    """.getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stubAccountService.start();
        registry.add("account-service.base-url",
                () -> "http://localhost:" + stubAccountService.getAddress().getPort());
    }

    @AfterAll
    static void stopStub() {
        stubAccountService.stop(0);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @LocalServerPort
    private int port;

    private void submitEvent() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String json = """
                {"eventId":"evt-%s","accountId":"acct-trace","type":"CREDIT","amount":10.00,"currency":"USD","eventTimestamp":"2026-05-15T10:00:00Z"}
                """.formatted(UUID.randomUUID());
        restTemplate.postForEntity("http://localhost:" + port + "/events", new HttpEntity<>(json, headers), String.class);
    }

    @Test
    void gatewayPropagatesATraceIdToAccountServiceOverHttpHeaders() {
        submitEvent(); // warm-up: rules out first-call bean initialization ordering as a factor
        submitEvent(); // calls are synchronous, so both have completed by the time this returns

        assertThat(RECEIVED_HEADER_NAMES).hasSize(2);
        Set<String> receivedHeaders = RECEIVED_HEADER_NAMES.get(1);

        boolean hasSingleHeaderTrace = receivedHeaders.contains("B3") || receivedHeaders.contains("b3");
        boolean hasMultiHeaderTrace = receivedHeaders.contains("X-B3-Traceid") || receivedHeaders.contains("X-B3-TraceId");
        boolean hasW3cTrace = receivedHeaders.contains("Traceparent") || receivedHeaders.contains("traceparent");
        assertThat(hasSingleHeaderTrace || hasMultiHeaderTrace || hasW3cTrace)
                .as("expected a trace header (b3, X-B3-TraceId, or traceparent) on the forwarded request; all captured requests' headers were: %s",
                        RECEIVED_HEADER_NAMES)
                .isTrue();
    }
}
