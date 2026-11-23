package com.corebank.transaction;

import com.corebank.account.AccountPinVerificationService;
import com.corebank.audit.AuditEvent;
import com.corebank.audit.AuditLogService;
import com.corebank.common.exception.BusinessException;
import com.corebank.common.exception.ErrorCode;
import com.corebank.common.idempotency.IdempotencyService;
import com.corebank.common.idempotency.IdempotentResponse;
import com.corebank.common.util.MaskingUtil;
import com.corebank.domain.account.Account;
import com.corebank.domain.account.AccountRepository;
import com.corebank.domain.account.AccountStatus;
import com.corebank.domain.audit.AuditResult;
import com.corebank.domain.ledger.EntryType;
import com.corebank.domain.ledger.LedgerEntry;
import com.corebank.domain.ledger.LedgerEntryRepository;
import com.corebank.domain.transaction.Transaction;
import com.corebank.domain.transaction.TransactionRepository;
import com.corebank.domain.transaction.TransactionStatus;
import com.corebank.domain.transaction.TransactionType;
import com.corebank.seed.SystemSeedInitializer;
import com.corebank.transaction.dto.DepositRequest;
import com.corebank.transaction.dto.TransactionResponse;
import com.corebank.transaction.dto.TransferRequest;
import com.corebank.transaction.dto.WithdrawRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Arrays;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 복식부기 원장 기반 입금·출금·이체.
 *
 * <p>처리 순서 (한 트랜잭션):
 * <ol>
 *   <li>금액·멱등키 검증, 저장된 응답이 있으면 재생</li>
 *   <li>관련 계좌(SYSTEM-VAULT 포함)를 id 오름차순으로 PESSIMISTIC_WRITE 잠금 — 데드락 방지</li>
 *   <li>잠금 후 멱등키 재확인 — 같은 키 동시 요청은 먼저 끝난 결과를 재생</li>
 *   <li>상태·소유권·PIN·한도·잔액 검증</li>
 *   <li>transactions 1건 + ledger_entries 2건(DEBIT/CREDIT) + 잔액 갱신 + 멱등 응답 저장 + 감사 로그</li>
 * </ol>
 * 비즈니스 실패는 별도 트랜잭션 감사 로그를 남기고 전체 롤백된다 (멱등키도 소비되지 않음).
 *
 * <p>출금·이체는 위 트랜잭션에 들어가기 <b>전에</b> {@link AccountPinVerificationService} 로 PIN 을 검증한다.
 * PIN 실패 집계·잠금은 본 거래 롤백과 무관하게 커밋돼야 하고, 본 거래가 출금 계좌 행 잠금을 쥔 채
 * REQUIRES_NEW 로 같은 행을 잠그면 자기 교착이 되므로 순서를 분리했다. 멱등 재생도 이 검증을 통과해야 한다.
 */
