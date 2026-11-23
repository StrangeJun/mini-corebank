package com.corebank.scheduled;

import com.corebank.account.AccountPinVerificationService;
import com.corebank.audit.AuditEvent;
import com.corebank.audit.AuditLogService;
import com.corebank.common.PageParams;
import com.corebank.common.PageResponse;
import com.corebank.common.exception.BusinessException;
import com.corebank.common.exception.ErrorCode;
import com.corebank.common.idempotency.IdempotencyService;
import com.corebank.common.util.MaskingUtil;
import com.corebank.domain.account.Account;
import com.corebank.domain.account.AccountRepository;
import com.corebank.domain.account.AccountStatus;
import com.corebank.domain.audit.AuditResult;
import com.corebank.domain.scheduled.ScheduledTransfer;
import com.corebank.domain.scheduled.ScheduledTransferRepository;
import com.corebank.domain.scheduled.ScheduledTransferStatus;
import com.corebank.domain.transaction.Transaction;
import com.corebank.scheduled.dto.CreateScheduledTransferRequest;
import com.corebank.scheduled.dto.ScheduledExecutionSummary;
import com.corebank.scheduled.dto.ScheduledTransferResponse;
import com.corebank.transaction.TransactionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 예약 이체 등록·조회·취소와 도래 건 실행.
 *
 * <p><b>등록</b>: 입력 검증 → (외곽 트랜잭션 전) {@link AccountPinVerificationService} PIN 선검증 →
 * 트랜잭션 안에서 멱등 재생 확인 → 계좌·한도 검증 → PENDING 저장 + 멱등 응답 저장 + 감사 로그.
 *
 * <p><b>실행</b>: 도래 건마다 독립 트랜잭션으로 예약 이체 행을 PESSIMISTIC_WRITE 로 잠그고 PENDING 인지 재확인한 뒤
 * 내부 멱등키 {@code SCHED-TX-{id}} 로 원장 이체를 기입하고 COMPLETED 로 바꾼다. 같은 건을 여러 실행기가 동시에
 * 잡아도 행 잠금 + 상태 재확인 + transactions.idempotency_key UNIQUE 로 이체는 한 번만 일어난다.
 * 실행 시점 검증 실패(잔액 부족·계좌 잠금·한도 초과 등)는 원장 변경을 전부 롤백한 뒤, 별도 트랜잭션에서
 * FAILED + failure_reason 을 커밋한다.
 * <p>잠금 순서는 예약 이체 행 → 계좌(id 오름차순)로 고정이며, 다른 경로는 예약 이체 행을 쥔 채 계좌를 기다리지 않는다.
 */
