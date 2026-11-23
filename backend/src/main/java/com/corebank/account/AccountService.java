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
import org.springframework.security.crypto.password.PasswordEncoder;
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

    public AccountService(AccountRepository accountRepository, AccountNumberGenerator numberGenerator,
                          PasswordEncoder passwordEncoder, AuditLogService auditLogService) {
        this.accountRepository = accountRepository;
        this.numberGenerator = numberGenerator;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public AccountResponse create(Long userId, CreateAccountRequest request, String clientIp) {
        BigDecimal perTxLimit = Objects.requireNonNullElse(request.perTxLimit(), Account.DEFAULT_PER_TX_LIMIT);
        BigDecimal dailyLimit = Objects.requireNonNullElse(request.dailyLimit(), Account.DEFAULT_DAILY_LIMIT);

        Account account = accountRepository.saveAndFlush(new Account(
                issueAccountNumber(), userId, passwordEncoder.encode(request.pin()), perTxLimit, dailyLimit));

        auditLogService.logInTransaction(new AuditEvent(
                userId, ACTION_CREATE, TARGET_ACCOUNT, String.valueOf(account.getId()), AuditResult.SUCCESS,
                "account created " + MaskingUtil.maskAccountNumber(account.getAccountNumber()), clientIp));
        return AccountResponse.from(account);
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> listMine(Long userId) {
        return accountRepository.findByUserIdAndSystemFalseOrderByCreatedAtAscIdAsc(userId).stream()
                .map(AccountResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public AccountResponse get(AuthUser user, Long accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        if (!account.isOwnedBy(user.userId()) && user.role() != UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.FORBIDDEN_ACCOUNT_ACCESS);
        }
        return AccountResponse.from(account);
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

    private String issueAccountNumber() {
        for (int i = 0; i < MAX_NUMBER_ATTEMPTS; i++) {
            String candidate = numberGenerator.generate();
            if (!accountRepository.existsByAccountNumber(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Failed to issue a unique account number");
    }
}
