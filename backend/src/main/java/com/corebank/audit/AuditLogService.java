package com.corebank.audit;

import com.corebank.common.util.MaskingUtil;
import com.corebank.domain.audit.AuditLog;
import com.corebank.domain.audit.AuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    public AuditLogService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /** 호출한 트랜잭션에 참여한다. 본 거래가 롤백되면 이 로그도 함께 롤백된다 (성공 거래용). */
    @Transactional
    public AuditLog logInTransaction(AuditEvent event) {
        return save(event);
    }

    /**
     * 별도 트랜잭션으로 즉시 커밋한다. 본 트랜잭션이 롤백돼도 남는다
     * (PIN 오류, 로그인 실패, 잔액 부족 등 실패 이벤트용).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditLog logIndependent(AuditEvent event) {
        return save(event);
    }

    private AuditLog save(AuditEvent event) {
        AuditLog log = new AuditLog(
                event.actorUserId(),
                event.actionType(),
                event.targetType(),
                MaskingUtil.maskSensitiveText(event.targetId()),
                event.result(),
                maskSummary(event.summary()),
                event.clientIp());
        return auditLogRepository.save(log);
    }

    private static String maskSummary(String summary) {
        String masked = MaskingUtil.maskSensitiveText(summary);
        return masked == null ? "" : masked;
    }
}
