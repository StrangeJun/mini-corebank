package com.corebank.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corebank.common.exception.BusinessException;
import com.corebank.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class IdempotencyServiceTest {

    private static final String ENDPOINT = "POST /api/transfers";

    @Autowired
    IdempotencyService service;

    @Test
    void hashIsDeterministicSha256Hex() {
        String h = service.hashRequest("{\"amount\":1000}");
        assertThat(h).hasSize(64).isEqualTo(service.hashRequest("{\"amount\":1000}"));
        assertThat(h).isNotEqualTo(service.hashRequest("{\"amount\":1001}"));
    }

    @Test
    void noRecordMeansNoReplay() {
        assertThat(service.findReplay("key-new", 1L, ENDPOINT, "h")).isEmpty();
    }

    @Test
    void sameRequestReturnsStoredResponse() {
        String hash = service.hashRequest("{\"amount\":1000}");
        service.saveResponse("key-1", 1L, ENDPOINT, hash, 201, "{\"transactionId\":10}");

        assertThat(service.findReplay("key-1", 1L, ENDPOINT, hash))
                .contains(new IdempotentResponse(201, "{\"transactionId\":10}"));
    }

    @Test
    void differentHashUserOrEndpointIsConflict() {
        String hash = service.hashRequest("{\"amount\":1000}");
        service.saveResponse("key-2", 1L, ENDPOINT, hash, 201, "{}");

        assertConflict(() -> service.findReplay("key-2", 1L, ENDPOINT, service.hashRequest("{\"amount\":9999}")));
        assertConflict(() -> service.findReplay("key-2", 2L, ENDPOINT, hash));
        assertConflict(() -> service.findReplay("key-2", 1L, "POST /api/withdrawals", hash));
    }

    @Test
    void duplicateSaveIsConflict() {
        service.saveResponse("key-3", 1L, ENDPOINT, "h", 201, "{}");
        assertConflict(() -> service.saveResponse("key-3", 1L, ENDPOINT, "h", 201, "{}"));
    }

    @Test
    void blankKeyIsInvalidInput() {
        assertThatThrownBy(() -> service.findReplay(" ", 1L, ENDPOINT, "h"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    private static void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT);
    }
}
