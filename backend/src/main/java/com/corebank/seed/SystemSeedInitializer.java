package com.corebank.seed;

import com.corebank.domain.account.Account;
import com.corebank.domain.account.AccountRepository;
import com.corebank.domain.user.User;
import com.corebank.domain.user.UserRepository;
import com.corebank.domain.user.UserRole;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 기동 시 시스템 시재 계좌와 기본 관리자를 없을 때만 생성한다 (idempotent).
 * 다중 인스턴스가 동시에 기동해 UNIQUE 충돌이 나면 다른 인스턴스가 먼저 만든 것으로 보고 통과한다 (RV-M1-07).
 */
@Component
public class SystemSeedInitializer implements ApplicationRunner {

    public static final String SYSTEM_VAULT_ACCOUNT_NUMBER = "SYSTEM-VAULT";

    private static final Logger log = LoggerFactory.getLogger(SystemSeedInitializer.class);

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transaction;
    private final String adminEmail;
    private final String adminPassword;
    private final String adminName;

    public SystemSeedInitializer(AccountRepository accountRepository, UserRepository userRepository,
                                 PasswordEncoder passwordEncoder, PlatformTransactionManager transactionManager,
                                 @Value("${corebank.seed.admin-email}") String adminEmail,
                                 @Value("${corebank.seed.admin-password}") String adminPassword,
                                 @Value("${corebank.seed.admin-name}") String adminName) {
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.transaction = new TransactionTemplate(transactionManager);
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
        this.adminName = adminName;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (accountRepository.findIdByAccountNumber(SYSTEM_VAULT_ACCOUNT_NUMBER).isEmpty()) {
            insertSystemVault();
        }
        if (!userRepository.existsByEmail(adminEmail)) {
            insertAdmin();
        }
    }

    /** 존재 확인 없이 INSERT 한다. 충돌하면 이미 시드된 것으로 본다. */
    void insertSystemVault() {
        // 시스템 계좌 PIN 은 아무도 알 수 없는 임의 값의 해시로 둔다.
        String dummyPinHash = passwordEncoder.encode(UUID.randomUUID().toString());
        insertOnce("system vault account",
                () -> accountRepository.findIdByAccountNumber(SYSTEM_VAULT_ACCOUNT_NUMBER).isPresent(),
                () -> accountRepository.saveAndFlush(new Account(SYSTEM_VAULT_ACCOUNT_NUMBER, null, true, dummyPinHash)));
    }

    void insertAdmin() {
        String passwordHash = passwordEncoder.encode(adminPassword);
        insertOnce("default admin user",
                () -> userRepository.existsByEmail(adminEmail),
                () -> userRepository.saveAndFlush(new User(adminEmail, passwordHash, adminName, UserRole.ADMIN)));
    }

    private void insertOnce(String what, BooleanSupplier alreadyExists, Runnable insert) {
        try {
            transaction.executeWithoutResult(status -> insert.run());
            log.info("Seeded {}", what);
        } catch (DataIntegrityViolationException e) {
            if (!alreadyExists.getAsBoolean()) {
                throw e;
            }
            log.info("Seed skipped, {} already created by another instance", what);
        }
    }
}
