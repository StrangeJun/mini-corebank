# 프론트엔드 전 파일 상세 해설

프론트엔드는 React 18 + TypeScript + Vite로 구현되며, 백엔드 REST API를 인증 헤더와 멱등키 헤더로 호출한다.

## 설정 파일

### `vite.config.ts`

- **레이어:** Vite 설정
- **핵심 메서드:** `defineConfig(({ mode }) => ...)`
- **설계 의도:** 개발 서버를 5173 포트로 고정하고 `/api`를 백엔드 8080으로 프록시한다.
- **환경 변수:** `VITE_PROXY_TARGET`, `VITE_API_BASE_URL`
- **연결:** `api.ts`
- **면접 포인트:** 프록시와 CORS 직접 호출의 차이를 설명한다.

### `package.json`

- **레이어:** npm 프로젝트 설정
- **핵심 스크립트:** `dev`, `build`, `preview`
- **설계 의도:** TypeScript 빌드 후 Vite 번들을 생성한다.
- **핵심 의존성:** React, React DOM, Vite, TypeScript
- **면접 포인트:** `tsc -b && vite build`가 하는 검사를 설명한다.

### `.env.example`

- **레이어:** 개발 환경 예시
- **설계 의도:** 프록시 대상과 API 직접 호출 주소를 분리한다.
- **주의:** 실제 시크릿을 저장하지 않는다.

## 공통 API와 타입

### `src/api.ts`

- **레이어:** API 클라이언트
- **핵심 상수:** `BASE`, `TOKEN_KEY`
- **핵심 메서드:** `request`, `newIdempotencyKey`, `getToken/setToken`, `setUnauthorizedHandler`

금액 요청은 숫자 타입이 아닌 문자열로 직렬화한다.

```ts
// 요청 예시
{ amount: "50000" }
```

백엔드 `BigDecimal`이 문자열을 그대로 파싱해 `19자리 NUMERIC` 정수로 처리한다. `normalizeAmount`가 쉼표와 공백을 제거한 뒤 1~19자리 숫자만 허용한다.

멱등키는 `crypto.randomUUID()` 또는 `getRandomValues()`로 UUID v4를 만든다. 금전 요청마다 멱등키를 헤더로 보내고 성공 후에만 새 키로 갱신한다.

금액 응답은 `preserveMoneyLiterals(rawJson)`를 통해 `JSON.parse()`를 호출하기 전에 금액 필드(`balance`, `amount`, `perTxLimit`, `dailyLimit`, `balanceAfter`, `expected`, `actual`, `difference` 등)의 정수 리터럴을 문자열로 보존한다. 이를 통해 JavaScript `number`(IEEE-754 double, $2^{53}-1$ 한계)로 인한 16~19자리 원화 금액 반올림 오차를 원천 차단한다.

- **연결:** 모든 화면 컴포넌트와 백엔드 Controller
- **면접 포인트:** JSON 파싱 전 문자열 보존(`preserveMoneyLiterals`)과 화면 합산·포맷팅(`BigInt`)으로 프론트엔드 부동소수점 오차를 제거한 원리를 설명한다.

### `src/types.ts`

- **레이어:** 프론트 데이터 계약
- **핵심 타입:** `Money = string`, `Account`, `TransactionResponse`, `ScheduledTransfer`, `ReconciliationRun`, `AuditLog`
- **설계 의도:** 백엔드 DTO 필드명과 응답 형식을 컴파일 타임에 고정하며, 모든 금액 필드를 `Money`(`string`) 타입으로 선언해 `BigInt` 연산과 결합한다.
- **연결:** 모든 화면과 `api.ts`
- **면접 포인트:** 프론트 타입이 실제 백엔드 JSON 계약과 일치하는지 검증하는 방법을 설명한다.

### `src/format.ts`

- **레이어:** 포맷/오류 변환 유틸
- **핵심 메서드:** `normalizeAmount`, `formatWon`, `formatDateTime`, `toErrorBanner`
- **설계 의도:** 원화 정수 검증, 한국 원화 표시, 백엔드 ErrorCode 한국어 변환을 담당한다.
- **금액 규칙:** 입력에서 쉼표/공백을 제거하고 양의 정수만 허용한다.
- **연결:** 모든 거래와 계좌 화면
- **면접 포인트:** 프론트 검증은 UX이고 최종 무결성은 서버 검증으로 보장된다는 점을 설명한다.

## 앱 조립과 인증

### `src/main.tsx`

- **레이어:** React 진입점
- **핵심 메서드:** `createRoot(...).render`
- **설계 의도:** `<App />`을 DOM 루트에 마운트한다.
- **연결:** `App.tsx`, CSS

### `src/App.tsx`

- **레이어:** 앱 루트/탭 조립
- **핵심 상태:** `user`, `tab`, `accounts`, `msg`, `showToken`
- **핵심 메서드:** `logout`, `handleLogin`, `reloadAccounts`, `tokenExpiry`
- **설계 의도:** JWT 토큰을 저장하고 새로고침 시 `/api/auth/me`로 사용자 복원한다.
- **보안:** 화면에서 JWT 토큰을 확인할 때는 `maskToken`으로 앞 12자와 뒤 8자만 남기고 중간을 마스킹해 전체 토큰 평문 노출을 방지한다.
- **연결:** 모든 패널과 `api.ts`
- **면접 포인트:** 프론트 역할 탭 숨김과 서버의 `/api/admin/**` 권한 검증은 별개라는 점을 설명한다.

### `src/components/AuthScreen.tsx`

