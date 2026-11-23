# 마일스톤 3 풀스택 코드 리뷰

검토 범위:

- `frontend/src/**`
- `frontend/vite.config.ts`
- 백엔드 인증/계좌/거래/대사/예약/감사 Controller와 프론트 요청 필드 대조

## 요약

- CRITICAL: 0
- HIGH: 1
- MEDIUM: 3
- LOW: 2

프론트엔드 빌드는 성공했고 주요 요청/응답 필드는 백엔드와 일치한다. React 일반 렌더링에는 직접적인 HTML 삽입 sink가 없다. 최종적으로 금액 응답 정밀도와 JWT 보관 방식은 운영 배포 전에 보완해야 한다.

## 발견 이슈

### [RV-M3-01] 원화 응답의 JavaScript number 정밀도 위험

- **심각도:** HIGH
- **위치:** `frontend/src/api.ts:59-71`, `frontend/src/types.ts:13-21,26-59,73-83`
- **위험 시나리오:** 19자리 `NUMERIC(19,0)` 잔액이 JSON number로 JavaScript number로 변환되면 2^53 초과 정수가 반올림될 수 있다.
- **권장 수정:** 금액 응답을 문자열로 직렬화하거나 원본 텍스트를 보존한다. 프론트는 `string`과 `BigInt` 기반으로 잔액/한도를 계산한다.

### [RV-M3-02] JWT를 localStorage에 저장하고 전체 토큰 표시

- **심각도:** MEDIUM
- **위치:** `frontend/src/api.ts:19-25`, `frontend/src/App.tsx:105-112`
- **권장 수정:** httpOnly 쿠키 또는 인메모리 보관을 검토하고 운영 빌드에서 전체 토큰 표시를 제거한다.

### [RV-M3-03] 기본 관리자 자격증명이 프론트 번들에 노출

- **심각도:** MEDIUM
- **위치:** `frontend/src/components/AuthScreen.tsx:7,68-72`
- **권장 수정:** 데모 환경에만 관리자 빠른 입력을 표시하고 운영 빌드에서는 제거한다.

### [RV-M3-04] 예약 일시의 타임존 계약이 명시되지 않음

- **심각도:** MEDIUM
- **위치:** `frontend/src/components/ScheduledPanel.tsx:45-64,103-104`
- **권장 수정:** `LocalDateTime` 대신 명시적 타임존 또는 UTC 규약을 사용해 서버와 일관화한다.

### [RV-M3-05] 프론트에서 계좌 정보와 마스킹 정보를 함께 표시

- **심각도:** LOW
- **위치:** `frontend/src/components/AccountsPanel.tsx:98-100`
- **위험 시나리오:** 본인 계좌는 원본 번호가 정상 응답이므로 UI 노출 범위는 서버 소유권 검증에 달려 있다.
- **권장 수정:** 카드에는 마스킹 번호를 기본값으로 사용하고 원본 번호는 상세 시나리오에서만 노출한다.

### [RV-M3-06] 직접 HTML 삽입 sink 없음

- **심각도:** LOW
- **검토 결과:** `dangerouslySetInnerHTML`, `innerHTML`, `eval`, 동적 스크립트 삽입이 없다.
- **권장 수정:** 향후 메모/공지에 리치 텍스트가 추가되면 반드시 sanitize한다.

## 강점

1. 입출금/이체/예약 요청에 멱등키를 일관되게 사용한다.
2. 실패 시 키를 유지하고 성공 후 새 키를 발급한다.
3. 관리자 탭은 역할로 숨기고 백엔드 `/api/admin/**` 권한도 별도 보장한다.
4. 요청 금액은 원 단위 문자열로 보내 서버 `BigDecimal` 오차를 피한다.
5. 백엔드 ErrorCode를 한국어 안내와 code로 명확히 표시한다.
