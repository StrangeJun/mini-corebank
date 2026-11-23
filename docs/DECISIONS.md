# 결정 기록

중요한 결정은 PM이 여기에 카드를 만들고 오너 승인을 받은 뒤에만 진행한다.

---

## D-001 백엔드 기술 스택 및 DB  (상태: 승인 완료)
- **배경**: 1금융권(하나·국민 등) 백엔드 포트폴리오에 가장 적합하면서, 현재 로컬 환경(OpenJDK 17, Gradle, Docker 설치됨)에서 바로 개발·검증 가능한 스택을 확정해야 합니다.
- **선택지 A**: **Java 17 + Spring Boot 3.x (Gradle) + Spring Data JPA + PostgreSQL (Docker Compose) + Flyway**
  - 장점: 국내 1금융권 백엔드 채용의 사실상 표준 스택. `@Transactional`, 비관적 락(`PESSIMISTIC_WRITE`), Flyway 스키마 버전 관리 등 은행 실무 면접 포인트와 직결됨. 로컬 OpenJDK 17·Gradle·Docker로 즉시 시작 가능.
  - 단점: Kotlin 대비 보일러플레이트 코드가 다소 많음 (Lombok/Java Record로 완화 가능).
- **선택지 B**: **Kotlin + Spring Boot 3.x (Gradle) + Spring Data JPA + PostgreSQL + Flyway**
  - 장점: 문법이 간결하고 null 안정성이 높음. 인터넷은행/핀테크 선호도 높음.
  - 단점: 전통 1금융권(하나·국민 등) 현업·과제·면접에서는 Java 비중이 여전히 압도적임.
- **PM 추천**: **선택지 A (Java 17 + Spring Boot 3.x + Gradle + JPA + PostgreSQL + Flyway)**
- **오너 결정**: **선택지 A 승인** (2026-11-24)

---

## D-002 데모용 웹 프론트엔드 방식  (상태: 승인 완료)
- **배경**: PRD에 따라 백엔드 기능을 시연할 수 있는 간단한 웹 화면이 필요합니다.
- **선택지 A**: **Spring Boot 내장 정적 웹(HTML/CSS/Vanilla JS) — 백엔드 REST API 직접 호출**
  - 장점: 별도 프론트 서버 없이 단일 실행 가능.
  - 단점: 화면 구성이 단순함.
- **선택지 B**: **React + TypeScript (Vite) 별도 프로젝트 (`frontend/`)**
  - 장점: 컴포넌트 구조가 깔끔하고 실제 인터넷뱅킹 대시보드 같은 시각적 완성도를 보여주기 좋음.
  - 단점: 별도 프론트 빌드 및 CORS 설정 필요.
- **PM 추천**: 선택지 A
- **오너 결정**: **선택지 B 승인 (React + TypeScript)** (2026-11-24)

---

## D-003 인증·보안, 민감정보 처리 및 금액 표현  (상태: 승인 완료)
- **배경**: 금융 도메인에서 인증/인가, 계좌 PIN 보호, 민감정보 로깅 차단, 금액 정밀도는 핵심 품질 기준이므로 설계 착수 전에 확정해야 합니다.
- **선택지 A**: **Spring Security + Stateless JWT(Access Token) + BCrypt(로그인 비밀번호 & 계좌 PIN 해시) + 역할(`USER`/`ADMIN`) + 금액 `BigDecimal`(`NUMERIC(19,0)`, scale 0) + 민감정보 로그 마스킹**
  - 장점: REST API 표준 인증 구조이며, 소유권 검증(`본인 계좌만 출금`), PIN 해시/5회 오류 잠금, `BigDecimal` 기반 무오차 원화 연산, 마스킹 필터/유틸리티까지 1금융권 면접 단골 주제를 모두 커버.
  - 단점: 세션 방식 대비 토큰 만료 처리 고려 필요.
- **선택지 B**: **Spring Security + 서버 세션(Cookie) + BCrypt + 금액 `long`(원 단위 정수, `BIGINT`) + 로그 마스킹**
- **PM 추천**: **선택지 A**
- **오너 결정**: **선택지 A 승인** (2026-11-24)

---

## D-004 데이터 모델·DB 스키마 및 디렉터리 구조  (상태: 승인 완료)
- **배경**: 복식부기 원장 정합성, 동시성/멱등성, PIN 잠금, 예약 이체, 대사(Reconciliation), 감사 로그를 흔들림 없이 구현하기 위해 핵심 테이블 구조와 레포지토리 레이아웃을 먼저 확정해야 합니다.
- **디렉터리 구조**:
  - `backend/`: Spring Boot 3.x (Java 17, Gradle) 프로젝트
  - `frontend/`: React + TypeScript (Vite) 프로젝트
  - `docker-compose.yml`: PostgreSQL 컨테이너 구성
