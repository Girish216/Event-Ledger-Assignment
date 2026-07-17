package com.eventledger.eventgateway.service;

import com.eventledger.eventgateway.client.AccountBalanceResult;
import com.eventledger.eventgateway.client.AccountServiceClient;
import com.eventledger.eventgateway.domain.Event;
import com.eventledger.eventgateway.domain.EventStatus;
import com.eventledger.eventgateway.dto.BalanceResponse;
import com.eventledger.eventgateway.dto.EventRequest;
import com.eventledger.eventgateway.dto.EventResponse;
import com.eventledger.eventgateway.exception.AccountServiceUnavailableException;
import com.eventledger.eventgateway.exception.EventNotFoundException;
import com.eventledger.eventgateway.repository.EventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final EventRepository eventRepository;
    private final AccountServiceClient accountServiceClient;
    private final MeterRegistry meterRegistry;

    public EventService(EventRepository eventRepository, AccountServiceClient accountServiceClient, MeterRegistry meterRegistry) {
        this.eventRepository = eventRepository;
        this.accountServiceClient = accountServiceClient;
        this.meterRegistry = meterRegistry;
    }

    public EventResponse submitEvent(EventRequest request) {
        Optional<Event> existing = eventRepository.findByEventId(request.eventId());

        if (existing.isPresent()) {
            Event event = existing.get();
            if (event.getStatus() == EventStatus.PROCESSED) {
                meterRegistry.counter("events.duplicate").increment();
                log.info("duplicate event received eventId={} accountId={}", event.getEventId(), event.getAccountId());
                return EventResponse.from(event, true);
            }
            // status is PENDING or FAILED: the transaction was never confirmed as applied, retry it
            log.info("retrying unresolved event eventId={} accountId={} previousStatus={}",
                    event.getEventId(), event.getAccountId(), event.getStatus());
            return process(event, true);
        }

        Event created = new Event(request.eventId(), request.accountId(), request.type(), request.amount(),
                request.currency(), request.eventTimestamp(), request.metadata(), Instant.now());
        try {
            eventRepository.save(created);
            meterRegistry.counter("events.received").increment();
            return process(created, false);
        } catch (DataIntegrityViolationException ex) {
            // lost a race with a concurrent identical submission; treat it as a duplicate of whatever landed first
            Event raced = eventRepository.findByEventId(request.eventId()).orElseThrow(() -> ex);
            if (raced.getStatus() == EventStatus.PROCESSED) {
                meterRegistry.counter("events.duplicate").increment();
                return EventResponse.from(raced, true);
            }
            return process(raced, true);
        }
    }

    private EventResponse process(Event event, boolean duplicate) {
        try {
            accountServiceClient.applyTransaction(event);
            event.markProcessed(Instant.now());
            eventRepository.save(event);
            meterRegistry.counter("events.processed").increment();
            log.info("event applied eventId={} accountId={}", event.getEventId(), event.getAccountId());
            return EventResponse.from(event, duplicate);
        } catch (AccountServiceUnavailableException ex) {
            event.markFailed(ex.getMessage(), Instant.now());
            eventRepository.save(event);
            meterRegistry.counter("events.failed", "reason", "account_service_unavailable").increment();
            log.warn("event could not be applied, account service unavailable eventId={} accountId={}",
                    event.getEventId(), event.getAccountId());
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public EventResponse getEvent(String eventId) {
        Event event = eventRepository.findByEventId(eventId)
                .orElseThrow(() -> new EventNotFoundException(eventId));
        return EventResponse.from(event, false);
    }

    @Transactional(readOnly = true)
    public List<EventResponse> listEvents(String accountId) {
        return eventRepository.findByAccountIdOrderByEventTimestampAsc(accountId).stream()
                .map(event -> EventResponse.from(event, false))
                .toList();
    }

    public BalanceResponse getBalance(String accountId) {
        AccountBalanceResult result = accountServiceClient.getBalance(accountId);
        return new BalanceResponse(result.accountId(), result.balance(), result.currency(), result.asOf());
    }
}