@Service
public class ScheduledTransferService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTransferService.class);

    /** 한 번의 실행에서 처리하는 최대 도래 건 수. 나머지는 다음 주기에 처리한다. */
    public static final int MAX_BATCH_SIZE = 100;

    static final String ENDPOINT_CREATE = "POST /api/scheduled-transfers";
    private static final String TARGET = "SCHEDULED_TRANSFER";
    private static final String ACTION_CREATE = "SCHEDULED_TRANSFER_CREATE";
    private static final String ACTION_CANCEL = "SCHEDULED_TRANSFER_CANCEL";
    private static final String ACTION_EXEC = "SCHEDULED_TRANSFER_EXEC";

    private enum Outcome { COMPLETED, FAILED, SKIPPED }

    private final ScheduledTransferRepository scheduledTransferRepository;
    private final AccountRepository accountRepository;
    private final TransactionService transactionService;
    private final AccountPinVerificationService pinVerificationService;
    private final IdempotencyService idempotencyService;
    private final AuditLogService auditLogService;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate readOnlyTemplate;
    private final ObjectMapper objectMapper;

    public ScheduledTransferService(ScheduledTransferRepository scheduledTransferRepository,
                                    AccountRepository accountRepository, TransactionService transactionService,
                                    AccountPinVerificationService pinVerificationService,
                                    IdempotencyService idempotencyService, AuditLogService auditLogService,
                                    PlatformTransactionManager transactionManager, ObjectMapper objectMapper) {
        this.scheduledTransferRepository = scheduledTransferRepository;
        this.accountRepository = accountRepository;
        this.transactionService = transactionService;
        this.pinVerificationService = pinVerificationService;
        this.idempotencyService = idempotencyService;
        this.auditLogService = auditLogService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTemplate.setReadOnly(true);
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- 등록

    public ScheduledTransferResponse create(Long userId, String idempotencyKey, CreateScheduledTransferRequest req,
                                            String clientIp) {
        try {
            BigDecimal amount = TransactionService.validAmount(req.amount());
            if (req.scheduledAt() == null || !req.scheduledAt().isAfter(LocalDateTime.now())) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "scheduledAt 은 현재 이후 시각이어야 합니다.");
            }
            if (req.toAccountNumber() == null || req.toAccountNumber().isBlank()) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "입금 계좌를 지정해야 합니다.");
            }
            String toNumber = req.toAccountNumber().trim();
            Long toId = accountRepository.findIdByAccountNumber(toNumber)
                    .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
            if (toId.equals(req.fromAccountId())) {
                throw new BusinessException(ErrorCode.SAME_ACCOUNT_TRANSFER);
            }
            IdempotencyService.validateKey(idempotencyKey);
            String hash = hash(req, toNumber, amount);

            // PIN 실패 집계는 본 트랜잭션 롤백과 무관하게 커밋돼야 하므로 외곽 트랜잭션 전에 검증한다.
            // 멱등 재생도 이 검증을 통과해야 한다 (같은 키 + 틀린 PIN 으로 응답을 받아내는 우회 차단).
            pinVerificationService.verify(userId, req.fromAccountId(), req.pin(), clientIp);

            return transactionTemplate.execute(status -> {
                Optional<ScheduledTransferResponse> replay = replay(idempotencyKey, userId, hash);
                if (replay.isPresent()) {
                    return replay.get();
                }
                Account from = accountRepository.findById(req.fromAccountId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
                Account to = accountRepository.findById(toId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
                if (to.isSystem()) {
                    throw new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND);
                }
                if (!from.isOwnedBy(userId)) {
                    throw new BusinessException(ErrorCode.FORBIDDEN_ACCOUNT_ACCESS);
                }
                requireActive(from);
                requireActive(to);
                if (amount.compareTo(from.getPerTxLimit()) > 0) {
                    throw new BusinessException(ErrorCode.PER_TX_LIMIT_EXCEEDED);
                }

                ScheduledTransfer saved = save(new ScheduledTransfer(userId, from.getId(), to.getId(), amount,
                        req.scheduledAt(), idempotencyKey));
                ScheduledTransferResponse response = toResponse(saved, to.getAccountNumber());
                idempotencyService.saveResponse(idempotencyKey, userId, ENDPOINT_CREATE, hash, 201, toJson(response));
                auditLogService.logInTransaction(new AuditEvent(userId, ACTION_CREATE, TARGET,
                        String.valueOf(saved.getId()), AuditResult.SUCCESS,
                        "Scheduled " + amount.toPlainString() + " from " + mask(from) + " to " + mask(to)
                                + " at " + req.scheduledAt(), clientIp));
                return response;
            });
        } catch (BusinessException e) {
            auditFailure(userId, ACTION_CREATE, "ACCOUNT", String.valueOf(req.fromAccountId()),
                    "Schedule failed: " + e.getErrorCode().getCode(), clientIp);
            throw e;
        }
    }

    private ScheduledTransfer save(ScheduledTransfer transfer) {
        try {
            return scheduledTransferRepository.saveAndFlush(transfer);
        } catch (DataIntegrityViolationException e) {
            // 같은 Idempotency-Key 로 동시에 등록된 경우 (scheduled_transfers.idempotency_key UNIQUE)
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
        }
    }

    // ---------------------------------------------------------------- 조회·취소

    public PageResponse<ScheduledTransferResponse> listMine(Long userId, ScheduledTransferStatus status,
                                                            int page, int size) {
        return readOnlyTemplate.execute(s -> {
            Page<ScheduledTransfer> result = status == null
                    ? scheduledTransferRepository.findByUserId(userId, PageParams.newestFirst(page, size))
                    : scheduledTransferRepository.findByUserIdAndStatus(userId, status,
                            PageParams.newestFirst(page, size));
            Map<Long, String> numbers = accountRepository.findAllById(
                            result.getContent().stream().map(ScheduledTransfer::getToAccountId).distinct().toList())
                    .stream().collect(Collectors.toMap(Account::getId, Account::getAccountNumber));
            return PageResponse.from(result, st -> toResponse(st, numbers.get(st.getToAccountId())));
        });
    }

    public ScheduledTransferResponse cancel(Long userId, Long id, String clientIp) {
        try {
            return transactionTemplate.execute(s -> {
                ScheduledTransfer st = scheduledTransferRepository.findByIdForUpdate(id)
                        .orElseThrow(() -> new BusinessException(ErrorCode.SCHEDULED_TRANSFER_NOT_FOUND));
                if (!st.isOwnedBy(userId)) {
                    throw new BusinessException(ErrorCode.FORBIDDEN);
                }
                if (st.getStatus() != ScheduledTransferStatus.PENDING) {
                    throw new BusinessException(ErrorCode.INVALID_INPUT,
                            "대기(PENDING) 중인 예약 이체만 취소할 수 있습니다.");
                }
                st.cancel();
                auditLogService.logInTransaction(new AuditEvent(userId, ACTION_CANCEL, TARGET, String.valueOf(id),
                        AuditResult.SUCCESS, "Canceled scheduled transfer " + id, clientIp));
                return toResponse(st, toAccountNumber(st));
            });
        } catch (BusinessException e) {
            auditFailure(userId, ACTION_CANCEL, TARGET, String.valueOf(id),
                    "Cancel failed: " + e.getErrorCode().getCode(), clientIp);
            throw e;
        }
    }

    // ---------------------------------------------------------------- 도래 건 실행

    /**
     * 스케줄러·관리자 수동 트리거 공용. 도래 건을 오래된 순으로 최대 {@link #MAX_BATCH_SIZE} 건 처리한다.
     * 건별 독립 트랜잭션 + 건별 예외 격리라 한 건의 실패가 다른 건 처리를 막지 않는다.
     */
    public ScheduledExecutionSummary executeDueTransfers() {
        List<Long> dueIds = scheduledTransferRepository.findDueIds(ScheduledTransferStatus.PENDING,
                LocalDateTime.now(), PageRequest.of(0, MAX_BATCH_SIZE));
        int completed = 0;
        int failed = 0;
        int skipped = 0;
        for (Long id : dueIds) {
            switch (executeOne(id)) {
                case COMPLETED -> completed++;
                case FAILED -> failed++;
                case SKIPPED -> skipped++;
            }
        }
        return new ScheduledExecutionSummary(dueIds.size(), completed, failed, skipped);
    }

    private Outcome executeOne(Long id) {
        try {
            return transactionTemplate.execute(s -> {
                ScheduledTransfer st = scheduledTransferRepository.findByIdForUpdate(id).orElse(null);
                if (st == null || st.getStatus() != ScheduledTransferStatus.PENDING) {
                    return Outcome.SKIPPED; // 다른 실행기가 먼저 처리했거나 그 사이 취소됨
                }
                Transaction tx = transactionService.executeScheduledTransfer(st.getUserId(), st.getFromAccountId(),
                        st.getToAccountId(), st.getAmount(), IdempotencyService.SCHEDULED_TX_KEY_PREFIX + id,
                        "예약이체 #" + id);
                st.complete(tx.getId(), LocalDateTime.now());
                auditLogService.logInTransaction(new AuditEvent(st.getUserId(), ACTION_EXEC, TARGET,
                        String.valueOf(id), AuditResult.SUCCESS,
                        "Executed scheduled transfer " + id + " as transaction " + tx.getId(), null));
                return Outcome.COMPLETED;
            });
        } catch (BusinessException e) {
            return markFailedSafely(id, e.getErrorCode());
        } catch (RuntimeException e) {
            // 락 타임아웃 등 일시 오류: PENDING 으로 두고 다음 주기에 재시도한다.
            log.error("예약 이체 실행 중 일시 오류, 다음 주기에 재시도: scheduledTransferId={}", id, e);
            return Outcome.SKIPPED;
        }
    }

    /** FAILED 기록 자체가 일시 오류로 실패하면 PENDING 으로 두고 다음 주기에 다시 실행·판정한다. */
    private Outcome markFailedSafely(Long id, ErrorCode reason) {
        try {
            return markFailed(id, reason);
        } catch (RuntimeException e) {
            log.error("예약 이체 실패 상태 기록 중 오류, PENDING 유지 후 다음 주기에 재시도: scheduledTransferId={}, reason={}",
                    id, reason.name(), e);
            return Outcome.SKIPPED;
        }
    }

    /** 원장 변경이 롤백된 뒤 별도 트랜잭션에서 FAILED 를 커밋한다. */
    private Outcome markFailed(Long id, ErrorCode reason) {
        return transactionTemplate.execute(s -> {
            ScheduledTransfer st = scheduledTransferRepository.findByIdForUpdate(id).orElse(null);
            if (st == null || st.getStatus() != ScheduledTransferStatus.PENDING) {
                return Outcome.SKIPPED;
            }
            st.fail(reason.name(), LocalDateTime.now());
            auditFailure(st.getUserId(), ACTION_EXEC, TARGET, String.valueOf(id),
                    "Scheduled transfer " + id + " failed: " + reason.name(), null);
            return Outcome.FAILED;
        });
    }

    // ---------------------------------------------------------------- 공통

    private static void requireActive(Account account) {
        if (account.getStatus() == AccountStatus.LOCKED) {
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED);
        }
        if (account.getStatus() == AccountStatus.CLOSED) {
            throw new BusinessException(ErrorCode.ACCOUNT_CLOSED);
        }
    }

    private String toAccountNumber(ScheduledTransfer st) {
        return accountRepository.findById(st.getToAccountId()).map(Account::getAccountNumber).orElse(null);
    }

    private static ScheduledTransferResponse toResponse(ScheduledTransfer st, String toAccountNumber) {
        return new ScheduledTransferResponse(st.getId(), st.getFromAccountId(),
                MaskingUtil.maskAccountNumber(toAccountNumber), st.getAmount(), st.getScheduledAt(), st.getStatus(),
                st.getFailureReason(), st.getExecutedTransactionId(), st.getCreatedAt(), st.getExecutedAt());
    }

    private void auditFailure(Long userId, String action, String targetType, String targetId, String summary,
                              String clientIp) {
        auditLogService.logIndependent(new AuditEvent(userId, action, targetType, targetId, AuditResult.FAILURE,
                summary, clientIp));
    }

    /** PIN 은 해시 입력에서 제외한다 (4~6자리 PIN 은 해시만으로도 역산 가능). */
    private String hash(CreateScheduledTransferRequest req, String toNumber, BigDecimal amount) {
        String canonical = String.join("|", ENDPOINT_CREATE, String.valueOf(req.fromAccountId()), toNumber,
                amount.toPlainString(), String.valueOf(req.scheduledAt()), String.valueOf(req.description()));
        return idempotencyService.hashRequest(canonical);
    }

    private Optional<ScheduledTransferResponse> replay(String key, Long userId, String hash) {
        return idempotencyService.findReplay(key, userId, ENDPOINT_CREATE, hash).map(stored -> {
            try {
                return objectMapper.readValue(stored.body(), ScheduledTransferResponse.class);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Stored idempotent response is unreadable", e);
            }
        });
    }

    private String toJson(ScheduledTransferResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String mask(Account account) {
        return MaskingUtil.maskAccountNumber(account.getAccountNumber());
    }
}
