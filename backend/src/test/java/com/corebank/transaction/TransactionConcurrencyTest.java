package com.corebank.transaction;

import static org.assertj.core.api.Assertions.assertThat;

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

class TransactionConcurrencyTest extends TransactionTestSupport {

    @Test
    void concurrentWithdrawalsNeverOverdraw() throws Exception {
        Customer c = newCustomer("conc");
        long acc = openAccount(c, "").get("id").asLong();
        deposit(c, acc, "5000");

        List<Integer> statuses = runConcurrently(10, i -> withdraw(c, acc, "1000").andReturn().getResponse().getStatus());

        assertThat(statuses).filteredOn(s -> s == 200).hasSize(5);
        assertThat(balance(acc)).isEqualByComparingTo("0");
        assertThat(count("select count(*) from transactions where tx_type = 'WITHDRAWAL'")).isEqualTo(5);
        assertLedgerConsistent();
    }

    @Test
    void opposingTransfersDoNotDeadlockAndPreserveTotals() throws Exception {
        Customer a = newCustomer("a");
        Customer b = newCustomer("b");
        JsonNode accA = openAccount(a, "");
        JsonNode accB = openAccount(b, "");
        long idA = accA.get("id").asLong();
        long idB = accB.get("id").asLong();
        deposit(a, idA, "100000");
        deposit(b, idB, "100000");

        List<Integer> statuses = runConcurrently(20, i -> (i % 2 == 0
                ? transfer(a, idA, accB.get("accountNumber").asText(), "1000")
                : transfer(b, idB, accA.get("accountNumber").asText(), "1000")).andReturn().getResponse().getStatus());

        assertThat(statuses).containsOnly(200);
        assertThat(balance(idA).add(balance(idB))).isEqualByComparingTo("200000");
        assertThat(balance(idA)).isEqualByComparingTo("100000");
        assertLedgerConsistent();
    }

    @Test
    void concurrentDuplicateKeyPostsOnce() throws Exception {
        Customer c = newCustomer("dup");
        long acc = openAccount(c, "").get("id").asLong();
        String body = "{\"accountId\":" + acc + ",\"amount\":1000}";

        List<Integer> statuses = runConcurrently(5, i -> call("deposit", c, "same-key", body)
                .andReturn().getResponse().getStatus());

        assertThat(statuses).allMatch(s -> s == 200 || s == 409).contains(200);
        assertThat(balance(acc)).isEqualByComparingTo("1000");
        assertThat(count("select count(*) from transactions")).isEqualTo(1);
    }

    /** 원장 불변식: 거래별 대차 일치, 계좌 잔액 = CREDIT - DEBIT, 전체 합 0. */
    private void assertLedgerConsistent() {
        assertThat(count("select count(*) from (select transaction_id from ledger_entries group by transaction_id "
                + "having sum(case when entry_type = 'DEBIT' then amount else -amount end) <> 0) t")).isZero();
        assertThat(count("select count(*) from accounts a where a.balance <> coalesce((select sum(case when "
                + "entry_type = 'CREDIT' then amount else -amount end) from ledger_entries l where l.account_id = a.id), 0)"))
                .isZero();
        assertThat(jdbc.queryForObject("select sum(balance) from accounts", BigDecimal.class)).isEqualByComparingTo("0");
    }

    interface Task {
        Integer run(int i) throws Exception;
    }

    private List<Integer> runConcurrently(int n, Task task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int idx = i;
            Callable<Integer> c = () -> {
                start.await();
                return task.run(idx);
            };
            futures.add(pool.submit(c));
        }
        start.countDown();
        List<Integer> results = new ArrayList<>();
        for (Future<Integer> f : futures) {
            results.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return results;
    }
}
