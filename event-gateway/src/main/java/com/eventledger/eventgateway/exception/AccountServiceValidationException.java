package com.eventledger.eventgateway.exception;

/** Raised when Account Service rejects a transaction with 400 Bad Request (invalid payload). */
public class AccountServiceValidationException extends RuntimeException {

    public AccountServiceValidationException(String message) {
        super(message);
    }
}
