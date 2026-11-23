package com.corebank.account.dto;

import com.corebank.common.util.MaskingUtil;
import com.corebank.domain.account.Account;
import com.corebank.domain.account.AccountStatus;

/** 관리자 잠금 해제 응답. 원본 계좌번호는 담지 않는다. */
public record AccountUnlockResponse(Long id, String maskedAccountNumber, AccountStatus status, int pinFailCount) {

    public static AccountUnlockResponse from(Account account) {
        return new AccountUnlockResponse(account.getId(), MaskingUtil.maskAccountNumber(account.getAccountNumber()),
                account.getStatus(), account.getPinFailCount());
    }
}
