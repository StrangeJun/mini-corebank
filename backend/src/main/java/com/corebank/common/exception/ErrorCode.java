package com.corebank.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
    INVALID_AMOUNT(HttpStatus.BAD_REQUEST, "금액이 올바르지 않습니다."),
    SAME_ACCOUNT_TRANSFER(HttpStatus.BAD_REQUEST, "같은 계좌로는 이체할 수 없습니다."),
    ACCOUNT_CLOSED(HttpStatus.BAD_REQUEST, "해지된 계좌입니다."),
    INSUFFICIENT_BALANCE(HttpStatus.BAD_REQUEST, "잔액이 부족합니다."),
    PER_TX_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "1회 이체 한도를 초과했습니다."),
    DAILY_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "1일 이체 한도를 초과했습니다."),

    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    INVALID_PIN(HttpStatus.UNAUTHORIZED, "계좌 비밀번호가 일치하지 않습니다."),

    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    FORBIDDEN_ACCOUNT_ACCESS(HttpStatus.FORBIDDEN, "해당 계좌에 접근할 권한이 없습니다."),
    ACCOUNT_LOCKED(HttpStatus.FORBIDDEN, "거래가 정지된 계좌입니다."),

    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
    ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "계좌를 찾을 수 없습니다."),
    SCHEDULED_TRANSFER_NOT_FOUND(HttpStatus.NOT_FOUND, "예약 이체를 찾을 수 없습니다."),
    RECONCILIATION_RUN_NOT_FOUND(HttpStatus.NOT_FOUND, "대사 실행 이력을 찾을 수 없습니다."),

    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 가입된 이메일입니다."),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "같은 멱등키로 다른 요청이 이미 처리되었습니다."),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus getStatus() { return status; }

    /** 외부 응답에 쓰는 표준 코드 (enum 이름과 동일). */
    public String getCode() { return name(); }

    public String getMessage() { return message; }
}
