package com.corebank.common.idempotency;

import com.corebank.common.exception.BusinessException;
import com.corebank.common.exception.ErrorCode;
import com.corebank.domain.idempotency.IdempotencyRecord;
import com.corebank.domain.idempotency.IdempotencyRecordRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotency-Key 기반 중복 요청 차단.
 * <pre>
 * hash = hashRequest(body)
 * findReplay(key, userId, endpoint, hash) 가 있으면 저장된 응답 반환
 * 없으면 처리 후 같은 트랜잭션에서 saveResponse(...)
 * </pre>
 */
@Service
public class IdempotencyService {

    static final int MAX_KEY_LENGTH = 100;

    /** 예약 이체 실행용 내부 멱등키 접두사. 클라이언트 키로는 쓸 수 없다 (예약 실행 키 선점 방지). */
    public static final String SCHEDULED_TX_KEY_PREFIX = "SCHED-TX-";

    private final IdempotencyRecordRepository repository;

    public IdempotencyService(IdempotencyRecordRepository repository) {
        this.repository = repository;
    }

    /** 요청 본문(또는 핵심 파라미터를 직렬화한 문자열)의 SHA-256 hex. */
    public String hashRequest(String canonicalRequest) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Objects.requireNonNullElse(canonicalRequest, "")
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * 같은 키로 처리된 요청이 있으면 저장된 응답을 돌려준다.
     * 키는 같은데 사용자·엔드포인트·요청 해시 중 하나라도 다르면 IDEMPOTENCY_CONFLICT.
     */
    @Transactional(readOnly = true)
    public Optional<IdempotentResponse> findReplay(String idempotencyKey, Long userId, String endpoint,
                                                   String requestHash) {
        validateKey(idempotencyKey);
        return repository.findByIdempotencyKey(idempotencyKey)
                .map(record -> {
                    if (!matches(record, userId, endpoint, requestHash)) {
                        throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
                    }
                    return new IdempotentResponse(record.getResponseStatus(), record.getResponseBody());
                });
    }

    /**
     * 처리 결과를 저장한다. 비즈니스 처리와 같은 트랜잭션에서 호출해야 한다.
     * 동시에 같은 키가 먼저 저장되면 IDEMPOTENCY_CONFLICT 를 던져 현재 트랜잭션을 롤백시킨다.
     */
    @Transactional
    public void saveResponse(String idempotencyKey, Long userId, String endpoint, String requestHash,
                             int responseStatus, String responseBody) {
        validateKey(idempotencyKey);
        try {
            repository.saveAndFlush(new IdempotencyRecord(
                    idempotencyKey, userId, endpoint, requestHash, responseStatus, responseBody));
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
        }
    }

    private static boolean matches(IdempotencyRecord record, Long userId, String endpoint, String requestHash) {
        return Objects.equals(record.getUserId(), userId)
                && Objects.equals(record.getEndpoint(), endpoint)
                && Objects.equals(record.getRequestHash(), requestHash);
    }

    /** 키 형식 검증. 부수효과가 있는 선행 단계(PIN 실패 집계 등) 전에 호출할 수 있도록 공개한다. */
    public static void validateKey(String key) {
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH
                || key.regionMatches(true, 0, SCHEDULED_TX_KEY_PREFIX, 0, SCHEDULED_TX_KEY_PREFIX.length())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "Idempotency-Key 가 올바르지 않습니다.");
        }
    }
}
