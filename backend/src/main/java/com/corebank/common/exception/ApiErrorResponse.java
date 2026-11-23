package com.corebank.common.exception;

import java.time.LocalDateTime;

public record ApiErrorResponse(String code, String message, LocalDateTime timestamp) {

    public static ApiErrorResponse of(ErrorCode errorCode, String message) {
        return new ApiErrorResponse(errorCode.getCode(), message, LocalDateTime.now());
    }

    public static ApiErrorResponse of(ErrorCode errorCode) {
        return of(errorCode, errorCode.getMessage());
    }
}
