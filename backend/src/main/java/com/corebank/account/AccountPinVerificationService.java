package com.corebank.account;

import com.corebank.audit.AuditEvent;
import com.corebank.audit.AuditLogService;
import com.corebank.common.exception.BusinessException;
import com.corebank.common.exception.ErrorCode;
import com.corebank.domain.account.Account;
import com.corebank.domain.account.AccountRepository;
import com.corebank.domain.account.AccountStatus;
import com.corebank.domain.audit.AuditResult;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계좌 PIN 검증 + 연속 오류 잠금.
 *
 * <p>REQUIRES_NEW 로 실행하고 BusinessException 에도 커밋한다(noRollbackFor). 그래서 PIN 오류로 본 거래가
 * 롤백돼도 pin_fail_count 증가, LOCKED 전환, PIN_FAIL/ACCOUNT_LOCKED 감사 로그는 남는다.
 * <p>호출자는 이 메서드를 대상 계좌 행 잠금을 잡기 <b>전에</b> 불러야 한다. 호출자가 같은 행의 FOR UPDATE 를
 * 쥔 채 부르면 일시 중단된 바깥 트랜잭션을 기다리는 자기 교착이 된다.
 */
@Service
public class AccountPinVerificationService {

    public static final int MAX_PIN_FAILURES = 5;
    private static final String TARGET_ACCOUNT = "ACCOUNT";

    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;

    public AccountPinVerificationService(AccountRepository accountRepository, PasswordEncoder passwordEncoder,
                                         AuditLogService auditLogService) {
        this.accountRepository = accountRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
    }

    /**
     * 소유권·상태를 먼저 확인한다(타인이 남의 계좌를 잠그지 못하게). 그 뒤 PIN 이 맞으면 실패 횟수를 0 으로,
     * 틀리면 +1 하고 5회째에 LOCKED 로 전환한다.
     *
     * @throws BusinessException ACCOUNT_NOT_FOUND, FORBIDDEN_ACCOUNT_ACCESS, ACCOUNT_LOCKED, ACCOUNT_CLOSED,
     *                           INVALID_PIN
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = BusinessException.class)
    public void verify(Long userId, Long accountId, String pin, String clientIp) {
        Account account = accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        if (!account.isOwnedBy(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN_ACCOUNT_ACCESS);
        }
        if (account.getStatus() == AccountStatus.LOCKED) {
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED);
        }
        if (account.getStatus() == AccountStatus.CLOSED) {
            throw new BusinessException(ErrorCode.ACCOUNT_CLOSED);
        }

        if (pin != null && passwordEncoder.matches(pin, account.getPinHash())) {
            if (account.getPinFailCount() > 0) {
                account.resetPinFailures();
            }
            return;
        }

        boolean lockedNow = account.recordPinFailure(MAX_PIN_FAILURES);
        audit(userId, "PIN_FAIL", account,
                "PIN mismatch (" + account.getPinFailCount() + "/" + MAX_PIN_FAILURES + ")", clientIp);
        if (lockedNow) {
            audit(userId, "ACCOUNT_LOCKED", account,
                    "Locked after " + MAX_PIN_FAILURES + " consecutive PIN failures", clientIp);
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED);
        }
        throw new BusinessException(ErrorCode.INVALID_PIN);
    }

    private void audit(Long userId, String action, Account account, String summary, String clientIp) {
        auditLogService.logInTransaction(new AuditEvent(userId, action, TARGET_ACCOUNT,
                String.valueOf(account.getId()), AuditResult.FAILURE, summary, clientIp));
    }
}
