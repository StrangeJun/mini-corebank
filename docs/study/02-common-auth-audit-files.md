# 공통 기반·인증·감사 전 파일 해설

이 문서는 요청이 Controller에 들어온 뒤 공통 예외, 인증, 멱등성, 감사가 어떤 순서로 동작하는지를 설명한다.

## 공통 예외 계층

### `common/exception/ErrorCode.java`

- **요약/레이어:** 표준 도메인 오류 enum
- **핵심 필드:** `HttpStatus`, 기본 메시지
- **핵심 메서드:** `getStatus`, `getCode`, `getMessage`
- **설계 의도:** 모든 API가 일정한 상태 코드와 코드명을 반환하도록 한다.
- **연결:** `BusinessException`, `GlobalExceptionHandler`
- **면접 포인트:** 내부 예외 메시지를 그대로 노출하지 않고 표준화된 코드를 쓰는 이유는 무엇인가?

### `common/exception/BusinessException.java`

- **요약/레이어:** 금융 도메인 런타임 예외
- **핵심 필드:** `ErrorCode`, 선택적 상세 메시지
- **설계 의도:** 금액/잔액/권한 같은 규칙 위반을 표준 코드로 전달한다.
- **연결:** 모든 Service와 전역 예외 처리
- **면접 포인트:** 체크 예외 대신 런타임 예외를 쓰는 이유와 트랜잭션 롤백 관계를 설명한다.

### `common/exception/ApiErrorResponse.java`

- **요약/레이어:** 오류 응답 DTO
- **핵심 필드:** `code`, `message`, `timestamp`
- **핵심 메서드:** `of(ErrorCode)`, `of(ErrorCode, String)`
- **설계 의도:** 스택트레이스와 입력값을 배제한 일관된 오류 응답을 제공한다.
- **연결:** `GlobalExceptionHandler`, `SecurityConfig`의 인증/인가 핸들러
- **면접 포인트:** timestamp와 표준 코드가 프론트와 운영 로그에 어떤 이점을 주는가?

### `common/exception/GlobalExceptionHandler.java`

- **요약/레이어:** `@RestControllerAdvice`
- **핵심 메서드:** `handleBusiness`, `handleValidation`, `handleBadRequest`, `handleUnexpected`
- **설계 의도:** `BusinessException`과 입력 검증 예외를 표준 JSON으로 변환하고 미확인 예외의 내부 정보를 숨긴다.
- **연결:** 모든 Controller/Service
- **면접 포인트:** 예상 예외와 예상 밖 예외를 분리해서 처리하는 이유를 설명한다.

## 민감정보와 페이징

### `common/util/MaskingUtil.java`

- **요약/레이어:** 보안 유틸리티
- **핵심 패턴:** secret pair 정규식과 하이픈/연속 계좌번호 정규식
- **핵심 메서드:** `maskAccountNumber`, `maskEmail`, `maskSensitiveText`
- **설계 의도:** 계좌 전체, PIN/password 값, 이메일의 노출 범위를 제한한다.
- **핵심 흐름:** 문자열에서 password/pin 키 값을 먼저 가린 뒤 계좌번호 패턴을 마스킹한다.
- **연결:** 계좌 응답, 감사 로그, 거래내역
- **면접 포인트:** 마스킹은 표시용 보안이며 원본은 별도 암호화/권한 통제와 함께 필요하다고 답한다.

### `common/PageParams.java`

- **요약/레이어:** 페이징 공통 유틸
- **핵심 상수:** `MAX_SIZE=100`
- **핵심 메서드:** `validate`, `newestFirst`
- **설계 의도:** 음수 page와 과도한 size를 거절하고 최신순 정렬을 일관화한다.
- **연결:** 거래내역, 감사 로그 조회
- **면접 포인트:** 페이징 상한을 두는 이유는 대량 요청과 메모리 폭증을 막기 위해서다.

### `common/PageResponse.java`

- **요약/레이어:** 페이징 응답 DTO
- **핵심 메서드:** `of`, `from`
- **설계 의도:** 페이지 정보와 데이터 변환을 공통 형식으로 제공한다.
- **연결:** history/audit query 서비스
- **면접 포인트:** offset 페이징의 한계와 최신 무한 스크롤에 커서 페이징이 필요한 이유를 설명한다.

## 멱등성

### `common/idempotency/IdempotentResponse.java`

- **요약/레이어:** 멱등 저장 응답 record
- **핵심 필드:** HTTP 상태, 저장된 JSON 본문
- **설계 의도:** 트랜잭션이 저장한 응답을 그대로 재생한다.
- **연결:** `IdempotencyService.findReplay`
- **면접 포인트:** 응답 본문을 JSON으로 저장하면 콘텐츠 타입과 버전 호환성이 왜 중요한가?

