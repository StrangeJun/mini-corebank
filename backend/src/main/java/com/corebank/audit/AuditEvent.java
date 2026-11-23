package com.corebank.audit;

import com.corebank.domain.audit.AuditResult;

/**
 * 감사 로그 기록 요청. summary 는 평문이 들어와도 저장 전에 마스킹된다.
 */
public record AuditEvent(
        Long actorUserId,
        String actionType,
        String targetType,
        String targetId,
        AuditResult result,
        String summary,
        String clientIp) {
}
