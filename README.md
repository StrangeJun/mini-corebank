# mini-corebank

원화 계좌·송금 코어뱅킹 시스템으로, 복식부기 원장과 동시성·멱등성 이체, 정합성 대사, 보안·감사 로그를 검증 가능한 테스트와 간단한 웹 데모로 구현합니다.

## 핵심 기능

- 회원가입/JWT 로그인과 사용자 역할(`USER`, `ADMIN`)
- 원화 계좌 개설, 조회, 해지 및 계좌 상태 관리
- 입금, 출금, 즉시 이체와 예약 이체
- 복식부기 원장 기반 거래 기록 및 원장-잔액 대사
- 이체 한도, PIN 검증 및 5회 실패 잠금
- 멱등성 요청 처리와 감사 로그 마스킹

## 금융 도메인 설계

### 복식부기 원장과 시스템 시재

모든 입금·출금·이체는 `DEBIT`/`CREDIT` 분개 2건을 생성합니다. 외부 입출금의 상대 계정은 `SYSTEM-VAULT`이며, 분개 금액과 `balance_after` 스냅샷을 원장에 기록합니다.

### 대사 불변식

1. 거래별 `SUM(CREDIT) == SUM(DEBIT)`
2. 계좌별 `accounts.balance == SUM(CREDIT) - SUM(DEBIT)`
3. 고객 계좌와 `SYSTEM-VAULT`를 포함한 전체 잔액 합계는 `0`

대사 실행 결과는 `PASSED` 또는 불일치를 나타내는 `MISMATCH_FOUND`로 기록합니다.

### 동시성·멱등성

- 잔액을 변경하는 거래는 대상 계좌의 `id` 오름차순으로 `PESSIMISTIC_WRITE` 락을 획득하여 교차 이체 데드락과 초과 출금을 방지합니다.
- 금액은 `BigDecimal` 또는 원 단위 정수로 처리하며 소수점 금액은 거부합니다.
- `Idempotency-Key`와 요청 해시가 일치하면 이전 응답을 재사용하고, 다르면 `409 IDEMPOTENCY_CONFLICT`를 반환합니다.

### 보안과 감사

Spring Security와 Stateless JWT로 인증 및 역할 기반 인가를 수행합니다. 로그인 비밀번호와 계좌 PIN은 BCrypt로 저장하며, 비밀번호/PIN/전체 계좌번호는 응답과 감사 로그에 노출하지 않습니다. 실패 거래가 롤백되어도 비즈니스 감사 로그는 독립 트랜잭션으로 유지됩니다.

## 기술 스택

- Backend: Java 17, Spring Boot 3.x, Spring Data JPA, Spring Security
- Database: PostgreSQL 16, Flyway
- Frontend: React, TypeScript, Vite
- Test: Gradle Test, Spring Boot Test, H2
- Infra: Docker Compose

## 디렉터리 구조

```text
mini-corebank/
├── backend/         # Spring Boot REST API
├── frontend/        # React + TypeScript 데모
├── tests/           # QA 자동화 테스트
├── docs/            # 요구사항, 설계 결정, 작업 명세
└── docker-compose.yml
```

핵심 테이블은 `users`, `accounts`, `transactions`, `ledger_entries`, `idempotency_records`, `scheduled_transfers`, `reconciliation_runs`, `audit_logs`입니다.

## 실행

```bash
docker compose up -d
cd backend
./gradlew bootRun
```

Frontend는 별도 개발 서버로 실행할 수 있습니다.

```bash
cd frontend
npm install
npm run dev
```

## 테스트

```bash
cd backend
./gradlew test
```

금융 도메인 필수 시나리오는 [`docs/TEST_PLAN.md`](docs/TEST_PLAN.md)를 따릅니다.
