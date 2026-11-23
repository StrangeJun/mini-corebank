# 금융 도메인 테스트 계획

이 문서는 마일스톤 1의 코어 원장 기능과 마일스톤 2의 보안·대사 심화 기능을 검증하기 위한 필수 시나리오를 정의한다. 금액은 원화 단위이고 소수점 금액은 허용하지 않는다.

공통 검증 데이터는 다음을 사용한다.

- 정상 잔액과 한도 설정을 가진 활성 계좌
- 잔액 부족 계좌
- `LOCKED` 및 `CLOSED` 계좌
- 정상 사용자와 관리자 사용자
- 타인 계좌
- 시스템 시재 계좌 `SYSTEM-VAULT`

## 1. 경계값 및 금액 무결성

| ID | 마일스톤 | 시나리오 | 검증 조건 |
|---|---|---|---|
| QA-M1-FIN-001 | M1 | 입금/출금/이체에 `0원` 또는 음수 금액 요청 | `400 INVALID_AMOUNT` |
| QA-M1-FIN-002 | M1 | 소수점 금액 또는 scale이 있는 금액 요청 | `400 INVALID_AMOUNT`, 원장과 잔액 무변경 |
| QA-M1-FIN-003 | M1 | 잔액보다 큰 출금 또는 이체 요청 | `400 INSUFFICIENT_BALANCE`, 잔액 무변경 |
| QA-M1-FIN-004 | M1 | 1회 이체 한도 초과 | `400 PER_TX_LIMIT_EXCEEDED`, 잔액 무변경 |
| QA-M1-FIN-005 | M1 | 당일 누적 출금·이체 한도 초과 | `400 DAILY_LIMIT_EXCEEDED`, 잔액 무변경 |
| QA-M1-FIN-006 | M1 | `LOCKED` 또는 `CLOSED` 계좌로 거래 요청 | 잠금 계좌는 `403 ACCOUNT_LOCKED`, 해지 계좌는 `400 ACCOUNT_CLOSED` |
| QA-M1-FIN-007 | M1 | 이미 해지된 계좌를 다시 해지 요청 | `400 ACCOUNT_CLOSED` |
| QA-M1-FIN-008 | M1 | 잔액이 남은 계좌 해지 요청 | `400 INVALID_INPUT`, 계좌 상태 무변경 |

## 2. 중복 요청과 동시성

| ID | 마일스톤 | 시나리오 | 검증 조건 |
|---|---|---|---|
| QA-M1-CON-001 | M1 | 동일 `Idempotency-Key`, 동일 본문으로 이체를 2회 호출 | 잔액은 1회만 변동하고 두 응답은 결과를 재사용 |
| QA-M1-CON-002 | M1 | 동일 `Idempotency-Key`, 다른 금액으로 이체 호출 | `409 IDEMPOTENCY_CONFLICT`, 잔액 무변경 |
| QA-M1-CON-003 | M1 | 필수 `Idempotency-Key` 누락 또는 공백 | `400 INVALID_INPUT` |
| QA-M1-CON-004 | M1 | 멀티스레드로 동일 계좌에서 잔액보다 많은 동시 출금 실행 | 초과 출금 성공 건수 `0`, 최종 잔액은 음수가 아닌지 검증 |
| QA-M1-CON-005 | M1 | 동일 계좌 동시 출금 또는 이체 실행 | Lost Update 없이 요청별 잔액과 원장이 정확히 일치 |
| QA-M1-CON-006 | M1 | A→B와 B→A 이체를 동시에 실행 | 데드락 `0건`, 두 계좌 잔액 합계는 거래 전후 동일 |
| QA-M2-CON-007 | M2 | 예약 이체 스케줄러가 동일 예약 건을 중복 실행 | 예약 이체는 멱등하게 1회만 완료, 거래 중복 생성 없음 |

## 3. 권한 및 접근 제어

