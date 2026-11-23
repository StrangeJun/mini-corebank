# mini-corebank 전체 코드 파일 학습 지도

이 문서는 `backend/src/main` 아래의 모든 파일을 빠짐없이 찾고 공부하는 인덱스다. 상세 설명은 아래 네 부분으로 나뉜다.

- [스키마와 도메인 계층](01-schema-and-domain-files.md): 설정, DB 마이그레이션, Entity/Repository
- [공통·인증·감사 계층](02-common-auth-audit-files.md): 예외, 멱등성, JWT, 감사, 시드
- [계좌·트랜잭션 계층](03-account-and-transaction-files.md): 계좌, PIN 잠금, 원장, 이체, 조회
- [정합성 대사 계층](04-reconciliation-files.md): 3대 불변식, 대사 이력, 스케줄러
- [마일스톤 1 면접 요약](M1-core-ledger.md): 전체 흐름과 핵심 면접 질문

## 추천 학습 동선

1. `README.md`에서 전체 파일 지도를 확인한다.
2. 스키마와 도메인 파일을 읽어 데이터 상태와 불변식을 이해한다.
3. 예외, 멱등성, JWT, 감사의 공통 흐름을 읽는다.
4. 계좌와 `TransactionService`를 읽고 입금·출금·이체의 순서로 계층을 종합한다.
5. 동시성·PIN 잠금 테스트와 면접 질문을 확인한다.

## 전체 디렉터리 트리

```text
backend/src/main/
├── resources/
│   ├── application.yml
│   └── db/migration/
│       ├── V1__init_schema.sql
│       └── V2__hardening_constraints_and_indexes.sql
└── java/com/corebank/
    ├── CorebankApplication.java
    ├── account/
    ├── audit/
    ├── auth/
    ├── common/
    ├── config/
    ├── domain/
    ├── reconciliation/
    ├── seed/
    ├── transaction/
    └── user/
```

## 설정과 스키마

| 파일 | 역할 | 상세 문서 |
|---|---|---|
| [`application.yml`](../../backend/src/main/resources/application.yml) | 데이터소스, JPA, JWT, CORS, 시드 설정 | [01권](01-schema-and-domain-files.md) |
| [`V1__init_schema.sql`](../../backend/src/main/resources/db/migration/V1__init_schema.sql) | 8개 핵심 테이블과 기본 제약 | [01권](01-schema-and-domain-files.md) |
| [`V2__hardening_constraints_and_indexes.sql`](../../backend/src/main/resources/db/migration/V2__hardening_constraints_and_indexes.sql) | 잔액 체크와 조회 복합 인덱스 | [01권](01-schema-and-domain-files.md) |
| [`CorebankApplication.java`](../../backend/src/main/java/com/corebank/CorebankApplication.java) | Spring Boot 진입점 | [01권](01-schema-and-domain-files.md) |

## 도메인 계층

