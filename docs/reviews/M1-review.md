# 마일스톤 1 코드 리뷰

검토 범위: `backend/src/main/java/com/corebank/**`, `backend/src/main/resources/db/migration/V1__init_schema.sql`

## 전체 요약

- CRITICAL: 1
- HIGH: 2
- MEDIUM: 3
- LOW: 1

전반적으로 복식부기 분개, 계좌 ID 정렬 잠금, 멱등성 2단계 재조회, 감사 로그 독립 트랜잭션은 마일스톤 목표와 일치한다. 다만 공개된 기본 JWT 시크릿은 운영 배포 시 즉시 치명적인 인증 우회가 될 수 있고, 멱등성 재응답 흐름은 PIN 검증을 우회할 가능성이 있다.

## 발견 이슈

### [RV-M1-01] 고정 기본 JWT 시크릿으로 관리자 권한 토큰 위조 가능

- **심각도:** CRITICAL
- **위치:** `backend/src/main/resources/application.yml:15-25`, `backend/src/main/java/com/corebank/auth/jwt/JwtTokenProvider.java:33-49`
- **위험 시나리오:** 운영 환경에서 `JWT_SECRET`을 설정하지 않으면 소스에 공개된 고정 키로 토큰을 만들 수 있다. 공격자는 `subject`, `email`, `role=ADMIN` 클레임을 가진 토큰을 직접 생성해 `/api/admin/**`에 접근할 수 있다.
- **권장 수정:** 고정 기본값을 제거하고 시작 시키 없는 JWT secret은 애플리케이션 기동을 실패시키도록 한다. 최소 256-bit 랜덤 시크릿을 환경 변수 또는 시크릿 매니저에서 주입하고, 배포 파이프라인에서 누락 여부를 검증한다.

### [RV-M1-02] 멱등성 재응답이 잘못된 PIN 검증을 우회할 수 있음

- **심각도:** HIGH
- **위치:** `backend/src/main/java/com/corebank/transaction/TransactionService.java:113-122, 269-297`
- **위험 시나리오:** 출금 또는 이체가 한 번 성공해 멱등성 기록이 저장되면, 이후 같은 키와 같은 계좌/금액/설명으로 다른 잘못된 PIN을 보내도 요청 해시에 PIN이 포함되지 않아 두 재조회에서 캐시 응답을 반환한다. 실제로는 거래 검증 단계에 도달하지 않는다.
- **권장 수정:** 성공 응답 재생과 인가 검증을 분리한다. 최소한 보안 파라미터 검증용 해시 또는 별도 비밀 파라미터 파생값을 멱등성 비교에 포함한다. 단순 평문 PIN을 해시에 넣지 말고 사용자의 계좌별 보호된 컨텍스트와 안전한 비교가 되도록 설계한다.

### [RV-M1-03] 고정 기본 관리자 비밀번호가 운영 계정 탈취로 이어질 수 있음

- **심각도:** HIGH
- **위치:** `backend/src/main/resources/application.yml:22-25`, `backend/src/main/java/com/corebank/seed/SystemSeedInitializer.java:55-57`
- **위험 시나리오:** `ADMIN_INITIAL_PASSWORD`가 설정되지 않은 채 배포되면 전 세계에 공개된 `Admin1234!`로 기본 관리자를 사용하게 된다.
- **권장 수정:** 프로덕션 프로필에서 기본 관리자 비밀번호를 금지한다. 최초 기동 시 강력한 임의 비밀번호를 생성해 안전한 채널로 전달하고, 사용자에게 최초 로그인 비밀번호 변경을 강제한다.

### [RV-M1-04] 당일 한도 및 거래내역 조회에 필요한 복합 인덱스 부재

