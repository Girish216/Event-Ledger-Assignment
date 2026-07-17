package com.eventledger.eventgateway.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record BalanceResponse(String accountId, BigDecimal balance, String currency, Instant asOf) {
}
