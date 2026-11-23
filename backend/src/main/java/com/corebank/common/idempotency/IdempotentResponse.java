package com.corebank.common.idempotency;

/** 이미 처리된 요청의 저장된 응답. */
public record IdempotentResponse(int status, String body) {
}
