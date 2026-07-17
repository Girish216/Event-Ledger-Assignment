package com.eventledger.eventgateway.dto;

import com.eventledger.eventgateway.domain.Event;
import com.eventledger.eventgateway.domain.EventStatus;
import com.eventledger.eventgateway.domain.EventType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record EventResponse(
        String eventId,
        String accountId,
        EventType type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp,
        Map<String, Object> metadata,
        EventStatus status,
        Instant receivedAt,
        Instant processedAt,
        String failureReason,
        boolean duplicate
) {
    public static EventResponse from(Event event, boolean duplicate) {
        return new EventResponse(
                event.getEventId(),
                event.getAccountId(),
                event.getType(),
                event.getAmount(),
                event.getCurrency(),
                event.getEventTimestamp(),
                event.getMetadata(),
                event.getStatus(),
                event.getReceivedAt(),
                event.getProcessedAt(),
                event.getFailureReason(),
                duplicate
        );
    }
}
