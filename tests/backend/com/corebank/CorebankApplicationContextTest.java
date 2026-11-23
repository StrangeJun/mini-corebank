package com.corebank;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebank.domain.account.Account;
import com.corebank.domain.account.AccountRepository;
import com.corebank.domain.user.User;
import com.corebank.domain.user.UserRepository;
import com.corebank.domain.user.UserRole;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** tests/backend 소스셋 연동 + Flyway V1 스키마/JPA 매핑(ddl-auto=validate) 검증. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CorebankApplicationContextTest {

    @Autowired
    UserRepository userRepository;

    @Autowired
    AccountRepository accountRepository;

    @Test
    void contextLoadsWithFlywaySchema() {
        // 시드(SYSTEM-VAULT)가 Flyway 스키마 위에 저장돼 있으면 스키마·매핑이 정상이다.
        assertThat(accountRepository.findByAccountNumber("SYSTEM-VAULT")).isPresent();
    }

    @Test
    void savesAccountWithDefaults() {
        User user = userRepository.save(new User("a@test.com", "hash", "홍길동", UserRole.USER));
        Account account = accountRepository.saveAndFlush(new Account("110-000-000001", user.getId(), false, "pinhash"));

        assertThat(account.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(account.getPerTxLimit()).isEqualByComparingTo("10000000");
        assertThat(account.getVersion()).isZero();
    }
}
