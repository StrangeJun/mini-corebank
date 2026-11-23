package com.corebank.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebank.account.dto.AccountResponse;
import com.corebank.account.dto.CreateAccountRequest;
import com.corebank.domain.user.User;
import com.corebank.domain.user.UserRepository;
import com.corebank.domain.user.UserRole;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 동시 개설 요청들이 같은 계좌번호 후보를 받아 동시에 INSERT 하면 UNIQUE 충돌이 난다.
 * 충돌한 요청은 새 후보로 재시도해 모두 성공해야 한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class AccountNumberCollisionTest {

    static final int THREADS = 6;
    static final String SAME_CANDIDATE = "110-333-333333";

    @TestConfiguration
    static class CollidingGeneratorConfig {
        @Bean
        @Primary
        CollidingGenerator collidingGenerator() {
            return new CollidingGenerator();
        }
    }

    /** 활성화되면 각 스레드의 첫 후보는 모두 같은 번호이고, 모든 스레드가 모인 뒤 동시에 반환된다. */
    static class CollidingGenerator extends AccountNumberGenerator {
        volatile CyclicBarrier barrier;
        final ThreadLocal<Boolean> used = ThreadLocal.withInitial(() -> false);

        @Override
        public String generate() {
            if (barrier != null && !used.get()) {
                used.set(true);
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return SAME_CANDIDATE;
            }
            return super.generate();
        }
    }

    @Autowired AccountService accountService;
    @Autowired CollidingGenerator generator;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        generator.barrier = null;
        jdbc.update("delete from audit_logs");
        jdbc.update("delete from accounts where is_system = false");
        jdbc.update("delete from users where email like '%@collision.test'");
    }

    @Test
    void concurrentCreatesWithSameCandidateAllSucceedWithDistinctNumbers() throws Exception {
        User user = userRepository.save(new User("u@collision.test", "x", "충돌", UserRole.USER));
        generator.barrier = new CyclicBarrier(THREADS);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<AccountResponse>> futures = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            futures.add(pool.submit(() ->
                    accountService.create(user.getId(), new CreateAccountRequest("1234", null, null), "127.0.0.1")));
        }
        List<String> numbers = new ArrayList<>();
        for (Future<AccountResponse> f : futures) {
            numbers.add(f.get(60, TimeUnit.SECONDS).accountNumber());
        }
        pool.shutdown();

        assertThat(numbers).doesNotHaveDuplicates().contains(SAME_CANDIDATE);
        assertThat(jdbc.queryForObject("select count(*) from accounts where user_id = ?", Integer.class, user.getId()))
                .isEqualTo(THREADS);
    }
}
