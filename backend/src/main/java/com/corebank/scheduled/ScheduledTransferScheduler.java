package com.corebank.scheduled;

import com.corebank.scheduled.dto.ScheduledExecutionSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 도래한 예약 이체 실행 배치. 주기는 corebank.scheduler.transfer-delay-ms, transfer-enabled=false 이면 비활성. */
@Component
@ConditionalOnProperty(name = "corebank.scheduler.transfer-enabled", havingValue = "true", matchIfMissing = true)
public class ScheduledTransferScheduler {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTransferScheduler.class);

    private final ScheduledTransferService scheduledTransferService;

    public ScheduledTransferScheduler(ScheduledTransferService scheduledTransferService) {
        this.scheduledTransferService = scheduledTransferService;
    }

    @Scheduled(fixedDelayString = "${corebank.scheduler.transfer-delay-ms:30000}")
    public void executeDueTransfers() {
        ScheduledExecutionSummary summary = scheduledTransferService.executeDueTransfers();
        if (summary.due() > 0) {
            log.info("예약 이체 실행: due={}, completed={}, failed={}, skipped={}",
                    summary.due(), summary.completed(), summary.failed(), summary.skipped());
        }
    }
}
