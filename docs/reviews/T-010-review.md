# T-010 코드 리뷰: 예약 이체

검토 범위:

- `backend/src/main/java/com/corebank/scheduled/**`
- `backend/src/main/java/com/corebank/domain/scheduled/**`
- `backend/src/main/java/com/corebank/transaction/TransactionService.java`의 예약 이체 실행 메서드
- `backend/src/main/java/com/corebank/common/idempotency/IdempotencyService.java`
- `backend/src/main/resources/application.yml`
- `backend/src/test/java/com/corebank/transaction/ScheduledTransferApiTest.java`

## 전체 요약

- CRITICAL: 0
- HIGH: 0
- MEDIUM: 4
- LOW: 1

T-010은 예약 행 비관적 락과 상태 재확인, 내부 멱등키, 독립 실패 상태 커밋을 모두 사용해 핵심 중복 실행과 원장 롤백을 잘 구현했다. 다만 대량 도래 건 처리의 격리, 등록 시 계좌 상태 경쟁, 실패 상태 커밋 실패가 전체 배치에 미치는 영향은 보완할 필요가 있다.

## 발견 이슈

### [RV-T10-01] FAILED 상태 커밋이 실패하면 다른 도래 건 처리가 중단될 수 있음

- **심각도:** MEDIUM
- **위치:** `backend/src/main/java/com/corebank/scheduled/ScheduledTransferService.java:225-262`
- **위험 시나리오:** 실행 중 `INSUFFICIENT_BALANCE` 등 비즈니스 예외가 발생하면 원장 트랜잭션은 롤백되고 `markFailed()`로 별도 트랜잭션에 FAILED를 기록한다. 그런데 `markFailed()`에서 락 타임아웃/감사 로그 실패 등 RuntimeException이 발생하면 이 예외는 `executeOne()`의 `catch (BusinessException)` 내부에서 처리되지 않고 `executeDueTransfers()`의 다음 건 루프까지 전파될 수 있다.
- **권장 수정:** `markFailed()`도 건별 방어 로직으로 감싸고 실패 시 PENDING 또는 SKIPPED로 남기며 다음 ID 처리를 계속한다. `executeDueTransfers()`의 각 ID에 최상위 예외 격리를 두고, 실패 상태 저장 실패는 별도 오류 카운터/경고 로그로 남긴다.

### [RV-T10-02] 도래 예약 이체 전체 ID를 제한 없이 메모리로 적재

- **심각도:** MEDIUM
- **위치:** `backend/src/main/java/com/corebank/domain/scheduled/ScheduledTransferRepository.java:16-22`, `backend/src/main/java/com/corebank/scheduled/ScheduledTransferService.java:208-222`
- **위험 시나리오:** 도래 시각이 지난 PENDING 건이 100만 건이라도 `findDueIds()`가 모든 ID를 한 번에 읽는다. 실행 배치가 장시간 점유하고 메모리와 커넥션 사용량이 급증할 수 있다.
- **권장 수정:** 배치 크기 또는 페이징/키셋 조회를 적용하고, 한 주기에 일정량만 처리한다. 조회 순서와 `scheduled_at`, `id`에 복합 인덱스를 추가해 도래 건 검색을 빠르게 한다.

### [RV-T10-03] 예약 등록 시 계좌 상태 검증과 PENDING 저장 사이 경쟁 가능

- **심각도:** MEDIUM
- **위치:** `backend/src/main/java/com/corebank/scheduled/ScheduledTransferService.java:124-141`
- **위험 시나리오:** 출금/입금 계좌를 일반 조회로 확인한 뒤 PENDING 예약을 저장한다. 사이에 계좌가 LOCKED/CLOSED로 전환되면 등록 시점 검증을 통과한 예약이 생성될 수 있다. 실행 시점에 다시 차단되므로 이중 이체는 막지만 사용자 입장에서 유효하지 않은 예약이 등록될 수 있다.
- **권장 수정:** 등록 트랜잭션에서도 대상 계좌를 `PESSIMISTIC_WRITE`로 잠그고 상태/소유권/한도를 검증한 뒤 PENDING을 저장한다. 이때 계좌 잠금 순서와 예약 행 잠금 순서를 고정해 계좌 교착을 피한다.

### [RV-T10-04] 예약 등록 INSERT의 무결성 위반을 전부 멱등 충돌로 매핑

- **심각도:** LOW
- **위치:** `backend/src/main/java/com/corebank/scheduled/ScheduledTransferService.java:157-163`
- **위험 시나리오:** `saveAndFlush()`에서 발생한 모든 `DataIntegrityViolationException`을 `IDEMPOTENCY_CONFLICT`로 바꾼다. 진짜 멱등키 UNIQUE 충돌과 다른 무결성 위반을 구분하지 못하면 운영자가 원인 없는 409를 받는다.
- **권장 수정:** 멱등키 UNIQUE 충돌만 식별하고 나머지는 원래 예외를 유지하거나 명시적인 도메인 오류로 매핑한다. 사전 재조회와 UNIQUE 충돌을 함께 사용한다.

### [RV-T10-05] 실행 시점 기존 거래 재조회가 계좌 재검증을 우회할 수 있는 경계

- **심각도:** MEDIUM
- **위치:** `backend/src/main/java/com/corebank/transaction/TransactionService.java:182-198`
- **위험 시나리오:** 내부 멱등키가 이미 존재하면 계좌 상태/잔액/한도 검증과 락 획득을 건너뛴 뒤 기존 거래를 반환한다. 정상적인 완료 재시도에는 안전하지만, 다른 경로가 같은 내부 키 형식으로 레코드를 만들거나 부분 데이터가 손상되면 예약 상태를 COMPLETED로 만들 수 있다.
- **권장 수정:** 내부 멱등키는 클라이언트 키와 분리된 예약 전용 형식으로만 생성/검증되고 이미 존재한 거래가 예약의 출발/도착 계좌/금액과 정확히 일치하는지 검증하도록 한다.

## 잘 구현된 설계 포인트

1. 등록은 멱등키를 외곽의 PIN 검증 뒤 본 트랜잭션에서 재조회한다.
2. 도래 건은 예약 행을 `PESSIMISTIC_WRITE`로 잠그고 PENDING 재확인 후 실행해 취소/중복 실행을 직렬화한다.
3. 예약 실행은 `SCHED-TX-{id}` 내부 멱등키로 원장을 한 번만 기입한다.
4. 비즈니스 실패는 원장 트랜잭션을 롤백하고 별도 트랜잭션에서 FAILED와 실패 사유를 커밋한다.
5. 실행 시점 상태/소유권/잔액/한도를 즉시 이체와 같은 규칙으로 재검증한다.
6. 정상 실행과 실패 실행을 모두 감사 로그로 남기고 동시 실행 테스트에서 이중 이체를 검증한다.
