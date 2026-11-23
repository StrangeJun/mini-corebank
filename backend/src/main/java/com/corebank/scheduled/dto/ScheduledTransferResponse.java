package com.corebank.scheduled.dto;

import com.corebank.domain.scheduled.ScheduledTransferStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 입금 계좌번호는 마스킹해서만 내보낸다. */
public record ScheduledTransferResponse(
        Long id,
        Long fromAccountId,
        String toAccountNumberMasked,
        BigDecimal amount,
        LocalDateTime scheduledAt,
        ScheduledTransferStatus status,
        String failureReason,
        Long executedTransactionId,
        LocalDateTime createdAt,
        LocalDateTime executedAt) {
}