| ID | 마일스톤 | 시나리오 | 검증 조건 |
|---|---|---|---|
| QA-M1-AUTH-001 | M1 | 미인증 상태로 보호 API 호출 | `401 UNAUTHORIZED` |
| QA-M1-AUTH-002 | M1 | 타인 계좌 상세 조회 | `403 FORBIDDEN_ACCOUNT_ACCESS` |
| QA-M1-AUTH-003 | M1 | 타인 계좌 거래내역 조회 | `403 FORBIDDEN_ACCOUNT_ACCESS` |
| QA-M1-AUTH-004 | M1 | 타인 계좌 출금 또는 이체 | `403 FORBIDDEN_ACCOUNT_ACCESS`, 잔액 무변경 |
| QA-M1-AUTH-005 | M1 | 타인 계좌 해지 | `403 FORBIDDEN_ACCOUNT_ACCESS`, 계좌 상태 무변경 |
| QA-M1-AUTH-006 | M1 | 일반 사용자(`USER`)가 `/api/admin/**` 호출 | `403` |
| QA-M2-AUTH-007 | M2 | 관리자 또는 운영자가 계좌 잠금 해제 수행 | 관리자만 성공, 일반 사용자는 `403` |
| QA-M1-AUTH-008 | M1 | 일반 사용자가 전체 감사 로그 조회 | `403`, 본인 로그 조회는 허용 |

## 4. 복식부기 원장과 대사 불변식

| ID | 마일스톤 | 시나리오 | 검증 조건 |
|---|---|---|---|
| QA-M1-LDG-001 | M1 | 입금 성공 | `ledger_entries` 정확히 2줄 생성, `DEBIT`은 시재, `CREDIT`은 고객 계좌 |
| QA-M1-LDG-002 | M1 | 출금 성공 | `ledger_entries` 정확히 2줄 생성, `DEBIT`은 고객 계좌, `CREDIT`은 시재 |
| QA-M1-LDG-003 | M1 | 이체 성공 | 출금 계좌 `DEBIT`, 입금 계좌 `CREDIT`, 각 2줄 분개 생성 |
| QA-M1-LDG-004 | M1 | 모든 성공 거래의 분개 금액 검증 | 거래별 `SUM(CREDIT) == SUM(DEBIT)` |
| QA-M1-LDG-005 | M1 | 분개 직후 잔액 스냅샷 검증 | 각 `ledger_entry.balance_after`가 해당 계좌 잔액 변화와 일치 |
| QA-M1-LDG-006 | M1 | 계좌 잔액과 원장 합계 대사 | 모든 계좌에서 `accounts.balance == SUM(CREDIT) - SUM(DEBIT)` |
| QA-M1-LDG-007 | M1 | 전체 제로섬 대사 | 고객 계좌와 `SYSTEM-VAULT` 포함한 전체 잔액 합계는 `0` |
| QA-M2-RCN-008 | M2 | 정상 원장 상태에서 대사 실행 | `reconciliation_runs.status == PASSED`, 불일치 건수 `0` |
| QA-M2-RCN-009 | M2 | 계좌 잔액 또는 분개 불일치 주입 | 대사 실행 결과 `MISMATCH_FOUND`, 불일치 건수가 상세와 일치 |
| QA-M2-RCN-010 | M2 | 거래별 차변/대변 불일치 주입 | 대사 실행이 불일치를 탐지하고 결과를 이력에 저장 |

## 5. 민감정보 마스킹과 감사 로그

| ID | 마일스톤 | 시나리오 | 검증 조건 |
|---|---|---|---|
| QA-M1-SEC-001 | M1 | 회원가입과 로그인 성공/실패 응답 검증 | 평문 비밀번호 또는 PIN이 응답에 포함되지 않음 |
| QA-M1-SEC-002 | M1 | 감사 로그 작성 검증 | `masked_summary`에 평문 비밀번호, PIN, 전체 계좌번호가 포함되지 않음 |
| QA-M1-SEC-003 | M1 | 계좌번호 마스킹 검증 | 예: `110-123-456789`는 `110-***-**6789`, `SYSTEM-VAULT`는 그대로 유지 |
| QA-M1-SEC-004 | M1 | 이메일 마스킹 검증 | 감사 로그의 이메일은 `ho***@example.com` 형식 또는 동등한 마스킹 형식 |
| QA-M1-SEC-005 | M1 | PIN 불일치 후 연속 실패 검증 | `INVALID_PIN` 또는 잠금 규칙에 따라 응답, 실패 5회째 계좌는 `LOCKED` |
| QA-M1-SEC-006 | M1 | PIN 오류 거래 검증 | 감사 로그가 독립 트랜잭션으로 유지되고 원래 거래는 롤백 |
| QA-M1-SEC-007 | M1 | 잔액 부족 또는 한도 초과 거래 검증 | 실패에도 감사 로그가 유지되고 계좌 잔액과 원장은 무변경 |
| QA-M1-SEC-008 | M1 | 감사 로그 조회 응답 검증 | 모든 항목의 `maskedSummary`에 민감정보 평문이 없음 |
