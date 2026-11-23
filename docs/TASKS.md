# 작업 목록

상태: 대기 / 진행 중 / 개발 완료 / QA 완료 / 보류

## 마일스톤 1 — 코어 계좌·복식부기 원장·안전한 이체

| ID | 작업 | 담당 | 상태 |
|---|---|---|---|
| T-001 | 백엔드 프로젝트 초기화 (`backend/` Spring Boot 3.x + Java 17 + Gradle + `docker-compose.yml` + Flyway V1 8개 테이블 스키마 + `tests/backend` 소스셋 연동) | claude | 개발 완료 |
| T-002 | 공통 기반 구현 (표준 에러 코드/예외 처리, 민감정보 마스킹 유틸, 감사 로그 `AuditLogService`, 멱등성 `IdempotencyService`) | claude | 개발 완료 |
| T-003 | 회원·인증·보안 구현 (회원가입, 로그인, JWT 인증 필터, `USER`/`ADMIN` 권한, 시스템 시재 `SYSTEM-VAULT` 및 기본 관리자 시드) | claude | 개발 완료 |
| T-004 | 계좌 관리 구현 (원화 계좌 개설·PIN 해시 저장, 내 계좌 목록/상세 조회, 소유권 검증, 계좌 상태 `ACTIVE`/`LOCKED`/`CLOSED` 관리) | claude | 개발 완료 |
| T-005 | 복식부기 원장 기반 입금·출금·즉시 이체 구현 (차변/대변 2줄 분개, 계좌 ID 정렬 비관적 락 데드락 방지, 1회/1일 한도 검증, `Idempotency-Key` 적용) | claude | 개발 완료 |
| T-006 | 거래내역 조회 및 감사 로그 조회 API 구현 (기간/유형 필터, 페이징, 계좌번호 마스킹 응답) | claude | 개발 완료 |
| T-007 | 마일스톤 1 QA — 코어 원장·입출금·이체·동시성·멱등성·권한·경계값(0원/음수/잔액부족/한도초과)·로그 마스킹 테스트 작성 및 실행 (`tests/backend`) | copilot | 대기 |

## 마일스톤 2 — 1금융권 실무 심화 기능

| ID | 작업 | 담당 | 상태 |
|---|---|---|---|
| T-008 | 계좌 비밀번호(PIN) 검증 & 5회 연속 오류 시 자동 `LOCKED`(거래정지) 전환 + 관리자(`ADMIN`) 잠금 해제 API | claude | 대기 |
| T-009 | 원장-잔액 정합성 대사(Reconciliation) 구현 (3대 불변식 검증: 거래별 대차 일치, 계좌 잔액=원장 합계, 전체 Zero-Sum 일치 + 이력 저장/조회 API) | claude | 대기 |
| T-010 | 예약 이체(Scheduled Transfer) 구현 (등록 시 PIN 검증, 조회·취소 API, 스케줄러 도래 건 멱등 실행 및 잔액/한도/상태 재검증·실패 사유 기록) | claude | 대기 |
| T-011 | 마일스톤 2 QA — PIN 5회 오류 잠금/해제, 정합성 대사(정상/불일치 탐지), 예약 이체(성공/잔액부족 실패/중복 실행 방지) 테스트 작성 및 실행 | copilot | 대기 |

## 마일스톤 3 — 데모용 웹 화면 & 포트폴리오 마무리

| ID | 작업 | 담당 | 상태 |
|---|---|---|---|
| T-012 | 프론트엔드 데모 웹 화면 구현 (`frontend/` React + TypeScript + Vite: 로그인/회원가입, 계좌 개설·조회, 입출금·즉시이체·예약이체, 거래내역, 관리자 대사/감사로그/잠금해제) | claude | 대기 |
| T-013 | 마일스톤 3 QA & 포트폴리오 문서화 — 전체 통합 테스트 점검 및 `README.md`(아키텍처, 복식부기·동시성·대사 설계, 실행 가이드) 작성 | copilot | 대기 |

