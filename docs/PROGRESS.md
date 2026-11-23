# 업무 진행 현황 대시보드 (PROGRESS)

- **프로젝트**: `mini-corebank` (1금융권 원화 계좌·송금 코어뱅킹 복식부기 원장 시스템)
- **브랜치 전략**: GitHub Flow (`main` + `feat/T-xxx-*` `--no-ff` 병합 + 마일스톤 태그)
- **현재 진행 단계**: **마일스톤 1·2·3 전체 작업(`T-001` ~ `T-013`) 100% 완료!**
- **최종 검증 결과**: 백엔드 테스트 **119개 통과 (실패 0)** + 프론트엔드 `npm run build` 성공 (`BigInt` 19자리 원화 무오차 처리) + **실제 PostgreSQL 16(Docker) 연동 E2E 검증 완료**
- **학습자료 및 리뷰 문서 총량**: **총 2,445줄** (전 파일 상세 해설서 `01~06`권 + 마일스톤 종합 가이드 `M1~M3` + 전체 파일 지도 `docs/study/README.md` + 코드 리뷰 보고서 7종)

---

## 1. 마일스톤별 요약

| 마일스톤 | 범위 | 상태 | 완료 Task | 태그 | 누적 테스트 |
|---|---|---|---|---|---|
| **M1. 코어 원장 & 안전 이체** | `T-001` ~ `T-007`, `T-007R`, `T-007R-FIX` | **완료** | 8 / 8 | `v0.1.0` | 81 passed |
| **M2. 1금융권 실무 심화** | `T-008` ~ `T-011`, `T-011R` | **완료** | 5 / 5 | `v0.2.0` | 119 passed |
| **M3. 웹 데모 & 포트폴리오 마무리** | `T-012` ~ `T-013`, `T-013R` | **완료** | 3 / 3 | `v1.0.0` (대기) | 119 passed + FE build ok |

---

## 2. Task별 6단계 파이프라인 추적표

| ID | 작업명 | 1. 브랜치 | 2. 개발 (Claude) | 3. 코드 리뷰 (Copilot) | 4. PM 판정 & 수정 | 5. `main` 병합 | 6. 학습자료 생성·업데이트 | 테스트 수 |
|---|---|---|---|---|---|---|---|---|
| **T-001** | 백엔드 초기화 & Flyway V1 8개 테이블 스키마 | `feat/T-001-init-schema` | 완료 | [M1-review.md](reviews/M1-review.md) | `T-007R-FIX` 반영 | 완료 | [01권](study/01-schema-and-domain-files.md) | 2 |
| **T-002** | 공통 기반 (에러코드, 마스킹, 감사로그, 멱등성) | `feat/T-002-common-foundation` | 완료 | [M1-review.md](reviews/M1-review.md) | `T-007R-FIX` 반영 | 완료 | [02권](study/02-common-auth-audit-files.md) | 18 |
| **T-003** | 회원·인증(JWT)·권한·`SYSTEM-VAULT`/관리자 시드 | `feat/T-003-auth-security` | 완료 | [M1-review.md](reviews/M1-review.md) | `T-007R-FIX` 반영 | 완료 | [02권](study/02-common-auth-audit-files.md) | 29 |
| **T-004** | 원화 계좌 개설(PIN 해시)·조회·소유권·해지 | `feat/T-004-account` | 완료 | [M1-review.md](reviews/M1-review.md) | `T-007R-FIX` 반영 | 완료 | [03권](study/03-account-and-transaction-files.md) | 38 |
| **T-005** | 복식부기 입금·출금·즉시 이체 (비관적 락·한도·멱등성) | `feat/T-005-ledger-transfer` | 완료 | [M1-review.md](reviews/M1-review.md) | `T-007R-FIX` 반영 | 완료 | [03권](study/03-account-and-transaction-files.md) | 58 |
| **T-006** | 거래내역 조회 및 감사 로그 조회 API | `feat/T-006-history-audit` | 완료 | [M1-review.md](reviews/M1-review.md) | `T-007R-FIX` 반영 | 완료 | [02권](study/02-common-auth-audit-files.md), [03권](study/03-account-and-transaction-files.md) | 64 |
| **T-007** | 마일스톤 1 통합 QA & 코드 리뷰 보강(`T-007R-FIX`) | `feat/T-007-m1-qa` / `fix/T-007R-*` | 완료 | [M1-review.md](reviews/M1-review.md) | 4대 보강 완료 | 완료 (`v0.1.0`) | [M1-core-ledger.md](study/M1-core-ledger.md) | 81 |
| **T-008** | 계좌 PIN 5회 연속 오류 자동 `LOCKED` & 관리자 해제 | `feat/T-008-pin-lock` | 완료 | [T-008-review.md](reviews/T-008-review.md) | 이슈 0건(LOW 1 기각) | 완료 | [03권](study/03-account-and-transaction-files.md), [M1](study/M1-core-ledger.md) 업데이트 완료 | 90 |
| **T-009** | 3대 불변식 원장-잔액 정합성 대사(Reconciliation) | `feat/T-009-reconciliation` | 완료 | [T-009-review.md](reviews/T-009-review.md) | V2 인덱스 + 상세 100건 상한 수정 완료 | 완료 | [04권](study/04-reconciliation-files.md), [README](study/README.md) 업데이트 완료 | 99 |
| **T-010** | 예약 이체 등록·조회·취소 및 도래 건 멱등 스케줄러 | `feat/T-010-scheduled-transfer` | 완료 | [T-010-review.md](reviews/T-010-review.md) | 3건 보강 완료(`markFailed` 격리, 배치 100건 상한+인덱스, 기존 거래 파라미터 검증) | 완료 | [05권](study/05-scheduled-transfer-files.md), [README](study/README.md) 업데이트 완료 | 115 |
| **T-011** | 마일스톤 2 통합 QA & 심화 종합 학습자료 | `feat/T-011-m2-qa-study` | 완료 (`119 passed`) | [M2-review.md](reviews/M2-review.md) | CRITICAL/HIGH 0건 (통과) | 완료 (`v0.2.0`) | [M2-banking-advanced.md](study/M2-banking-advanced.md) 완료 | 119 |
| **T-012** | React + TypeScript (Vite) 프론트엔드 데모 대시보드 | `feat/T-012-frontend-demo` | 완료 | [T-012-review.md](reviews/T-012-review.md) | `BigInt` 19자리 금액 보존 + JWT 마스킹 반영 완료 | 완료 | [06권](study/06-frontend-files.md), [README](study/README.md) 완료 | 119 + FE ok |
| **T-013** | 마일스톤 3 최종 통합 QA, `README.md` & 풀스택 학습자료 | `feat/T-013-final-qa-docs` | 완료 | [M3-review.md](reviews/M3-review.md) | 완료 | 완료 | [M3-fullstack-architecture.md](study/M3-fullstack-architecture.md), [README.md](../README.md) 완료 | 119 + FE ok |

