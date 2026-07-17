package com.eventledger.accountservice.exception;

public class EventIdConflictException extends RuntimeException {

    public EventIdConflictException(String eventId, String existingAccountId, String requestedAccountId) {
        super("eventId " + eventId + " was already recorded for account " + existingAccountId
                + " and cannot be reused for account " + requestedAccountId);
    }
}
