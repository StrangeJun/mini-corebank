package com.corebank.scheduled.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 예약 이체 등록 요청. description 은 scheduled_transfers 에 컬럼이 없어 저장하지 않고 요청 해시에만 반영한다
 * (실행 거래의 description 은 "예약이체 #id").
 */
public record CreateScheduledTransferRequest(
        @NotNull Long fromAccountId,
        @Size(max = 30) String toAccountNumber,
        BigDecimal amount,
        @NotNull String pin,
        LocalDateTime scheduledAt,
        @Size(max = 255) String description) {

    @Override
    public String toString() {
        return "CreateScheduledTransferRequest[fromAccountId=" + fromAccountId + ", amount=" + amount
                + ", scheduledAt=" + scheduledAt + ", pin=****]";
    }
}
