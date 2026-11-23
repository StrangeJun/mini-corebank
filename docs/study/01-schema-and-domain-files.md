# 스키마와 도메인 계층 전 파일 해설

이 문서는 설정, Flyway 스키마, JPA Entity/Repository를 읽는 순서다.

## 설정과 애플리케이션 진입점

### `application.yml`

- **요약/레이어:** 애플리케이션 설정
- **설계 의도:** PostgreSQL/Flyway/JPA/JWT/CORS/시드 값을 한곳에 둔다.
- **핵심:** `ddl-auto=validate`, PostgreSQL 연결, JWT secret과 validity, 운영 시크릿을 검증할 시드 기본값을 바인딩한다.
- **연결:** `JwtProperties`, `ProductionSecretsGuard`, `SystemSeedInitializer`
- **면접 포인트:** 왜 운영에서 고정 JWT secret을 금지하는가? 고정 키로 서명된 토큰 위조를 막기 위해 환경별 시크릿을 강제한다.

### `V1__init_schema.sql`

- **요약/레이어:** Flyway 베이스 스키마
- **설계 의도:** users, accounts, transactions, ledger_entries, idempotency_records, scheduled_transfers, reconciliation_runs, audit_logs를 생성한다.
- **핵심 테이블:** 금액은 `NUMERIC(19,0)`, 계좌번호는 UNIQUE, 분개와 거래는 외래키로 연결된다.
- **핵심 메서드 대신 핵심 제약:** transaction/ledger amount는 양수, 멱등키는 UNIQUE, append-only 엔티티는 JPA에서 update를 막는다.
- **연결:** 모든 JPA Entity와 Repository
- **면접 포인트:** 왜 잔액에 float/double을 쓰면 안 되는가? 원화의 정확한 정수를 보장하기 위해 NUMERIC/BigDecimal을 사용한다.

### `V2__hardening_constraints_and_indexes.sql`

- **요약/레이어:** 데이터베이스 무결성 강화 마이그레이션
- **설계 의도:** 애플리케이션 우회와 조회 성능을 DB 레벨에서 보강한다.
- **핵심 제약/인덱스:** 고객 계좌 음수 잔액 CHECK, 일일 한도 복합 인덱스 `idx_transactions_from_status_type_created`, 거래내역 최신순 복합 인덱스 `idx_ledger_entries_account_created_id`, 대사 거래별 조인 인덱스 `idx_ledger_entries_transaction_id`
- **설계 의도:** PostgreSQL은 외래키 컬럼에 인덱스를 자동 생성하지 않으므로 대사와 누락 분개 검사를 위한 `ledger_entries.transaction_id` 인덱스를 별도로 만든다.
- **연결:** `Account`, `TransactionRepository.sumOutgoing`, `TransactionHistoryService`, `ReconciliationChecker`
- **면접 포인트:** 애플리케이션 검증만으로 충분하지 않은 이유는 직접 SQL이나 배치가 제약을 우회할 수 있기 때문이다.

### `CorebankApplication.java`

- **요약/레이어:** Spring Boot 진입점
- **설계 의도:** 자동 설정과 컴포넌트 스캔을 시작한다.
- **핵심 메서드:** `main(String[])`이 `SpringApplication.run(...)`을 호출한다.
- **연결:** 전체 Bean과 ApplicationRunner 시드
- **면접 포인트:** ApplicationRunner는 언제 실행되며 트랜잭션 시드를 어디에 적합하게 쓰는가?

## 회원 도메인

### `domain/user/User.java`

- **요약/레이어:** 회원 Entity
- **핵심 필드:** 이메일, 비밀번호 해시, 이름, 역할, 생성/수정 시각
- **핵심 메서드:** 생성자, `@PrePersist`, `@PreUpdate`, getter
- **설계 의도:** 평문 비밀번호를 저장하지 않고 역할과 감사 주체를 식별한다.
- **연결:** `UserRepository`, `AuthService`
- **면접 포인트:** 엔티티에 비밀번호 해시만 저장하는 이유와 BCrypt 특성을 설명할 수 있어야 한다.

### `domain/user/UserRole.java`

- **요약/레이어:** 역할 enum
- **핵심 값:** `USER`, `ADMIN`
- **설계 의도:** Spring 역할 접두사 `ROLE_`와 매핑 가능한 최소 권한 모델을 제공한다.
- **연결:** `SecurityConfig`, JWT claims
- **면접 포인트:** 서버 측 역할 검증이 프론트 숨김보다 중요한 이유를 답한다.

### `domain/user/UserRepository.java`

- **요약/레이어:** 회원 Repository
- **핵심 메서드:** `findByEmail`, `existsByEmail`
- **설계 의도:** 가입 중복과 로그인 조회를 빠르게 처리한다.
- **연결:** `AuthService`, `SystemSeedInitializer`
- **면접 포인트:** 체크 후 insert만으로 중복 가입을 막을 수 없는 이유와 DB UNIQUE의 역할을 설명한다.

