package com.corebank.reconciliation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;

/**
 * 대사 불일치 1건. 유형별로 쓰는 필드만 채운다.
 * <ul>
 *   <li>TX_BALANCE_MISMATCH: transactionId, expected(거래 금액), creditTotal, debitTotal</li>
 *   <li>ACCOUNT_LEDGER_MISMATCH: accountId, accountNumber(마스킹), expected(원장 CREDIT-DEBIT), actual(잔액), difference(actual-expected)</li>
 *   <li>ZERO_SUM_MISMATCH: 잔액 합계(expected 0, actual, difference) 또는 전체 분개(creditTotal, debitTotal, difference)</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReconciliationMismatch(
        String type,
        String description,
        Long transactionId,
        Long accountId,
        String accountNumber,
        BigDecimal expected,
        BigDecimal actual,
        BigDecimal creditTotal,
        BigDecimal debitTotal,
        BigDecimal difference) {

    public static final String TX_BALANCE_MISMATCH = "TX_BALANCE_MISMATCH";
    public static final String ACCOUNT_LEDGER_MISMATCH = "ACCOUNT_LEDGER_MISMATCH";
    public static final String ZERO_SUM_MISMATCH = "ZERO_SUM_MISMATCH";
}
