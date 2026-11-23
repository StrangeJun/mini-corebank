package com.corebank.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corebank.audit.AuditLogService;
import com.corebank.domain.audit.AuditResult;
import com.corebank.scheduled.ScheduledTransferService;
import com.corebank.scheduled.dto.ScheduledExecutionSummary;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;

/** RV-T10-01: FAILED 기록(markFailed) 중 일시 오류가 나도 배치는 다음 건을 계속 처리한다. */
class ScheduledTransferFailureIsolationTest extends TransactionTestSupport {

    @Autowired ScheduledTransferService scheduledTransferService;
    @SpyBean AuditLogService auditLogService;

    @Test
    void errorWhileRecordingFailureKeepsPendingAndContinuesWithNextTransfer() throws Exception {
        Customer alice = newCustomer("alice");
        Customer bob = newCustomer("bob");
        long aliceAcc = openAccount(alice, "").get("id").asLong();
        JsonNode bobAcc = openAccount(bob, "");
        deposit(alice, aliceAcc, "10000").andExpect(status().isOk());

        long failing = register(alice, aliceAcc, bobAcc.get("accountNumber").asText(), "50000");
        long succeeding = register(alice, aliceAcc, bobAcc.get("accountNumber").asText(), "5000");
        // failing 이 먼저 처리되도록 더 이른 시각으로
        jdbc.update("update scheduled_transfers set scheduled_at = ? where id = ?",
                LocalDateTime.now().minusMinutes(10), failing);
        jdbc.update("update scheduled_transfers set scheduled_at = ? where id = ?",
                LocalDateTime.now().minusMinutes(5), succeeding);
        doThrow(new IllegalStateException("audit store down")).when(auditLogService)
                .logIndependent(argThat(e -> e != null && "SCHEDULED_TRANSFER_EXEC".equals(e.actionType())
                        && e.result() == AuditResult.FAILURE));

        ScheduledExecutionSummary summary = scheduledTransferService.executeDueTransfers();

        assertThat(summary.due()).isEqualTo(2);
        assertThat(summary.completed()).isEqualTo(1);
        assertThat(summary.failed()).isZero();
        assertThat(summary.skipped()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from scheduled_transfers where id = ?", String.class, failing))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select status from scheduled_transfers where id = ?", String.class,
                succeeding)).isEqualTo("COMPLETED");
    }

    private long register(Customer c, long fromId, String toNumber, String amount) throws Exception {
        String json = "{\"fromAccountId\":" + fromId + ",\"toAccountNumber\":\"" + toNumber + "\",\"amount\":"
                + amount + ",\"pin\":\"" + PIN + "\",\"scheduledAt\":\""
                + LocalDateTime.now().plusDays(1).withNano(0) + "\"}";
        String res = mockMvc.perform(post("/api/scheduled-transfers").header("Authorization", "Bearer " + c.token())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(res).get("id").asLong();
    }
}
