package com.corebank.account.dto;

import com.corebank.common.util.MaskingUtil;
import com.corebank.domain.account.Account;
import com.corebank.domain.account.AccountStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** pin_hash 등 내부 필드는 포함하지 않는다. */
public record AccountResponse(
        Long id,
        String accountNumber,
        String maskedAccountNumber,
        BigDecimal balance,
        AccountStatus status,
        BigDecimal perTxLimit,
        BigDecimal dailyLimit,
        LocalDateTime createdAt) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getAccountNumber(),
                MaskingUtil.maskAccountNumber(account.getAccountNumber()),
                account.getBalance(),
                account.getStatus(),
                account.getPerTxLimit(),
                account.getDailyLimit(),
                account.getCreatedAt());
    }
}
