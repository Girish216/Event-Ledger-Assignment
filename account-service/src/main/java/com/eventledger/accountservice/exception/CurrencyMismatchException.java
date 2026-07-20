package com.eventledger.accountservice.exception;

/** Raised when a transaction's currency differs from the currency an existing account was opened in. */
public class CurrencyMismatchException extends RuntimeException {

    public CurrencyMismatchException(String accountId, String accountCurrency, String requestedCurrency) {
        super("Account " + accountId + " is denominated in " + accountCurrency
                + " and cannot accept a " + requestedCurrency + " transaction");
    }
}
