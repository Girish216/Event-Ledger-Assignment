package com.eventledger.eventgateway.client;

import com.eventledger.eventgateway.domain.Event;
import com.eventledger.eventgateway.exception.AccountNotFoundUpstreamException;
import com.eventledger.eventgateway.exception.AccountServiceConflictException;
import com.eventledger.eventgateway.exception.AccountServiceUnavailableException;
import com.eventledger.eventgateway.exception.AccountServiceValidationException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Component
public class AccountServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AccountServiceClient.class);
    private static final String CIRCUIT_BREAKER = "accountService";

    private final RestClient restClient;

    public AccountServiceClient(RestClient accountServiceRestClient) {
        this.restClient = accountServiceRestClient;
    }

    @CircuitBreaker(name = CIRCUIT_BREAKER, fallbackMethod = "applyTransactionFallback")
    public AccountTransactionResult applyTransaction(Event event) {
        AccountTransactionRequest request = new AccountTransactionRequest(
                event.getEventId(), event.getType().name(), event.getAmount(), event.getCurrency(), event.getEventTimestamp());

        try {
            return restClient.post()
                    .uri("/accounts/{accountId}/transactions", event.getAccountId())
                    .body(request)
                    .retrieve()
                    .body(AccountTransactionResult.class);
        } catch (HttpServerErrorException | ResourceAccessException ex) {
            throw new AccountServiceUnavailableException("Account Service call failed: " + ex.getMessage(), ex);
        } catch (HttpClientErrorException.Conflict ex) {
            throw new AccountServiceConflictException(messageFrom(ex));
        } catch (HttpClientErrorException.BadRequest ex) {
            throw new AccountServiceValidationException(messageFrom(ex));
        } catch (HttpClientErrorException ex) {
            throw new AccountServiceUnavailableException("Account Service rejected the request: " + ex.getMessage(), ex);
        }
    }

    private static String messageFrom(HttpClientErrorException ex) {
        String body = ex.getResponseBodyAsString();
        return (body == null || body.isBlank()) ? ex.getMessage() : body;
    }

    @SuppressWarnings("unused")
    private AccountTransactionResult applyTransactionFallback(Event event, Throwable t) {
        if (t instanceof AccountServiceConflictException conflict) {
            throw conflict;
        }
        if (t instanceof AccountServiceValidationException invalid) {
            throw invalid;
        }
        log.warn("account service circuit open or call failed for accountId={} eventId={}: {}",
                event.getAccountId(), event.getEventId(), t.toString());
        throw new AccountServiceUnavailableException("Account Service is unavailable", t);
    }

    @CircuitBreaker(name = CIRCUIT_BREAKER, fallbackMethod = "getBalanceFallback")
    public AccountBalanceResult getBalance(String accountId) {
        try {
            return restClient.get()
                    .uri("/accounts/{accountId}/balance", accountId)
                    .retrieve()
                    .body(AccountBalanceResult.class);
        } catch (HttpClientErrorException.NotFound ex) {
            throw new AccountNotFoundUpstreamException(accountId);
        } catch (HttpServerErrorException | ResourceAccessException | HttpClientErrorException ex) {
            throw new AccountServiceUnavailableException("Account Service call failed: " + ex.getMessage(), ex);
        }
    }

    @SuppressWarnings("unused")
    private AccountBalanceResult getBalanceFallback(String accountId, Throwable t) {
        if (t instanceof AccountNotFoundUpstreamException notFound) {
            throw notFound;
        }
        log.warn("account service circuit open or call failed for accountId={}: {}", accountId, t.toString());
        throw new AccountServiceUnavailableException("Account Service is unavailable", t);
    }
}