| 파일 | 역할 | 상세 문서 |
|---|---|---|
| [`domain/user/User.java`](../../backend/src/main/java/com/corebank/domain/user/User.java) | 회원 엔티티 | [01권](01-schema-and-domain-files.md) |
| [`domain/user/UserRole.java`](../../backend/src/main/java/com/corebank/domain/user/UserRole.java) | USER/ADMIN 역할 enum | [01권](01-schema-and-domain-files.md) |
| [`domain/user/UserRepository.java`](../../backend/src/main/java/com/corebank/domain/user/UserRepository.java) | 회원 조회 Repository | [01권](01-schema-and-domain-files.md) |
| [`domain/account/Account.java`](../../backend/src/main/java/com/corebank/domain/account/Account.java) | 계좌 잔액/상태/PIN 실패 도메인 | [01권](01-schema-and-domain-files.md) |
| [`domain/account/AccountStatus.java`](../../backend/src/main/java/com/corebank/domain/account/AccountStatus.java) | ACTIVE/LOCKED/CLOSED 상태 enum | [01권](01-schema-and-domain-files.md) |
| [`domain/account/AccountRepository.java`](../../backend/src/main/java/com/corebank/domain/account/AccountRepository.java) | 계좌 조회와 비관적 락 | [01권](01-schema-and-domain-files.md) |
| [`domain/transaction/Transaction.java`](../../backend/src/main/java/com/corebank/domain/transaction/Transaction.java) | 거래 헤더 엔티티 | [01권](01-schema-and-domain-files.md) |
| [`domain/transaction/TransactionType.java`](../../backend/src/main/java/com/corebank/domain/transaction/TransactionType.java) | 입금/출금/이체 타입 | [01권](01-schema-and-domain-files.md) |
| [`domain/transaction/TransactionStatus.java`](../../backend/src/main/java/com/corebank/domain/transaction/TransactionStatus.java) | 거래 완료/실패 상태 | [01권](01-schema-and-domain-files.md) |
| [`domain/transaction/TransactionRepository.java`](../../backend/src/main/java/com/corebank/domain/transaction/TransactionRepository.java) | 당일 출금 합계 JPQL | [01권](01-schema-and-domain-files.md) |
| [`domain/ledger/LedgerEntry.java`](../../backend/src/main/java/com/corebank/domain/ledger/LedgerEntry.java) | 불변 분개 엔티티 | [01권](01-schema-and-domain-files.md) |
| [`domain/ledger/EntryType.java`](../../backend/src/main/java/com/corebank/domain/ledger/EntryType.java) | DEBIT/CREDIT enum | [01권](01-schema-and-domain-files.md) |
| [`domain/ledger/LedgerEntryRepository.java`](../../backend/src/main/java/com/corebank/domain/ledger/LedgerEntryRepository.java) | 분개 Repository | [01권](01-schema-and-domain-files.md) |
| [`domain/idempotency/IdempotencyRecord.java`](../../backend/src/main/java/com/corebank/domain/idempotency/IdempotencyRecord.java) | 멱등키 저장 엔티티 | [01권](01-schema-and-domain-files.md) |
| [`domain/idempotency/IdempotencyRecordRepository.java`](../../backend/src/main/java/com/corebank/domain/idempotency/IdempotencyRecordRepository.java) | 멱등키 조회 Repository | [01권](01-schema-and-domain-files.md) |
| [`domain/audit/AuditLog.java`](../../backend/src/main/java/com/corebank/domain/audit/AuditLog.java) | 불변 감사 로그 엔티티 | [01권](01-schema-and-domain-files.md) |
| [`domain/audit/AuditResult.java`](../../backend/src/main/java/com/corebank/domain/audit/AuditResult.java) | 성공/실패 결과 enum | [01권](01-schema-and-domain-files.md) |
| [`domain/audit/AuditLogRepository.java`](../../backend/src/main/java/com/corebank/domain/audit/AuditLogRepository.java) | 감사 로그 조회 Repository | [01권](01-schema-and-domain-files.md) |
| [`domain/reconciliation/ReconciliationRun.java`](../../backend/src/main/java/com/corebank/domain/reconciliation/ReconciliationRun.java) | 대사 실행 이력 엔티티 | [01권](01-schema-and-domain-files.md) |
| [`domain/reconciliation/ReconciliationStatus.java`](../../backend/src/main/java/com/corebank/domain/reconciliation/ReconciliationStatus.java) | 대사 결과 enum | [01권](01-schema-and-domain-files.md) |
| [`domain/reconciliation/ReconciliationTriggerType.java`](../../backend/src/main/java/com/corebank/domain/reconciliation/ReconciliationTriggerType.java) | 대사 실행 주체 enum | [01권](01-schema-and-domain-files.md) |
| [`domain/reconciliation/ReconciliationRunRepository.java`](../../backend/src/main/java/com/corebank/domain/reconciliation/ReconciliationRunRepository.java) | 대사 이력 Repository | [01권](01-schema-and-domain-files.md) |
| [`domain/scheduled/ScheduledTransfer.java`](../../backend/src/main/java/com/corebank/domain/scheduled/ScheduledTransfer.java) | 예약 이체 엔티티 | [01권](01-schema-and-domain-files.md) |
| [`domain/scheduled/ScheduledTransferStatus.java`](../../backend/src/main/java/com/corebank/domain/scheduled/ScheduledTransferStatus.java) | 예약 이체 상태 enum | [01권](01-schema-and-domain-files.md) |
| [`domain/scheduled/ScheduledTransferRepository.java`](../../backend/src/main/java/com/corebank/domain/scheduled/ScheduledTransferRepository.java) | 예약 이체 Repository | [01권](01-schema-and-domain-files.md) |

