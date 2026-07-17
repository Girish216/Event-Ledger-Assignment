package com.eventledger.eventgateway.exception;

public class AccountNotFoundUpstreamException extends RuntimeException {

    public AccountNotFoundUpstreamException(String accountId) {
        super("Account not found: " + accountId);
    }
}
