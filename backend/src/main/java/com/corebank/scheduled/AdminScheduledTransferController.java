package com.corebank.scheduled;

import com.corebank.scheduled.dto.ScheduledExecutionSummary;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** /api/admin/** 는 SecurityConfig 에서 ADMIN 전용으로 막혀 있다. */
@RestController
@RequestMapping("/api/admin/scheduled-transfers")
public class AdminScheduledTransferController {

    private final ScheduledTransferService scheduledTransferService;

    public AdminScheduledTransferController(ScheduledTransferService scheduledTransferService) {
        this.scheduledTransferService = scheduledTransferService;
    }

    @PostMapping("/execute-due")
    public ScheduledExecutionSummary executeDue() {
        return scheduledTransferService.executeDueTransfers();
    }
}
