package com.corebank.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corebank.auth.jwt.AuthUser;
import com.corebank.domain.user.User;
import com.corebank.scheduled.ScheduledTransferService;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** T-010: 예약 이체 등록·조회·취소 및 도래 건 멱등 실행. */
class ScheduledTransferApiTest extends TransactionTestSupport {

    @Autowired ScheduledTransferService scheduledTransferService;

    Customer alice;
    Customer bob;
    String adminToken;
    long aliceAcc;
    long bobAcc;
    String aliceNo;
    String bobNo;

    @BeforeEach
    void setUp() throws Exception {
        alice = newCustomer("alice");
        bob = newCustomer("bob");
        User admin = userRepository.findByEmail("admin@corebank.com").orElseThrow();
        adminToken = tokenProvider.createAccessToken(new AuthUser(admin.getId(), admin.getEmail(), admin.getRole()));
        JsonNode a = openAccount(alice, "\"perTxLimit\":1000000");
        aliceAcc = a.get("id").asLong();
        aliceNo = a.get("accountNumber").asText();
        JsonNode b = openAccount(bob, "");
        bobAcc = b.get("id").asLong();
        bobNo = b.get("accountNumber").asText();
        deposit(alice, aliceAcc, "100000").andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- 등록

    @Test
    void registerCreatesPendingTransferWithoutMovingMoney() throws Exception {
        register(alice, UUID.randomUUID().toString(), aliceAcc, bobNo, "30000", PIN, future())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.fromAccountId").value(aliceAcc))
                .andExpect(jsonPath("$.toAccountNumberMasked").value(org.hamcrest.Matchers.not(bobNo)))
                .andExpect(jsonPath("$.amount").value(30000))
                .andExpect(jsonPath("$.status").value("PENDING"));

        assertThat(count("select count(*) from scheduled_transfers where status = 'PENDING'")).isEqualTo(1);
        assertThat(count("select count(*) from transactions where tx_type = 'TRANSFER'")).isZero();
        assertThat(balance(aliceAcc)).isEqualByComparingTo("100000");
        assertThat(auditCount("SCHEDULED_TRANSFER_CREATE", "SUCCESS")).isEqualTo(1);
    }

    @Test
    void registerWithWrongPinIsCountedAndRejected() throws Exception {
        register(alice, UUID.randomUUID().toString(), aliceAcc, bobNo, "30000", "9999", future())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_PIN"));

        assertThat(count("select pin_fail_count from accounts where id = ?", aliceAcc)).isEqualTo(1);
        assertThat(count("select count(*) from scheduled_transfers")).isZero();
        assertThat(auditCount("SCHEDULED_TRANSFER_CREATE", "FAILURE")).isEqualTo(1);
    }

    @Test
    void registerIsIdempotent() throws Exception {
        String key = UUID.randomUUID().toString();
        String at = future();
        long first = id(register(alice, key, aliceAcc, bobNo, "30000", PIN, at).andExpect(status().isCreated()));
        long second = id(register(alice, key, aliceAcc, bobNo, "30000", PIN, at).andExpect(status().isCreated()));

        assertThat(second).isEqualTo(first);
        assertThat(count("select count(*) from scheduled_transfers")).isEqualTo(1);

        register(alice, key, aliceAcc, bobNo, "40000", PIN, at).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        // 재생도 PIN 검증을 통과해야 한다
        register(alice, key, aliceAcc, bobNo, "30000", "9999", at).andExpect(status().isUnauthorized());
    }

    @Test
    void registerValidations() throws Exception {
        register(alice, null, aliceAcc, bobNo, "30000", PIN, future()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        register(alice, key(), aliceAcc, aliceNo, "30000", PIN, future()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SAME_ACCOUNT_TRANSFER"));
        register(alice, key(), aliceAcc, bobNo, "1000001", PIN, future()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PER_TX_LIMIT_EXCEEDED"));
        register(alice, key(), aliceAcc, bobNo, "0", PIN, future()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
        register(alice, key(), aliceAcc, bobNo, "100.5", PIN, future()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
        register(alice, key(), aliceAcc, "110-000-000000", "30000", PIN, future()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        register(alice, key(), aliceAcc, "SYSTEM-VAULT", "30000", PIN, future()).andExpect(status().isNotFound());
        register(alice, key(), aliceAcc, bobNo, "30000", PIN, LocalDateTime.now().minusMinutes(1).toString())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        register(bob, key(), aliceAcc, bobNo, "30000", PIN, future()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ACCOUNT_ACCESS"));
        register(alice, "SCHED-TX-1", aliceAcc, bobNo, "30000", PIN, future()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        jdbc.update("update accounts set status = 'CLOSED' where id = ?", bobAcc);
        register(alice, key(), aliceAcc, bobNo, "30000", PIN, future()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACCOUNT_CLOSED"));

        assertThat(count("select count(*) from scheduled_transfers")).isZero();
        assertThat(count("select pin_fail_count from accounts where id = ?", aliceAcc)).isZero();
    }

    // ---------------------------------------------------------------- 조회·취소

    @Test
    void listReturnsOnlyMyTransfersNewestFirstWithStatusFilter() throws Exception {
        long first = registerOk("10000");
        long second = registerOk("20000");
        JsonNode bobsAcc = openAccount(bob, "");
        deposit(bob, bobsAcc.get("id").asLong(), "50000").andExpect(status().isOk());
        register(bob, key(), bobsAcc.get("id").asLong(), aliceNo, "1000", PIN, future())
                .andExpect(status().isCreated());
        cancel(alice, first).andExpect(status().isOk());

        mockMvc.perform(get("/api/scheduled-transfers").header("Authorization", "Bearer " + alice.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(second))
                .andExpect(jsonPath("$.content[1].id").value(first));

        mockMvc.perform(get("/api/scheduled-transfers").param("status", "CANCELED")
                        .header("Authorization", "Bearer " + alice.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(first))
                .andExpect(jsonPath("$.content[0].status").value("CANCELED"));
    }

    @Test
    void cancelOnlyOwnPendingTransfer() throws Exception {
        long id = registerOk("30000");

        cancel(bob, id).andExpect(status().isForbidden());
        cancel(alice, 999_999L).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SCHEDULED_TRANSFER_NOT_FOUND"));
        cancel(alice, id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELED"));
        cancel(alice, id).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        assertThat(auditCount("SCHEDULED_TRANSFER_CANCEL", "SUCCESS")).isEqualTo(1);

        makeDue(id);
        executeDue().andExpect(status().isOk());
        assertThat(stStatus(id)).isEqualTo("CANCELED");
        assertThat(count("select count(*) from transactions where tx_type = 'TRANSFER'")).isZero();
    }

    // ---------------------------------------------------------------- 실행

    @Test
    void dueTransferExecutesWithDoubleEntryAndCompletes() throws Exception {
        long due = registerOk("30000");
        long notYet = registerOk("10000");
        makeDue(due);

        executeDue().andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(1))
                .andExpect(jsonPath("$.failed").value(0));

        assertThat(stStatus(due)).isEqualTo("COMPLETED");
        assertThat(stStatus(notYet)).isEqualTo("PENDING");
        Long txId = jdbc.queryForObject("select executed_transaction_id from scheduled_transfers where id = ?",
                Long.class, due);
        assertThat(txId).isNotNull();
        assertThat(jdbc.queryForObject("select idempotency_key from transactions where id = ?", String.class, txId))
                .isEqualTo("SCHED-TX-" + due);
        assertThat(count("select count(*) from ledger_entries where transaction_id = ? and entry_type = 'DEBIT'"
                + " and account_id = ?", txId, aliceAcc)).isEqualTo(1);
        assertThat(count("select count(*) from ledger_entries where transaction_id = ? and entry_type = 'CREDIT'"
                + " and account_id = ?", txId, bobAcc)).isEqualTo(1);
        assertThat(count("select count(*) from scheduled_transfers where id = ? and executed_at is not null", due))
                .isEqualTo(1);
        assertThat(balance(aliceAcc)).isEqualByComparingTo("70000");
        assertThat(balance(bobAcc)).isEqualByComparingTo("30000");
        assertThat(auditCount("SCHEDULED_TRANSFER_EXEC", "SUCCESS")).isEqualTo(1);
    }

    @Test
    void executingTwiceDoesNotTransferTwice() throws Exception {
        long id = registerOk("30000");
        makeDue(id);

        executeDue().andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(1));
        executeDue().andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(0));
        scheduledTransferService.executeDueTransfers();

        assertThat(count("select count(*) from transactions where tx_type = 'TRANSFER'")).isEqualTo(1);
        assertThat(balance(aliceAcc)).isEqualByComparingTo("70000");
    }

    @Test
    void concurrentExecutorsTransferExactlyOnce() throws Exception {
        long id = registerOk("30000");
        makeDue(id);

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return scheduledTransferService.executeDueTransfers();
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        assertThat(stStatus(id)).isEqualTo("COMPLETED");
        assertThat(count("select count(*) from transactions where tx_type = 'TRANSFER'")).isEqualTo(1);
        assertThat(count("select count(*) from ledger_entries where transaction_id in "
                + "(select id from transactions where tx_type = 'TRANSFER')")).isEqualTo(2);
        assertThat(balance(aliceAcc)).isEqualByComparingTo("70000");
        assertThat(balance(bobAcc)).isEqualByComparingTo("30000");
    }

    @Test
    void insufficientBalanceAtExecutionMarksFailed() throws Exception {
        long id = registerOk("30000");
        withdraw(alice, aliceAcc, "80000").andExpect(status().isOk());
        makeDue(id);

        executeDue().andExpect(status().isOk()).andExpect(jsonPath("$.failed").value(1));

        assertThat(stStatus(id)).isEqualTo("FAILED");
        assertThat(failureReason(id)).isEqualTo("INSUFFICIENT_BALANCE");
        assertThat(count("select count(*) from scheduled_transfers where id = ? and executed_at is not null"
                + " and executed_transaction_id is null", id)).isEqualTo(1);
        assertThat(count("select count(*) from transactions where tx_type = 'TRANSFER'")).isZero();
        assertThat(balance(aliceAcc)).isEqualByComparingTo("20000");
        assertThat(balance(bobAcc)).isEqualByComparingTo("0");
        assertThat(auditCount("SCHEDULED_TRANSFER_EXEC", "FAILURE")).isEqualTo(1);

        // FAILED 건은 다시 실행되지 않는다
        deposit(alice, aliceAcc, "100000").andExpect(status().isOk());
        executeDue().andExpect(status().isOk());
        assertThat(stStatus(id)).isEqualTo("FAILED");
        assertThat(count("select count(*) from transactions where tx_type = 'TRANSFER'")).isZero();
    }

    @Test
    void lockedAccountAtExecutionMarksFailedAndOthersStillRun() throws Exception {
        long blocked = registerOk("30000");
        JsonNode carolAcc = openAccount(bob, "");
        deposit(bob, carolAcc.get("id").asLong(), "50000").andExpect(status().isOk());
        long ok = id(register(bob, key(), carolAcc.get("id").asLong(), bobNo, "5000", PIN, future())
                .andExpect(status().isCreated()));
        jdbc.update("update accounts set status = 'LOCKED' where id = ?", aliceAcc);
        makeDue(blocked);
        makeDue(ok);

        executeDue().andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(1))
                .andExpect(jsonPath("$.failed").value(1));

        assertThat(stStatus(blocked)).isEqualTo("FAILED");
        assertThat(failureReason(blocked)).isEqualTo("ACCOUNT_LOCKED");
        assertThat(stStatus(ok)).isEqualTo("COMPLETED");
    }

    @Test
    void oneRunProcessesAtMostBatchSizeDueTransfers() throws Exception {
        int batch = ScheduledTransferService.MAX_BATCH_SIZE;
        for (int i = 0; i < batch + 1; i++) {
            jdbc.update("insert into scheduled_transfers (user_id, from_account_id, to_account_id, amount, scheduled_at,"
                            + " status, idempotency_key, created_at) values (?, ?, ?, 1, ?, 'PENDING', ?, now())",
                    alice.userId(), aliceAcc, bobAcc, LocalDateTime.now().minusMinutes(10).plusSeconds(i), key());
        }

        var first = scheduledTransferService.executeDueTransfers();
        assertThat(first.due()).isEqualTo(batch);
        assertThat(first.completed()).isEqualTo(batch);
        assertThat(count("select count(*) from scheduled_transfers where status = 'PENDING'")).isEqualTo(1);

        var second = scheduledTransferService.executeDueTransfers();
        assertThat(second.due()).isEqualTo(1);
        assertThat(count("select count(*) from scheduled_transfers where status = 'COMPLETED'")).isEqualTo(batch + 1);
    }

    @Test
    void existingInternalKeyTransactionIsReusedOnlyWhenItMatches() throws Exception {
        long matching = registerOk("30000");
        long mismatched = registerOk("20000");
        long matchingTx = insertTransaction("SCHED-TX-" + matching, aliceAcc, bobAcc, "30000");
        insertTransaction("SCHED-TX-" + mismatched, aliceAcc, bobAcc, "99999");
        makeDue(matching);
        makeDue(mismatched);

        executeDue().andExpect(status().isOk());

        assertThat(stStatus(matching)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select executed_transaction_id from scheduled_transfers where id = ?",
                Long.class, matching)).isEqualTo(matchingTx);
        assertThat(stStatus(mismatched)).isEqualTo("FAILED");
        assertThat(failureReason(mismatched)).isEqualTo("INTERNAL_ERROR");
        // 기존 거래 재사용·불일치 모두 새 원장 기입 없음
        assertThat(count("select count(*) from transactions where tx_type = 'TRANSFER'")).isEqualTo(2);
        assertThat(count("select count(*) from ledger_entries")).isEqualTo(2); // 셋업 입금 1건분
        assertThat(balance(aliceAcc)).isEqualByComparingTo("100000");
    }

    @Test
    void executeDueRequiresAdmin() throws Exception {
        mockMvc.perform(post("/api/admin/scheduled-transfers/execute-due")
                        .header("Authorization", "Bearer " + alice.token()))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- 헬퍼

    ResultActions register(Customer c, String key, long fromId, String toNumber, String amount, String pin,
                           String scheduledAt) throws Exception {
        String json = "{\"fromAccountId\":" + fromId + ",\"toAccountNumber\":\"" + toNumber + "\",\"amount\":"
                + amount + ",\"pin\":\"" + pin + "\",\"scheduledAt\":\"" + scheduledAt
                + "\",\"description\":\"적금 자동이체\"}";
        var req = post("/api/scheduled-transfers").header("Authorization", "Bearer " + c.token())
                .contentType(MediaType.APPLICATION_JSON).content(json);
        if (key != null) {
            req.header("Idempotency-Key", key);
        }
        return mockMvc.perform(req);
    }

    long registerOk(String amount) throws Exception {
        return id(register(alice, key(), aliceAcc, bobNo, amount, PIN, future()).andExpect(status().isCreated()));
    }

    ResultActions cancel(Customer c, long id) throws Exception {
        return mockMvc.perform(post("/api/scheduled-transfers/" + id + "/cancel")
                .header("Authorization", "Bearer " + c.token()));
    }

    ResultActions executeDue() throws Exception {
        return mockMvc.perform(post("/api/admin/scheduled-transfers/execute-due")
                .header("Authorization", "Bearer " + adminToken));
    }

    long id(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    void makeDue(long id) {
        jdbc.update("update scheduled_transfers set scheduled_at = ? where id = ?",
                LocalDateTime.now().minusMinutes(1), id);
    }

    long insertTransaction(String key, long fromId, long toId, String amount) {
        jdbc.update("insert into transactions (tx_type, status, amount, from_account_id, to_account_id,"
                + " idempotency_key, description, created_at) values ('TRANSFER', 'COMPLETED', ?, ?, ?, ?, 'x', now())",
                new java.math.BigDecimal(amount), fromId, toId, key);
        return jdbc.queryForObject("select id from transactions where idempotency_key = ?", Long.class, key);
    }

    String stStatus(long id) {
        return jdbc.queryForObject("select status from scheduled_transfers where id = ?", String.class, id);
    }

    String failureReason(long id) {
        return jdbc.queryForObject("select failure_reason from scheduled_transfers where id = ?", String.class, id);
    }

    int auditCount(String action, String result) {
        return count("select count(*) from audit_logs where action_type = ? and result = ?", action, result);
    }

    static String future() {
        return LocalDateTime.now().plusDays(1).withNano(0).toString();
    }

    static String key() {
        return UUID.randomUUID().toString();
    }
}