- **레이어:** 인증 화면
- **핵심 메서드:** `submit`
- **설계 의도:** 회원가입 후 로그인 화면으로 전환하거나 로그인 토큰을 루트에 전달한다.
- **민감정보:** 비밀번호는 password input이며 가입은 8~64자 검증을 한다.
- **데모 자격증명:** 기본 관리자 계정은 데모 편의를 위해 번들에 포함된다. 운영 빌드에서는 제거/환경 분리가 필요하다.
- **연결:** `/api/auth/signup`, `/api/auth/login`

## 계좌와 거래 화면

### `src/components/AccountsPanel.tsx`

- **레이어:** 계좌 목록/개설/해지 화면
- **핵심 메서드:** `create`, `close`
- **설계 의도:** PIN 확인 일치와 원화 한도를 먼저 검증한 뒤 백엔드에 보낸다.
- **핵심 코드:** `api.ts`의 `preserveMoneyLiterals`가 보존한 문자열 금액을 `toBigIntMoney`로 변환해 총잔액 합산과 잔액 0원(`=== 0n`) 해지 가능 여부 판정을 19자리까지 오차 없이 수행한다.
- **연결:** `/api/accounts`
- **면접 포인트:** 프론트 BigInt와 백엔드 BigDecimal/DB NUMERIC의 역할을 비교한다.

### `src/components/TransactionsPanel.tsx` (명세상의 `TransferPanel` 역할 포함)

- **레이어:** 입금/출금/즉시 이체 화면
- **핵심 메서드:** `send`, `submit`, `replay`
- **핵심 흐름:** 금액 정수 검증 → 동일 멱등키 헤더 요청 → 성공 시 키 갱신 → 실패 시 키 유지
- **설계 의도:** 중복 클릭을 막고 재시도 시 이중 출금이 없음을 시연한다.
- **출금/이체:** PIN 4~6자리 검증과 계좌 선택을 한다.
- **연결:** `/api/transactions/deposit|withdraw|transfer`
- **면접 포인트:** 실패 시 키를 유지하고 성공 후에만 갱신하는 이유를 설명한다.

### `src/components/ScheduledPanel.tsx`

- **레이어:** 예약 이체 화면
- **핵심 메서드:** `submit`, `cancel`, `load`
- **설계 의도:** 출금/입금 계좌, 금액, 로컬 예약 일시, PIN, 메모를 등록한다.
- **멱등키:** 등록 요청 성공 후 키를 갱신하고 실패 시 키를 유지한다.
- **상태 표시:** PENDING/COMPLETED/FAILED/CANCELED 및 실패 사유를 렌더링한다.
- **연결:** `/api/scheduled-transfers`
- **면접 포인트:** 예약 일시는 서버의 LocalDateTime 계약과 타임존을 명시해야 한다.

### `src/components/HistoryPanel.tsx`

- **레이어:** 복식부기 거래내역 화면
- **핵심 메서드:** `load`, `resetPage`
- **필터:** 계좌, 거래 유형, 차변/대변, 시작일, 종료일, 페이징
- **설계 의도:** 대변은 잔액 증가, 차변은 잔액 감소로 표시한다.
- **연결:** `GET /api/accounts/{accountId}/transactions`
- **면접 포인트:** 차변/대변과 실제 계좌 잔액 스냅샷을 혼동하지 않는 방법을 설명한다.

## 관리자와 감사 화면

### `src/components/AdminPanel.tsx`

- **레이어:** 관리자 운영 화면
- **핵심 섹션:** 대사 실행/이력, 도래 예약 실행, 계좌 잠금 해제, 전체 감사 로그
- **설계 의도:** 서버가 거부한 일반 사용자의 `/api/admin/**` 요청을 호출하지 않고 UI 탭도 역할로 숨긴다.
- **주의:** UI 역할 숨김은 보안이 아니다. 최종 권한은 백엔드 `/api/admin/**`가 보장한다.
- **연결:** 대사/예약 실행/잠금 해제/관리자 감사 엔드포인트
- **면접 포인트:** 프론트 권한 UI와 서버 권한의 역할 차이를 설명한다.

### `src/components/AuditTable.tsx`

- **레이어:** 감사 로그 공용 테이블
- **설계 의도:** 마스킹 요약과 대상 ID를 일반 텍스트로 표시한다.
- **안전성:** React JSX이므로 문자열은 기본적으로 이스케이프된다.
- **연결:** 내 감사 로그와 관리자 전체 감사 로그

### `src/components/MyAuditPanel.tsx`

- **레이어:** 본인 감사 로그 화면
- **핵심 메서드:** `load`
- **연결:** `GET /api/audit-logs/me`
- **면접 포인트:** 프론트는 본인 필터를 믿지 않고 서버가 actor_user_id로 제한하는지 확인한다.

## 공통 컴포넌트

### `src/components/common.tsx`

- **핵심 컴포넌트:** `Banner`, `StatusBadge`, `Pager`, `Field`, `Card`, `Empty`, `AccountSelect`, `PinInput`
- **멱등키 훅:** `useIdempotencyKey`
- **설계 의도:** 상태, 페이징, 계좌 선택, PIN 입력을 재사용한다.
- **PIN 입력:** 숫자만 허용하고 최대 6자리로 제한한다.
- **XSS:** 일반 JSX 렌더링만 사용하며 민감 문자열을 HTML로 해석하지 않는다.

### `src/styles.css`

- **레이어:** 화면 스타일
- **설계 의도:** 운영 대시보드형 레이아웃과 상태 배지를 제공한다.
