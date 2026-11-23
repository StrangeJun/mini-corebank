package com.corebank.reconciliation;

import com.corebank.audit.AuditEvent;
import com.corebank.audit.AuditLogService;
import com.corebank.common.PageParams;
import com.corebank.common.PageResponse;
import com.corebank.common.exception.BusinessException;
import com.corebank.common.exception.ErrorCode;
import com.corebank.domain.audit.AuditResult;
import com.corebank.domain.reconciliation.ReconciliationRun;
import com.corebank.domain.reconciliation.ReconciliationRunRepository;
import com.corebank.domain.reconciliation.ReconciliationStatus;
import com.corebank.domain.reconciliation.ReconciliationTriggerType;
import com.corebank.reconciliation.dto.ReconciliationMismatch;
import com.corebank.reconciliation.dto.ReconciliationRunResponse;
import com.corebank.reconciliation.dto.ReconciliationRunSummary;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReconciliationService {

    static final String ACTION_RUN = "RECONCILIATION_RUN";
    private static final TypeReference<List<ReconciliationMismatch>> DETAILS_TYPE = new TypeReference<>() {
    };

    private final ReconciliationChecker checker;
    private final ReconciliationRunRepository runRepository;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    public ReconciliationService(ReconciliationChecker checker, ReconciliationRunRepository runRepository,
                                 AuditLogService auditLogService, ObjectMapper objectMapper) {
        this.checker = checker;
        this.runRepository = runRepository;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
    }

    /**
     * 3대 불변식을 하나의 스냅샷(REPEATABLE_READ)에서 전수 검사하고 결과를 저장한다.
     * 읽기만 하므로 잔액 락을 잡지 않아 운영 중 거래를 막지 않는다.
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public ReconciliationRunResponse run(ReconciliationTriggerType triggerType, Long executedBy, String clientIp) {
        ReconciliationChecker.Result result = checker.check();
        List<ReconciliationMismatch> details = result.details();
        int mismatchCount = result.mismatchCount();
        ReconciliationStatus status = mismatchCount == 0 ? ReconciliationStatus.PASSED
                : ReconciliationStatus.MISMATCH_FOUND;

        ReconciliationRun run = runRepository.save(new ReconciliationRun(triggerType, status,
                result.checkedAccounts(), result.checkedTransactions(), mismatchCount,
                toJson(details), executedBy, LocalDateTime.now()));

        auditLogService.logInTransaction(new AuditEvent(executedBy, ACTION_RUN, "RECONCILIATION_RUN",
                String.valueOf(run.getId()), AuditResult.SUCCESS,
                triggerType + " reconciliation " + status + " mismatchCount=" + mismatchCount
                        + " accounts=" + result.checkedAccounts() + " transactions=" + result.checkedTransactions(),
                clientIp));
        return toResponse(run, details);
    }

    @Transactional(readOnly = true)
    public PageResponse<ReconciliationRunSummary> list(int page, int size) {
        PageParams.validate(page, size);
        PageRequest pageable = PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("executedAt"), Sort.Order.desc("id")));
        return PageResponse.from(runRepository.findAll(pageable), ReconciliationRunSummary::from);
    }

    @Transactional(readOnly = true)
    public ReconciliationRunResponse get(Long id) {
        ReconciliationRun run = runRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RECONCILIATION_RUN_NOT_FOUND));
        return toResponse(run, fromJson(run.getDetailsJson()));
    }

    private static ReconciliationRunResponse toResponse(ReconciliationRun run, List<ReconciliationMismatch> details) {
        return new ReconciliationRunResponse(run.getId(), run.getTriggerType(), run.getStatus(),
                run.getCheckedAccounts(), run.getCheckedTransactions(), run.getMismatchCount(), details,
                run.getExecutedBy(), run.getExecutedAt());
    }

    private String toJson(List<ReconciliationMismatch> mismatches) {
        try {
            return objectMapper.writeValueAsString(mismatches);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("대사 결과 직렬화 실패", e);
        }
    }

    private List<ReconciliationMismatch> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, DETAILS_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("대사 결과 역직렬화 실패", e);
        }
    }
}
