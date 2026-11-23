package com.corebank.account.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/** perTxLimit / dailyLimit 은 생략하면 기본값을 쓴다. 원 단위 정수만 허용한다. */
public record CreateAccountRequest(
        @NotNull @Pattern(regexp = "^\\d{4,6}$", message = "PIN 은 숫자 4~6자리여야 합니다.") String pin,
        @Positive @Digits(integer = 19, fraction = 0) BigDecimal perTxLimit,
        @Positive @Digits(integer = 19, fraction = 0) BigDecimal dailyLimit) {

    @Override
    public String toString() {
        return "CreateAccountRequest[pin=****, perTxLimit=" + perTxLimit + ", dailyLimit=" + dailyLimit + "]";
    }
}
