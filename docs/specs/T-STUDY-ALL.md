# T-STUDY-ALL 상세 명세: 전 코드 파일 1:1 완전 해설 학습 자료 작성

## 1. 목표
오너가 프로젝트의 **모든 코드 파일 하나하나를 빠짐없이 보면서 공부**할 수 있도록, `backend/src/main/**` 하위 모든 파일에 대해 파일별·메서드별·핵심 라인별 상세 해설서를 `docs/study/` 폴더에 작성한다.

> **주의**: 한 파일에 몰아 쓰면 내용이 축약되므로 아래 **4개 문서로 나누어 모든 파일을 하나도 빠짐없이 깊이 있게** 설명한다. (`docs/study/**`만 수정하고 `backend/`나 `docs/TASKS.md`는 건드리지 않는다.)

## 2. 작성할 문서 구성 (`docs/study/`)

### ① `docs/study/README.md` — 전체 코드베이스 파일 지도 (Index)
- 전체 디렉터리 트리와 **모든 파일(70여 개)의 클릭 가능한 상대 경로 링크 + 역할 한 줄 요약 + 수록된 해설 문서 링크**를 한눈에 보는 표로 정리
- 초보/면접 대비용 추천 학습 동선 안내

### ② `docs/study/01-schema-and-domain-files.md` — DB 스키마 & 도메인 계층 전 파일 해설
대상 파일 전체를 하나씩 섹션으로 나누어 설명:
- `application.yml`, `V1__init_schema.sql`, `V2__hardening_constraints_and_indexes.sql`, `CorebankApplication.java`
- `domain/user/**` (`User.java`, `UserRole.java`, `UserRepository.java`)
- `domain/account/**` (`Account.java`, `AccountStatus.java`, `AccountRepository.java` — 특히 `@Lock(PESSIMISTIC_WRITE)` 쿼리들과 `debit`/`credit` 도메인 메서드 해설)
- `domain/transaction/**` (`Transaction.java`, `TransactionType.java`, `TransactionStatus.java`, `TransactionRepository.java` — 당일 누적 출금·이체 합산 JPQL 쿼리 해설)
- `domain/ledger/**` (`LedgerEntry.java`, `EntryType.java`, `LedgerEntryRepository.java` — 왜 `updatable = false` 불변 엔티티인지 해설)
- `domain/idempotency/**`, `domain/audit/**`, `domain/reconciliation/**`, `domain/scheduled/**` 전 파일

### ③ `docs/study/02-common-auth-audit-files.md` — 공통 기반·인증/JWT·감사로그·시드 전 파일 해설
대상 파일 전체를 하나씩 섹션으로 나누어 설명:
- `common/exception/**` (`ErrorCode.java`, `BusinessException.java`, `ApiErrorResponse.java`, `GlobalExceptionHandler.java`)
- `common/util/MaskingUtil.java` (정규식 패턴과 계좌번호·이메일·민감 키워드 마스킹 원리)
- `common/idempotency/**` (`IdempotencyService.java`, `IdempotentResponse.java` — SHA-256 해싱, 키 충돌 처리, 동시 INSERT 방어)
- `common/PageParams.java`, `common/PageResponse.java`
- `config/SecurityConfig.java`, `config/ProductionSecretsGuard.java`
- `auth/**` (`JwtProperties`, `JwtTokenProvider`, `JwtAuthenticationFilter`, `AuthUser`, `AuthService`, `AuthController`, `dto/*`)
- `audit/**` (`AuditEvent`, `AuditLogService` — `REQUIRED` vs `REQUIRES_NEW`, `AuditLogQueryService`, `AuditLogController`, `dto/*`)
- `seed/SystemSeedInitializer.java`, `user/UserResponse.java`

### ④ `docs/study/03-account-and-transaction-files.md` — 계좌·PIN 잠금·복식부기 입출금/이체·거래내역 전 파일 해설
대상 파일 전체를 하나씩 섹션으로 나누어 설명:
- `account/**` (`AccountNumberGenerator.java`, `AccountService.java`, `AccountPinVerificationService.java`, `AccountController.java`, `AdminAccountController.java`, `dto/*`)
- `transaction/**` (`TransactionService.java` — 입금/출금/이체의 단계별 코드 흐름과 비관적 락 정렬·멱등성·한도·복식부기 분개 상세 해설, `TransactionController.java`, `TransactionHistoryService.java`, `TransactionHistoryController.java`, `dto/*`)

## 3. 각 파일 설명 시 필수 포함 요소
모든 파일마다 아래 항목을 일관되게 작성해 한눈에 읽히도록 한다:
1. **파일 한 줄 요약 & 소속 레이어** (`Controller` / `Service` / `Repository` / `Entity` / `DTO` / `Config`)
2. **왜 이 파일이 필요한가? (설계 의도 & 금융 도메인 배경)**
3. **핵심 코드 & 메서드별 동작 해설** (중요 어노테이션 `@Transactional(propagation = ...)`, `@Lock(...)`, 핵심 로직 흐름을 다이어그램/표/코드 스니펫으로 쉽게 풀어서 설명)
4. **연결된 파일 (호출하는 곳 ↔ 호출받는 곳)**
5. **면접 포인트** (이 파일에서 면접관이 물어볼 수 있는 핵심 질문 1개와 답변 요약)