## 공통·인증·감사 계층

| 파일 | 역할 | 상세 문서 |
|---|---|---|
| [`common/exception/ErrorCode.java`](../../backend/src/main/java/com/corebank/common/exception/ErrorCode.java) | 표준 HTTP/도메인 코드 | [02권](02-common-auth-audit-files.md) |
| [`common/exception/BusinessException.java`](../../backend/src/main/java/com/corebank/common/exception/BusinessException.java) | 도메인 예외 | [02권](02-common-auth-audit-files.md) |
| [`common/exception/ApiErrorResponse.java`](../../backend/src/main/java/com/corebank/common/exception/ApiErrorResponse.java) | 표준 오류 응답 | [02권](02-common-auth-audit-files.md) |
| [`common/exception/GlobalExceptionHandler.java`](../../backend/src/main/java/com/corebank/common/exception/GlobalExceptionHandler.java) | 전역 예외 처리 | [02권](02-common-auth-audit-files.md) |
| [`common/util/MaskingUtil.java`](../../backend/src/main/java/com/corebank/common/util/MaskingUtil.java) | 계좌·PIN·비밀번호 마스킹 | [02권](02-common-auth-audit-files.md) |
| [`common/idempotency/IdempotencyService.java`](../../backend/src/main/java/com/corebank/common/idempotency/IdempotencyService.java) | 멱등키 검증/충돌/저장 | [02권](02-common-auth-audit-files.md) |
| [`common/idempotency/IdempotentResponse.java`](../../backend/src/main/java/com/corebank/common/idempotency/IdempotentResponse.java) | 저장 응답 DTO | [02권](02-common-auth-audit-files.md) |
| [`common/PageParams.java`](../../backend/src/main/java/com/corebank/common/PageParams.java) | 페이징 검증/정렬 | [02권](02-common-auth-audit-files.md) |
| [`common/PageResponse.java`](../../backend/src/main/java/com/corebank/common/PageResponse.java) | 페이징 응답 | [02권](02-common-auth-audit-files.md) |
| [`config/SecurityConfig.java`](../../backend/src/main/java/com/corebank/config/SecurityConfig.java) | Spring Security 체인 | [02권](02-common-auth-audit-files.md) |
| [`config/ProductionSecretsGuard.java`](../../backend/src/main/java/com/corebank/config/ProductionSecretsGuard.java) | 운영 기본 시크릿 차단 | [02권](02-common-auth-audit-files.md) |
| [`auth/AuthUser.java`](../../backend/src/main/java/com/corebank/auth/jwt/AuthUser.java) | 인증 주체 record | [02권](02-common-auth-audit-files.md) |
| [`auth/jwt/JwtProperties.java`](../../backend/src/main/java/com/corebank/auth/jwt/JwtProperties.java) | JWT 설정 바인딩 | [02권](02-common-auth-audit-files.md) |
| [`auth/jwt/JwtTokenProvider.java`](../../backend/src/main/java/com/corebank/auth/jwt/JwtTokenProvider.java) | JWT 발급/검증 | [02권](02-common-auth-audit-files.md) |
| [`auth/jwt/JwtAuthenticationFilter.java`](../../backend/src/main/java/com/corebank/auth/jwt/JwtAuthenticationFilter.java) | Authorization 헤더 필터 | [02권](02-common-auth-audit-files.md) |
| [`auth/AuthController.java`](../../backend/src/main/java/com/corebank/auth/AuthController.java) | 회원가입/로그인/me API | [02권](02-common-auth-audit-files.md) |
| [`auth/AuthService.java`](../../backend/src/main/java/com/corebank/auth/AuthService.java) | 인증 비즈니스 로직 | [02권](02-common-auth-audit-files.md) |
| [`auth/dto/LoginRequest.java`](../../backend/src/main/java/com/corebank/auth/dto/LoginRequest.java) | 로그인 요청 DTO | [02권](02-common-auth-audit-files.md) |
| [`auth/dto/LoginResponse.java`](../../backend/src/main/java/com/corebank/auth/dto/LoginResponse.java) | 로그인 응답 DTO | [02권](02-common-auth-audit-files.md) |
| [`auth/dto/SignupRequest.java`](../../backend/src/main/java/com/corebank/auth/dto/SignupRequest.java) | 가입 요청 DTO | [02권](02-common-auth-audit-files.md) |
| [`audit/AuditEvent.java`](../../backend/src/main/java/com/corebank/audit/AuditEvent.java) | 감사 이벤트 DTO | [02권](02-common-auth-audit-files.md) |
| [`audit/AuditLogService.java`](../../backend/src/main/java/com/corebank/audit/AuditLogService.java) | REQUIRED/REQUIRES_NEW 감사 저장 | [02권](02-common-auth-audit-files.md) |
| [`audit/AuditLogQueryService.java`](../../backend/src/main/java/com/corebank/audit/AuditLogQueryService.java) | 본인/관리자 감사 조회 | [02권](02-common-auth-audit-files.md) |
| [`audit/AuditLogController.java`](../../backend/src/main/java/com/corebank/audit/AuditLogController.java) | 감사 조회 API | [02권](02-common-auth-audit-files.md) |
| [`audit/dto/AuditLogResponse.java`](../../backend/src/main/java/com/corebank/audit/dto/AuditLogResponse.java) | 감사 응답 마스킹 | [02권](02-common-auth-audit-files.md) |
| [`seed/SystemSeedInitializer.java`](../../backend/src/main/java/com/corebank/seed/SystemSeedInitializer.java) | 시재/관리자 시드 | [02권](02-common-auth-audit-files.md) |
| [`user/UserResponse.java`](../../backend/src/main/java/com/corebank/user/UserResponse.java) | 사용자 응답 DTO | [02권](02-common-auth-audit-files.md) |

