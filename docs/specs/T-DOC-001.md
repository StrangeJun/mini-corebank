# 문서 및 QA 설계 작업 명세 (Copilot)

## 1. 목표
프로젝트명이 **`mini-corebank`** 로 확정되었다.
`docs/PRD.md`, `docs/DECISIONS.md`, `docs/specs/T-001.md` ~ `docs/specs/T-006.md`를 바탕으로 아래 두 문서를 작성한다.
> **주의**: 현재 개발자(Claude)가 `backend/` 및 `docs/TASKS.md`를 업데이트 중일 수 있으므로, 이번 작업에서는 **`README.md`와 `docs/TEST_PLAN.md` 두 파일만 생성/수정**하고 `docs/TASKS.md`는 수정하지 않는다.

## 2. 작성할 파일

### A. `README.md` (1금융권 백엔드 포트폴리오용 루트 README 초안)
1. **프로젝트 제목 및 한 줄 소개**: `mini-corebank` — 1금융권(시중은행) 코어뱅킹의 복식부기 원장, 동시성·멱등성 이체, 정합성 대사, 보안·감사 로그를 구현한 원화 계좌·송금 시스템
2. **핵심 아키텍처 & 금융 도메인 설계 포인트**:
   - **복식부기 원장(Double-Entry Ledger) & `SYSTEM-VAULT`**: 모든 입금·출금·이체를 예외 없이 `DEBIT`/`CREDIT` 2줄 분개로 기록
   - **원장-잔액 정합성 대사(Reconciliation) 3대 불변식**:
     1) 거래별 `SUM(CREDIT) == SUM(DEBIT)`
     2) 계좌별 `accounts.balance == SUM(CREDIT) - SUM(DEBIT)`
     3) 전체 원장(고객 계좌 + `SYSTEM-VAULT`) `SUM(balance) == 0` (Zero-Sum)
   - **동시성 & 데드락 방지**: 계좌 `id` 오름차순 `PESSIMISTIC_WRITE`(`SELECT ... FOR UPDATE`) 잠금
   - **멱등성(`Idempotency-Key`)**: 동일 키+동일 요청 해시 시 캐시 응답 반환, 동일 키+상이한 요청 해시 시 `409 IDEMPOTENCY_CONFLICT`
   - **보안·감사 로그**: JWT + BCrypt(비밀번호/PIN 해시), PIN 5회 연속 오류 시 자동 `LOCKED`, `REQUIRES_NEW` 독립 트랜잭션 감사 로그, 계좌번호/이메일/PIN 마스킹
3. **기술 스택 & 디렉터리 구조**: Java 17, Spring Boot 3.x, Spring Data JPA, Spring Security, PostgreSQL 16 (Docker Compose), Flyway, React + TypeScript (Vite)
4. **DB 스키마 요약 (8개 핵심 테이블)**: `users`, `accounts`, `transactions`, `ledger_entries`, `idempotency_records`, `scheduled_transfers`, `reconciliation_runs`, `audit_logs`
5. **실행 및 테스트 방법**: `docker compose up -d`, `cd backend && ./gradlew test`, `cd backend && ./gradlew bootRun`

### B. `docs/TEST_PLAN.md` (금융 도메인 QA 테스트 시나리오 명세)
`docs/roles/qa.md`의 5대 필수 점검 항목을 기준으로 마일스톤 1·2의 테스트 케이스 ID와 검증 조건을 표로 정리한다:
1. **경계값 및 금액 무결성**: 0원, 음수, 소수점 금액 거부(`400 INVALID_AMOUNT`), 잔액 부족(`400 INSUFFICIENT_BALANCE`), 1회 한도 초과(`400 PER_TX_LIMIT_EXCEEDED`), 1일 누적 한도 초과(`400 DAILY_LIMIT_EXCEEDED`), 해지/잠금 계좌 거래 차단
2. **중복 요청(멱등성) 및 동시성**: 동일 `Idempotency-Key` 2회 호출 시 잔액 1회만 변동, 동일 키 다른 금액 호출 시 `409 IDEMPOTENCY_CONFLICT`, 멀티스레드 동시 출금 시 초과 출금 0건, A↔B 교차 동시 이체 시 데드락 0건 및 총잔액 불변
3. **권한 및 접근 제어**: 미인증 `401`, 타인 계좌 상세/거래내역/출금/이체/해지 `403 FORBIDDEN_ACCOUNT_ACCESS`, 일반 사용자(`USER`)의 관리자 API(`/api/admin/**`) 접근 `403`
4. **복식부기 원장 및 3대 대사 불변식**: 입금·출금·이체 후 `ledger_entries` 2줄 생성 확인, `balance_after` 스냅샷 정합성, 대사 실행 시 `PASSED` 및 인위적 불일치 주입 시 `MISMATCH_FOUND` 탐지
5. **민감정보 마스킹 및 감사 로그**: 응답·감사 로그(`audit_logs.masked_summary`)에 평문 `password`, `pin`, 원본 계좌번호 전체가 절대 포함되지 않는지 검증, 실패 거래(PIN 오류, 잔액 부족 등) 롤백 시에도 감사 로그가 유지되는지 검증
