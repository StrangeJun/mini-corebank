package com.corebank.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TransactionApiTest extends TransactionTestSupport {

    Customer alice;
    Customer bob;
    long aliceAcc;
    String aliceNo;
    long bobAcc;
    String bobNo;

    @BeforeEach
    void setUp() throws Exception {
        alice = newCustomer("alice");
        bob = newCustomer("bob");
        JsonNode a = openAccount(alice, "\"perTxLimit\":100000,\"dailyLimit\":150000");
        JsonNode b = openAccount(bob, "");
        aliceAcc = a.get("id").asLong();
        aliceNo = a.get("accountNumber").asText();
        bobAcc = b.get("id").asLong();
        bobNo = b.get("accountNumber").asText();
    }

    // ---------- 성공 + 복식부기 ----------

    @Test
    void depositPostsVaultDebitAndCustomerCredit() throws Exception {
        long txId = objectMapper.readTree(deposit(alice, aliceAcc, "50000")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.txType").value("DEPOSIT"))
                .andExpect(jsonPath("$.amount").value(50000))
                .andExpect(jsonPath("$.balanceAfter").value(50000))
                .andExpect(jsonPath("$.description").value("입금"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andReturn().getResponse().getContentAsString()).get("transactionId").asLong();

        assertThat(balance(aliceAcc)).isEqualByComparingTo("50000");
        assertThat(vaultBalance()).isEqualByComparingTo("-50000");
        assertEntries(txId, vaultId(), aliceAcc, "-50000", "50000");
        assertThat(jdbc.queryForMap("select tx_type, status, from_account_id, to_account_id from transactions where id = ?", txId))
                .containsEntry("tx_type", "DEPOSIT").containsEntry("status", "COMPLETED")
                .containsEntry("from_account_id", vaultId()).containsEntry("to_account_id", aliceAcc);
    }

    @Test
    void withdrawPostsCustomerDebitAndVaultCredit() throws Exception {
        deposit(alice, aliceAcc, "50000").andExpect(status().isOk());

        long txId = objectMapper.readTree(withdraw(alice, aliceAcc, "10000")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.txType").value("WITHDRAWAL"))
                .andExpect(jsonPath("$.balanceAfter").value(40000))
                .andReturn().getResponse().getContentAsString()).get("transactionId").asLong();

        assertThat(balance(aliceAcc)).isEqualByComparingTo("40000");
        assertThat(vaultBalance()).isEqualByComparingTo("-40000");
        assertEntries(txId, aliceAcc, vaultId(), "40000", "-40000");
    }

    @Test
    void transferByAccountNumberPostsTwoEntries() throws Exception {
        deposit(alice, aliceAcc, "50000");

        long txId = objectMapper.readTree(transfer(alice, aliceAcc, bobNo, "20000")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.txType").value("TRANSFER"))
                .andExpect(jsonPath("$.fromAccountId").value(aliceAcc))
                .andExpect(jsonPath("$.toAccountNumberMasked").value(org.hamcrest.Matchers.matchesPattern("110-\\*{3}-\\*{2}\\d{4}")))
                .andExpect(jsonPath("$.balanceAfter").value(30000))
                .andReturn().getResponse().getContentAsString()).get("transactionId").asLong();

        assertThat(balance(aliceAcc)).isEqualByComparingTo("30000");
        assertThat(balance(bobAcc)).isEqualByComparingTo("20000");
        assertEntries(txId, aliceAcc, bobAcc, "30000", "20000");
        assertZeroSum();
    }

    @Test
    void transferAcceptsToAccountIdAndLegacyFieldName() throws Exception {
        deposit(alice, aliceAcc, "50000");
        call("transfer", alice, "k-id", "{\"fromAccountId\":" + aliceAcc + ",\"toAccountId\":" + bobAcc
                + ",\"amount\":1000,\"pin\":\"1234\"}").andExpect(status().isOk());
        call("transfer", alice, "k-legacy", "{\"fromAccountId\":" + aliceAcc + ",\"toAccountAccountNumber\":\"" + bobNo
                + "\",\"amount\":1000,\"pin\":\"1234\"}").andExpect(status().isOk());
        assertThat(balance(bobAcc)).isEqualByComparingTo("2000");
    }

    // ---------- 금액 검증 ----------

    @Test
    void rejectsZeroNegativeFractionalAndMissingAmount() throws Exception {
        for (String amount : List.of("0", "-1000", "100.5", "1000.0", "null")) {
            deposit(alice, aliceAcc, amount)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
            withdraw(alice, aliceAcc, amount).andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
            transfer(alice, aliceAcc, bobNo, amount).andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
        }
        assertThat(count("select count(*) from transactions")).isZero();
    }

    // ---------- 잔액·한도 ----------

    @Test
    void insufficientBalanceIsRejectedAndAuditedIndependently() throws Exception {
        deposit(alice, aliceAcc, "5000");

        withdraw(alice, aliceAcc, "5001")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));
        transfer(alice, aliceAcc, bobNo, "5001").andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));

        assertThat(balance(aliceAcc)).isEqualByComparingTo("5000");
        assertThat(count("select count(*) from transactions")).isEqualTo(1);
        assertThat(count("select count(*) from ledger_entries")).isEqualTo(2);
        assertThat(count("select count(*) from audit_logs where result = 'FAILURE' and masked_summary like '%INSUFFICIENT_BALANCE%'"))
                .isEqualTo(2);
        assertThat(jdbc.queryForList("select masked_summary from audit_logs", String.class))
                .noneMatch(s -> s.contains(aliceNo) || s.contains(bobNo) || s.contains("1234"));
    }

    @Test
    void perTxLimitIsEnforced() throws Exception {
        deposit(alice, aliceAcc, "500000");
        withdraw(alice, aliceAcc, "100001")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PER_TX_LIMIT_EXCEEDED"));
        withdraw(alice, aliceAcc, "100000").andExpect(status().isOk());
    }

    @Test
    void dailyLimitCountsWithdrawalsAndTransfers() throws Exception {
        deposit(alice, aliceAcc, "500000");
        withdraw(alice, aliceAcc, "100000").andExpect(status().isOk());
        transfer(alice, aliceAcc, bobNo, "50000").andExpect(status().isOk());

        transfer(alice, aliceAcc, bobNo, "1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DAILY_LIMIT_EXCEEDED"));
        withdraw(alice, aliceAcc, "1").andExpect(jsonPath("$.code").value("DAILY_LIMIT_EXCEEDED"));
        // 입금은 한도와 무관
        deposit(alice, aliceAcc, "1").andExpect(status().isOk());
    }

    // ---------- 계좌·권한 ----------

    @Test
    void sameAccountTransferIsRejected() throws Exception {
        deposit(alice, aliceAcc, "5000");
        transfer(alice, aliceAcc, aliceNo, "1000")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SAME_ACCOUNT_TRANSFER"));
    }

    @Test
    void cannotWithdrawOrTransferFromOthersAccount() throws Exception {
        deposit(alice, aliceAcc, "5000");
        withdraw(bob, aliceAcc, "1000")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ACCOUNT_ACCESS"));
        transfer(bob, aliceAcc, bobNo, "1000").andExpect(status().isForbidden());
        deposit(bob, aliceAcc, "1000").andExpect(status().isForbidden());
        assertThat(balance(aliceAcc)).isEqualByComparingTo("5000");
    }

    @Test
    void wrongPinIsRejected() throws Exception {
        deposit(alice, aliceAcc, "5000");
        call("withdraw", alice, "k-pin", "{\"accountId\":" + aliceAcc + ",\"amount\":1000,\"pin\":\"9999\"}")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_PIN"));
        assertThat(balance(aliceAcc)).isEqualByComparingTo("5000");
    }

    @Test
    void lockedAndClosedAccountsCannotTransact() throws Exception {
        deposit(alice, aliceAcc, "5000");
        jdbc.update("update accounts set status = 'LOCKED' where id = ?", aliceAcc);
        withdraw(alice, aliceAcc, "1000").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
        deposit(alice, aliceAcc, "1000").andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));

        jdbc.update("update accounts set status = 'CLOSED' where id = ?", bobAcc);
        jdbc.update("update accounts set status = 'ACTIVE' where id = ?", aliceAcc);
        transfer(alice, aliceAcc, bobNo, "1000").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACCOUNT_CLOSED"));
    }

    @Test
    void unknownTargetAndSystemVaultTargetAreNotFound() throws Exception {
        deposit(alice, aliceAcc, "5000");
        transfer(alice, aliceAcc, "110-000-000000", "1000").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        transfer(alice, aliceAcc, "SYSTEM-VAULT", "1000").andExpect(status().isNotFound());
    }

    // ---------- 멱등성 ----------

    @Test
    void idempotencyKeyIsRequired() throws Exception {
        String body = "{\"accountId\":" + aliceAcc + ",\"amount\":1000}";
        call("deposit", alice, null, body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        call("deposit", alice, "  ", body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void sameKeySameBodyReplaysWithoutReposting() throws Exception {
        String body = "{\"accountId\":" + aliceAcc + ",\"amount\":1000,\"description\":\"급여\"}";
        String first = call("deposit", alice, "dup-1", body).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String second = call("deposit", alice, "dup-1", body).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(second)).isEqualTo(objectMapper.readTree(first));
        assertThat(balance(aliceAcc)).isEqualByComparingTo("1000");
        assertThat(count("select count(*) from transactions")).isEqualTo(1);
        assertThat(count("select count(*) from ledger_entries")).isEqualTo(2);
    }

    @Test
    void sameKeyDifferentBodyIsConflict() throws Exception {
        call("deposit", alice, "dup-2", "{\"accountId\":" + aliceAcc + ",\"amount\":1000}").andExpect(status().isOk());
        call("deposit", alice, "dup-2", "{\"accountId\":" + aliceAcc + ",\"amount\":2000}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        call("withdraw", alice, "dup-2", "{\"accountId\":" + aliceAcc + ",\"amount\":1000,\"pin\":\"1234\"}")
                .andExpect(status().isConflict());
        assertThat(balance(aliceAcc)).isEqualByComparingTo("1000");
    }

    @Test
    void failedRequestDoesNotConsumeKey() throws Exception {
        String body = "{\"accountId\":" + aliceAcc + ",\"amount\":1000,\"pin\":\"1234\"}";
        call("withdraw", alice, "retry-1", body).andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));
        deposit(alice, aliceAcc, "1000");
        call("withdraw", alice, "retry-1", body).andExpect(status().isOk());
    }

    // ---------- helpers ----------

    private void assertEntries(long txId, long debitAcc, long creditAcc, String debitAfter, String creditAfter) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select account_id, entry_type, amount, balance_after from ledger_entries where transaction_id = ? order by entry_type desc",
                txId);
        assertThat(rows).hasSize(2);
        Map<String, Object> debit = rows.stream().filter(r -> "DEBIT".equals(r.get("entry_type"))).findFirst().orElseThrow();
        Map<String, Object> credit = rows.stream().filter(r -> "CREDIT".equals(r.get("entry_type"))).findFirst().orElseThrow();
        assertThat(debit.get("account_id")).isEqualTo(debitAcc);
        assertThat(credit.get("account_id")).isEqualTo(creditAcc);
        assertThat((BigDecimal) debit.get("amount")).isEqualByComparingTo((BigDecimal) credit.get("amount"));
        assertThat((BigDecimal) debit.get("balance_after")).isEqualByComparingTo(debitAfter);
        assertThat((BigDecimal) credit.get("balance_after")).isEqualByComparingTo(creditAfter);
    }

    private void assertZeroSum() {
        assertThat(jdbc.queryForObject("select sum(balance) from accounts", BigDecimal.class)).isEqualByComparingTo("0");
    }
}
