package com.corebank.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corebank.auth.jwt.AuthUser;
import com.corebank.domain.user.User;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class Milestone2QaIntegrationTest extends TransactionTestSupport {

    static final String WRONG_PIN = "9999";

    Customer alice;
    Customer bob;
    String adminToken;
    long aliceAcc;
    long bobAcc;
    String aliceNo;
    String bobNo;

    @BeforeEach
    void setUp() throws Exception {
        alice = newCustomer("m2-alice");
        bob = newCustomer("m2-bob");
        User admin = userRepository.findByEmail("admin@corebank.com").orElseThrow();
        adminToken = tokenProvider.createAccessToken(new AuthUser(admin.getId(), admin.getEmail(), admin.getRole()));
        JsonNode aliceAccount = openAccount(alice, "\"perTxLimit\":100000,\"dailyLimit\":200000");
        JsonNode bobAccount = openAccount(bob, "");
        aliceAcc = aliceAccount.get("id").asLong();
        bobAcc = bobAccount.get("id").asLong();
        aliceNo = aliceAccount.get("accountNumber").asText();
        bobNo = bobAccount.get("accountNumber").asText();
        deposit(alice, aliceAcc, "100000").andExpect(status().isOk());
    }

    @Test
    void pinFourFailuresThenCorrectPinResetsAndFiveFailuresLockAndAdminUnlocks() throws Exception {
        for (int i = 0; i < 4; i++) {
            withdrawWithPin(WRONG_PIN).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_PIN"));
        }
        withdrawWithPin(PIN).andExpect(status().isOk());
        assertThat(pinFailCount()).isZero();

        for (int i = 0; i < 5; i++) {
            withdrawWithPin(WRONG_PIN).andReturn();
        }
        assertThat(accountStatus()).isEqualTo("LOCKED");
        assertThat(pinFailCount()).isEqualTo(5);
        assertThat(balance(aliceAcc)).isEqualByComparingTo("99000");
        withdrawWithPin(PIN).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));

        mockMvc.perform(post("/api/admin/accounts/" + aliceAcc + "/unlock")
                        .header("Authorization", "Bearer " + alice.token()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/accounts/" + aliceAcc + "/unlock")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.pinFailCount").value(0));

        assertThat(accountStatus()).isEqualTo("ACTIVE");
        assertThat(pinFailCount()).isZero();
        withdrawWithPin(PIN).andExpect(status().isOk());
    }

    @Test
    void reconciliationPassesConsistentLedgerAndDetectsTamperedBalance() throws Exception {
        transfer(alice, aliceAcc, bobNo, "20000").andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/reconciliations/run")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PASSED"))
                .andExpect(jsonPath("$.mismatchCount").value(0))
                .andExpect(jsonPath("$.details").isEmpty());

        jdbc.update("update accounts set balance = balance + 500 where id = ?", bobAcc);
        mockMvc.perform(post("/api/admin/reconciliations/run")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MISMATCH_FOUND"))
                .andExpect(jsonPath("$.details[?(@.type == 'ACCOUNT_LEDGER_MISMATCH')].accountId")
                        .exists());
    }

    @Test
    void scheduledTransferExecutesOnceAndRollbacksFailureToFailedStatus() throws Exception {
        long success = registerOk(alice, bobNo, "30000");
        makeDue(success);

        executeDue().andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(1));
        executeDue().andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(0));

        assertThat(stStatus(success)).isEqualTo("COMPLETED");
        assertThat(count("select count(*) from transactions where tx_type = 'TRANSFER'")).isEqualTo(1);
        assertThat(balance(aliceAcc)).isEqualByComparingTo("70000");
        assertThat(balance(bobAcc)).isEqualByComparingTo("30000");

        long failed = registerOk(alice, bobNo, "70001");
        makeDue(failed);

        executeDue().andExpect(status().isOk())
                .andExpect(jsonPath("$.failed").value(1));
        assertThat(stStatus(failed)).isEqualTo("FAILED");
        assertThat(stFailureReason(failed)).isEqualTo("INSUFFICIENT_BALANCE");
    }

    @Test
    void scheduledTransferLockedAccountFailsAndDoesNotBlockOtherJob() throws Exception {
        long blockedId = registerOk(alice, bobNo, "10000");

        JsonNode successAccount = openAccount(bob, "");
        long successFromAcc = successAccount.get("id").asLong();
        deposit(bob, successFromAcc, "50000").andExpect(status().isOk());
        long success = registerOkForFromAccount(bob, successFromAcc, bobNo, "5000");

        jdbc.update("update accounts set status = 'LOCKED' where id = ?", aliceAcc);
        makeDue(blockedId);
        makeDue(success);

        executeDue().andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(1))
                .andExpect(jsonPath("$.failed").value(1));
        assertThat(stStatus(blockedId)).isEqualTo("FAILED");
        assertThat(stFailureReason(blockedId)).isEqualTo("ACCOUNT_LOCKED");
        assertThat(stStatus(success)).isEqualTo("COMPLETED");
    }

    private ResultActions withdrawWithPin(String pin) throws Exception {
        return call("withdraw", alice, UUID.randomUUID().toString(),
                "{\"accountId\":" + aliceAcc + ",\"amount\":1000,\"pin\":\"" + pin + "\"}");
    }

    private long registerOk(Customer customer, String toAccountNumber, String amount) throws Exception {
        long fromId = customer == alice ? aliceAcc : bobAcc;
        return registerOkForFromAccount(customer, fromId, toAccountNumber, amount);
    }

    private long registerOkForFromAccount(Customer customer, long fromId, String toAccountNumber,
                                          String amount) throws Exception {
        String json = "{\"fromAccountId\":" + fromId + ",\"toAccountNumber\":\"" + toAccountNumber
                + "\",\"amount\":" + amount + ",\"pin\":\"" + PIN
                + "\",\"scheduledAt\":\"" + LocalDateTime.now().plusDays(1).withNano(0)
                + "\",\"description\":\"M2 QA\"}";
        var req = post("/api/scheduled-transfers")
                .header("Authorization", "Bearer " + customer.token())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
        String body = mockMvc.perform(req).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private ResultActions executeDue() throws Exception {
        return mockMvc.perform(post("/api/admin/scheduled-transfers/execute-due")
                .header("Authorization", "Bearer " + adminToken));
    }

    private void makeDue(long id) {
        jdbc.update("update scheduled_transfers set scheduled_at = ? where id = ?",
                LocalDateTime.now().minusMinutes(1), id);
    }

    private String accountStatus() {
        return jdbc.queryForObject("select status from accounts where id = ?", String.class, aliceAcc);
    }

    private int pinFailCount() {
        return count("select pin_fail_count from accounts where id = ?", aliceAcc);
    }

    private String stStatus(long id) {
        return jdbc.queryForObject("select status from scheduled_transfers where id = ?", String.class, id);
    }

    private String stFailureReason(long id) {
        return jdbc.queryForObject("select failure_reason from scheduled_transfers where id = ?", String.class, id);
    }
}