@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    static final String ENDPOINT_DEPOSIT = "POST /api/transactions/deposit";
    static final String ENDPOINT_WITHDRAW = "POST /api/transactions/withdraw";
    static final String ENDPOINT_TRANSFER = "POST /api/transactions/transfer";
    private static final String TARGET_TRANSACTION = "TRANSACTION";
    private static final int MAX_AMOUNT_DIGITS = 19;
    private static final EnumSet<TransactionType> OUTGOING_TYPES =
            EnumSet.of(TransactionType.WITHDRAWAL, TransactionType.TRANSFER);

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final IdempotencyService idempotencyService;
    private final AuditLogService auditLogService;
    private final AccountPinVerificationService pinVerificationService;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public TransactionService(AccountRepository accountRepository, TransactionRepository transactionRepository,
                              LedgerEntryRepository ledgerEntryRepository, IdempotencyService idempotencyService,
                              AuditLogService auditLogService, AccountPinVerificationService pinVerificationService,
                              PlatformTransactionManager transactionManager, ObjectMapper objectMapper) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.idempotencyService = idempotencyService;
        this.auditLogService = auditLogService;
        this.pinVerificationService = pinVerificationService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    @Transactional
    public TransactionResponse deposit(Long userId, String idempotencyKey, DepositRequest req, String clientIp) {
        return audited(userId, TransactionType.DEPOSIT, req.accountId(), clientIp, () -> {
            BigDecimal amount = validAmount(req.amount());
            String hash = hash(ENDPOINT_DEPOSIT, req.accountId(), null, null, amount, req.description());
            return idempotent(idempotencyKey, userId, ENDPOINT_DEPOSIT, hash,
                    () -> lockInOrder(req.accountId(), vaultId()), locked -> {
                Account customer = requireAccount(locked, req.accountId());
                Account vault = locked.get(vaultId());
                requireOwner(customer, userId);
                requireActive(customer);
                requireActive(vault);

                Transaction tx = post(TransactionType.DEPOSIT, vault, customer, amount, idempotencyKey, req.description());
                auditSuccess(userId, tx, "DEPOSIT " + amount.toPlainString() + " to " + mask(customer), clientIp);
                return new TransactionResponse(tx.getId(), tx.getTxType(), amount, null, null,
                        customer.getBalance(), tx.getDescription(), tx.getCreatedAt());
            });
        });
    }

    /** 트랜잭션 경계는 메서드 안에서 연다 (PIN 검증을 본 트랜잭션 밖에서 먼저 커밋하기 위해). */
    public TransactionResponse withdraw(Long userId, String idempotencyKey, WithdrawRequest req, String clientIp) {
        return audited(userId, TransactionType.WITHDRAWAL, req.accountId(), clientIp, () -> {
            BigDecimal amount = validAmount(req.amount());
            String hash = hash(ENDPOINT_WITHDRAW, req.accountId(), null, null, amount, req.description());
            IdempotencyService.validateKey(idempotencyKey);
            pinVerificationService.verify(userId, req.accountId(), req.pin(), clientIp);
            return inTransaction(() -> idempotent(idempotencyKey, userId, ENDPOINT_WITHDRAW, hash,
                    () -> lockInOrder(req.accountId(), vaultId()), locked -> {
                Account customer = requireAccount(locked, req.accountId());
                Account vault = locked.get(vaultId());
                requireOwner(customer, userId);
                requireActive(customer);
                requireActive(vault);
                requireWithinLimitsAndBalance(customer, amount);

                Transaction tx = post(TransactionType.WITHDRAWAL, customer, vault, amount, idempotencyKey, req.description());
                auditSuccess(userId, tx, "WITHDRAWAL " + amount.toPlainString() + " from " + mask(customer), clientIp);
                return new TransactionResponse(tx.getId(), tx.getTxType(), amount, null, null,
                        customer.getBalance(), tx.getDescription(), tx.getCreatedAt());
            }));
        });
    }

    /** 트랜잭션 경계는 메서드 안에서 연다 (PIN 검증을 본 트랜잭션 밖에서 먼저 커밋하기 위해). */
    public TransactionResponse transfer(Long userId, String idempotencyKey, TransferRequest req, String clientIp) {
        return audited(userId, TransactionType.TRANSFER, req.fromAccountId(), clientIp, () -> {
            BigDecimal amount = validAmount(req.amount());
            String hash = hash(ENDPOINT_TRANSFER, req.fromAccountId(), req.toAccountId(), req.toAccountNumber(),
                    amount, req.description());
            Long toId = resolveTransferTarget(req);
            if (toId.equals(req.fromAccountId())) {
                throw new BusinessException(ErrorCode.SAME_ACCOUNT_TRANSFER);
            }
            IdempotencyService.validateKey(idempotencyKey);
            pinVerificationService.verify(userId, req.fromAccountId(), req.pin(), clientIp);
            return inTransaction(() -> idempotent(idempotencyKey, userId, ENDPOINT_TRANSFER, hash,
                    () -> lockInOrder(req.fromAccountId(), toId), locked -> {
                Account from = requireAccount(locked, req.fromAccountId());
                Account to = requireAccount(locked, toId);
                if (to.isSystem()) {
                    throw new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND);
                }
                requireOwner(from, userId);
                requireActive(from);
                requireActive(to);
                requireWithinLimitsAndBalance(from, amount);

                Transaction tx = post(TransactionType.TRANSFER, from, to, amount, idempotencyKey, req.description());
                auditSuccess(userId, tx, "TRANSFER " + amount.toPlainString() + " from " + mask(from) + " to " + mask(to),
                        clientIp);
                return new TransactionResponse(tx.getId(), tx.getTxType(), amount, from.getId(), mask(to),
                        from.getBalance(), tx.getDescription(), tx.getCreatedAt());
            }));
        });
    }

    /**
     * 예약 이체 실행용 이체. 호출자 트랜잭션(예약 이체 행 잠금을 쥔 상태) 안에서만 실행된다.
     * <p>PIN 은 등록 시 검증했으므로 다시 묻지 않는다. 실행 시점의 상태·소유권·한도·잔액은 즉시 이체와 같은 규칙으로
     * 재검증한다. 같은 내부 멱등키({@code SCHED-TX-{id}})의 거래가 이미 있으면 새로 기입하지 않고 그 거래를 돌려주되,
     * 그 거래의 출금·입금 계좌와 금액이 예약과 정확히 일치할 때만 재사용한다 (불일치면 INTERNAL_ERROR — 예약을
     * 엉뚱한 거래로 COMPLETED 처리하지 않는다).
     *
     * @throws BusinessException 검증 실패 (호출자 트랜잭션은 롤백돼야 한다)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Transaction executeScheduledTransfer(Long userId, Long fromAccountId, Long toAccountId, BigDecimal amount,
                                                String internalKey, String description) {
        Optional<Transaction> existing = transactionRepository.findByIdempotencyKey(internalKey);
        if (existing.isPresent()) {
            Transaction tx = existing.get();
            if (!fromAccountId.equals(tx.getFromAccountId()) || !toAccountId.equals(tx.getToAccountId())
                    || tx.getAmount() == null || tx.getAmount().compareTo(amount) != 0) {
                log.error("예약 이체 내부 멱등키 거래 불일치: key={}, transactionId={}", internalKey, tx.getId());
                throw new BusinessException(ErrorCode.INTERNAL_ERROR);
            }
            return tx;
        }
        Map<Long, Account> locked = lockInOrder(fromAccountId, toAccountId);
        Account from = requireAccount(locked, fromAccountId);
        Account to = requireAccount(locked, toAccountId);
        if (to.isSystem()) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND);
        }
        requireOwner(from, userId);
        requireActive(from);
        requireActive(to);
        requireWithinLimitsAndBalance(from, amount);

        Transaction tx = post(TransactionType.TRANSFER, from, to, amount, internalKey, description);
        auditSuccess(userId, tx, "SCHEDULED TRANSFER " + amount.toPlainString() + " from " + mask(from)
                + " to " + mask(to), null);
        return tx;
    }

    // ---------------------------------------------------------------- 원장 기입

    /** 거래 헤더 1건 + DEBIT/CREDIT 분개 2건. balance_after 는 각 분개 반영 직후 잔액. */
    private Transaction post(TransactionType type, Account debitAccount, Account creditAccount, BigDecimal amount,
                             String idempotencyKey, String description) {
        Transaction tx = transactionRepository.save(new Transaction(type, TransactionStatus.COMPLETED, amount,
                debitAccount.getId(), creditAccount.getId(), idempotencyKey, description));

        debitAccount.debit(amount);
        creditAccount.credit(amount);
        ledgerEntryRepository.save(new LedgerEntry(tx.getId(), debitAccount.getId(), EntryType.DEBIT,
                amount, debitAccount.getBalance()));
        ledgerEntryRepository.save(new LedgerEntry(tx.getId(), creditAccount.getId(), EntryType.CREDIT,
                amount, creditAccount.getBalance()));
        return tx;
    }

    // ---------------------------------------------------------------- 잠금

    /** id 오름차순으로 SELECT ... FOR UPDATE. 모든 거래가 같은 순서로 잠그므로 교차 이체도 데드락이 없다. */
    private Map<Long, Account> lockInOrder(Long... accountIds) {
        Map<Long, Account> locked = new TreeMap<>();
        for (Long id : new TreeSet<>(Arrays.asList(accountIds))) {
            accountRepository.findByIdForUpdate(id).ifPresent(a -> locked.put(id, a));
        }
        return locked;
    }

    private Long vaultId() {
        return accountRepository.findIdByAccountNumber(SystemSeedInitializer.SYSTEM_VAULT_ACCOUNT_NUMBER)
                .orElseThrow(() -> new IllegalStateException("SYSTEM-VAULT account is missing"));
    }

    private Long resolveTransferTarget(TransferRequest req) {
        if (req.toAccountNumber() != null && !req.toAccountNumber().isBlank()) {
            return accountRepository.findIdByAccountNumber(req.toAccountNumber().trim())
                    .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        }
        if (req.toAccountId() != null) {
            return req.toAccountId();
        }
        throw new BusinessException(ErrorCode.INVALID_INPUT, "입금 계좌를 지정해야 합니다.");
    }

    // ---------------------------------------------------------------- 검증

    public static BigDecimal validAmount(BigDecimal amount) {
        if (amount == null || amount.scale() > 0 || amount.signum() <= 0
                || amount.precision() - amount.scale() > MAX_AMOUNT_DIGITS) {
            throw new BusinessException(ErrorCode.INVALID_AMOUNT);
        }
        return amount.setScale(0);
    }

    private static Account requireAccount(Map<Long, Account> locked, Long id) {
        Account account = locked.get(id);
        if (account == null) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND);
        }
        return account;
    }

    private static void requireOwner(Account account, Long userId) {
        if (!account.isOwnedBy(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN_ACCOUNT_ACCESS);
        }
    }

    private static void requireActive(Account account) {
        if (account.getStatus() == AccountStatus.LOCKED) {
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED);
        }
        if (account.getStatus() == AccountStatus.CLOSED) {
            throw new BusinessException(ErrorCode.ACCOUNT_CLOSED);
        }
    }

    /** 출금 계좌가 잠긴 상태에서 계산하므로 같은 계좌의 동시 거래가 한도를 우회할 수 없다. */
    private void requireWithinLimitsAndBalance(Account from, BigDecimal amount) {
        if (amount.compareTo(from.getPerTxLimit()) > 0) {
            throw new BusinessException(ErrorCode.PER_TX_LIMIT_EXCEEDED);
        }
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        BigDecimal todayOutgoing = transactionRepository.sumOutgoing(from.getId(), TransactionStatus.COMPLETED,
                OUTGOING_TYPES, dayStart, dayStart.plusDays(1));
        if (todayOutgoing.add(amount).compareTo(from.getDailyLimit()) > 0) {
            throw new BusinessException(ErrorCode.DAILY_LIMIT_EXCEEDED);
        }
        if (from.getBalance().compareTo(amount) < 0) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_BALANCE);
        }
    }

    // ---------------------------------------------------------------- 멱등성

    /**
     * 저장된 응답이 있으면 재생한다. 없으면 잠금 → (잠금 대기 중 같은 키가 처리됐는지) 재확인 → 실행 → 응답 저장.
     * <p>PIN 은 요청 해시에 넣지 않으므로(역산 위험), 출금·이체는 이 메서드 전에 PIN 검증을 통과해야 한다
     * (같은 키 + 틀린 PIN 으로 성공 응답을 받아내는 우회 차단).
     */
    private TransactionResponse idempotent(String key, Long userId, String endpoint, String hash,
                                           Supplier<Map<Long, Account>> lock,
                                           Function<Map<Long, Account>, TransactionResponse> execute) {
        Optional<TransactionResponse> replay = replay(key, userId, endpoint, hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        Map<Long, Account> locked = lock.get();
        replay = replay(key, userId, endpoint, hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        TransactionResponse response = execute.apply(locked);
        idempotencyService.saveResponse(key, userId, endpoint, hash, 200, toJson(response));
        return response;
    }

    private TransactionResponse inTransaction(Supplier<TransactionResponse> body) {
        return transactionTemplate.execute(status -> body.get());
    }

    private Optional<TransactionResponse> replay(String key, Long userId, String endpoint, String hash) {
        return idempotencyService.findReplay(key, userId, endpoint, hash).map(this::fromStored);
    }

    /** PIN 은 해시 입력에서 제외한다 (4~6자리 PIN 은 해시만으로도 역산 가능). */
    private String hash(String endpoint, Long accountId, Long toAccountId, String toAccountNumber,
                        BigDecimal amount, String description) {
        String canonical = String.join("|", endpoint, String.valueOf(accountId), String.valueOf(toAccountId),
                String.valueOf(toAccountNumber == null ? null : toAccountNumber.trim()), amount.toPlainString(),
                String.valueOf(description));
        return idempotencyService.hashRequest(canonical);
    }

    private TransactionResponse fromStored(IdempotentResponse stored) {
        try {
            return objectMapper.readValue(stored.body(), TransactionResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored idempotent response is unreadable", e);
        }
    }

    private String toJson(TransactionResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- 감사 로그

    /** 비즈니스 실패는 본 트랜잭션 롤백과 무관하게 남도록 별도 트랜잭션으로 기록한 뒤 다시 던진다. */
    private TransactionResponse audited(Long userId, TransactionType type, Long accountId, String clientIp,
                                        Supplier<TransactionResponse> action) {
        try {
            return action.get();
        } catch (BusinessException e) {
            auditLogService.logIndependent(new AuditEvent(userId, type.name(), "ACCOUNT", String.valueOf(accountId),
                    AuditResult.FAILURE, type.name() + " failed: " + e.getErrorCode().getCode(), clientIp));
            throw e;
        }
    }

    private void auditSuccess(Long userId, Transaction tx, String summary, String clientIp) {
        auditLogService.logInTransaction(new AuditEvent(userId, tx.getTxType().name(), TARGET_TRANSACTION,
                String.valueOf(tx.getId()), AuditResult.SUCCESS, summary, clientIp));
    }

    private static String mask(Account account) {
        return MaskingUtil.maskAccountNumber(account.getAccountNumber());
    }
}
