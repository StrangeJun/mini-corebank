# 계좌·PIN 잠금·복식부기 거래 전 파일 해설

이 문서는 계좌 개설부터 출금/이체 원장 기록, 거래내역 조회까지 메서드 흐름을 설명한다.

## 계좌 계층

### `account/AccountNumberGenerator.java`

- **요약/레이어:** 계좌번호 생성 컴포넌트
- **핵심 메서드:** `generate`
- **설계 의도:** `110-XXX-XXXXXX` 형식의 후보를 `SecureRandom`으로 만든다.
- **연결:** `AccountService.issueAccountNumber`
- **면접 포인트:** 예측 가능한 `Random` 대신 암호학적으로 안전한 난수를 쓰는 이유를 설명한다.

### `account/dto/CreateAccountRequest.java`

- **요약/레이어:** 계좌 개설 요청 DTO
- **핵심 검증:** PIN은 숫자 4~6자리, 한도는 양수이고 소수점 없음
- **설계 의도:** 계좌 PIN과 한도가 서비스 진입 전에 검증되게 한다.
- **연결:** `AccountController.create`, `AccountService.create`
- **면접 포인트:** `toString`에서 PIN을 마스킹한 이유는 Request logging을 방어하기 위해서다.

### `account/dto/AccountResponse.java`

- **요약/레이어:** 계좌 응답 DTO
- **핵심 메서드:** `from`
- **설계 의도:** 계좌번호는 마스킹하고 원본 PIN 해시는 절대 포함하지 않는다.
- **연결:** 계좌 조회/개설/해지
- **면접 포인트:** Entity를 직접 반환하지 않아 응답 스키마와 영속 계층을 분리한다.

### `account/dto/AccountUnlockResponse.java`

- **요약/레이어:** 관리자 잠금 해제 응답 DTO
- **핵심 메서드:** `from`
- **설계 의도:** 해제된 상태와 초기화된 실패 횟수만 노출한다.
- **연결:** `AdminAccountController`, `AccountService.unlock`
- **면접 포인트:** 원본 계좌번호 대신 마스킹된 번호를 응답하는 이유를 설명한다.

### `account/AccountController.java`

- **요약/레이어:** 고객 계좌 REST Controller
- **핵심 메서드:** `create`, `listMine`, `get`, `close`
- **핵심 흐름:** 인증 주체와 요청을 받아 `AccountService`에 위임한다.
- **설계 의도:** 계좌 소유권과 상태 검증을 Controller가 아닌 Service에서 수행한다.
- **연결:** `AccountService`
- **면접 포인트:** IDOR 방지를 위해 JWT 사용자 ID와 계좌 소유자를 비교하는 위치는 어디인가?

### `account/AdminAccountController.java`

- **요약/레이어:** 관리자 REST Controller
- **핵심 메서드:** `unlock`
- **핵심 경로:** `POST /api/admin/accounts/{accountId}/unlock`
- **설계 의도:** `/api/admin/**`에 설정된 ADMIN 권한 뒤에 잠금 해제 기능을 둔다.
- **연결:** `AccountService.unlock`
- **면접 포인트:** URL 권한과 서비스 내부 권한을 어디까지 중복 검증해야 하는가?

### `account/AccountService.java`

- **요약/레이어:** 계좌 Service
- **핵심 메서드:**
  - `create`: 한도 기본값 적용, PIN BCrypt 해시, 후보 번호 발급, 감사 로그
  - `listMine`: 본인 비시스템 계좌만 생성 순 조회
  - `get`: 본인 또는 ADMIN 읽기 권한
  - `findReadable`: 계좌 존재 여부와 소유권 검증
  - `close`: 행 잠금 후 잔액 0, ACTIVE 상태를 확인하고 해지
  - `unlock`: 행 잠금 후 CLOSED 제외, ACTIVE 복귀와 실패 횟수 0
- **설계 의도:** 계좌 개설/조회/해지/해제 규칙을 한곳에 모은다.
- **핵심 트랜잭션:** close/unlock은 `@Transactional`, 읽기는 `readOnly=true`
- **연결:** Repository, PIN 검증, 감사 로그
- **면접 포인트:** 해지와 입금이 동시에 들어오면 PESSIMISTIC_WRITE로 어떤 순서를 보장하는가?

### `account/AccountPinVerificationService.java`

- **요약/레이어:** PIN 검증 트랜잭션 서비스
- **핵심 메서드:** `verify`, `audit`
- **핵심 흐름:**
  1. 대상 계좌를 `PESSIMISTIC_WRITE`로 잠근다.
  2. 소유권과 상태를 확인한다.
  3. PIN 일치이면 실패 횟수를 0으로 초기화한다.
  4. 불일치이면 실패 횟수를 1 증가한다.
  5. 5회 도달이면 `LOCKED`로 전환하고 두 감사 이벤트를 남긴다.
- **핵심 어노테이션:** `REQUIRES_NEW`, `noRollbackFor=BusinessException`
- **설계 의도:** PIN 실패 상태와 감사 로그가 본 출금 트랜잭션 롤백과 무관하게 커밋되게 한다.
- **연결:** `TransactionService.withdraw/transfer`, `Account`, `AuditLogService`
- **면접 포인트:** 본 트랜잭션이 계좌 행을 잠근 뒤 REQUIRES_NEW로 같은 행을 잠그면 왜 자기 교착이 되는가?

## 거래 DTO와 Controller

### `transaction/dto/DepositRequest.java`

- **요약/레이어:** 입금 요청 DTO
- **핵심 필드:** 계좌 ID, 금액, 설명
- **설계 의도:** 금액은 서비스에서 scale/양수 검증한다.
- **연결:** `TransactionController.deposit`, `TransactionService.deposit`

