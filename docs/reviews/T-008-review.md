# T-008 코드 리뷰: 계좌 PIN 잠금 및 관리자 해제

검토 범위:

- `backend/src/main/java/com/corebank/account/AccountPinVerificationService.java`
- `backend/src/main/java/com/corebank/account/AdminAccountController.java`
- `backend/src/main/java/com/corebank/account/AccountService.java`
- `backend/src/main/java/com/corebank/account/dto/AccountUnlockResponse.java`
- `backend/src/main/java/com/corebank/domain/account/Account.java`
- `backend/src/main/java/com/corebank/transaction/TransactionService.java`
- `backend/src/main/java/com/corebank/common/idempotency/IdempotencyService.java`

## 요약

- CRITICAL: 0
- HIGH: 0
- MEDIUM: 0
- LOW: 1

T-008 구현은 PIN 실패 카운트와 계좌 잠금을 본 출금 트랜잭션과 분리해 커밋하며, 비관적 락과 관리자 권한 검증을 일관되게 적용했다. 별도 크리티컬/높은 위험 이슈는 발견되지 않았다.

## 발견 이슈

### [RV-T8-01] 잠금 해제 감사 기록에 해제 사유 필드가 없음

- **심각도:** LOW
- **위치:** `backend/src/main/java/com/corebank/account/AccountService.java:137-154`
- **문제점 및 위험 시나리오:** 운영자가 계좌를 해제할 때 기존 상태와 이전 실패 횟수는 남지만, 해제 사유나 승인 근거가 구조화되어 저장되지 않는다. 감사 추적은 가능하지만 고객 문의 또는 내부 감사 시 해제 사유를 별도로 증명하기 어렵다.
- **권장 수정:** 관리자 해제 요청에 선택적 사유 필드를 추가하거나, 감사 로그의 `masked_summary`에 승인 티켓/사유 코드를 포함한다. 사유에 민감정보가 포함되지 않도록 기존 마스킹 경로를 유지한다.

## 잘 구현된 점

1. `AccountPinVerificationService`가 `REQUIRES_NEW + noRollbackFor=BusinessException`으로 PIN 실패 카운트와 잠금 전환을 독립 커밋한다.
2. PIN 검증 전에 대상 계좌 행을 `PESSIMISTIC_WRITE`로 잠가 동시에 들어오는 잘못된 PIN 요청도 직렬화된다.
3. 출금/이체는 본 행 잠금을 잡는 트랜잭션에 진입하기 전에 PIN 검증을 수행해 자기 교착을 방지한다.
4. 동일 멱등키 재요청에서 오류 PIN이 캐시 응답을 재생하지 않도록 PIN 검증이 멱등성 재조회보다 먼저 실행된다.
5. 관리자 잠금 해제는 행 잠금 후 계좌 상태를 확인하고, `CLOSED` 계좌는 되살리지 않는다.
6. 테스트는 1~4회 누적, 5회 잠금, 성공 시 초기화, 동시 실패, 일반 사용자 차단, 관리자 해제와 재거래를 모두 검증한다.
