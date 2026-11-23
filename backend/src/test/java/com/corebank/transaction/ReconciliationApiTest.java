package com.corebank.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corebank.auth.jwt.AuthUser;
import com.corebank.domain.user.User;
import com.corebank.reconciliation.ReconciliationScheduler;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/** T-009: 원장-잔액 정합성 대사 3대 불변식 + 이력 API + 스케줄러 진입점. */
class ReconciliationApiTest extends TransactionTestSupport {

    @Autowired ReconciliationScheduler scheduler;

    String adminToken;
    Long adminId;
    Customer alice;
    Customer bob;
    long aliceAcc;
    long bobAcc;
    String bobNumber;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("delete from reconciliation_runs");
        User admin = userRepository.findByEmail("admin@corebank.com").orElseThrow();
        adminId = admin.getId();
        adminToken = tokenProvider.createAccessToken(new AuthUser(admin.getId(), admin.getEmail(), admin.getRole()));
        alice = newCustomer("alice");
        bob = newCustomer("bob");
        aliceAcc = openAccount(alice, "").get("id").asLong();
        JsonNode bobAccount = openAccount(bob, "");
        bobAcc = bobAccount.get("id").asLong();
        bobNumber = bobAccount.get("accountNumber").asText();

        deposit(alice, aliceAcc, "100000").andExpect(status().isOk());
        deposit(bob, bobAcc, "50000").andExpect(status().isOk());
        withdraw(alice, aliceAcc, "10000").andExpect(status().isOk());
        transfer(alice, aliceAcc, bobNumber, "30000").andExpect(status().isOk());
    }

    @AfterEach
    void cleanUpRuns() {
        jdbc.update("delete from reconciliation_runs");
    }

    @Test
    void consistentLedgerPasses() throws Exception {
        int accounts = count("select count(*) from accounts");
        int txs = count("select count(*) from transactions where status = 'COMPLETED'");

        String body = run(adminToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.triggerType").value("MANUAL"))
                .andExpect(jsonPath("$.status").value("PASSED"))
                .andExpect(jsonPath("$.checkedAccounts").value(accounts))
                .andExpect(jsonPath("$.checkedTransactions").value(txs))
                .andExpect(jsonPath("$.mismatchCount").value(0))
                .andExpect(jsonPath("$.details").isEmpty())
                .andExpect(jsonPath("$.executedAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        long runId = objectMapper.readTree(body).get("id").asLong();
        Map<String, Object> row = jdbc.queryForMap("select * from reconciliation_runs where id = ?", runId);
        assertThat(row.get("trigger_type")).isEqualTo("MANUAL");
        assertThat(row.get("status")).isEqualTo("PASSED");
        assertThat(((Number) row.get("executed_by")).longValue()).isEqualTo(adminId);
        assertThat(txs).isEqualTo(4);
        assertThat(count("select count(*) from audit_logs where action_type = 'RECONCILIATION_RUN' and actor_user_id = ?",
                adminId)).isEqualTo(1);
    }

    @Test
    void tamperedBalanceIsDetectedWithExactDifference() throws Exception {
        jdbc.update("update accounts set balance = balance + 500 where id = ?", bobAcc);

        run(adminToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MISMATCH_FOUND"))
                .andExpect(jsonPath("$.mismatchCount").value(2))
                .andExpect(jsonPath("$.details[?(@.type == 'ACCOUNT_LEDGER_MISMATCH')].accountId").value(hasItem((int) bobAcc)))
                .andExpect(jsonPath("$.details[?(@.type == 'ACCOUNT_LEDGER_MISMATCH')].expected").value(hasItem(80000)))
                .andExpect(jsonPath("$.details[?(@.type == 'ACCOUNT_LEDGER_MISMATCH')].actual").value(hasItem(80500)))
                .andExpect(jsonPath("$.details[?(@.type == 'ACCOUNT_LEDGER_MISMATCH')].difference").value(hasItem(500)))
                .andExpect(jsonPath("$.details[?(@.type == 'ACCOUNT_LEDGER_MISMATCH')].accountNumber")
                        .value(not(hasItem(bobNumber))))
                .andExpect(jsonPath("$.details[?(@.type == 'ZERO_SUM_MISMATCH')].difference").value(hasItem(500)));

        Map<String, Object> row = jdbc.queryForMap("select * from reconciliation_runs");
        assertThat(row.get("status")).isEqualTo("MISMATCH_FOUND");
        assertThat(((Number) row.get("mismatch_count")).intValue()).isEqualTo(2);
        assertThat((String) row.get("details_json")).contains("ACCOUNT_LEDGER_MISMATCH").doesNotContain(bobNumber);
    }

    @Test
    void tamperedLedgerEntryBreaksTransactionBalance() throws Exception {
        long txId = jdbc.queryForObject("select id from transactions where tx_type = 'TRANSFER'", Long.class);
        jdbc.update("update ledger_entries set amount = amount + 100 where transaction_id = ? and entry_type = 'CREDIT'",
                txId);

        run(adminToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MISMATCH_FOUND"))
                .andExpect(jsonPath("$.details[?(@.type == 'TX_BALANCE_MISMATCH')].transactionId").value(hasItem((int) txId)))
                .andExpect(jsonPath("$.details[?(@.type == 'TX_BALANCE_MISMATCH')].expected").value(hasItem(30000)))
                .andExpect(jsonPath("$.details[?(@.type == 'TX_BALANCE_MISMATCH')].creditTotal").value(hasItem(30100)))
                .andExpect(jsonPath("$.details[?(@.type == 'TX_BALANCE_MISMATCH')].debitTotal").value(hasItem(30000)))
                .andExpect(jsonPath("$.details[?(@.type == 'ACCOUNT_LEDGER_MISMATCH')].accountId").value(hasItem((int) bobAcc)))
                .andExpect(jsonPath("$.details[?(@.type == 'ZERO_SUM_MISMATCH')].creditTotal").isNotEmpty());
    }

    @Test
    void missingLedgerEntriesBreakTransactionBalance() throws Exception {
        long txId = jdbc.queryForObject("select id from transactions where tx_type = 'WITHDRAWAL'", Long.class);
        jdbc.update("delete from ledger_entries where transaction_id = ? and entry_type = 'DEBIT'", txId);

        run(adminToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.details[?(@.type == 'TX_BALANCE_MISMATCH')].transactionId").value(hasItem((int) txId)))
                .andExpect(jsonPath("$.details[?(@.type == 'TX_BALANCE_MISMATCH')].debitTotal").value(hasItem(0)));
    }

    @Test
    void detailsAreCappedButMismatchCountIsExact() throws Exception {
        for (int i = 0; i < 150; i++) {
            jdbc.update("insert into transactions (tx_type, status, amount, created_at) "
                    + "values ('DEPOSIT', 'COMPLETED', 1000, now())");
        }

        String body = run(adminToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MISMATCH_FOUND"))
                .andExpect(jsonPath("$.mismatchCount").value(150))
                .andExpect(jsonPath("$.details.length()").value(100))
                .andReturn().getResponse().getContentAsString();

        long runId = objectMapper.readTree(body).get("id").asLong();
        String json = jdbc.queryForObject("select details_json from reconciliation_runs where id = ?", String.class, runId);
        assertThat(objectMapper.readTree(json).size()).isEqualTo(100);
        assertThat(((Number) jdbc.queryForObject("select mismatch_count from reconciliation_runs where id = ?",
                Integer.class, runId)).intValue()).isEqualTo(150);
    }

    @Test
    void nonAdminIsForbidden() throws Exception {
        run(alice.token()).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/reconciliations").header("Authorization", "Bearer " + alice.token()))
                .andExpect(status().isForbidden());
        assertThat(count("select count(*) from reconciliation_runs")).isZero();
    }

    @Test
    void historyListIsNewestFirstAndDetailIsRetrievable() throws Exception {
        long first = idOf(run(adminToken));
        jdbc.update("update accounts set balance = balance - 1 where id = ?", aliceAcc);
        long second = idOf(run(adminToken));

        mockMvc.perform(get("/api/admin/reconciliations").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(second))
                .andExpect(jsonPath("$.content[0].status").value("MISMATCH_FOUND"))
                .andExpect(jsonPath("$.content[1].id").value(first));

        mockMvc.perform(get("/api/admin/reconciliations/" + second).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mismatchCount").value(2))
                .andExpect(jsonPath("$.details[?(@.type == 'ACCOUNT_LEDGER_MISMATCH')].difference").value(hasItem(-1)));

        mockMvc.perform(get("/api/admin/reconciliations/999999").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECONCILIATION_RUN_NOT_FOUND"));
    }

    @Test
    void scheduledEntryPointRecordsScheduledRun() {
        scheduler.runScheduledReconciliation();

        Map<String, Object> row = jdbc.queryForMap("select * from reconciliation_runs");
        assertThat(row.get("trigger_type")).isEqualTo("SCHEDULED");
        assertThat(row.get("status")).isEqualTo("PASSED");
        assertThat(row.get("executed_by")).isNull();
        assertThat(count("select count(*) from audit_logs where action_type = 'RECONCILIATION_RUN'")).isEqualTo(1);
    }

    private ResultActions run(String token) throws Exception {
        return mockMvc.perform(post("/api/admin/reconciliations/run").header("Authorization", "Bearer " + token));
    }

    private long idOf(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .get("id").asLong();
    }
}
