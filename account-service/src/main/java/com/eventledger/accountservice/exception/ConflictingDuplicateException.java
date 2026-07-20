package com.eventledger.accountservice.exception;

/** Raised when an already-recorded {@code eventId} is resubmitted with different transaction details. */
public class ConflictingDuplicateException extends RuntimeException {

    public ConflictingDuplicateException(String eventId) {
        super("eventId " + eventId + " was already recorded with different transaction details "
                + "(type/amount/currency/eventTimestamp must match exactly to be treated as a duplicate)");
    }
}
