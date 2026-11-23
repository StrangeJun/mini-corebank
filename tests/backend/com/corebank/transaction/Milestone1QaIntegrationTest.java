package com.corebank.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class Milestone1QaIntegrationTest extends TransactionTestSupport {

    @Test
    void boundaryAmountsLimitsAndClosedAccountAreRejectedWithoutLedgerChanges() throws Exception {
        Customer alice = newCustomer("qa-boundary");
        Customer bob = newCustomer("qa-boundary-b");
        JsonNode aliceAccount = openAccount(alice, "\"perTxLimit\":100000,\"dailyLimit\":150000");
        JsonNode bobAccount = openAccount(bob, "");
        long aliceId = aliceAccount.get("id").asLong();
        long bobId = bobAccount.get("id").asLong();
        String bobNumber = bobAccount.get("accountNumber").asText();

        for (String amount : List.of("0", "-1000", "100.5")) {
            deposit(alice, aliceId, amount)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
            withdraw(alice, aliceId, amount)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
            transfer(alice, aliceId, bobNumber, amount)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
        }

        deposit(alice, aliceId, "500000").andExpect(status().isOk());
        withdraw(alice, aliceId, "100001")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PER_TX_LIMIT_EXCEEDED"));
        withdraw(alice, aliceId, "100000").andExpect(status().isOk());
        transfer(alice, aliceId, bobNumber, "50000").andExpect(status().isOk());
        transfer(alice, aliceId, bobNumber, "1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DAILY_LIMIT_EXCEEDED"));

        withdraw(bob, bobId, "50001")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));
        transfer(alice, aliceId, aliceAccount.get("accountNumber").asText(), "1000")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SAME_ACCOUNT_TRANSFER"));

        jdbc.update("update accounts set status = 'CLOSED' where id = ?", bobId);
        transfer(alice, aliceId, bobNumber, "1000")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACCOUNT_CLOSED"));
        assertThat(balance(aliceId)).isEqualByComparingTo("350000");
        assertThat(balance(bobId)).isEqualByComparingTo("50000");
        assertLedgerConsistent();
    }

    @Test
    void ledgerAndIdempotencyAreConsistentAcrossReplayAndConflict() throws Exception {
        Customer alice = newCustomer("qa-ledger");
        Customer bob = newCustomer("qa-ledger-b");
        JsonNode aliceAccount = openAccount(alice, "");
        JsonNode bobAccount = openAccount(bob, "");
        long aliceId = aliceAccount.get("id").asLong();
        long bobId = bobAccount.get("id").asLong();
        String bobNumber = bobAccount.get("accountNumber").asText();

        String depositBody = "{\"accountId\":" + aliceId + ",\"amount\":50000,\"description\":\"QA 입금\"}";
        JsonNode first = objectMapper.readTree(call("deposit", alice, "qa-replay-deposit", depositBody)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        JsonNode replay = objectMapper.readTree(call("deposit", alice, "qa-replay-deposit", depositBody)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(replay).isEqualTo(first);
        assertThat(count("select count(*) from transactions")).isEqualTo(1);
        assertThat(count("select count(*) from ledger_entries where transaction_id = ?", first.get("transactionId").asLong()))
                .isEqualTo(2);
        assertThat(balance(aliceId)).isEqualByComparingTo("50000");
        assertThat(vaultBalance()).isEqualByComparingTo("-50000");

        call("deposit", alice, "qa-replay-deposit",
                "{\"accountId\":" + aliceId + ",\"amount\":50001,\"description\":\"QA 충돌\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        transfer(alice, aliceId, bobNumber, "20000").andExpect(status().isOk());
        assertThat(balance(aliceId)).isEqualByComparingTo("30000");
        assertThat(balance(bobId)).isEqualByComparingTo("20000");
        assertLedgerConsistent();
    }

    @Test
    void concurrentWithdrawalsAndOpposingTransfersPreserveBalances() throws Exception {
        Customer alice = newCustomer("qa-concurrent");
        Customer bob = newCustomer("qa-concurrent-b");
        JsonNode aliceAccount = openAccount(alice, "");
        JsonNode bobAccount = openAccount(bob, "");
        long aliceId = aliceAccount.get("id").asLong();
        long bobId = bobAccount.get("id").asLong();

        deposit(alice, aliceId, "100000").andExpect(status().isOk());
        List<Integer> withdrawalStatuses = runConcurrently(10,
                i -> withdraw(alice, aliceId, "30000").andReturn().getResponse().getStatus());
        assertThat(withdrawalStatuses).filteredOn(statusCode -> statusCode == 200).hasSize(3);
        assertThat(balance(aliceId)).isEqualByComparingTo("10000");

        deposit(alice, aliceId, "90000").andExpect(status().isOk());
        deposit(bob, bobId, "100000").andExpect(status().isOk());
        String aliceNumber = aliceAccount.get("accountNumber").asText();
        String bobNumber = bobAccount.get("accountNumber").asText();
        BigDecimal totalBefore = balance(aliceId).add(balance(bobId));

        List<Integer> transferStatuses = runConcurrently(10, i -> (i % 2 == 0
                ? transfer(alice, aliceId, bobNumber, "10000")
                : transfer(bob, bobId, aliceNumber, "10000"))
                .andReturn().getResponse().getStatus());
        assertThat(transferStatuses).containsOnly(200);
        assertThat(balance(aliceId).add(balance(bobId))).isEqualByComparingTo(totalBefore);
        assertLedgerConsistent();
    }

    @Test
    void unauthorizedCrossAccountAccessAndSensitiveAuditOutputAreBlocked() throws Exception {
        Customer alice = newCustomer("qa-access");
        Customer bob = newCustomer("qa-access-b");
        JsonNode aliceAccount = openAccount(alice, "");
        JsonNode bobAccount = openAccount(bob, "");
        long aliceId = aliceAccount.get("id").asLong();
        long bobId = bobAccount.get("id").asLong();
        String aliceNumber = aliceAccount.get("accountNumber").asText();
        String bobNumber = bobAccount.get("accountNumber").asText();

        deposit(alice, aliceId, "5000").andExpect(status().isOk());

        mockMvc.perform(get("/api/accounts/{accountId}", aliceId)
                        .header("Authorization", "Bearer " + bob.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ACCOUNT_ACCESS"));
        mockMvc.perform(get("/api/accounts/{accountId}/transactions", aliceId)
                        .header("Authorization", "Bearer " + bob.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ACCOUNT_ACCESS"));
        withdraw(bob, aliceId, "1000")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ACCOUNT_ACCESS"));
        transfer(bob, aliceId, bobNumber, "1000")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ACCOUNT_ACCESS"));
        mockMvc.perform(patch("/api/accounts/{accountId}/close", aliceId)
                        .header("Authorization", "Bearer " + bob.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ACCOUNT_ACCESS"));
        transfer(alice, aliceId, "SYSTEM-VAULT", "1000")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));

        mockMvc.perform(get("/api/admin/audit-logs")
                        .header("Authorization", "Bearer " + bob.token()))
                .andExpect(status().isForbidden());

        withdraw(alice, aliceId, "6000")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));

        assertThat(balance(aliceId)).isEqualByComparingTo("5000");
        assertThat(balance(bobId)).isEqualByComparingTo("0");
        List<String> summaries = jdbc.queryForList("select masked_summary from audit_logs", String.class);
        assertThat(summaries)
                .noneMatch(summary -> summary.contains("1234")
                        || summary.contains(aliceNumber)
                        || summary.contains(bobNumber)
                        || summary.toLowerCase().contains("password"));
    }

    private void assertLedgerConsistent() {
        assertThat(count("select count(*) from (select transaction_id from ledger_entries group by transaction_id "
                + "having sum(case when entry_type = 'DEBIT' then amount else -amount end) <> 0) t")).isZero();
        assertThat(count("select count(*) from accounts a where a.balance <> coalesce((select sum(case when "
                + "entry_type = 'CREDIT' then amount else -amount end) from ledger_entries l where l.account_id = a.id), 0)"))
                .isZero();
        assertThat(jdbc.queryForObject("select sum(balance) from accounts", BigDecimal.class))
                .isEqualByComparingTo("0");
    }

    interface Task {
        Integer run(int i) throws Exception;
    }

    private List<Integer> runConcurrently(int n, Task task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int index = i;
            Callable<Integer> callable = () -> {
                start.await();
                return task.run(index);
            };
            futures.add(pool.submit(callable));
        }
        start.countDown();
        List<Integer> results = new ArrayList<>();
        for (Future<Integer> future : futures) {
            results.add(future.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return results;
    }
}