## 계좌·거래 계층

| 파일 | 역할 | 상세 문서 |
|---|---|---|
| [`account/AccountNumberGenerator.java`](../../backend/src/main/java/com/corebank/account/AccountNumberGenerator.java) | SecureRandom 계좌번호 생성 | [03권](03-account-and-transaction-files.md) |
| [`account/AccountService.java`](../../backend/src/main/java/com/corebank/account/AccountService.java) | 계좌 개설/조회/해지/해제 | [03권](03-account-and-transaction-files.md) |
| [`account/AccountPinVerificationService.java`](../../backend/src/main/java/com/corebank/account/AccountPinVerificationService.java) | PIN 검증과 5회 잠금 | [03권](03-account-and-transaction-files.md) |
| [`account/AccountController.java`](../../backend/src/main/java/com/corebank/account/AccountController.java) | 고객 계좌 API | [03권](03-account-and-transaction-files.md) |
| [`account/AdminAccountController.java`](../../backend/src/main/java/com/corebank/account/AdminAccountController.java) | 관리자 잠금 해제 API | [03권](03-account-and-transaction-files.md) |
| [`account/dto/AccountResponse.java`](../../backend/src/main/java/com/corebank/account/dto/AccountResponse.java) | 계좌 응답 DTO | [03권](03-account-and-transaction-files.md) |
| [`account/dto/AccountUnlockResponse.java`](../../backend/src/main/java/com/corebank/account/dto/AccountUnlockResponse.java) | 잠금 해제 응답 DTO | [03권](03-account-and-transaction-files.md) |
| [`account/dto/CreateAccountRequest.java`](../../backend/src/main/java/com/corebank/account/dto/CreateAccountRequest.java) | 계좌 개설 요청 검증 | [03권](03-account-and-transaction-files.md) |
| [`transaction/TransactionService.java`](../../backend/src/main/java/com/corebank/transaction/TransactionService.java) | 입출금/이체 원장 트랜잭션 | [03권](03-account-and-transaction-files.md) |
| [`transaction/TransactionController.java`](../../backend/src/main/java/com/corebank/transaction/TransactionController.java) | 입출금/이체 API | [03권](03-account-and-transaction-files.md) |
| [`transaction/TransactionHistoryService.java`](../../backend/src/main/java/com/corebank/transaction/TransactionHistoryService.java) | 거래내역 필터/페이징 | [03권](03-account-and-transaction-files.md) |
| [`transaction/TransactionHistoryController.java`](../../backend/src/main/java/com/corebank/transaction/TransactionHistoryController.java) | 거래내역 조회 API | [03권](03-account-and-transaction-files.md) |
| [`transaction/dto/DepositRequest.java`](../../backend/src/main/java/com/corebank/transaction/dto/DepositRequest.java) | 입금 요청 DTO | [03권](03-account-and-transaction-files.md) |
| [`transaction/dto/WithdrawRequest.java`](../../backend/src/main/java/com/corebank/transaction/dto/WithdrawRequest.java) | 출금 요청 DTO | [03권](03-account-and-transaction-files.md) |
| [`transaction/dto/TransferRequest.java`](../../backend/src/main/java/com/corebank/transaction/dto/TransferRequest.java) | 이체 요청 DTO | [03권](03-account-and-transaction-files.md) |
| [`transaction/dto/TransactionResponse.java`](../../backend/src/main/java/com/corebank/transaction/dto/TransactionResponse.java) | 거래 응답 DTO | [03권](03-account-and-transaction-files.md) |
| [`transaction/dto/TransactionHistoryItem.java`](../../backend/src/main/java/com/corebank/transaction/dto/TransactionHistoryItem.java) | 거래내역 항목 DTO | [03권](03-account-and-transaction-files.md) |