## 계좌 도메인

### `domain/account/Account.java`

- **요약/레이어:** 계좌 Entity/도메인 상태
- **핵심 필드:** 계좌번호, 소유자, 시스템 여부, 잔액, 상태, PIN 해시, 실패 횟수, 한도, 낙관적 버전
- **핵심 메서드:** `isOwnedBy`, `debit`, `credit`, `close`, `recordPinFailure`, `resetPinFailures`, `unlock`
- **설계 의도:** 잔액 변경과 PIN 잠금 상태 전이를 도메인 메서드로 제한한다.
- **연결:** `AccountRepository`, `AccountService`, `AccountPinVerificationService`, `TransactionService`
- **면접 포인트:** 비관적 락과 `@Version`을 함께 쓰는 이유와 각자의 역할을 설명한다.

### `domain/account/AccountStatus.java`

- **요약/레이어:** 계좌 상태 enum
- **핵심 값:** `ACTIVE`, `LOCKED`, `CLOSED`
- **설계 의도:** 거래 가능 상태와 잠금/해지 상태를 명시한다.
- **연결:** 모든 계좌 상태 검증
- **면접 포인트:** 상태 전이 중 잘못된 전이(CLOSED에서 ACTIVE)가 발생하지 않도록 어디서 막는가?

### `domain/account/AccountRepository.java`

- **요약/레이어:** 계좌 Repository
- **핵심 메서드:** `findByUserIdAndSystemFalseOrder...`, `findIdByAccountNumber`, `findByIdForUpdate`
- **핵심 어노테이션:** `@Lock(PESSIMISTIC_WRITE)`, `@Query`
- **설계 의도:** 잔액 변경 전 행 잠금을 지원하고 해시에 엔티티를 미리 올리지 않는다.
- **연결:** `TransactionService`, `AccountService`, `AccountPinVerificationService`
- **면접 포인트:** 왜 잠금 전에 계좌 엔티티를 조회하면 stale read가 생길 수 있는가?

## 거래 도메인

### `domain/transaction/Transaction.java`

- **요약/레이어:** 거래 헤더 Entity
- **핵심 필드:** 타입, 상태, 금액, 출발/도착 계좌, 멱등키, 설명, 생성 시각
- **핵심 메서드:** 생성자와 getter, `@PrePersist`
- **설계 의도:** 분개 2건을 묶는 논리적 거래 단위를 제공한다.
- **연결:** `LedgerEntry`, `TransactionRepository`, `TransactionService`
- **면접 포인트:** 헤더와 분개를 분리하는 것이 단일 거래내역 테이블보다 좋은 이유는 무엇인가?

### `domain/transaction/TransactionType.java`

- **요약/레이어:** 거래 유형 enum
- **핵심 값:** `DEPOSIT`, `WITHDRAWAL`, `TRANSFER`
- **연결:** 분개 방향과 일일 한도 쿼리
- **면접 포인트:** 입금은 출금 한도에 포함하지 않는 이유를 설명한다.

### `domain/transaction/TransactionStatus.java`

- **요약/레이어:** 거래 상태 enum
- **핵심 값:** `COMPLETED`, `FAILED`
- **설계 의도:** 성공 거래만 원장 합계와 일일 한도에 포함한다.
- **연결:** `TransactionRepository.sumOutgoing`
- **면접 포인트:** 실패 거래가 원장에 남지 않는 구조가 필요한 이유는 무엇인가?

### `domain/transaction/TransactionRepository.java`

- **요약/레이어:** 거래 Repository
- **핵심 메서드:** `findByIdempotencyKey`, `sumOutgoing`
- **핵심 JPQL:** 당일 완료된 출금/이체의 `fromAccountId` 합계를 구한다.
- **설계 의도:** 잔액과 별도로 1일 누적 한도를 원장 거래에서 계산한다.
- **연결:** `TransactionService.requireWithinLimitsAndBalance`
- **면접 포인트:** 일일 한도 쿼리에 복합 인덱스가 필요한 이유와 시간 경계를 설명한다.

## 분개 도메인

### `domain/ledger/LedgerEntry.java`

- **요약/레이어:** 불변 분개 Entity
- **핵심 필드:** 거래 ID, 계좌 ID, DEBIT/CREDIT, 금액, 분개 직후 잔액 스냅샷
- **핵심 어노테이션:** 모든 변경 필드에 `updatable=false`
- **설계 의도:** 원장 추가만 허용하고 과거 분개를 수정하지 않게 한다.
- **연결:** `LedgerEntryRepository`, `TransactionService`, 대사
- **면접 포인트:** 왜 분개에 `balance_after` 스냅샷을 저장하는가?

