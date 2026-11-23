# CLAUDE.md — mini-corebank

이 저장소에서 작업하는 Claude Code(메인 개발자)는 항상 아래 문서와 원칙을 따른다.

## 1. 역할 및 작업 문서 (필독)
- **역할 규칙**: [`docs/roles/developer.md`](docs/roles/developer.md)
- **요구사항(PRD)**: [`docs/PRD.md`](docs/PRD.md)
- **아키텍처·스택 결정(SSOT)**: [`docs/DECISIONS.md`](docs/DECISIONS.md) (D-001 ~ D-004 승인 완료)
- **현재 작업 상태**: [`docs/TASKS.md`](docs/TASKS.md) 및 `docs/specs/T-xxx.md`

## 2. 기술 스택 & 디렉터리 구조
- `backend/`: Java 17, Spring Boot 3.3.x, Gradle, Spring Data JPA, Spring Security(JWT), Flyway, PostgreSQL 16 (테스트 프로필: H2 PostgreSQL 호환 모드)
- `frontend/`: React + TypeScript (Vite)
- `tests/backend/`: QA(Copilot) 전용 테스트 소스셋 (`backend/build.gradle`에 연동됨)
- `docker-compose.yml`: PostgreSQL 16 (`localhost:5432/corebank`)

## 3. 금융 도메인 필수 코딩 규칙
1. **금액**: `float`/`double` 절대 금지. 반드시 `BigDecimal` (DB `NUMERIC(19, 0)`, scale 0, 양의 정수 원 단위)만 사용한다.
2. **복식부기 원장**: 모든 입금·출금·이체는 `transactions` 1건당 반드시 `ledger_entries` 2줄(`DEBIT` 1건, `CREDIT` 1건)을 남긴다. 외부 입출금의 상대 계정은 시스템 시재 계좌(`SYSTEM-VAULT`, `is_system = true`)다.
3. **동시성 & 데드락 방지**: 잔액 변경 시 대상 계좌들을 반드시 **계좌 `id` 오름차순으로 정렬하여 `PESSIMISTIC_WRITE` (`SELECT ... FOR UPDATE`)** 락을 획득한다.
4. **멱등성**: 금전 변경 요청은 `Idempotency-Key`와 요청 해시(SHA-256)를 검증해 중복 실행을 차단한다.
5. **보안 & 감사 로그**:
   - 비밀번호와 계좌 PIN은 BCrypt 해시로만 저장한다.
   - 성공 거래는 `AuditLogService.logInTransaction`, 실패/예외 이벤트(로그인 실패, PIN 오류, 잔액 부족 등)는 롤백과 무관하게 남도록 `AuditLogService.logIndependent`(`REQUIRES_NEW`)를 사용한다.
   - 로그·감사로그·응답에 평문 비밀번호, PIN, 원본 계좌번호 전체를 남기지 않고 `MaskingUtil`로 마스킹한다.

## 4. 빌드·검증 명령 및 완료 보고
- 백엔드 테스트: `cd backend && ./gradlew test`
- 작업 완료 시 [`docs/TASKS.md`](docs/TASKS.md)의 해당 작업 상태를 `개발 완료`로 변경하고 `## 작업 기록`에 한 줄 요약을 남긴 뒤, `hc send t10 "T-xxx 개발 완료"`를 실행해 PM(`t10`)에게 완료 메시지를 보낸다.