### `transaction/dto/WithdrawRequest.java`

- **요약/레이어:** 출금 요청 DTO
- **핵심 필드:** 계좌 ID, 금액, PIN, 설명
- **설계 의도:** PIN을 평문으로 받고 `toString`에서 마스킹한다.
- **연결:** `AccountPinVerificationService`, `TransactionService.withdraw`

### `transaction/dto/TransferRequest.java`

- **요약/레이어:** 이체 요청 DTO
- **핵심 필드:** 출금 계좌, 도착 계좌 ID/번호, 금액, PIN, 설명
- **설계 의도:** 번호와 ID 양쪽 호환을 제공한다.
- **핵심 어노테이션:** `@JsonAlias("toAccountAccountNumber")`
- **연결:** `resolveTransferTarget`, `TransactionService.transfer`

### `transaction/dto/TransactionResponse.java`

- **요약/레이어:** 거래 응답 DTO
- **핵심 필드:** 거래 ID/종류/금액/계좌 정보/거래 후 잔액
- **설계 의도:** 멱등 응답을 JSON으로 재생할 수 있는 고정 스키마를 제공한다.
- **연결:** `TransactionService`, 멱등 응답 본문

### `transaction/dto/TransactionHistoryItem.java`

- **요약/레이어:** 거래내역 항목 DTO
- **핵심 필드:** 거래 ID, 거래/분개 유형, 금액, 잔액 스냅샷, 상대계좌 마스킹
- **설계 의도:** 원장 분개와 상대 거래를 사용자 관점으로 변환한다.
- **연결:** `TransactionHistoryService`

### `transaction/TransactionController.java`

- **요약/레이어:** 입출금/이체 REST Controller
- **핵심 메서드:** `deposit`, `withdraw`, `transfer`
- **핵심 흐름:** 인증 주체, 멱등키 헤더, 요청 DTO, remote IP를 전달한다.
- **설계 의도:** HTTP 바인딩과 금융 비즈니스 규칙을 분리한다.
- **연결:** `TransactionService`
- **면접 포인트:** 멱등키 누락을 헤더에서 언제 검증하는 것이 좋은가?

## 핵심 거래 서비스

### `transaction/TransactionService.java`

- **요약/레이어:** 금융 핵심 Service
- **핵심 필드:** 계좌/거래/분개/멱등/감사/PIN 검증/트랜잭션 템플릿

**deposit**

1. 금액을 검증한다.
2. 멱등 요청 해시를 만든다.
3. 고객과 SYSTEM-VAULT를 잠근다.
4. 소유권/상태를 검증한다.
5. 거래 헤더와 분개 2건을 기록한다.
6. 성공 감사와 멱등 응답을 같은 트랜잭션에 커밋한다.

**withdraw**

1. 금액과 멱등키를 검증한다.
2. `AccountPinVerificationService`를 본 거래 트랜잭션 밖에서 먼저 실행한다.
3. 별도 트랜잭션 템플릿에서 계좌와 시재를 잠근다.
4. 멱등성을 재확인하고 잔액/한도를 검증한다.
5. 고객 DEBIT, 시재 CREDIT 분개를 기록한다.

**transfer**

1. 도착 계좌를 번호 또는 ID로 해석한다.
2. 같은 계좌 이체를 차단한다.
3. PIN을 독립 트랜잭션으로 검증한다.
4. 출발/도착 계좌를 ID 오름차순으로 잠근다.
5. 소유권/상태/PIN/한도/잔액을 검증한다.
6. 출발 계좌 DEBIT와 도착 계좌 CREDIT 분개를 기록한다.

**핵심 보조 메서드**

- `validAmount`: null, 음수, 소수점, 최대 자릿수를 차단한다.
- `lockInOrder`: 계좌 ID를 정렬하고 `PESSIMISTIC_WRITE`로 잠근다.
- `requireWithinLimitsAndBalance`: 1회 한도, 당일 누적 한도, 잔액 순서로 검증한다.
- `idempotent`: 잠금 전후 두 번 replay하고 성공 응답을 저장한다.
- `audited`: 실패 시 독립 감사 로그를 남긴다.
- `inTransaction`: PIN 검증 이후 거래 본체만 별도 트랜잭션으로 연다.

- **연결:** 모든 거래 도메인과 공통 감사/멱등 계층
- **면접 포인트:** 왜 PIN 검증과 행 잠금 트랜잭션을 분리해야 하는가?

## 거래내역 계층

### `transaction/TransactionHistoryController.java`

- **요약/레이어:** 거래내역 REST Controller
- **핵심 메서드:** `find`
- **설계 의도:** 쿼리 파라미터를 `Filter`와 페이징 값으로 변환한다.
- **연결:** `TransactionHistoryService`
- **면접 포인트:** 날짜 범위와 페이지 파라미터 검증은 어디서 수행하는가?

### `transaction/TransactionHistoryService.java`

- **요약/레이어:** 거래내역 조회 Service
- **핵심 메서드:** `find`, `loadCounterparties`, `counterpartyId`
- **핵심 흐름:** 소유권 확인 → 동적 JPQL 생성 → count → 페이지 조회 → 상대 계좌 일괄 조회
- **설계 의도:** 분개와 거래 헤더를 조인하고 상대계좌를 마스킹한다.
- **연결:** `LedgerEntry`, `Transaction`, `AccountRepository`
- **면접 포인트:** count 쿼리를 별도로 실행하고 상대 계좌를 batch 조회하는 이유를 설명한다.
