# T-007R-FIX 상세 명세: 마일스톤 1 코드 리뷰(`docs/reviews/M1-review.md`) PM 검토 반영 수정

## 1. 배경 및 PM 심각도 판정
Copilot이 제출한 [`docs/reviews/M1-review.md`](../reviews/M1-review.md)의 7건 이슈를 PM이 검토한 결과, 데모 실행 편의성을 해치지 않으면서 금융권 보안·동시성·DB 무결성을 높이기 위해 아래 5가지 수정을 즉시 반영한다. (설치된 TDD/디버깅/검증 스킬을 적극 활용하라.)

## 2. 수정 항목

### ① [RV-M1-02] (HIGH) 멱등성 재생 시 PIN 변경/오류 우회 방지 (`TransactionService`)
- **문제**: 출금/이체 요청 해시(`requestHash`)에서 PIN을 단순 제외하면서, 성공 후 같은 `Idempotency-Key`로 **다른(틀린) PIN**을 보내도 1차 조회에서 캐시된 성공 응답이 재생됨.
- **수정**:
  - 평문 PIN을 해시에 직접 넣지 않으면서도 PIN 일치 여부를 해시에 안전하게 반영하기 위해, 출금/이체의 `requestHash` 생성 시 출금 계좌의 `pinHash`와 입력 `pin`의 일치 여부(`boolean pinMatched = passwordEncoder.matches(request.pin(), fromAccount.getPinHash())`) 또는 `HMAC/파생값`을 반영하거나,
  - 가장 깔끔하게는 **출금/이체에서 동일한 `Idempotency-Key` 재요청이 들어왔을 때도 출금 계좌의 PIN 검증(`passwordEncoder.matches`)이 실패하면 캐시 재생 대신 `409 IDEMPOTENCY_CONFLICT`(또는 `401 INVALID_PIN`)로 차단**하도록 보강한다.

### ② [RV-M1-01 & RV-M1-03] (CRITICAL/HIGH) 운영(`prod`) 프로필에서 기본 JWT 시크릿·기본 관리자 비밀번호 차단
- **문제**: 로컬 데모 편의를 위한 기본 `JWT_SECRET` 및 `Admin1234!`가 운영(`prod`) 환경에서 그대로 쓰일 위험.
- **수정**:
  - 로컬(`default`) 및 `test` 프로필에서는 면접관 원클릭 데모와 기존 테스트를 위해 현재 기본값을 유지한다.
  - 단, `prod` 프로필 활성화 시(`Environment.acceptsProfiles(Profiles.of("prod"))`), `jwt.secret`이 기본값과 같거나 `admin.initial-password`가 기본값(`Admin1234!`)이면 애플리케이션 기동 시 즉시 `IllegalStateException`을 던져 기동을 차단한다 (단위 테스트 추가).

### ③ [RV-M1-04 & RV-M1-06] (MEDIUM) Flyway `V2__hardening_constraints_and_indexes.sql` 추가
- `backend/src/main/resources/db/migration/V2__hardening_constraints_and_indexes.sql`을 새로 추가하여:
  1. 고객 계좌 음수 잔액 방지 DB CHECK 제약 추가:
     `ALTER TABLE accounts ADD CONSTRAINT chk_accounts_non_negative_customer_balance CHECK (is_system = TRUE OR balance >= 0);`
  2. 당일 한도 조회 및 거래내역 페이징 조회용 복합 인덱스 추가:
     - `CREATE INDEX idx_transactions_from_status_type_created ON transactions (from_account_id, status, tx_type, created_at);`
     - `CREATE INDEX idx_ledger_entries_account_created_id ON ledger_entries (account_id, created_at DESC, id DESC);`

### ④ [RV-M1-05 & RV-M1-07] (MEDIUM/LOW) 계좌번호 발급 및 시드 초기화 UNIQUE 충돌 방어
- `AccountService`: 계좌 생성 저장 시 계좌번호 UNIQUE 충돌(`DataIntegrityViolationException`)에 대비한 후보 재시도/예외 방어 보강.
- `SystemSeedInitializer`: 다중 인스턴스 동시 기동 시 UNIQUE 충돌(`DataIntegrityViolationException`)이 발생해도 이미 시드가 생성된 것으로 간주하고 안전하게 통과하도록 방어.

## 3. 완료 조건
- 위 수정 사항에 대한 검증 테스트를 추가하고 `cd backend && ./gradlew test` 전체 통과 확인.
- `docs/TASKS.md`의 `## 작업 기록`에 `- T-007R-FIX 개발 완료: ...` 한 줄 요약 추가 후 `hc send t10 "T-007R-FIX 개발 완료"` 실행.
