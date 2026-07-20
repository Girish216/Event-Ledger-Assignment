package com.eventledger.eventgateway.exception;

/** Raised when an already-received {@code eventId} is resubmitted with different event details. */
public class ConflictingDuplicateException extends RuntimeException {

    public ConflictingDuplicateException(String eventId) {
        super("eventId " + eventId + " was already received with different event details "
                + "(accountId/type/amount/currency/eventTimestamp must match exactly to be treated as a duplicate)");
    }
}
