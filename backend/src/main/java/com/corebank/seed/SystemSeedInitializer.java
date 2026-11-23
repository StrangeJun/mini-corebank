package com.corebank.seed;

import com.corebank.domain.account.Account;
import com.corebank.domain.account.AccountRepository;
import com.corebank.domain.user.User;
import com.corebank.domain.user.UserRepository;
import com.corebank.domain.user.UserRole;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 기동 시 시스템 시재 계좌와 기본 관리자를 없을 때만 생성한다 (idempotent). */
@Component
public class SystemSeedInitializer implements ApplicationRunner {

    public static final String SYSTEM_VAULT_ACCOUNT_NUMBER = "SYSTEM-VAULT";

    private static final Logger log = LoggerFactory.getLogger(SystemSeedInitializer.class);

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminEmail;
    private final String adminPassword;
    private final String adminName;

    public SystemSeedInitializer(AccountRepository accountRepository, UserRepository userRepository,
                                 PasswordEncoder passwordEncoder,
                                 @Value("${corebank.seed.admin-email}") String adminEmail,
                                 @Value("${corebank.seed.admin-password}") String adminPassword,
                                 @Value("${corebank.seed.admin-name}") String adminName) {
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
        this.adminName = adminName;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (accountRepository.findByAccountNumber(SYSTEM_VAULT_ACCOUNT_NUMBER).isEmpty()) {
            // 시스템 계좌 PIN 은 아무도 알 수 없는 임의 값의 해시로 둔다.
            String dummyPinHash = passwordEncoder.encode(UUID.randomUUID().toString());
            accountRepository.save(new Account(SYSTEM_VAULT_ACCOUNT_NUMBER, null, true, dummyPinHash));
            log.info("Seeded system vault account");
        }
        if (!userRepository.existsByEmail(adminEmail)) {
            userRepository.save(new User(adminEmail, passwordEncoder.encode(adminPassword), adminName, UserRole.ADMIN));
            log.info("Seeded default admin user");
        }
    }
}