### `common/idempotency/IdempotencyService.java`

- **요약/레이어:** 멱등성 Service
- **핵심 메서드:** `hashRequest`, `findReplay`, `saveResponse`, `matches`, `validateKey`
- **핵심 흐름:**
  1. 1~100자 키를 검증한다.
  2. 출금/이체는 먼저 `AccountPinVerificationService`로 소유권·상태·PIN을 독립 트랜잭션에서 검증한다.
  3. 그 뒤 본 거래 트랜잭션에서 키로 기존 레코드를 조회한다.
  4. 사용자/엔드포인트/요청 해시 중 하나라도 다르면 `409 IDEMPOTENCY_CONFLICT`를 던진다.
  5. 성공 처리와 같은 트랜잭션에서 응답을 `saveAndFlush`한다.
- **동시성:** UNIQUE insert 충돌을 잡아 다른 동시 요청을 충돌로 롤백한다.
- **연결:** `TransactionService`, `idempotency_records`
- **면접 포인트:** 멱등키와 락의 차이는 무엇인가? 멱등키는 재시도 동일성, 락은 동시 잔액 경합을 막는다.

## 보안 설정

### `config/SecurityConfig.java`

- **요약/레이어:** Spring Security 설정
- **핵심 Bean:** `securityFilterChain`, `PasswordEncoder`, `corsConfigurationSource`
- **핵심 메서드:** OPTIONS/공개 인증 경로 허용, `/api/admin/**` 관리자 권한, 나머지 `/api/**` 인증
- **설계 의도:** Stateless JWT와 역할 기반 접근 통제를 연결한다.
- **연결:** `JwtAuthenticationFilter`, `JwtTokenProvider`
- **면접 포인트:** 세션 대신 Stateless JWT를 쓰는 장단점과 토큰 만료/폐기 전략을 설명한다.

### `config/ProductionSecretsGuard.java`

- **요약/레이어:** 운영 기동 검증 설정
- **핵심 메서드:** `verify`, `rejectDefault`
- **설계 의도:** prod 프로필에서 고정 JWT secret과 고정 관리자 비밀번호로 기동하는 것을 막는다.
- **핵심 어노테이션:** `@PostConstruct`
- **연결:** `application.yml`, 환경변수
- **면접 포인트:** 기동 시점 검증이 왜 설정값보다 더 안전한지 설명한다.

## JWT와 인증

### `auth/jwt/AuthUser.java`

- **요약/레이어:** 인증 주체 DTO
- **핵심 필드:** userId, email, role
- **설계 의도:** JWT claims와 SecurityContext 주체를 타입 안전하게 전달한다.
- **연결:** 필터, Controller의 `@AuthenticationPrincipal`, Account 서비스
- **면접 포인트:** 토큰의 subject와 권한 claim을 서버 상태와 어떻게 검증하는가?

### `auth/jwt/JwtProperties.java`

- **요약/레이어:** 설정 바인딩 record
- **핵심 필드:** secret, access validity
- **설계 의도:** application.yml의 JWT 설정을 주입한다.
- **연결:** `JwtTokenProvider`
- **면접 포인트:** 설정 바인딩과 시크릿 검증을 분리하는 장점을 설명한다.

### `auth/jwt/JwtTokenProvider.java`

- **요약/레이어:** JWT 발급/검증 컴포넌트
- **핵심 메서드:** `createAccessToken`, `parse`
- **핵심 흐름:** HS256 서명, subject/email/role 클레임, 발급/만료 시각 설정, 검증 후 AuthUser 생성
- **설계 의도:** Stateless 인증을 구현한다.
- **연결:** `JwtAuthenticationFilter`, `AuthService`
- **면접 포인트:** 서명 키가 공개되면 발생할 사고와 키 회전은 어떻게 해야 하는가?

### `auth/jwt/JwtAuthenticationFilter.java`

- **요약/레이어:** 서블릿 보안 필터
- **핵심 메서드:** `doFilterInternal`
- **핵심 흐름:** Bearer 헤더 추출 → 토큰 검증 → ROLE 권한과 함께 SecurityContext 설정 → 체인 계속
- **설계 의도:** 요청마다 현재 사용자를 복원한다.
- **연결:** `JwtTokenProvider`, `SecurityConfig`
- **면접 포인트:** OncePerRequestFilter가 필요한 이유와 필터 예외 처리 위치를 설명한다.

## 인증 서비스와 API

### `auth/dto/SignupRequest.java`

- **요약/레이어:** 가입 요청 DTO
- **핵심 검증:** 이메일 형식, 비밀번호 길이, 이름 필수
- **설계 의도:** 계정 생성 전 입력을 검증한다.
- **연결:** `AuthController.signup`, `AuthService.signup`
- **면접 포인트:** 이메일 정규화와 중복 UNIQUE 제약의 관계를 설명한다.