## 정합성 대사 계층

| 파일 | 역할 | 상세 문서 |
|---|---|---|
| [`reconciliation/ReconciliationChecker.java`](../../backend/src/main/java/com/corebank/reconciliation/ReconciliationChecker.java) | 3대 불변식 집계와 불일치 상한 | [04권](04-reconciliation-files.md) |
| [`reconciliation/ReconciliationService.java`](../../backend/src/main/java/com/corebank/reconciliation/ReconciliationService.java) | 대사 실행/이력 저장/조회 | [04권](04-reconciliation-files.md) |
| [`reconciliation/ReconciliationController.java`](../../backend/src/main/java/com/corebank/reconciliation/ReconciliationController.java) | 관리자 대사 실행/목록/상세 API | [04권](04-reconciliation-files.md) |
| [`reconciliation/ReconciliationScheduler.java`](../../backend/src/main/java/com/corebank/reconciliation/ReconciliationScheduler.java) | 정기 대사 스케줄러 | [04권](04-reconciliation-files.md) |
| [`config/SchedulingConfig.java`](../../backend/src/main/java/com/corebank/config/SchedulingConfig.java) | Spring Scheduling 활성화 | [04권](04-reconciliation-files.md) |
| [`reconciliation/dto/ReconciliationMismatch.java`](../../backend/src/main/java/com/corebank/reconciliation/dto/ReconciliationMismatch.java) | 불일치 상세 DTO | [04권](04-reconciliation-files.md) |
| [`reconciliation/dto/ReconciliationRunResponse.java`](../../backend/src/main/java/com/corebank/reconciliation/dto/ReconciliationRunResponse.java) | 대사 실행 상세 응답 | [04권](04-reconciliation-files.md) |
| [`reconciliation/dto/ReconciliationRunSummary.java`](../../backend/src/main/java/com/corebank/reconciliation/dto/ReconciliationRunSummary.java) | 대사 이력 목록 요약 | [04권](04-reconciliation-files.md) |