- **심각도:** MEDIUM
- **위치:** `backend/src/main/resources/db/migration/V1__init_schema.sql:30-50`, `backend/src/main/java/com/corebank/domain/transaction/TransactionRepository.java:16-29`, `backend/src/main/java/com/corebank/transaction/TransactionHistoryService.java:54-83`
- **위험 시나리오:** 거래량이 커지면 당일 출금 합계와 계좌별 분개 조회가 거래 테이블 전체 스캔을 유발한다. 지연 시간 증가가 한도 검증의 응답 시간과 조회 정확도에 영향을 줄 수 있다.
- **권장 수정:** `(from_account_id, status, tx_type, created_at)` 복합 인덱스를, `(account_id, created_at, id)`, `(transaction_id, account_id)` 또는 조회 패턴에 맞는 인덱스를 추가한다. Flyway 후속 마이그레이션으로 적용하고 실행 계획을 검증한다.

### [RV-M1-05] 계좌번호 발급의 생성-검사 경쟁과 예외 매핑

- **심각도:** MEDIUM
- **위치:** `backend/src/main/java/com/corebank/account/AccountService.java:107-114`, `backend/src/main/java/com/corebank/account/AccountNumberGenerator.java:14-16`
- **위험 시나리오:** 두 요청이 같은 미사용 후보를 생성해 존재 여부를 동시에 확인한 뒤 insert하면 하나는 UNIQUE 제약으로 실패한다. 현재 경로는 이 충돌을 재시도하지 않고 예외를 500 계열로 보낼 수 있다.
- **권장 수정:** account insert의 UNIQUE 충돌을 포착해 최대 10회 후보 재발급 후 재시도한다. 더 확실한 방법은 번호를 DB 시퀀스 기반으로 생성하고 중복 가능성을 구조적으로 제거한다.

### [RV-M1-06] 원장·감사 로그의 불변성과 계좌 잔액 제약이 애플리케이션에만 의존

- **심각도:** MEDIUM
- **위치:** `backend/src/main/resources/db/migration/V1__init_schema.sql:14-28,42-50,90-100`
- **위험 시나리오:** JPA의 `updatable=false` 또는 엔티티 메서드만으로는 외부 배치/DB 콘솔에서 분개 수정 또는 고객 계좌 음수 잔액을 막지 못한다. 애플리케이션 우회 시 Zero-Sum과 append-only 보장을 깨뜨릴 수 있다.
- **권장 수정:** Ledger/Audit 테이블은 UPDATE/DELETE를 제한하는 DB 권한 또는 트리거를 적용하고, 고객 계좌의 음수 잔액은 상태에 따라 제한하는 CHECK 제약을 설계한다. 시스템 시재는 음수를 허용하므로 전체 계좌 일괄 제약은 사용하지 않는다.

### [RV-M1-07] 다중 인스턴스 기동 시 시드 레이스

- **심각도:** LOW
- **위치:** `backend/src/main/java/com/corebank/seed/SystemSeedInitializer.java:46-57`
- **위험 시나리오:** 여러 애플리케이션 인스턴스가 동시에 기동하면 각각 시드 부재를 보고 insert를 시도할 수 있다. 계좌번호/이메일 UNIQUE 위반이 일부 인스턴스의 기동 실패로 이어질 수 있다.
- **권장 수정:** `saveAndFlush`와 UNIQUE 충돌 처리, 또는 분산 잠금/단일 초기화 잡을 적용해 시드를 멱등한 경쟁 처리가 되도록 한다.

## 잘 구현된 설계 포인트

1. 입금/출금/이체가 모두 거래 헤더와 DEBIT/CREDIT 분개 2건을 같은 트랜잭션에 기록한다.
2. 계좌 잠금을 `id` 오름차순 `PESSIMISTIC_WRITE`로 통일해 교차 이체 데드락을 설계적으로 줄였다.
3. 멱등성은 잠금 전 1차 재조회와 잠금 후 2차 재조회, 저장 시 UNIQUE 충돌 처리를 모두 사용한다.
4. 실패 감사 로그는 `REQUIRES_NEW`로 본 거래 롤백과 분리하고 응답/로그 이중 마스킹을 적용한다.
5. 거래내역은 필터 조건을 동적으로 구성하고 상대 계좌를 `findAllById`로 일괄 조회해 불필요한 N+1을 줄였다.
