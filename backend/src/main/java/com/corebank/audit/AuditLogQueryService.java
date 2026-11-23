package com.corebank.audit;

import com.corebank.audit.dto.AuditLogResponse;
import com.corebank.common.PageParams;
import com.corebank.common.PageResponse;
import com.corebank.domain.audit.AuditLog;
import com.corebank.domain.audit.AuditLogRepository;
import com.corebank.domain.audit.AuditResult;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AuditLogQueryService {

    private final AuditLogRepository repository;

    public AuditLogQueryService(AuditLogRepository repository) {
        this.repository = repository;
    }

    public PageResponse<AuditLogResponse> findMine(Long userId, int page, int size) {
        return PageResponse.from(repository.findByActorUserId(userId, PageParams.newestFirst(page, size)),
                AuditLogResponse::from);
    }

    public PageResponse<AuditLogResponse> search(String actionType, AuditResult result, Long actorUserId,
                                                 int page, int size) {
        Specification<AuditLog> spec = Specification.where(null);
        if (actionType != null && !actionType.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("actionType"), actionType.trim()));
        }
        if (result != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("result"), result));
        }
        if (actorUserId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("actorUserId"), actorUserId));
        }
        return PageResponse.from(repository.findAll(spec, PageParams.newestFirst(page, size)), AuditLogResponse::from);
    }
}
