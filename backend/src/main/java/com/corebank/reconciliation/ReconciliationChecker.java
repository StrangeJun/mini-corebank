package com.corebank.reconciliation;

import static com.corebank.reconciliation.dto.ReconciliationMismatch.ACCOUNT_LEDGER_MISMATCH;
import static com.corebank.reconciliation.dto.ReconciliationMismatch.TX_BALANCE_MISMATCH;
import static com.corebank.reconciliation.dto.ReconciliationMismatch.ZERO_SUM_MISMATCH;

import com.corebank.common.util.MaskingUtil;
import com.corebank.reconciliation.dto.ReconciliationMismatch;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 복식부기 3대 불변식을 집계 쿼리로 전수 검사한다 (엔티티를 메모리에 올리지 않는다).
 * 호출자가 하나의 스냅샷 트랜잭션(REPEATABLE_READ) 안에서 부르면 쿼리 사이에 커밋된 거래로 인한 오탐이 없다.
 */
@Component
class ReconciliationChecker {

    private static final String CREDIT_SUM = "coalesce(sum(case when le.entry_type = 'CREDIT' then le.amount end), 0)";
    private static final String DEBIT_SUM = "coalesce(sum(case when le.entry_type = 'DEBIT' then le.amount end), 0)";

    private final JdbcTemplate jdbc;

    ReconciliationChecker(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record Result(int checkedAccounts, int checkedTransactions, List<ReconciliationMismatch> mismatches) {
    }

    Result check() {
        List<ReconciliationMismatch> mismatches = new ArrayList<>();
        mismatches.addAll(transactionBalanceMismatches());
        mismatches.addAll(accountLedgerMismatches());
        mismatches.addAll(zeroSumMismatches());
        int accounts = jdbc.queryForObject("select count(*) from accounts", Integer.class);
        int transactions = jdbc.queryForObject("select count(*) from transactions where status = 'COMPLETED'",
                Integer.class);
        return new Result(accounts, transactions, mismatches);
    }

    /** 불변식 1: 완료 거래마다 CREDIT 합 == DEBIT 합 == 거래 금액. */
    private List<ReconciliationMismatch> transactionBalanceMismatches() {
        String sql = "select t.id, t.amount, " + CREDIT_SUM + " as credit_total, " + DEBIT_SUM + " as debit_total"
                + " from transactions t left join ledger_entries le on le.transaction_id = t.id"
                + " where t.status = 'COMPLETED'"
                + " group by t.id, t.amount"
                + " having " + CREDIT_SUM + " <> t.amount or " + DEBIT_SUM + " <> t.amount"
                + " order by t.id";
        return jdbc.query(sql, (rs, i) -> new ReconciliationMismatch(TX_BALANCE_MISMATCH,
                "거래 차변/대변 합계가 거래 금액과 다릅니다.",
                rs.getLong("id"), null, null,
                rs.getBigDecimal("amount"), null,
                rs.getBigDecimal("credit_total"), rs.getBigDecimal("debit_total"), null));
    }

    /** 불변식 2: 모든 계좌(SYSTEM-VAULT 포함) balance == CREDIT 합 - DEBIT 합. */
    private List<ReconciliationMismatch> accountLedgerMismatches() {
        String sql = "select a.id, a.account_number, a.balance,"
                + " coalesce(l.credit_total, 0) - coalesce(l.debit_total, 0) as ledger_net"
                + " from accounts a left join ("
                + "   select le.account_id, " + CREDIT_SUM + " as credit_total, " + DEBIT_SUM + " as debit_total"
                + "   from ledger_entries le group by le.account_id"
                + " ) l on l.account_id = a.id"
                + " where a.balance <> coalesce(l.credit_total, 0) - coalesce(l.debit_total, 0)"
                + " order by a.id";
        return jdbc.query(sql, (rs, i) -> {
            BigDecimal balance = rs.getBigDecimal("balance");
            BigDecimal ledgerNet = rs.getBigDecimal("ledger_net");
            return new ReconciliationMismatch(ACCOUNT_LEDGER_MISMATCH,
                    "계좌 잔액이 원장 합계(CREDIT-DEBIT)와 다릅니다.",
                    null, rs.getLong("id"), MaskingUtil.maskAccountNumber(rs.getString("account_number")),
                    ledgerNet, balance, null, null, balance.subtract(ledgerNet));
        });
    }

    /** 불변식 3: 전체 계좌 잔액 합 == 0, 전체 분개 CREDIT 합 == DEBIT 합. */
    private List<ReconciliationMismatch> zeroSumMismatches() {
        List<ReconciliationMismatch> result = new ArrayList<>();
        BigDecimal balanceSum = jdbc.queryForObject("select coalesce(sum(balance), 0) from accounts", BigDecimal.class);
        if (balanceSum.signum() != 0) {
            result.add(new ReconciliationMismatch(ZERO_SUM_MISMATCH, "전체 계좌 잔액 합계가 0이 아닙니다.",
                    null, null, null, BigDecimal.ZERO, balanceSum, null, null, balanceSum));
        }
        Map<String, Object> sums = jdbc.queryForMap("select " + CREDIT_SUM + " as credit_total, " + DEBIT_SUM
                + " as debit_total from ledger_entries le");
        BigDecimal credit = toBigDecimal(sums.get("credit_total"));
        BigDecimal debit = toBigDecimal(sums.get("debit_total"));
        if (credit.compareTo(debit) != 0) {
            result.add(new ReconciliationMismatch(ZERO_SUM_MISMATCH, "전체 분개의 CREDIT 합계와 DEBIT 합계가 다릅니다.",
                    null, null, null, null, null, credit, debit, credit.subtract(debit)));
        }
        return result;
    }

    private static BigDecimal toBigDecimal(Object value) {
        return value instanceof BigDecimal bd ? bd : new BigDecimal(value.toString());
    }
}
