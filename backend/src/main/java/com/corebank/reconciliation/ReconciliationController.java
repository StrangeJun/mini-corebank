package com.corebank.reconciliation;

import com.corebank.auth.jwt.AuthUser;
import com.corebank.common.PageResponse;
import com.corebank.domain.reconciliation.ReconciliationTriggerType;
import com.corebank.reconciliation.dto.ReconciliationRunResponse;
import com.corebank.reconciliation.dto.ReconciliationRunSummary;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** /api/admin/** 는 SecurityConfig 에서 ADMIN 전용으로 막혀 있다. */
@RestController
@RequestMapping("/api/admin/reconciliations")
public class ReconciliationController {

    private final ReconciliationService reconciliationService;

    public ReconciliationController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @PostMapping("/run")
    public ReconciliationRunResponse run(@AuthenticationPrincipal AuthUser admin, HttpServletRequest http) {
        return reconciliationService.run(ReconciliationTriggerType.MANUAL, admin.userId(), http.getRemoteAddr());
    }

    @GetMapping
    public PageResponse<ReconciliationRunSummary> list(@RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        return reconciliationService.list(page, size);
    }

    @GetMapping("/{id}")
    public ReconciliationRunResponse get(@PathVariable Long id) {
        return reconciliationService.get(id);
    }
}
