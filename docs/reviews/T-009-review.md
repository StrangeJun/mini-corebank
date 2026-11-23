# T-009 코드 리뷰: 원장-잔액 정합성 대사

검토 범위:

- `backend/src/main/java/com/corebank/reconciliation/**`
- `backend/src/main/java/com/corebank/domain/reconciliation/**`
- `backend/src/main/java/com/corebank/config/SchedulingConfig.java`
- `backend/src/main/resources/application.yml`
- `backend/src/main/resources/db/migration/V2__hardening_constraints_and_indexes.sql`
- `backend/src/test/java/com/corebank/transaction/ReconciliationApiTest.java`

## 전체 요약

- CRITICAL: 0
- HIGH: 1
- MEDIUM: 3
- LOW: 1

T-009는 세 불변식을 집계 쿼리로 전수 검사하고, `REPEATABLE_READ` 스냅샷과 재현 가능한 불일치 항목을 이력으로 저장한다. 권한 분리와 정상/이상 계좌 테스트도 충실하다. 다만 정기 배치의 비활성 설정, 분산 실행 중복, 대량 불일치 저장 메모리에는 보완이 필요하다.

## 발견 이슈

### [RV-T9-01] 설정 주석의 "-" 비활성값이 스케줄러를 기동 시 오류로 만들 수 있음

- **심각도:** HIGH
- **위치:** `backend/src/main/resources/application.yml:27-29`, `backend/src/main/java/com/corebank/reconciliation/ReconciliationScheduler.java:22-24`
- **문제점 및 위험 시나리오:** 주석은 `"-"`이면 정기 대사를 비활성화한다고 안내하지만 `@Scheduled(cron = "${...}")`는 이를 유효한 cron 표현식으로 해석하지 않는다. 운영자가 애플리케이션 설정만 바꿔 비활성화를 시도하면 스케줄러 초기화 단계에서 cron 파싱 예외가 발생할 수 있다.
- **권장 수정:** `"-"`를 애플리케이션에서 명시적으로 지원하는 분기 또는 프로필/Conditional을 추가한다. 예를 들어 프로퍼티를 별도 관리하고 `@Scheduled`는 빈 설정이 아닌 두 `@Scheduled` 조건을 분리하거나, `runScheduledReconciliation()` 진입부에서 `"-"`이면 즉시 반환한다. 주석과 실제 동작을 반드시 일치시킨다.

### [RV-T9-02] 대사 전수 검사에 필요한 ledger 복합 인덱스가 부족할 수 있음

- **심각도:** MEDIUM
- **위치:** `backend/src/main/java/com/corebank/reconciliation/ReconciliationChecker.java:47-69`, `backend/src/main/resources/db/migration/V2__hardening_constraints_and_indexes.sql:11-13`
- **문제점 및 위험 시나리오:** 거래별 검사는 `ledger_entries.transaction_id`로 조인되고, 계좌별 검사는 `ledger_entries.account_id`로 그룹되지만 현재 인덱스는 계좌별 최신순 조회용 `(account_id, created_at DESC, id DESC)` 하나뿐이다. 거래량이 커지면 대사 배치가 원장 테이블을 반복 스캔하여 매일 새벽 운영 대사 시간과 I/O를 크게 증가시킬 수 있다.
- **권장 수정:** `ledger_entries(transaction_id, entry_type, amount)`와 `ledger_entries(account_id, entry_type, amount)` 또는 실제 실행계획에 맞는 covering index를 후속 Flyway 마이그레이션으로 추가한다. 대사 쿼리 실행계획을 부하 환경에서 검증한다.

### [RV-T9-03] 다중 인스턴스에서 정기 대사가 중복 실행될 수 있음

- **심각도:** MEDIUM
- **위치:** `backend/src/main/java/com/corebank/reconciliation/ReconciliationScheduler.java:22-24`
- **문제점 및 위험 시나리오:** 여러 애플리케이션 인스턴스가 모두 스케줄러를 실행하면 같은 시각에 중복 대사가 시작되고, 동일한 스냅샷에서 별도의 이력 행이 생길 수 있다. 검증 정확성 자체보다 운영 이력 중복과 실행 부하가 문제다.
- **권장 수정:** 분산 락(ShedLock 또는 DB advisory lock) 또는 단일 스케줄러 인스턴스를 사용해 실행 키와 날짜를 기준으로 원자적 선점 처리한다.

### [RV-T9-04] 불일치 상세가 무제한으로 메모리와 DB/응답에 누적될 수 있음

- **심각도:** MEDIUM
- **위치:** `backend/src/main/java/com/corebank/reconciliation/ReconciliationChecker.java:35-43`, `backend/src/main/java/com/corebank/reconciliation/ReconciliationService.java:59-61,92-105`
- **문제점 및 위험 시나리오:** 모든 불일치 거래와 계좌를 `List`에 모아 `details_json`과 API 응답으로 직렬화한다. 대규모 데이터 부패 또는 ledger 스캔에 많은 불일치가 나오면 힙 메모리와 상세 조회 응답 크기가 급증할 수 있다.
- **권장 수정:** 상세 내역 최대 개수/크기를 설정하고, 초과분은 요약 카운트와 샘플로 제한한다. 대사 실행에 따른 비동기 내보내기 또는 별도 불일치 테이블을 고려한다.

### [RV-T9-05] 대사 실행 스냅샷 시각을 이력 상세와 별도로 증명하기 어려움

- **심각도:** LOW
- **위치:** `backend/src/main/java/com/corebank/reconciliation/ReconciliationService.java:52-68`, `backend/src/main/java/com/corebank/domain/reconciliation/ReconciliationRun.java:30-38`
- **문제점 및 위험 시나리오:** `executedAt`은 이력 저장 시각으로만 남고, 실제 `REPEATABLE_READ` 스냅샷을 시작한 시각이나 데이터베이스 트랜잭션 식별자는 없다. 결과를 나중에 조사할 때 어떤 시점의 스냅샷인지 두 번째로 추적하기 어렵다.
- **권장 수정:** 스냅샷 시작 시각 또는 감사용 실행 상관관계 ID를 이력에 포함한다. 단순히 `LocalDateTime.now()`뿐 아니라 전체 실행 키를 로그와 상세 내역에 남긴다.

## 잘 구현된 설계 포인트

1. 세 불변식을 별도 집계 쿼리로 분리해 거래/계좌/전체 문제를 유형화했다.
2. `REPEATABLE_READ` 트랜잭션으로 각 검증 쿼리 사이의 새 커밋을 배제해 오탐을 줄인다.
3. 거래별 검사는 누락 분개와 차변/대변 불일치를 모두 탐지한다.
4. 계좌별 불일치는 마스킹된 계좌번호와 expected/actual/difference를 정확히 계산해 운영자가 원인을 찾기 쉽게 한다.
5. 수동/정기 이력을 같은 저장 경로로 저장하고 감사 로그까지 남기며 관리자 API로 조회한다.
6. 정상 원장, 잔액 조작, 분개 조작, 누락 분개, 권한, 이력 조회와 스케줄러를 테스트로 검증한다.