### `auth/dto/LoginRequest.java`

- **요약/레이어:** 로그인 요청 DTO
- **핵심 필드:** 이메일, 비밀번호
- **설계 의도:** 로그인 입력만 분리한다.
- **연결:** `AuthService.login`

### `auth/dto/LoginResponse.java`

- **요약/레이어:** 로그인 응답 DTO
- **핵심 필드:** accessToken, UserResponse
- **설계 의도:** 토큰과 공개 사용자 정보를 함께 반환한다.

### `auth/AuthController.java`

- **요약/레이어:** 인증 REST Controller
- **핵심 메서드:** `signup`, `login`, `me`
- **설계 의도:** 회원가입/로그인/me 엔드포인트와 HTTP 상태를 담당한다.
- **연결:** `AuthService`
- **면접 포인트:** 로그인 실패를 일반화해 계정 열거를 막는 설계를 설명한다.

### `auth/AuthService.java`

- **요약/레이어:** 인증 Service
- **핵심 메서드:** `signup`, `login`, `me`, `normalizeEmail`
- **핵심 흐름:** 이메일 정규화와 중복 차단, BCrypt 해시, 더미 해시 타이밍 비교, JWT 발급, 감사 로그
- **설계 의도:** 비밀번호 평문 저장과 계정 열거를 방지한다.
- **연결:** `UserRepository`, `PasswordEncoder`, `JwtTokenProvider`, `AuditLogService`
- **면접 포인트:** 없는 이메일과 잘못된 비밀번호를 왜 같은 UNAUTHORIZED로 처리하는가?

## 감사 로그

### `audit/AuditEvent.java`

- **요약/레이어:** 감사 이벤트 DTO
- **핵심 필드:** actor, action, target, result, summary, client IP
- **설계 의도:** 감사 저장 전에 서비스 간 일관된 입력을 제공한다.
- **연결:** `AuditLogService`
- **면접 포인트:** 감사 로그와 애플리케이션 로그의 차이를 설명한다.

### `audit/AuditLogService.java`

- **요약/레이어:** 감사 저장 Service
- **핵심 메서드:** `logInTransaction`, `logIndependent`, `save`, `maskSummary`
- **전파 차이:** REQUIRED는 본 트랜잭션 참여, REQUIRES_NEW는 실패 독립 커밋
- **설계 의도:** 성공은 원자 커밋, 실패는 롤백과 무관한 증적을 남긴다.
- **연결:** 모든 금융 거래와 인증 서비스
- **면접 포인트:** 감사 로그는 왜 유일한 독립 트랜잭션 기록으로 남아야 하는가?

### `audit/AuditLogQueryService.java`

- **요약/레이어:** 감사 조회 Service
- **핵심 메서드:** `findMine`, `search`
- **설계 의도:** 일반 사용자는 본인 로그만, 관리자만 필터 조회를 수행한다.
- **연결:** `AuditLogRepository`, `PageParams`
- **면접 포인트:** 권한 검사는 어디서 걸고 행 단위 필터는 어디서 수행하는가?

### `audit/AuditLogController.java`

- **요약/레이어:** 감사 조회 REST Controller
- **핵심 메서드:** 본인 조회와 관리자 전체 조회
- **설계 의도:** 사용자별 접근과 ADMIN 역할을 분리한다.
- **연결:** `AuditLogQueryService`
- **면접 포인트:** 왜 관리 로그 조회가 일반 사용자에게 노출되면 안 되는가?

### `audit/dto/AuditLogResponse.java`

- **요약/레이어:** 감사 응답 DTO
- **핵심 메서드:** `from`
- **설계 의도:** 저장 마스킹 외에 응답 직전 한 번 더 마스킹한다.
- **연결:** `MaskingUtil`
- **면접 포인트:** 다중 마스킹이 왜 defense-in-depth가 되는가?

## 시드와 사용자 응답

### `seed/SystemSeedInitializer.java`

- **요약/레이어:** `ApplicationRunner`
- **핵심 메서드:** `run`
- **설계 의도:** SYSTEM-VAULT와 기본 관리자 계정을 멱등하게 만든다.
- **연결:** 계좌/사용자 Repository
- **면접 포인트:** 다중 인스턴스 기동 시 시드 레이스를 어떻게 막는가?

### `user/UserResponse.java`

- **요약/레이어:** 공개 사용자 DTO
- **핵심 메서드:** `from`
- **설계 의도:** 비밀번호 해시를 제외한 공개 필드만 반환한다.
- **연결:** `AuthService`, `AuthController`
- **면접 포인트:** Entity를 직접 응답하지 않는 이유는 직렬화와 누출을 막기 위해서다.
