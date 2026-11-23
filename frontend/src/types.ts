// 백엔드 DTO 와 1:1 대응하는 타입.
/** 원화 금액: api.ts 가 JSON 숫자 리터럴을 문자열로 보존한 값(예: "9223372036854775807"). 계산은 BigInt 로만 한다. */
export type Money = string;
export type Role = 'USER' | 'ADMIN';
export type AccountStatus = 'ACTIVE' | 'LOCKED' | 'CLOSED';
export type TransactionType = 'DEPOSIT' | 'WITHDRAWAL' | 'TRANSFER';
export type EntryType = 'DEBIT' | 'CREDIT';
export type ScheduledStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED' | 'CANCELED';
export type AuditResult = 'SUCCESS' | 'FAILURE';
export type ReconciliationStatus = 'PASSED' | 'MISMATCH_FOUND';

export interface User { id: number; email: string; name: string; role: Role }
export interface LoginResponse { accessToken: string; user: User }

export interface Account {
  id: number;
  accountNumber: string;
  maskedAccountNumber: string;
  balance: Money;
  status: AccountStatus;
  perTxLimit: Money;
  dailyLimit: Money;
  createdAt: string;
}

export interface Page<T> { content: T[]; page: number; size: number; totalElements: number; totalPages: number }

export interface TransactionResponse {
  transactionId: number;
  txType: TransactionType;
  amount: Money;
  fromAccountId?: number;
  toAccountNumberMasked?: string;
  balanceAfter: Money;
  description?: string;
  createdAt: string;
}

export interface HistoryItem {
  transactionId: number;
  txType: TransactionType;
  entryType: EntryType;
  amount: Money;
  balanceAfter: Money;
  counterpartyMasked: string | null;
  description: string | null;
  createdAt: string;
}

export interface ScheduledTransfer {
  id: number;
  fromAccountId: number;
  toAccountNumberMasked: string;
  amount: Money;
  scheduledAt: string;
  status: ScheduledStatus;
  failureReason: string | null;
  executedTransactionId: number | null;
  createdAt: string;
  executedAt: string | null;
}

export interface AuditLog {
  id: number;
  actorUserId: number | null;
  actionType: string;
  targetType: string | null;
  targetId: string | null;
  result: AuditResult;
  maskedSummary: string | null;
  clientIp: string | null;
  createdAt: string;
}

export interface ReconciliationMismatch {
  type: string;
  description: string;
  transactionId?: number;
  accountId?: number;
  accountNumber?: string;
  expected?: Money;
  actual?: Money;
  creditTotal?: Money;
  debitTotal?: Money;
  difference?: Money;
}

export interface ReconciliationSummary {
  id: number;
  triggerType: 'MANUAL' | 'SCHEDULED';
  status: ReconciliationStatus;
  checkedAccounts: number;
  checkedTransactions: number;
  mismatchCount: number;
  executedBy: number | null;
  executedAt: string;
}
export interface ReconciliationRun extends ReconciliationSummary { details: ReconciliationMismatch[] }

export interface ScheduledExecutionSummary { due: number; completed: number; failed: number; skipped: number }
export interface UnlockResponse { id: number; maskedAccountNumber: string; status: AccountStatus; pinFailCount: number }