---

## 3. 산출된 코드 리뷰 및 학습자료 아카이브

### A. 코드 리뷰 보고서 (`docs/reviews/`)
- [M1-review.md](reviews/M1-review.md): 마일스톤 1 전체 리뷰 (7건 발견 → `T-007R-FIX`로 멱등 재생 전 PIN 재검증, `ProductionSecretsGuard`, Flyway V2 `CHECK`/인덱스, UNIQUE 충돌 재시도 반영 완료)
- [T-008-review.md](reviews/T-008-review.md): PIN 5회 오류 잠금 및 관리자 해제 리뷰 (`CRITICAL 0 / HIGH 0 / MEDIUM 0 / LOW 1` → 통과)
- [T-009-review.md](reviews/T-009-review.md): 정합성 대사 리뷰 (`RV-T9-01` Spring 공식 `cron="-"` 사양 팩트체크로 기각, `RV-T9-02` `ledger_entries(transaction_id)` 인덱스 및 `RV-T9-04` `MAX_DETAILS=100` 상한 즉시 반영 완료)
- [T-010-review.md](reviews/T-010-review.md): 예약 이체 리뷰 (`RV-T10-01` `markFailedSafely` 실패 격리, `RV-T10-02` `MAX_BATCH_SIZE=100` 및 복합 인덱스, `RV-T10-05` 기존 거래 파라미터 검증 즉시 반영 완료)
- [M2-review.md](reviews/M2-review.md): 마일스톤 2 종합 리뷰 (`CRITICAL 0 / HIGH 0` → 통과)
- [T-012-review.md](reviews/T-012-review.md): 프론트엔드 리뷰 (`RV-T12-01` `preserveMoneyLiterals` + `BigInt` 19자리 무오차 연산 및 `RV-T12-02/05` 화면 JWT 마스킹 즉시 반영 완료)
- [M3-review.md](reviews/M3-review.md): 마일스톤 3 풀스택 종합 리뷰

