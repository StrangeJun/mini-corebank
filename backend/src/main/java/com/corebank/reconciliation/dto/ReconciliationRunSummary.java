package com.corebank.reconciliation.dto;

import com.corebank.domain.reconciliation.ReconciliationRun;
import com.corebank.domain.reconciliation.ReconciliationStatus;
import com.corebank.domain.reconciliation.ReconciliationTriggerType;
import java.time.LocalDateTime;

/** 목록 조회용 요약 (상세 불일치 내역은 단건 조회에서만 내려준다). */
public record ReconciliationRunSummary(
        Long id,
        ReconciliationTriggerType triggerType,
        ReconciliationStatus status,
        int checkedAccounts,
        int checkedTransactions,
        int mismatchCount,
        Long executedBy,
        LocalDateTime executedAt) {

    public static ReconciliationRunSummary from(ReconciliationRun run) {
        return new ReconciliationRunSummary(run.getId(), run.getTriggerType(), run.getStatus(),
                run.getCheckedAccounts(), run.getCheckedTransactions(), run.getMismatchCount(),
                run.getExecutedBy(), run.getExecutedAt());
    }
}
