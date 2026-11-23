package com.corebank.transaction.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record WithdrawRequest(
        @NotNull Long accountId,
        BigDecimal amount,
        @NotNull String pin,
        @Size(max = 255) String description) {

    @Override
    public String toString() {
        return "WithdrawRequest[accountId=" + accountId + ", amount=" + amount + ", pin=****]";
    }
}
