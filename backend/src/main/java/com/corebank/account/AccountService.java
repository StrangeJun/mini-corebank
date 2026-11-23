package com.corebank.account;

import com.corebank.account.dto.AccountResponse;
import com.corebank.account.dto.CreateAccountRequest;
import com.corebank.audit.AuditEvent;
import com.corebank.audit.AuditLogService;
import com.corebank.auth.jwt.AuthUser;
import com.corebank.common.exception.BusinessException;
import com.corebank.common.exception.ErrorCode;
import com.corebank.common.util.MaskingUtil;
import com.corebank.domain.account.Account;
import com.corebank.domain.account.AccountRepository;
import com.corebank.domain.account.AccountStatus;
import com.corebank.domain.audit.AuditResult;
import com.corebank.domain.user.UserRole;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    static final String ACTION_CREATE = "ACCOUNT_CREATE";
    static final String ACTION_CLOSE = "ACCOUNT_CLOSE";
    private static final String TARGET_ACCOUNT = "ACCOUNT";
    private static final int MAX_NUMBER_ATTEMPTS = 10;

    private final AccountRepository accountRepository;
    private final AccountNumberGenerator numberGenerator;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;
    private final TransactionTemplate attemptTransaction;

    public AccountService(AccountRepository accountRepository, AccountNumberGenerator numberGenerator,
                          PasswordEncoder passwordEncoder, AuditLogService auditLogService,
                          PlatformTransactionManager transactionManager) {
        this.accountRepository = accountRepository;
        // REQUIRED: 컨트롤러처럼 바깥 트랜잭션이 없으면 시도마다 새 트랜잭션이 열려 재시도가 가능하다.
        // 바깥 트랜잭션 안에서 호출되면 거기에 합류한다 (그 경우 충돌 시 바깥 트랜잭션째 롤백).
        this.attemptTransaction = new TransactionTemplate(transactionManager);
        this.numberGenerator = numberGenerator;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
    }

    /**
     * 계좌번호 후보마다 별도 트랜잭션으로 INSERT 한다. 동시 요청이 같은 후보를 써서 UNIQUE 충돌이 나면
     * 그 시도만 롤백하고 새 후보로 재시도한다 (바깥 트랜잭션 없이 호출될 때) (최대 {@value #MAX_NUMBER_ATTEMPTS}회, RV-M1-05).
     */
    public AccountResponse create(Long userId, CreateAccountRequest request, String clientIp) {
        BigDecimal perTxLimit = Objects.requireNonNullElse(request.perTxLimit(), Account.DEFAULT_PER_TX_LIMIT);
        BigDecimal dailyLimit = Objects.requireNonNullElse(request.dailyLimit(), Account.DEFAULT_DAILY_LIMIT);
        String pinHash = passwordEncoder.encode(request.pin());

        for (int attempt = 1; attempt <= MAX_NUMBER_ATTEMPTS; attempt++) {
            String candidate = numberGenerator.generate();
            if (accountRepository.existsByAccountNumber(candidate)) {
                continue;
            }
            try {
                return attemptTransaction.execute(status -> {
                    Account account = accountRepository.saveAndFlush(
                            new Account(candidate, userId, pinHash, perTxLimit, dailyLimit));
                    auditLogService.logInTransaction(new AuditEvent(
                            userId, ACTION_CREATE, TARGET_ACCOUNT, String.valueOf(account.getId()), AuditResult.SUCCESS,
                            "account created " + MaskingUtil.maskAccountNumber(account.getAccountNumber()), clientIp));
                    return AccountResponse.from(account);
                });
            } catch (DataIntegrityViolationException e) {
                // 계좌번호 충돌만 재시도한다. 그 밖의 무결성 위반(FK 등)은 그대로 던진다.
                if (!accountRepository.existsByAccountNumber(candidate)) {
                    throw e;
                }
            }
        }
        throw new IllegalStateException("Failed to issue a unique account number");
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> listMine(Long userId) {
        return accountRepository.findByUserIdAndSystemFalseOrderByCreatedAtAscIdAsc(userId).stream()
                .map(AccountResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public AccountResponse get(AuthUser user, Long accountId) {
        return AccountResponse.from(findReadable(user, accountId));
    }

    /** 본인 계좌 또는 ADMIN 만 조회 가능. 없으면 404, 타인 계좌면 403. */
    @Transactional(readOnly = true)
    public Account findReadable(AuthUser user, Long accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        if (!account.isOwnedBy(user.userId()) && user.role() != UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.FORBIDDEN_ACCOUNT_ACCESS);
        }
        return account;
    }

    /** 행 잠금 후 검증해 해지 도중 입금 등으로 잔액이 바뀌는 경합을 막는다. */
    @Transactional
    public AccountResponse close(Long userId, Long accountId, String clientIp) {
        Account account = accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        if (!account.isOwnedBy(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN_ACCOUNT_ACCESS);
        }
        if (account.getStatus() == AccountStatus.CLOSED) {
            throw new BusinessException(ErrorCode.ACCOUNT_CLOSED);
        }
        if (account.getStatus() == AccountStatus.LOCKED) {
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED);
        }
        if (account.getBalance().signum() != 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "잔액이 남아있는 계좌는 해지할 수 없습니다");
        }

        account.close();
        accountRepository.flush();
        auditLogService.logInTransaction(new AuditEvent(
                userId, ACTION_CLOSE, TARGET_ACCOUNT, String.valueOf(account.getId()), AuditResult.SUCCESS,
                "account closed " + MaskingUtil.maskAccountNumber(account.getAccountNumber()), clientIp));
        return AccountResponse.from(account);
    }

}
