package com.eventledger.accountservice.dto;

import com.eventledger.accountservice.domain.AccountTransaction;
import com.eventledger.accountservice.domain.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;

public record TransactionResponse(
        String eventId,
        String accountId,
        TransactionType type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp,
        BigDecimal balance,
        boolean duplicate
) {
    public static TransactionResponse from(AccountTransaction transaction, BigDecimal currentBalance, boolean duplicate) {
        return new TransactionResponse(
                transaction.getEventId(),
                transaction.getAccountId(),
                transaction.getType(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getEventTimestamp(),
                currentBalance,
                duplicate
        );
    }
}
