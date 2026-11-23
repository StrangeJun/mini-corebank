package com.corebank.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebank.domain.audit.AuditLog;
import com.corebank.domain.audit.AuditLogRepository;
import com.corebank.domain.audit.AuditResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
class AuditLogServiceTest {

    @Autowired
    AuditLogService auditLogService;

    @Autowired
    AuditLogRepository auditLogRepository;

    @Autowired
    TransactionTemplate transactionTemplate;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        auditLogRepository.deleteAll();
    }

    @Test
    void independentLogSurvivesOuterRollbackButInTransactionLogDoesNot() {
        transactionTemplate.executeWithoutResult(status -> {
            auditLogService.logInTransaction(event("TRANSFER", AuditResult.SUCCESS, "ok"));
            auditLogService.logIndependent(event("PIN_VERIFY", AuditResult.FAILURE, "invalid pin"));
            status.setRollbackOnly();
        });

        assertThat(auditLogRepository.findAll())
                .extracting(AuditLog::getActionType)
                .containsExactly("PIN_VERIFY");
    }

    @Test
    void inTransactionLogCommitsWithOuterTransaction() {
        transactionTemplate.executeWithoutResult(status ->
                auditLogService.logInTransaction(event("TRANSFER", AuditResult.SUCCESS, "ok")));

        assertThat(auditLogRepository.count()).isEqualTo(1);
    }

    @Test
    void summaryAndTargetAreMaskedBeforeSave() {
        AuditLog saved = auditLogService.logIndependent(new AuditEvent(
                1L, "LOGIN", "ACCOUNT", "110-123-456789", AuditResult.FAILURE,
                "password=Secret123! pin=1234 account 110-123-456789", "127.0.0.1"));

        AuditLog stored = auditLogRepository.findById(saved.getId()).orElseThrow();
        assertThat(stored.getMaskedSummary()).doesNotContain("Secret123!", "1234", "110-123-456789");
        assertThat(stored.getTargetId()).isEqualTo("110-***-**6789");
    }

    private static AuditEvent event(String action, AuditResult result, String summary) {
        return new AuditEvent(1L, action, "ACCOUNT", "1", result, summary, "127.0.0.1");
    }
}
