package com.corebank.transaction.dto;

import com.corebank.domain.transaction.TransactionType;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** balanceAfter 는 요청한 고객 계좌(입금: 입금 계좌, 출금·이체: 출금 계좌)의 거래 후 잔액. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransactionResponse(
        Long transactionId,
        TransactionType txType,
        BigDecimal amount,
        Long fromAccountId,
        String toAccountNumberMasked,
        BigDecimal balanceAfter,
        String description,
        LocalDateTime createdAt) {
}
