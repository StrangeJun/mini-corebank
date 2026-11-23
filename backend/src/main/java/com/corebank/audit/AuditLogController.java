package com.corebank.audit;

import com.corebank.audit.dto.AuditLogResponse;
import com.corebank.auth.jwt.AuthUser;
import com.corebank.common.PageResponse;
import com.corebank.domain.audit.AuditResult;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuditLogController {

    private final AuditLogQueryService queryService;

    public AuditLogController(AuditLogQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/api/audit-logs/me")
    public PageResponse<AuditLogResponse> mine(@AuthenticationPrincipal AuthUser user,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return queryService.findMine(user.userId(), page, size);
    }

    /** ADMIN 전용 — SecurityConfig 의 /api/admin/** 규칙으로 보호된다. */
    @GetMapping("/api/admin/audit-logs")
    public PageResponse<AuditLogResponse> search(@RequestParam(required = false) String actionType,
                                                 @RequestParam(required = false) AuditResult result,
                                                 @RequestParam(required = false) Long actorUserId,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        return queryService.search(actionType, result, actorUserId, page, size);
    }
}