### `domain/ledger/EntryType.java`

- **요약/레이어:** 분개 방향 enum
- **핵심 값:** `DEBIT`, `CREDIT`
- **설계 의도:** 차변/대변의 의미를 명시한다.
- **연결:** 복식부기 분개 생성
- **면접 포인트:** 입출금에서 차변 계좌와 대변 계좌가 왜 바뀌는지 설명한다.

### `domain/ledger/LedgerEntryRepository.java`

- **요약/레이어:** 분개 Repository
- **설계 의도:** JPA 기본 CRUD와 페이징/대사 확장을 위한 진입점이다.
- **연결:** `LedgerEntry`, `TransactionService`
- **면접 포인트:** 원장 테이블에 UPDATE API를 노출하지 않는 이유를 설명한다.

## 멱등성 도메인

### `domain/idempotency/IdempotencyRecord.java`

- **요약/레이어:** 멱등 응답 Entity
- **핵심 필드:** 키, 사용자, 엔드포인트, 요청 해시, HTTP 상태, 응답 본문
- **설계 의도:** 재시도 응답과 원본 요청을 함께 보관한다.
- **연결:** `IdempotencyService`
- **면접 포인트:** 왜 멱등키는 전역 UNIQUE이고 사용자/엔드포인트/해시도 함께 검증하는가?

### `domain/idempotency/IdempotencyRecordRepository.java`

- **요약/레이어:** 멱등키 Repository
- **핵심 메서드:** `findByIdempotencyKey`
- **설계 의도:** 저장된 응답 재생과 충돌 판별을 제공한다.
- **연결:** `IdempotencyService`
- **면접 포인트:** UNIQUE 충돌을 어떤 예외로 처리하고 무엇을 롤백하는가?

## 감사 도메인

### `domain/audit/AuditLog.java`

- **요약/레이어:** 불변 감사 로그 Entity
- **핵심 필드:** 행위자, 액션, 대상, 결과, 마스킹 요약, IP, 시각
- **설계 의도:** 누가 무엇을 성공/실패했는지 증적을 남긴다.
- **연결:** `AuditLogService`, `AuditLogQueryService`
- **면접 포인트:** 감사 로그의 대상 ID와 요약을 왜 마스킹하는가?

### `domain/audit/AuditResult.java`

- **요약/레이어:** 감사 결과 enum
- **핵심 값:** `SUCCESS`, `FAILURE`
- **연결:** `AuditEvent`, `AuditLog`
- **면접 포인트:** 성공 거래와 실패 PIN 오버라이트를 구분하는 이유를 설명한다.

### `domain/audit/AuditLogRepository.java`

- **요약/레이어:** 감사 Repository
- **핵심 메서드:** 사용자별 조회, Specification 기반 전체 조회
- **연결:** `AuditLogQueryService`
- **면접 포인트:** 최신순 페이징과 정적 필터가 왜 필요한가?

## 대사 도메인

### `domain/reconciliation/ReconciliationRun.java`

- **요약/레이어:** 대사 실행 이력 Entity
- **핵심 필드:** 실행 주체, 상태, 검사 수, 불일치 수, 상세 JSON
- **설계 의도:** 대사 실행의 증적을 남긴다.
- **연결:** 대사 배치/API 구현
- **면접 포인트:** 대사 결과도 원장과 함께 추적 가능한가?

### `domain/reconciliation/ReconciliationStatus.java`

- **요약/레이어:** 대사 상태 enum
- **핵심 값:** `PASSED`, `MISMATCH_FOUND`

### `domain/reconciliation/ReconciliationTriggerType.java`

- **요약/레이어:** 대사 주체 enum
- **핵심 값:** `MANUAL`, `SCHEDULED`

### `domain/reconciliation/ReconciliationRunRepository.java`

- **요약/레이어:** 대사 이력 Repository
- **설계 의도:** 대사 실행 이력을 조회하고 저장한다.

## 예약 이체 도메인

### `domain/scheduled/ScheduledTransfer.java`

- **요약/레이어:** 예약 이체 Entity
- **핵심 필드:** 출발/도착 계좌, 금액, 예약 시각, 상태, 실패 사유, 실행 거래 ID
- **설계 의도:** 예약 등록과 스케줄 실행을 상태 전이로 관리한다.

### `domain/scheduled/ScheduledTransferStatus.java`

- **요약/레이어:** 예약 상태 enum
- **핵심 값:** `PENDING`, `PROCESSING`, `COMPLETED`, `FAILED`, `CANCELED`

### `domain/scheduled/ScheduledTransferRepository.java`

- **요약/레이어:** 예약 이체 Repository
- **설계 의도:** 도래 예약 조회와 멱등 실행을 위한 진입점이다.
