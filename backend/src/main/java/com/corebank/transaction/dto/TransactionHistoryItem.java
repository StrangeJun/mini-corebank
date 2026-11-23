package com.corebank.transaction.dto;

import com.corebank.domain.ledger.EntryType;
import com.corebank.domain.transaction.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 조회 계좌 기준 분개 1줄. 상대 계좌는 마스킹된 번호만 노출한다. */
public record TransactionHistoryItem(
        Long transactionId,
        TransactionType txType,
        EntryType entryType,
        BigDecimal amount,
        BigDecimal balanceAfter,
        String counterpartyMasked,
        String description,
        LocalDateTime createdAt) {
}
