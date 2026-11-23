package com.corebank.transaction.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** amount 는 서비스에서 INVALID_AMOUNT 로 검증한다. */
public record DepositRequest(
        @NotNull Long accountId,
        BigDecimal amount,
        @Size(max = 255) String description) {
}
