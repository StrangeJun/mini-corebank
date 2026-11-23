package com.corebank.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebank.domain.account.Account;
import com.corebank.domain.account.AccountRepository;
import com.corebank.domain.account.AccountStatus;
import com.corebank.domain.user.User;
import com.corebank.domain.user.UserRepository;
import com.corebank.domain.user.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class SystemSeedInitializerTest {

    @Autowired SystemSeedInitializer initializer;
    @Autowired AccountRepository accountRepository;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void seedsSystemVaultAndAdminOnStartup() {
        Account vault = accountRepository.findByAccountNumber("SYSTEM-VAULT").orElseThrow();
        assertThat(vault.isSystem()).isTrue();
        assertThat(vault.getUserId()).isNull();
        assertThat(vault.getBalance()).isEqualByComparingTo("0");
        assertThat(vault.getStatus()).isEqualTo(AccountStatus.ACTIVE);

        User admin = userRepository.findByEmail("admin@corebank.com").orElseThrow();
        assertThat(admin.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(admin.getName()).isEqualTo("시스템관리자");
        assertThat(passwordEncoder.matches("Admin1234!", admin.getPasswordHash())).isTrue();
    }

    @Test
    void rerunIsIdempotent() {
        long accounts = accountRepository.count();
        long users = userRepository.count();

        initializer.run(new DefaultApplicationArguments());

        assertThat(accountRepository.count()).isEqualTo(accounts);
        assertThat(userRepository.count()).isEqualTo(users);
    }
}