## 작업 기록
- T-001 개발 완료: Spring Boot 3.3.4/Java 17/Gradle 8.14.3 wrapper 백엔드, docker-compose(PostgreSQL 16), Flyway V1 8개 테이블, JPA 엔티티·enum·리포지토리(com.corebank.domain.*), H2(PostgreSQL 모드) test 프로필, tests/backend 소스셋 연동 + 컨텍스트/매핑 테스트 2건 / 변경 파일: docker-compose.yml, backend/(build.gradle, settings.gradle, gradlew*, gradle/, .gitignore, src/main/java/com/corebank/**, src/main/resources/application.yml, db/migration/V1__init_schema.sql, src/test/resources/application-test.yml), tests/backend/com/corebank/CorebankApplicationContextTest.java / 확인: cd backend && ./gradlew test (2 passed) — 실제 PostgreSQL 기동 검증은 Docker 데몬 미실행으로 미수행
- T-002 개발 완료: ErrorCode(17종)/BusinessException/ApiErrorResponse/GlobalExceptionHandler(스택트레이스·입력값 비노출), MaskingUtil(계좌번호·이메일·password/pin 값 마스킹), AuditLogService(logInTransaction=REQUIRED, logIndependent=REQUIRES_NEW, 저장 전 마스킹), IdempotencyService(SHA-256 요청 해시, 키 재사용 시 user/endpoint/hash 불일치→409, 동시 중복 저장→409) / 변경 파일: backend/src/main/java/com/corebank/{common/exception/*, common/util/MaskingUtil.java, common/idempotency/*, audit/*}, backend/src/test/java/com/corebank/{common/**, audit/*}Test.java / 확인: cd backend && ./gradlew test (18 passed)
- T-003 개발 완료: POST /api/auth/signup(BCrypt, 이메일 소문자 정규화, 중복→409, USER_SIGNUP 감사로그)·POST /api/auth/login(JWT HS256 userId/email/role, 실패 시 LOGIN_FAIL REQUIRES_NEW 감사로그+401·이메일 존재 여부 비노출)·GET /api/auth/me, Stateless SecurityConfig(CSRF off, /api/admin/** ADMIN, 401/403 표준 JSON), CORS(5173/3000), SystemSeedInitializer(SYSTEM-VAULT·admin@corebank.com idempotent 시드), ErrorCode.FORBIDDEN 추가 / 변경 파일: backend/src/main/java/com/corebank/{CorebankApplication.java, auth/**, config/SecurityConfig.java, seed/SystemSeedInitializer.java, user/UserResponse.java, common/exception/ErrorCode.java}, application.yml, application-test.yml, tests/backend/com/corebank/CorebankApplicationContextTest.java(시드 반영), backend/src/test/java/com/corebank/{auth/AuthApiTest, seed/SystemSeedInitializerTest, audit/AuditLogServiceTest}.java / 확인: cd backend && ./gradlew test (29 passed)
- T-004 개발 완료: POST /api/accounts(110-XXX-XXXXXX SecureRandom 발급+중복 체크, PIN ^\d{4,6}$ BCrypt 해시만 저장, 한도 기본값·양의 정수 검증, ACCOUNT_CREATE 감사로그), GET /api/accounts(본인·비시스템 계좌 생성순), GET /api/accounts/{id}(본인/ADMIN만, 타인 403·없음 404), PATCH /api/accounts/{id}/close(본인만, 비관적 락 후 CLOSED 400/LOCKED 403/잔액≠0 400 검증, ACCOUNT_CLOSE 감사로그), AccountRepository.findByIdForUpdate(PESSIMISTIC_WRITE) 추가 / 변경 파일: backend/src/main/java/com/corebank/{account/**, domain/account/Account.java, domain/account/AccountRepository.java}, backend/src/test/java/com/corebank/account/AccountApiTest.java / 확인: cd backend && ./gradlew test (38 passed)
- T-005 개발 완료: POST /api/transactions/{deposit,withdraw,transfer} — BigDecimal scale 0·양수·19자리 검증(INVALID_AMOUNT), 계좌 id 오름차순 PESSIMISTIC_WRITE 잠금(SYSTEM-VAULT 포함, 잠금 전 엔티티 미적재), 거래당 ledger_entries DEBIT/CREDIT 2줄+balance_after, 1회/1일(당일 완료 출금+이체 합) 한도·잔액·상태·소유권·PIN 검증, Idempotency-Key 필수(잠금 전후 2회 재생 확인, PIN 제외 SHA-256, 실패 시 키 미소비), 성공 logInTransaction/실패 logIndependent(계좌번호 마스킹) / 변경 파일: backend/src/main/java/com/corebank/{transaction/**, domain/account/Account.java, domain/account/AccountRepository.java, domain/transaction/TransactionRepository.java}, backend/src/test/java/com/corebank/transaction/*, src/test/resources/application-test.yml(H2 LOCK_TIMEOUT=10000) / 확인: cd backend && ./gradlew test (58 passed, 동시성 테스트 3회 반복 통과)
- T-006 개발 완료: GET /api/accounts/{id}/transactions(본인/ADMIN, ledger_entries+transactions 조인, type/entryType/fromDate/toDate 필터, createdAt·id DESC 페이징 size≤100, 상대계좌 마스킹), GET /api/audit-logs/me(본인 최신순 페이징), GET /api/admin/audit-logs(ADMIN, actionType/result/actorUserId 필터, 응답 직전 재마스킹), 공통 PageResponse/PageParams, AccountService.findReadable 추출 / 변경 파일: backend/src/main/java/com/corebank/{common/PageResponse.java, common/PageParams.java, audit/AuditLogController.java, audit/AuditLogQueryService.java, audit/dto/AuditLogResponse.java, transaction/TransactionHistory*.java, transaction/dto/TransactionHistoryItem.java, account/AccountService.java, domain/audit/AuditLogRepository.java}, backend/src/test/java/com/corebank/transaction/HistoryAndAuditQueryApiTest.java / 확인: cd backend && ./gradlew test (64 passed)