- **선택지 A (복식부기 원장 + 시스템 시재 계정 + 8개 핵심 테이블 구조)**:
  1. `users`: 회원 (`id`, `email`, `password_hash`, `name`, `role[USER|ADMIN]`, `created_at`)
  2. `accounts`: 계좌 (`id`, `account_number` UNIQUE, `user_id`, `is_system`, `balance NUMERIC(19,0)`, `status[ACTIVE|LOCKED|CLOSED]`, `pin_hash`, `pin_fail_count`, `per_tx_limit`, `daily_limit`, `version`, `created_at`)
     - 외부 입·출금의 상대 계정으로 시스템 시재 계좌(`SYSTEM-VAULT`, `is_system=true`)를 두어 모든 입금·출금·이체가 예외 없이 2줄 분개(차변/대변)를 이루도록 설계
  3. `transactions`: 거래 원장 헤더 (`id`, `tx_type[DEPOSIT|WITHDRAWAL|TRANSFER]`, `status[COMPLETED|FAILED]`, `amount NUMERIC(19,0)`, `from_account_id`, `to_account_id`, `idempotency_key` UNIQUE, `description`, `created_at`)
  4. `ledger_entries`: 복식부기 원장 분개 — Append-Only 불변 (`id`, `transaction_id`, `account_id`, `entry_type[DEBIT|CREDIT]`, `amount NUMERIC(19,0)`, `balance_after NUMERIC(19,0)`, `created_at`)
     - **대사(Reconciliation) 3대 불변식**:
       ① 모든 거래별 `SUM(CREDIT) == SUM(DEBIT)`
       ② 모든 계좌별 `accounts.balance == SUM(CREDIT) - SUM(DEBIT)`
       ③ 전체 계좌(고객 + `SYSTEM-VAULT`) `SUM(balance) == 0`
  5. `idempotency_records`: 멱등성 키 처리 기록 (`idempotency_key` UNIQUE, `user_id`, `endpoint`, `request_hash`, `response_status`, `response_body`, `created_at`)
  6. `scheduled_transfers`: 예약 이체 (`id`, `user_id`, `from_account_id`, `to_account_id`, `amount`, `scheduled_at`, `status[PENDING|PROCESSING|COMPLETED|FAILED|CANCELED]`, `failure_reason`, `executed_transaction_id`, `idempotency_key` UNIQUE)
  7. `reconciliation_runs`: 원장-잔액 대사 실행 이력 (`id`, `trigger_type`, `status[PASSED|MISMATCH_FOUND]`, `checked_accounts`, `checked_transactions`, `mismatch_count`, `details_json`, `executed_by`, `executed_at`)
  8. `audit_logs`: 보안·거래 감사 로그 — Append-Only 불변 (`id`, `actor_user_id`, `action_type`, `target_type`, `target_id`, `result`, `masked_summary`, `client_ip`, `created_at`)
- **선택지 B (단일 거래내역 테이블 방식 — 분개 테이블 생략)**:
  - `ledger_entries` 없이 `transactions` 테이블 하나에만 입출금 내역을 기록하고 `accounts.balance`만 증감.
  - 장점: 테이블 수가 적고 쿼리가 단순함.
  - 단점: 복식부기 대차 검증과 진짜 의미의 원장-잔액 정합성 대사(Reconciliation)를 보여줄 수 없어 1금융권 코어뱅킹 포트폴리오의 차별점이 사라짐.
- **PM 추천**: **선택지 A (8개 핵심 테이블 + 시스템 시재 계정을 포함한 복식부기 분개 구조)** — PRD의 핵심 역량(복식부기 원장·3대 불변식 대사·멱등성·예약이체·감사로그)을 가장 깔끔하고 정석적으로 증명할 수 있습니다.
- **오너 결정**: **선택지 A 승인** (2026-11-24)

---

## D-005 Git 브랜치 및 이력 관리 전략  (상태: 승인 완료)
- **배경**: 포트폴리오 리뷰어가 커밋 로그와 브랜치 병합 그래프만 봐도 기능 단위 개발 흐름을 한눈에 파악할 수 있어야 합니다.
- **선택지 A (GitHub Flow / Trunk-Based)**: `main` 단일 기준 브랜치 + 작업별 `feat/T-xxx-*` 브랜치(`--no-ff` 병합) + 마일스톤 태그(`v0.1.0`, `v0.2.0`, `v1.0.0`)
- **선택지 B (경량 Gitflow)**: `main` + `develop` + `feat/T-xxx-*`
- **PM 추천**: **선택지 A (GitHub Flow)**
- **오너 결정**: **선택지 A 승인** (2026-11-24)
