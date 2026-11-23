# T-012 프론트엔드 코드 리뷰

검토 범위:

- `frontend/src/api.ts`
- `frontend/src/types.ts`
- `frontend/src/App.tsx`
- `frontend/src/components/**`
- `frontend/vite.config.ts`
- 백엔드 Controller/DTO의 요청·응답 필드 대조

검증 결과: `cd frontend && npm run build` 성공.

## 요약

- CRITICAL: 0
- HIGH: 1
- MEDIUM: 3
- LOW: 2

프론트엔드는 백엔드 주요 엔드포인트와 멱등키 헤더를 대부분 일치하게 호출한다. React의 일반 렌더링도 HTML 주입을 사용하지 않아 기본적인 XSS 면에서는 안전하다. 다만 원화 `NUMERIC(19,0)` 응답을 JavaScript `number`로 받는 것이 실거래 금액 정밀도 위험을 만든다.

## 발견 이슈

### [RV-T12-01] BigDecimal 원화 잔액을 JavaScript number로 변환해 정밀도를 잃을 수 있음

- **심각도:** HIGH
- **위치:** `frontend/src/api.ts:59-71`, `frontend/src/types.ts:13-21,26-59,73-83`, `frontend/src/components/AccountsPanel.tsx:17-18`
- **문제점 및 위험 시나리오:** 백엔드 `NUMERIC(19,0)` 값이 JSON number로 내려오면 `JSON.parse`와 JavaScript `number`가 2^53보다 큰 정수를 안전하게 표현하지 못한다. 예를 들어 최대 금액대에서 계좌 잔액이 다른 값으로 반올림되면 총잔액 합산, 해지 가능 여부, 거래 후 잔액 표시가 틀릴 수 있다. 요청 금액은 문자열로 보내지만 응답 잔액은 이미 number로 들어간다.
- **권장 수정:** 금액 응답을 문자열로 직렬화하거나, 프론트엔드가 원본 JSON 텍스트에서 잔액 필드를 별도 문자열로 보존해야 한다. 최소한 모든 금액 필드 타입을 `string`으로 바꾸고 화면 계산은 `BigInt`, 포맷팅도 문자열 기반으로 수행한다. 요청과 응답의 19자리 경계값 테스트를 프론트엔드 빌드와 통합 테스트에 포함한다.

### [RV-T12-02] JWT를 localStorage에 저장하고 전체 토큰을 화면에 노출

- **심각도:** MEDIUM
- **위치:** `frontend/src/api.ts:19-25`, `frontend/src/App.tsx:105-112`
- **문제점 및 위험 시나리오:** localStorage는 동일 오리진에서 실행되는 스크립트가 읽을 수 있다. 앱에 의도치 않은 스크립트가 삽입되거나 공급망 공격이 발생하면 JWT가 즉시 유출될 수 있다. 전체 토큰을 UI에 표시하는 기능도 브라우저 화면 캡처/지연 확장에 노출 범위를 넓힌다.
- **권장 수정:** 데모용에서도 현재는 httpOnly Secure 쿠키 또는 메모리 보관을 검토한다. 불가피하면 localStorage 대신 인메모리 유지, 탭 종료 시 제거, 전체 토큰 표시 제거 또는 마스킹 표시를 권장한다. 프로덕션에는 CSP와 공급망 스크립트 통제를 추가한다.

### [RV-T12-03] 데모 관리자 계정이 프론트엔드 번들에 하드코딩됨

- **심각도:** MEDIUM
- **위치:** `frontend/src/components/AuthScreen.tsx:7,68-72`
- **위험 시나리오:** 기본 관리자 계정이 소스와 빌드 산출물에 그대로 노출된다. 데모 환경이 인터넷에 공개되면 누구나 로그인 화면과 함께 계정 비밀번호를 알 수 있다.
- **권장 수정:** 데모 자동 입력은 로컬 개발 환경에만 표시하고, 운영 빌드에서는 제거한다. 운영 환경 기본 관리자 비밀번호는 이미 강제 가드가 있으므로 프론트엔드에도 환경별 분기를 둔다.

### [RV-T12-04] 예약 일시의 클라이언트 로컬 시각과 서버 LocalDateTime 해석 불일치

- **심각도:** MEDIUM
- **위치:** `frontend/src/components/ScheduledPanel.tsx:45-64,103-104`
- **위험 시나리오:** `datetime-local` 값은 ISO 오프셋이 없어 브라우저가 만든 문자열과 서버의 `LocalDateTime.now()`가 서로 다른 시간대를 가질 수 있다. 사용자가 예약 시각을 입력한 즉시 서버 기준보다 이전/이후로 판정될 수 있다.
- **권장 수정:** UI는 타임존을 명시하거나 UTC ISO 또는 epoch 변환을 서버 계약에 맞게 전달한다. 백엔드가 `OffsetDateTime` 또는 명시적 타임존을 받도록 일관화한다.

## 낮은 위험 및 양호 항목

### [RV-T12-05] 전체 JWT 노출 기능은 데모 목적이지만 감사 화면과 분리 필요

- **심각도:** LOW
- **위치:** `frontend/src/App.tsx:105-112`
- **권장 수정:** JWT 보기 기능을 개발 환경에서만 활성화하거나 부분 마스킹된 디버그 패널로 교체한다.

### [RV-T12-06] 현재 소스에 직접적인 HTML 삽입 XSS sink가 없음

- **심각도:** LOW
- **위치:** `frontend/src/**`
- **검토 결과:** React JSX 일반 렌더링만 사용하며 `dangerouslySetInnerHTML`, `innerHTML`, `eval` 또는 동적 스크립트 삽입이 없다. 서버의 마스킹 데이터와 사용자 입력은 일반 텍스트로 표시된다.
- **권장 수정:** 향후 리치 텍스트/첨부파일/계좌 메모를 표시할 경우 먼저 허용 태그를 제한하고 렌더링 라이브러리로 sanitize한다.

## 백엔드 DTO 및 엔드포인트 일치 검토

- 인증: `/api/auth/signup`, `/api/auth/login`, `/api/auth/me`와 `accessToken/user` 구조가 일치한다.
- 계좌: `AccountResponse`의 ID, 마스킹 번호, 잔액, 한도, 상태 필드와 프론트 인터페이스가 일치한다.
- 거래: 입금/출금/이체 요청 필드와 멱등키 헤더가 백엔드와 일치한다.
- 예약 이체: 등록/목록/취소 필드와 상태 enum이 일치한다.
- 대사: 실행/목록/상세 엔드포인트와 결과 필드가 일치한다.
- 관리자: 잠금 해제와 전체 감사 로그 엔드포인트가 일치한다.
- 거래내역: `type`, `entryType`, `fromDate`, `toDate`, `page`, `size` 쿼리명이 백엔드와 일치한다.

다만 금액 타입은 계약상 숫자 대신 문자열 정밀도 보존이 필요하고, 예약 일시는 타임존 계약을 명시해야 한다.
