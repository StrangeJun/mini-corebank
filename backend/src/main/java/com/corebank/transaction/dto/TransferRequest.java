package com.corebank.transaction.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** 입금 계좌는 toAccountNumber(권장, 명세 표기 toAccountAccountNumber 도 허용) 또는 toAccountId 로 지정한다. */
public record TransferRequest(
        @NotNull Long fromAccountId,
        Long toAccountId,
        @JsonAlias("toAccountAccountNumber") @Size(max = 30) String toAccountNumber,
        BigDecimal amount,
        @NotNull String pin,
        @Size(max = 255) String description) {

    @Override
    public String toString() {
        return "TransferRequest[fromAccountId=" + fromAccountId + ", toAccountId=" + toAccountId
                + ", amount=" + amount + ", pin=****]";
    }
}
