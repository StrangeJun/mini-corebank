package com.corebank.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corebank.auth.jwt.AuthUser;
import com.corebank.domain.user.User;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** T-008: PIN 5회 연속 오류 자동 잠금 및 관리자 잠금 해제. */
class PinLockApiTest extends TransactionTestSupport {

    static final String WRONG_PIN = "9999";

    Customer alice;
    Customer bob;
    String adminToken;
    long aliceAcc;
    String bobNo;

    @BeforeEach
    void setUp() throws Exception {
        alice = newCustomer("alice");
        bob = newCustomer("bob");
        User admin = userRepository.findByEmail("admin@corebank.com").orElseThrow();
        adminToken = tokenProvider.createAccessToken(new AuthUser(admin.getId(), admin.getEmail(), admin.getRole()));
        aliceAcc = openAccount(alice, "").get("id").asLong();
        JsonNode b = openAccount(bob, "");
        bobNo = b.get("accountNumber").asText();
        deposit(alice, aliceAcc, "100000").andExpect(status().isOk());
    }

    @Test
    void wrongPinIsCountedEvenThoughTransactionRollsBack() throws Exception {
        withdrawWithPin(aliceAcc, WRONG_PIN).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_PIN"));

        assertThat(pinFailCount()).isEqualTo(1);
        assertThat(count("select count(*) from transactions where tx_type = 'WITHDRAWAL'")).isZero();
        assertThat(auditCount("PIN_FAIL")).isEqualTo(1);
        assertThat(count("select count(*) from audit_logs where action_type = 'PIN_FAIL' and masked_summary like ?",
                "%" + WRONG_PIN + "%")).isZero();
    }

    @Test
    void transferWithWrongPinIsCounted() throws Exception {
        transferWithPin(WRONG_PIN).andExpect(status().isUnauthorized());
        assertThat(pinFailCount()).isEqualTo(1);
    }

    @Test
    void correctPinResetsFailCount() throws Exception {
        for (int i = 0; i < 3; i++) {
            withdrawWithPin(aliceAcc, WRONG_PIN).andExpect(status().isUnauthorized());
        }
        assertThat(pinFailCount()).isEqualTo(3);

        withdrawWithPin(aliceAcc, PIN).andExpect(status().isOk());
        assertThat(pinFailCount()).isZero();
    }

    @Test
    void fifthConsecutiveFailureLocksAccountAndBlocksAllTransactions() throws Exception {
        for (int i = 0; i < 4; i++) {
            withdrawWithPin(aliceAcc, WRONG_PIN).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_PIN"));
        }
        withdrawWithPin(aliceAcc, WRONG_PIN).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));

        assertThat(accountStatus()).isEqualTo("LOCKED");
        assertThat(pinFailCount()).isEqualTo(5);
        assertThat(auditCount("PIN_FAIL")).isEqualTo(5);
        assertThat(auditCount("ACCOUNT_LOCKED")).isEqualTo(1);

        // 잠긴 뒤에는 올바른 PIN 도, 입금·이체도 차단되고 실패 횟수는 더 늘지 않는다
        withdrawWithPin(aliceAcc, PIN).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
        transferWithPin(PIN).andExpect(status().isForbidden());
        deposit(alice, aliceAcc, "1000").andExpect(status().isForbidden());
        withdrawWithPin(aliceAcc, WRONG_PIN).andExpect(status().isForbidden());
        assertThat(pinFailCount()).isEqualTo(5);
        assertThat(balance(aliceAcc)).isEqualByComparingTo("100000");
    }

    @Test
    void nonOwnerWrongPinDoesNotCountTowardsLock() throws Exception {
        call("withdraw", bob, UUID.randomUUID().toString(),
                "{\"accountId\":" + aliceAcc + ",\"amount\":1000,\"pin\":\"" + WRONG_PIN + "\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ACCOUNT_ACCESS"));
        assertThat(pinFailCount()).isZero();
    }

    @Test
    void concurrentWrongPinsLockExactlyAtFive() throws Exception {
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> withdrawWithPin(aliceAcc, WRONG_PIN).andReturn().getResponse().getStatus()));
        }
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : futures) {
            statuses.add(f.get());
        }
        pool.shutdown();

        assertThat(statuses).filteredOn(s -> s == 401).hasSize(4);
        assertThat(statuses).filteredOn(s -> s == 403).hasSize(4);
        assertThat(pinFailCount()).isEqualTo(5);
        assertThat(accountStatus()).isEqualTo("LOCKED");
        assertThat(auditCount("ACCOUNT_LOCKED")).isEqualTo(1);
    }

    @Test
    void adminUnlockRestoresAccount() throws Exception {
        lockAliceAccount();

        unlock(alice.token(), aliceAcc).andExpect(status().isForbidden());
        assertThat(accountStatus()).isEqualTo("LOCKED");

        unlock(adminToken, aliceAcc).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(aliceAcc))
                .andExpect(jsonPath("$.maskedAccountNumber").exists())
                .andExpect(jsonPath("$.accountNumber").doesNotExist())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.pinFailCount").value(0));

        assertThat(accountStatus()).isEqualTo("ACTIVE");
        assertThat(pinFailCount()).isZero();
        assertThat(auditCount("ACCOUNT_UNLOCKED")).isEqualTo(1);
        withdrawWithPin(aliceAcc, PIN).andExpect(status().isOk());
    }

    @Test
    void unlockUnknownAccountIsNotFound() throws Exception {
        unlock(adminToken, 999_999L).andExpect(status().isNotFound());
    }

    @Test
    void unlockClosedAccountIsRejected() throws Exception {
        long emptyAcc = openAccount(alice, "").get("id").asLong();
        jdbc.update("update accounts set status = 'CLOSED' where id = ?", emptyAcc);
        unlock(adminToken, emptyAcc).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACCOUNT_CLOSED"));
    }

    private void lockAliceAccount() throws Exception {
        for (int i = 0; i < 5; i++) {
            withdrawWithPin(aliceAcc, WRONG_PIN);
        }
        assertThat(accountStatus()).isEqualTo("LOCKED");
    }

    private ResultActions withdrawWithPin(long accountId, String pin) throws Exception {
        return call("withdraw", alice, UUID.randomUUID().toString(),
                "{\"accountId\":" + accountId + ",\"amount\":1000,\"pin\":\"" + pin + "\"}");
    }

    private ResultActions transferWithPin(String pin) throws Exception {
        return call("transfer", alice, UUID.randomUUID().toString(),
                "{\"fromAccountId\":" + aliceAcc + ",\"toAccountNumber\":\"" + bobNo + "\",\"amount\":1000,\"pin\":\""
                        + pin + "\"}");
    }

    private ResultActions unlock(String token, long accountId) throws Exception {
        return mockMvc.perform(post("/api/admin/accounts/" + accountId + "/unlock")
                .header("Authorization", "Bearer " + token));
    }

    private int pinFailCount() {
        return count("select pin_fail_count from accounts where id = ?", aliceAcc);
    }

    private String accountStatus() {
        return jdbc.queryForObject("select status from accounts where id = ?", String.class, aliceAcc);
    }

    private int auditCount(String actionType) {
        return count("select count(*) from audit_logs where action_type = ?", actionType);
    }
}
