package com.eventledger.eventgateway;

import com.eventledger.eventgateway.client.AccountServiceClient;
import com.eventledger.eventgateway.client.AccountTransactionResult;
import com.eventledger.eventgateway.domain.Event;
import com.eventledger.eventgateway.domain.EventStatus;
import com.eventledger.eventgateway.domain.EventType;
import com.eventledger.eventgateway.dto.EventRequest;
import com.eventledger.eventgateway.dto.EventResponse;
import com.eventledger.eventgateway.exception.AccountServiceUnavailableException;
import com.eventledger.eventgateway.exception.ConflictingDuplicateException;
import com.eventledger.eventgateway.repository.EventRepository;
import com.eventledger.eventgateway.service.EventService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-level coverage for races that are impractical to reproduce deterministically over real
 * HTTP: a failed retry racing a concurrent confirmation of the same eventId, and a resubmission
 * with different transaction details for an eventId event-gateway already has on file.
 *
 * <p>{@link EventRepository} is an interface so Mockito can mock it directly. {@link
 * AccountServiceClient} is a concrete class, which needs Mockito's inline mock maker to
 * bytecode-retransform - a mechanism that doesn't yet support very new JDK bytecode versions, so
 * it's stubbed here with a plain subclass instead.
 */
class EventServiceTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-05-15T10:00:00Z");

    private final EventRepository repository = mock(EventRepository.class);

    @Test
    void failedRetryDoesNotDowngradeAnEventProcessedConcurrently() {
        Event pending = newEvent("evt-race", EventStatus.PENDING);
        Event processedByAnotherThread = newEvent("evt-race", EventStatus.PROCESSED);

        when(repository.findByEventId("evt-race"))
                .thenReturn(Optional.of(pending))
                .thenReturn(Optional.of(processedByAnotherThread));

        EventService service = new EventService(repository, new AlwaysUnavailableAccountServiceClient(), new SimpleMeterRegistry());
        EventRequest request = new EventRequest("evt-race", "acct-1", EventType.CREDIT,
                new BigDecimal("10.00"), "USD", OCCURRED_AT, Map.of());
        EventResponse response = service.submitEvent(request);

        assertThat(response.status()).isEqualTo(EventStatus.PROCESSED);
        verify(repository, never()).save(argThat(e -> e != null && e.getStatus() == EventStatus.FAILED));
    }

    @Test
    void resubmissionOfKnownEventIdWithDifferentPayloadIsRejected() {
        Event original = newEvent("evt-known", EventStatus.PROCESSED);
        when(repository.findByEventId("evt-known")).thenReturn(Optional.of(original));

        EventService service = new EventService(repository, new AlwaysUnavailableAccountServiceClient(), new SimpleMeterRegistry());
        EventRequest changedAmount = new EventRequest("evt-known", "acct-1", EventType.CREDIT,
                new BigDecimal("99.00"), "USD", OCCURRED_AT, Map.of());

        assertThatThrownBy(() -> service.submitEvent(changedAmount)).isInstanceOf(ConflictingDuplicateException.class);
    }

    private static Event newEvent(String eventId, EventStatus status) {
        Event event = new Event(eventId, "acct-1", EventType.CREDIT, new BigDecimal("10.00"),
                "USD", OCCURRED_AT, Map.of(), Instant.now());
        if (status == EventStatus.PROCESSED) {
            event.markProcessed(Instant.now());
        }
        return event;
    }

    private static final class AlwaysUnavailableAccountServiceClient extends AccountServiceClient {
        AlwaysUnavailableAccountServiceClient() {
            super(null);
        }

        @Override
        public AccountTransactionResult applyTransaction(Event event) {
            throw new AccountServiceUnavailableException("timed out");
        }
    }
}
