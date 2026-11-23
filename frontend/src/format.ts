import { ApiError } from './api';

const won = new Intl.NumberFormat('ko-KR');

/** 금액 문자열(정수) → BigInt. 정수가 아니면 null (Number 변환을 거치지 않는다). */
export function toBigIntMoney(v: string | bigint | null | undefined): bigint | null {
  if (v === null || v === undefined) return null;
  if (typeof v === 'bigint') return v;
  return /^-?\d+$/.test(v.trim()) ? BigInt(v.trim()) : null;
}

/** 원화 포맷: BigInt 로 천 단위 구분(Intl 은 bigint 를 정밀도 손실 없이 포맷). 정수가 아니면 원문 그대로 표시. */
export function formatWon(v: string | bigint | null | undefined): string {
  if (v === null || v === undefined) return '-';
  const n = toBigIntMoney(v);
  return n === null ? `${v}원` : `${won.format(n)}원`;
}

/** LocalDateTime("2026-11-24T10:00:00.123") → "2026-11-24 10:00:00" */
export function formatDateTime(v: string | null | undefined): string {
  if (!v) return '-';
  return v.replace('T', ' ').slice(0, 19);
}

/** 원 단위 양의 정수 문자열인지 검사(앞자리 0 제거). 올바르지 않으면 null. */
export function normalizeAmount(raw: string): string | null {
  const s = raw.replace(/[,\s]/g, '');
  if (!/^\d{1,19}$/.test(s)) return null;
  const n = s.replace(/^0+/, '');
  return n === '' ? null : n;
}

export const TX_TYPE_LABEL: Record<string, string> = { DEPOSIT: '입금', WITHDRAWAL: '출금', TRANSFER: '이체' };
export const ENTRY_LABEL: Record<string, string> = { DEBIT: '차변(−)', CREDIT: '대변(+)' };
export const ACCOUNT_STATUS_LABEL: Record<string, string> = { ACTIVE: '정상', LOCKED: '거래정지', CLOSED: '해지' };
export const SCHEDULED_STATUS_LABEL: Record<string, string> = {
  PENDING: '대기', PROCESSING: '처리 중', COMPLETED: '완료', FAILED: '실패', CANCELED: '취소',
};

/** 백엔드 ErrorCode → 고객 안내 문구. 매핑이 없으면 서버 메시지를 그대로 쓴다. */
const ERROR_MESSAGES: Record<string, string> = {
  INVALID_INPUT: '입력값을 다시 확인해 주세요.',
  INVALID_AMOUNT: '금액은 1원 이상의 정수(원 단위)로 입력해 주세요.',
  SAME_ACCOUNT_TRANSFER: '출금 계좌와 같은 계좌로는 이체할 수 없습니다.',
  ACCOUNT_CLOSED: '해지된 계좌입니다. 다른 계좌를 선택해 주세요.',
  INSUFFICIENT_BALANCE: '잔액이 부족합니다. 출금 가능 금액을 확인해 주세요.',
  PER_TX_LIMIT_EXCEEDED: '1회 이체 한도를 초과했습니다. 금액을 줄이거나 한도를 확인해 주세요.',
  DAILY_LIMIT_EXCEEDED: '오늘의 1일 이체 한도를 초과했습니다. 내일 다시 시도해 주세요.',
  UNAUTHORIZED: '로그인이 필요하거나 아이디/비밀번호가 일치하지 않습니다.',
  INVALID_PIN: '계좌 비밀번호(PIN)가 일치하지 않습니다. 5회 연속 오류 시 거래가 정지됩니다.',
  FORBIDDEN: '이 기능에 접근할 권한이 없습니다.',
  FORBIDDEN_ACCOUNT_ACCESS: '본인 소유 계좌만 이용할 수 있습니다.',
  ACCOUNT_LOCKED: '거래가 정지된 계좌입니다(PIN 5회 오류 등). 관리자에게 잠금 해제를 요청하세요.',
  USER_NOT_FOUND: '사용자를 찾을 수 없습니다.',
  ACCOUNT_NOT_FOUND: '계좌를 찾을 수 없습니다. 계좌번호를 확인해 주세요.',
  SCHEDULED_TRANSFER_NOT_FOUND: '예약 이체 내역을 찾을 수 없습니다.',
  RECONCILIATION_RUN_NOT_FOUND: '대사 실행 이력을 찾을 수 없습니다.',
  DUPLICATE_EMAIL: '이미 가입된 이메일입니다.',
  IDEMPOTENCY_CONFLICT: '같은 요청 번호(멱등키)로 다른 내용의 요청이 이미 처리되었습니다. 새로고침 후 다시 시도해 주세요.',
  INTERNAL_ERROR: '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',
};

export interface BannerMessage { kind: 'error' | 'success' | 'info'; text: string; code?: string; detail?: string }

export function toErrorBanner(e: unknown): BannerMessage {
  if (e instanceof ApiError) {
    const friendly = ERROR_MESSAGES[e.code];
    return {
      kind: 'error',
      code: e.code,
      text: friendly ?? e.message,
      detail: friendly && e.message && e.message !== friendly ? e.message : undefined,
    };
  }
  return { kind: 'error', text: e instanceof Error ? e.message : '알 수 없는 오류가 발생했습니다.' };
}
