package com.eventledger.eventgateway.exception;

/** Raised when Account Service rejects a transaction with 409 Conflict (duplicate mismatch or currency mismatch). */
public class AccountServiceConflictException extends RuntimeException {

    public AccountServiceConflictException(String message) {
        super(message);
    }
}
