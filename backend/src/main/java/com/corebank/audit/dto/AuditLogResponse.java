package com.corebank.audit.dto;

import com.corebank.common.util.MaskingUtil;
import com.corebank.domain.audit.AuditLog;
import com.corebank.domain.audit.AuditResult;
import java.time.LocalDateTime;

public record AuditLogResponse(
        Long id,
        Long actorUserId,
        String actionType,
        String targetType,
        String targetId,
        AuditResult result,
        String maskedSummary,
        String clientIp,
        LocalDateTime createdAt) {

    /** 저장 시 이미 마스킹되지만, 응답 직전에 한 번 더 마스킹해 평문 노출을 이중으로 막는다. */
    public static AuditLogResponse from(AuditLog log) {
        return new AuditLogResponse(log.getId(), log.getActorUserId(), log.getActionType(), log.getTargetType(),
                MaskingUtil.maskSensitiveText(log.getTargetId()), log.getResult(),
                MaskingUtil.maskSensitiveText(log.getMaskedSummary()), log.getClientIp(), log.getCreatedAt());
    }
}
