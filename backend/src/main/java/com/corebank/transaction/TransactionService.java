package com.corebank.transaction;

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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 */
@Service
public class TransactionService {

    static final String ENDPOINT_DEPOSIT = "POST /api/transactions/deposit";
    static final String ENDPOINT_WITHDRAW = "POST /api/transactions/withdraw";
    static final String ENDPOINT_TRANSFER = "POST /api/transactions/transfer";
    private static final String TARGET_TRANSACTION = "TRANSACTION";
    private static final int MAX_AMOUNT_DIGITS = 19;
    private static final Runnable NO_REPLAY_GUARD = () -> { };
    private static final EnumSet<TransactionType> OUTGOING_TYPES =
            EnumSet.of(TransactionType.WITHDRAWAL, TransactionType.TRANSFER);

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final IdempotencyService idempotencyService;
    private final AuditLogService auditLogService;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;

    public TransactionService(AccountRepository accountRepository, TransactionRepository transactionRepository,
                              LedgerEntryRepository ledgerEntryRepository, IdempotencyService idempotencyService,
                              AuditLogService auditLogService, PasswordEncoder passwordEncoder,
                              ObjectMapper objectMapper) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.idempotencyService = idempotencyService;
        this.auditLogService = auditLogService;
        this.passwordEncoder = passwordEncoder;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public TransactionResponse deposit(Long userId, String idempotencyKey, DepositRequest req, String clientIp) {
        return audited(userId, TransactionType.DEPOSIT, req.accountId(), clientIp, () -> {
            BigDecimal amount = validAmount(req.amount());
            String hash = hash(ENDPOINT_DEPOSIT, req.accountId(), null, null, amount, req.description());
            return idempotent(idempotencyKey, userId, ENDPOINT_DEPOSIT, hash, NO_REPLAY_GUARD,
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

    @Transactional
    public TransactionResponse withdraw(Long userId, String idempotencyKey, WithdrawRequest req, String clientIp) {
        return audited(userId, TransactionType.WITHDRAWAL, req.accountId(), clientIp, () -> {
            BigDecimal amount = validAmount(req.amount());
            String hash = hash(ENDPOINT_WITHDRAW, req.accountId(), null, null, amount, req.description());
            return idempotent(idempotencyKey, userId, ENDPOINT_WITHDRAW, hash,
                    () -> requirePinForReplay(req.accountId(), req.pin()),
                    () -> lockInOrder(req.accountId(), vaultId()), locked -> {
                Account customer = requireAccount(locked, req.accountId());
                Account vault = locked.get(vaultId());
                requireOwner(customer, userId);
                requireActive(customer);
                requireActive(vault);
                requirePin(customer, req.pin());
                requireWithinLimitsAndBalance(customer, amount);

                Transaction tx = post(TransactionType.WITHDRAWAL, customer, vault, amount, idempotencyKey, req.description());
                auditSuccess(userId, tx, "WITHDRAWAL " + amount.toPlainString() + " from " + mask(customer), clientIp);
                return new TransactionResponse(tx.getId(), tx.getTxType(), amount, null, null,
                        customer.getBalance(), tx.getDescription(), tx.getCreatedAt());
            });
        });
    }

    @Transactional
    public TransactionResponse transfer(Long userId, String idempotencyKey, TransferRequest req, String clientIp) {
        return audited(userId, TransactionType.TRANSFER, req.fromAccountId(), clientIp, () -> {
            BigDecimal amount = validAmount(req.amount());
            String hash = hash(ENDPOINT_TRANSFER, req.fromAccountId(), req.toAccountId(), req.toAccountNumber(),
                    amount, req.description());
            Long toId = resolveTransferTarget(req);
            if (toId.equals(req.fromAccountId())) {
                throw new BusinessException(ErrorCode.SAME_ACCOUNT_TRANSFER);
            }
            return idempotent(idempotencyKey, userId, ENDPOINT_TRANSFER, hash,
                    () -> requirePinForReplay(req.fromAccountId(), req.pin()),
                    () -> lockInOrder(req.fromAccountId(), toId), locked -> {
                Account from = requireAccount(locked, req.fromAccountId());
                Account to = requireAccount(locked, toId);
                if (to.isSystem()) {
                    throw new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND);
                }
                requireOwner(from, userId);
                requireActive(from);
                requireActive(to);
                requirePin(from, req.pin());
                requireWithinLimitsAndBalance(from, amount);

                Transaction tx = post(TransactionType.TRANSFER, from, to, amount, idempotencyKey, req.description());
                auditSuccess(userId, tx, "TRANSFER " + amount.toPlainString() + " from " + mask(from) + " to " + mask(to),
                        clientIp);
                return new TransactionResponse(tx.getId(), tx.getTxType(), amount, from.getId(), mask(to),
                        from.getBalance(), tx.getDescription(), tx.getCreatedAt());
            });
        });
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

    static BigDecimal validAmount(BigDecimal amount) {
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

    /**
     * 재생 경로용 PIN 검증. 계좌 엔티티를 1차 캐시에 올리지 않도록 pin_hash 만 조회한다
     * (이후 FOR UPDATE 조회가 캐시된 옛 값을 받지 않게).
     */
    private void requirePinForReplay(Long accountId, String pin) {
        String pinHash = accountRepository.findPinHashById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        if (pin == null || !passwordEncoder.matches(pin, pinHash)) {
            throw new BusinessException(ErrorCode.INVALID_PIN);
        }
    }

    /** 기본 PIN 일치 확인. 5회 오류 잠금은 T-008 에서 추가한다. */
    private void requirePin(Account account, String pin) {
        if (pin == null || !passwordEncoder.matches(pin, account.getPinHash())) {
            throw new BusinessException(ErrorCode.INVALID_PIN);
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
     * 저장된 응답이 있으면 replayGuard 통과 후 재생한다. 없으면 잠금 → (잠금 대기 중 같은 키가 처리됐는지) 재확인
     * → 실행 → 응답 저장.
     * <p>PIN 은 요청 해시에 넣지 않으므로(역산 위험), 재생 전에 replayGuard 로 PIN 을 다시 검증해
     * 같은 키 + 틀린 PIN 으로 성공 응답을 받아내는 우회를 막는다.
     */
    private TransactionResponse idempotent(String key, Long userId, String endpoint, String hash,
                                           Runnable replayGuard,
                                           Supplier<Map<Long, Account>> lock,
                                           Function<Map<Long, Account>, TransactionResponse> execute) {
        Optional<TransactionResponse> replay = replay(key, userId, endpoint, hash);
        if (replay.isPresent()) {
            replayGuard.run();
            return replay.get();
        }
        Map<Long, Account> locked = lock.get();
        replay = replay(key, userId, endpoint, hash);
        if (replay.isPresent()) {
            replayGuard.run();
            return replay.get();
        }
        TransactionResponse response = execute.apply(locked);
        idempotencyService.saveResponse(key, userId, endpoint, hash, 200, toJson(response));
        return response;
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
