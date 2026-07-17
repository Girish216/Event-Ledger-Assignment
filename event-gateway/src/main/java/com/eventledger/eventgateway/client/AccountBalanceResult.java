package com.eventledger.eventgateway.client;

import java.math.BigDecimal;
import java.time.Instant;

public record AccountBalanceResult(String accountId, BigDecimal balance, String currency, Instant asOf) {
}
