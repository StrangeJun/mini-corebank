package com.corebank.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corebank.auth.jwt.AuthUser;
import com.corebank.domain.user.User;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

class HistoryAndAuditQueryApiTest extends TransactionTestSupport {

    Customer alice;
    Customer bob;
    String adminToken;
    long aliceAcc;
    String aliceNo;
    long bobAcc;
    String bobNo;

    @BeforeEach
    void setUp() throws Exception {
        alice = newCustomer("alice");
        bob = newCustomer("bob");
        User admin = userRepository.findByEmail("admin@corebank.com").orElseThrow();
        adminToken = tokenProvider.createAccessToken(new AuthUser(admin.getId(), admin.getEmail(), admin.getRole()));
        JsonNode a = openAccount(alice, "");
        JsonNode b = openAccount(bob, "");
        aliceAcc = a.get("id").asLong();
        aliceNo = a.get("accountNumber").asText();
        bobAcc = b.get("id").asLong();
        bobNo = b.get("accountNumber").asText();

        deposit(alice, aliceAcc, "50000");                  // CREDIT 50000
        withdraw(alice, aliceAcc, "10000");                 // DEBIT  40000
        transfer(alice, aliceAcc, bobNo, "20000");          // DEBIT  20000
    }

    // ---------- 거래내역 ----------

    @Test
    void historyIsNewestFirstWithMaskedCounterparty() throws Exception {
        history(alice.token(), aliceAcc, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].txType").value("TRANSFER"))
                .andExpect(jsonPath("$.content[0].entryType").value("DEBIT"))
                .andExpect(jsonPath("$.content[0].amount").value(20000))
                .andExpect(jsonPath("$.content[0].balanceAfter").value(20000))
                .andExpect(jsonPath("$.content[0].counterpartyMasked")
                        .value(bobNo.substring(0, 4) + "***-**" + bobNo.substring(bobNo.length() - 4)))
                .andExpect(jsonPath("$.content[1].txType").value("WITHDRAWAL"))
                .andExpect(jsonPath("$.content[1].counterpartyMasked").value("SYSTEM-VAULT"))
                .andExpect(jsonPath("$.content[2].txType").value("DEPOSIT"))
                .andExpect(jsonPath("$.content[2].entryType").value("CREDIT"))
                .andExpect(jsonPath("$.content[2].balanceAfter").value(50000));

        String body = history(bob.token(), bobAcc, "").andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].entryType").value("CREDIT"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(aliceNo);
    }

    @Test
    void filtersByTypeEntryTypeAndDate() throws Exception {
        history(alice.token(), aliceAcc, "type=TRANSFER")
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].txType").value("TRANSFER"));
        history(alice.token(), aliceAcc, "entryType=DEBIT").andExpect(jsonPath("$.totalElements").value(2));
        history(alice.token(), aliceAcc, "entryType=CREDIT").andExpect(jsonPath("$.totalElements").value(1));

        String today = LocalDate.now().toString();
        String yesterday = LocalDate.now().minusDays(1).toString();
        history(alice.token(), aliceAcc, "fromDate=" + today + "&toDate=" + today)
                .andExpect(jsonPath("$.totalElements").value(3));
        history(alice.token(), aliceAcc, "toDate=" + yesterday).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void paginates() throws Exception {
        history(alice.token(), aliceAcc, "page=0&size=2")
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content[0].txType").value("TRANSFER"));
        history(alice.token(), aliceAcc, "page=1&size=2")
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].txType").value("DEPOSIT"));
    }

    @Test
    void historyAccessControlAndValidation() throws Exception {
        history(bob.token(), aliceAcc, "").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ACCOUNT_ACCESS"));
        history(adminToken, aliceAcc, "").andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3));
        history(alice.token(), 999_999L, "").andExpect(status().isNotFound());

        for (String bad : new String[] {"type=FOO", "fromDate=2026-13-01", "size=0", "size=101", "page=-1",
                "fromDate=2026-11-02&toDate=2026-11-01"}) {
            history(alice.token(), aliceAcc, bad).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
    }

    // ---------- 감사 로그 ----------

    @Test
    void myAuditLogsShowOnlyMineAndAreMasked() throws Exception {
        withdraw(bob, bobAcc, "999999999");  // 실패 감사 로그 (bob)

        String body = mockMvc.perform(get("/api/audit-logs/me").header("Authorization", "Bearer " + alice.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].actionType").value("TRANSFER"))
                .andReturn().getResponse().getContentAsString();
        JsonNode content = objectMapper.readTree(body).get("content");
        assertThat(content).isNotEmpty();
        content.forEach(n -> assertThat(n.get("actorUserId").asLong()).isEqualTo(alice.userId()));
        assertThat(body).doesNotContain(aliceNo, bobNo, "\"1234\"");
    }

    @Test
    void adminAuditLogsRequireAdminAndSupportFilters() throws Exception {
        withdraw(bob, bobAcc, "999999999");

        mockMvc.perform(get("/api/admin/audit-logs").header("Authorization", "Bearer " + alice.token()))
                .andExpect(status().isForbidden());

        adminLogs("").andExpect(status().isOk());
        adminLogs("actionType=TRANSFER&result=SUCCESS")
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].targetType").value("TRANSACTION"));
        adminLogs("result=FAILURE&actorUserId=" + bob.userId())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].actionType").value("WITHDRAWAL"));
        adminLogs("actorUserId=" + alice.userId() + "&size=2")
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(4)); // ACCOUNT_CREATE, DEPOSIT, WITHDRAWAL, TRANSFER

        String all = adminLogs("size=100").andReturn().getResponse().getContentAsString();
        assertThat(all).doesNotContain(aliceNo, bobNo);
    }

    private ResultActions history(String token, long accountId, String query) throws Exception {
        return mockMvc.perform(get("/api/accounts/" + accountId + "/transactions?" + query)
                .header("Authorization", "Bearer " + token));
    }

    private ResultActions adminLogs(String query) throws Exception {
        return mockMvc.perform(get("/api/admin/audit-logs?" + query).header("Authorization", "Bearer " + adminToken));
    }
}
