package com.corebank.reconciliation.dto;

import com.corebank.domain.reconciliation.ReconciliationStatus;
import com.corebank.domain.reconciliation.ReconciliationTriggerType;
import java.time.LocalDateTime;
import java.util.List;

public record ReconciliationRunResponse(
        Long id,
        ReconciliationTriggerType triggerType,
        ReconciliationStatus status,
        int checkedAccounts,
        int checkedTransactions,
        int mismatchCount,
        List<ReconciliationMismatch> details,
        Long executedBy,
        LocalDateTime executedAt) {
}
