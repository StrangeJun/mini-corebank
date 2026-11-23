package com.corebank.scheduled.dto;

/** 도래 건 1회 실행 결과. skipped = 이미 다른 실행기가 처리했거나 일시 오류로 다음 주기에 재시도할 건. */
public record ScheduledExecutionSummary(int due, int completed, int failed, int skipped) {
}
