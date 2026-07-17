package com.eventledger.eventgateway.client;

import java.math.BigDecimal;

public record AccountTransactionResult(
        String eventId,
        String accountId,
        String type,
        BigDecimal amount,
        String currency,
        BigDecimal balance,
        boolean duplicate
) {
}
