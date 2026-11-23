import type {
  Account, AuditLog, AuditResult, EntryType, HistoryItem, LoginResponse, Page, ReconciliationRun,
  ReconciliationSummary, ScheduledExecutionSummary, ScheduledStatus, ScheduledTransfer, TransactionResponse,
  TransactionType, UnlockResponse, User,
} from './types';

const BASE = ((import.meta.env.VITE_API_BASE_URL as string | undefined) ?? '').replace(/\/$/, '');
const TOKEN_KEY = 'corebank.token';

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) {
    super(message);
  }
}

/**
 * 원화 금액(NUMERIC(19,0))은 2^53 을 넘을 수 있어 JSON number → JS number 변환 시 정밀도를 잃는다.
 * JSON.parse 전에 금액 필드의 숫자 리터럴을 문자열로 감싸 원본 자릿수를 그대로 보존한다.
 * (?<!\\) 로 문자열 값 안의 이스케이프된 \"amount\": 같은 텍스트는 건드리지 않는다.
 */
export const MONEY_FIELDS = ['balance', 'amount', 'perTxLimit', 'dailyLimit', 'balanceAfter',
  'expected', 'actual', 'difference', 'creditTotal', 'debitTotal'] as const;
const MONEY_LITERAL = new RegExp(`(?<!\\\\)"(${MONEY_FIELDS.join('|')})"(\\s*):(\\s*)(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)`, 'g');
export function preserveMoneyLiterals(json: string): string {
  return json.replace(MONEY_LITERAL, '"$1"$2:$3"$4"');
}

let token: string | null = safeGet();
let onUnauthorized: (() => void) | null = null;

function safeGet(): string | null {
  try { return localStorage.getItem(TOKEN_KEY); } catch { return null; }
}
export function getToken() { return token; }
export function setToken(t: string | null) {
  token = t;
  try { if (t) localStorage.setItem(TOKEN_KEY, t); else localStorage.removeItem(TOKEN_KEY); } catch { /* 저장 불가 환경 무시 */ }
}
export function setUnauthorizedHandler(fn: (() => void) | null) { onUnauthorized = fn; }

/** 멱등키: UUID v4. crypto.randomUUID 미지원 환경은 getRandomValues 로 생성한다. */
export function newIdempotencyKey(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  const b = crypto.getRandomValues(new Uint8Array(16));
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

type Query = Record<string, string | number | undefined | null>;
interface Options { body?: unknown; query?: Query; idempotencyKey?: string }

async function request<T>(method: string, path: string, opts: Options = {}): Promise<T> {
  const qs = new URLSearchParams();
  Object.entries(opts.query ?? {}).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') qs.set(k, String(v));
  });
  const url = `${BASE}${path}${qs.toString() ? `?${qs}` : ''}`;
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json';
  if (token) headers.Authorization = `Bearer ${token}`;
  if (opts.idempotencyKey) headers['Idempotency-Key'] = opts.idempotencyKey;

  let res: Response;
  try {
    res = await fetch(url, { method, headers, body: opts.body === undefined ? undefined : JSON.stringify(opts.body) });
  } catch {
    throw new ApiError(0, 'NETWORK_ERROR', '서버에 연결할 수 없습니다. 백엔드(8080)가 실행 중인지 확인하세요.');
  }
  const text = await res.text();
  let data: unknown = null;
  if (text) {
    try { data = JSON.parse(preserveMoneyLiterals(text)); } catch { data = null; }
  }
  if (!res.ok) {
    const err = (data ?? {}) as { code?: string; message?: string };
    const code = err.code ?? `HTTP_${res.status}`;
    // 로그인 요청 자체의 401 은 세션 만료가 아니다.
    if (res.status === 401 && code === 'UNAUTHORIZED' && !path.startsWith('/api/auth/login')) onUnauthorized?.();
    throw new ApiError(res.status, code, err.message ?? `요청이 실패했습니다. (HTTP ${res.status})`);
  }
  return data as T;
}

// 금액은 부동소수 변환 없이 숫자 문자열 그대로 보낸다(백엔드 BigDecimal 이 문자열을 그대로 파싱).
export const api = {
  signup: (b: { email: string; password: string; name: string }) => request<User>('POST', '/api/auth/signup', { body: b }),
  login: (b: { email: string; password: string }) => request<LoginResponse>('POST', '/api/auth/login', { body: b }),
  me: () => request<User>('GET', '/api/auth/me'),

  listAccounts: () => request<Account[]>('GET', '/api/accounts'),
  createAccount: (b: { pin: string; perTxLimit?: string; dailyLimit?: string }) =>
    request<Account>('POST', '/api/accounts', { body: b }),
  closeAccount: (id: number) => request<Account>('PATCH', `/api/accounts/${id}/close`),

  deposit: (key: string, b: { accountId: number; amount: string; description?: string }) =>
    request<TransactionResponse>('POST', '/api/transactions/deposit', { body: b, idempotencyKey: key }),
  withdraw: (key: string, b: { accountId: number; amount: string; pin: string; description?: string }) =>
    request<TransactionResponse>('POST', '/api/transactions/withdraw', { body: b, idempotencyKey: key }),
  transfer: (key: string, b: { fromAccountId: number; toAccountNumber: string; amount: string; pin: string; description?: string }) =>
    request<TransactionResponse>('POST', '/api/transactions/transfer', { body: b, idempotencyKey: key }),

  history: (accountId: number, q: { type?: TransactionType | ''; entryType?: EntryType | ''; fromDate?: string; toDate?: string; page: number; size: number }) =>
    request<Page<HistoryItem>>('GET', `/api/accounts/${accountId}/transactions`, { query: q }),
  myAuditLogs: (page: number, size: number) => request<Page<AuditLog>>('GET', '/api/audit-logs/me', { query: { page, size } }),

  createScheduled: (key: string, b: { fromAccountId: number; toAccountNumber: string; amount: string; pin: string; scheduledAt: string; description?: string }) =>
    request<ScheduledTransfer>('POST', '/api/scheduled-transfers', { body: b, idempotencyKey: key }),
  listScheduled: (q: { status?: ScheduledStatus | ''; page: number; size: number }) =>
    request<Page<ScheduledTransfer>>('GET', '/api/scheduled-transfers', { query: q }),
  cancelScheduled: (id: number) => request<ScheduledTransfer>('POST', `/api/scheduled-transfers/${id}/cancel`),

  runReconciliation: () => request<ReconciliationRun>('POST', '/api/admin/reconciliations/run'),
  listReconciliations: (page: number, size: number) =>
    request<Page<ReconciliationSummary>>('GET', '/api/admin/reconciliations', { query: { page, size } }),
  getReconciliation: (id: number) => request<ReconciliationRun>('GET', `/api/admin/reconciliations/${id}`),
  executeDue: () => request<ScheduledExecutionSummary>('POST', '/api/admin/scheduled-transfers/execute-due'),
  unlockAccount: (id: number) => request<UnlockResponse>('POST', `/api/admin/accounts/${id}/unlock`),
  adminAuditLogs: (q: { actionType?: string; result?: AuditResult | ''; actorUserId?: string; page: number; size: number }) =>
    request<Page<AuditLog>>('GET', '/api/admin/audit-logs', { query: q }),
};