### B. 학습자료 (`docs/study/`)
- [docs/study/README.md](study/README.md): 백엔드·프론트엔드 전체 코드 파일(80여 개) 1:1 클릭 지도 및 추천 학습 동선
- [01-schema-and-domain-files.md](study/01-schema-and-domain-files.md): `application.yml`, Flyway `V1`·`V2`, `domain/**` 전 엔티티·Enum·Repository 상세 해설
- [02-common-auth-audit-files.md](study/02-common-auth-audit-files.md): 예외 처리, 마스킹, 멱등성, Security/JWT, 감사 로그(`REQUIRED` vs `REQUIRES_NEW`), 시드 전 파일 해설
- [03-account-and-transaction-files.md](study/03-account-and-transaction-files.md): 계좌 관리, PIN 5회 잠금(`REQUIRES_NEW` + 자기 교착 방지), 복식부기 입출금·이체, 거래내역 전 파일 해설
- [04-reconciliation-files.md](study/04-reconciliation-files.md): 3대 불변식 원장-잔액 정합성 대사(`REPEATABLE_READ` 스냅샷 집계 SQL, 스케줄러, `MAX_DETAILS=100`) 전 파일 해설
- [05-scheduled-transfer-files.md](study/05-scheduled-transfer-files.md): 예약 이체 상태머신, 도래 건 멱등 스케줄러, `markFailedSafely` 실패 격리 전 파일 해설
- [06-frontend-files.md](study/06-frontend-files.md): React + TypeScript + Vite 프론트엔드 전 파일(`api.ts`, `format.ts`, `App.tsx`, 각 패널 컴포넌트) 1:1 상세 해설
- [M1-core-ledger.md](study/M1-core-ledger.md): 마일스톤 1 핵심 아키텍처 Mermaid 다이어그램 6종 & 1금융권 기술 면접 Q&A
- [M2-banking-advanced.md](study/M2-banking-advanced.md): 마일스톤 2 코어뱅킹 심화 Mermaid 다이어그램 4종 & 기술 면접 Q&A
- [M3-fullstack-architecture.md](study/M3-fullstack-architecture.md): 마일스톤 3 풀스택 엔드투엔드 아키텍처, 데모 시연 가이드 & 최종 면접 치트시트

---

## 4. 진행 타임라인 로그
- `01:20` — [PRD.md](PRD.md) 오너 승인 완료
- `01:25` — [DECISIONS.md](DECISIONS.md) D-001 ~ D-004 기술 스택·8개 테이블 복식부기 스키마 승인 완료
- `01:41` — 프로젝트명 `mini-corebank` 확정, `T-001` 개발 완료 (`2 passed`)
- `01:46` — `T-002` 공통 기반 개발 완료 (`18 passed`), [README.md](../README.md) 및 [TEST_PLAN.md](TEST_PLAN.md) 초안 완료
- `01:50` — `T-003` JWT 인증·시드 개발 완료 (`29 passed`)
- `01:54` — `T-004` 계좌 관리 개발 완료 (`38 passed`)
- `01:58` — D-005 GitHub Flow(`main` + `feat/T-xxx`) 이력 재구성 및 GitHub 원격 push 완료
- `02:05` — `T-005` 복식부기 입출금·이체 및 동시성/데드락 방지 테스트 완료 (`58 passed`)
- `02:09` — `T-006` 거래내역 및 감사로그 조회 API 개발 완료 (`64 passed`)
- `02:15` — `corebank-postgres`(PostgreSQL 16) Docker 컨테이너 기동 완료, `T-007` 마일스톤 1 통합 QA 완료 (`68 passed`)
- `02:29` — `T-007R` 마일스톤 1 코드 리뷰([M1-review.md](reviews/M1-review.md)) 및 학습 가이드([M1-core-ledger.md](study/M1-core-ledger.md)) 완료
- `02:36` — `T-007R-FIX` 마일스톤 1 리뷰 반영 수정 완료 (`81 passed`), `main` 병합 및 `v0.1.0` 태그 생성
- `02:44` — `T-008` 계좌 PIN 5회 오류 자동 잠금 및 관리자 해제 완료 (`90 passed`)
- `02:50` — `T-008` 코드 리뷰([T-008-review.md](reviews/T-008-review.md)) 완료 및 전체 코드 파일 학습자료(`01~03`권, `README.md`) 작성 완료
- `02:55` — `T-008` 학습자료 동기화 완료, `T-009` 정합성 대사 개발 완료 (`97 passed`)
- `02:58` — `T-009` 코드 리뷰([T-009-review.md](reviews/T-009-review.md)) 완료 및 PM 팩트체크/판정 완료
- `03:01` — `T-009` 리뷰 반영 수정 완료 (`99 passed`), `main` 병합 후 `T-010` 착수
- `03:05` — `T-009` 정합성 대사 전 파일 상세 학습자료([04-reconciliation-files.md](study/04-reconciliation-files.md)) 생성 및 기존 학습자료 업데이트 완료
