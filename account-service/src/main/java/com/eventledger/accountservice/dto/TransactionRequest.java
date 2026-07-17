package com.eventledger.accountservice.dto;

import com.eventledger.accountservice.domain.TransactionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

public record TransactionRequest(
        @NotBlank(message = "eventId is required") String eventId,
        @NotNull(message = "type is required") TransactionType type,
        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.0", inclusive = false, message = "amount must be greater than 0") BigDecimal amount,
        @NotBlank(message = "currency is required") String currency,
        @NotNull(message = "eventTimestamp is required") Instant eventTimestamp
) {
}
