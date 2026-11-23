package com.corebank.reconciliation;

import com.corebank.domain.reconciliation.ReconciliationTriggerType;
import com.corebank.reconciliation.dto.ReconciliationRunResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 정기 대사 배치 진입점. 주기는 corebank.reconciliation.cron ("-" 이면 비활성). */
@Component
public class ReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationScheduler.class);

    private final ReconciliationService reconciliationService;

    public ReconciliationScheduler(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @Scheduled(cron = "${corebank.reconciliation.cron:0 0 2 * * *}")
    public void runScheduledReconciliation() {
        ReconciliationRunResponse result = reconciliationService.run(ReconciliationTriggerType.SCHEDULED, null, null);
        if (result.mismatchCount() > 0) {
            log.warn("정기 대사 불일치 발견: runId={}, mismatchCount={}", result.id(), result.mismatchCount());
        } else {
            log.info("정기 대사 통과: runId={}", result.id());
        }
    }
}
