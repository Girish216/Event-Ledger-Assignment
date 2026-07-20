package com.eventledger.accountservice.dto;

import com.eventledger.accountservice.domain.TransactionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.Instant;

public record TransactionRequest(
        @NotBlank(message = "eventId is required") String eventId,
        @NotNull(message = "type is required") TransactionType type,
        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.0", inclusive = false, message = "amount must be greater than 0")
        @Digits(integer = 15, fraction = 2, message = "amount must have at most 15 integer digits and 2 decimal places")
        BigDecimal amount,
        @NotBlank(message = "currency is required")
        @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be a 3-letter ISO 4217 code, e.g. USD")
        String currency,
        @NotNull(message = "eventTimestamp is required") Instant eventTimestamp
) {
}
