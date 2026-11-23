import { useCallback, useState, type ReactNode } from 'react';
import { newIdempotencyKey } from '../api';
import { ACCOUNT_STATUS_LABEL, formatWon, type BannerMessage } from '../format';
import type { Account } from '../types';

export function Banner({ msg, onClose }: { msg: BannerMessage | null; onClose?: () => void }) {
  if (!msg) return null;
  return (
    <div className={`banner banner-${msg.kind}`} role={msg.kind === 'error' ? 'alert' : 'status'}>
      <div>
        <strong>{msg.kind === 'error' ? '처리 실패' : msg.kind === 'success' ? '처리 완료' : '안내'}</strong>
        {msg.code && <span className="banner-code">{msg.code}</span>}
        <div>{msg.text}</div>
        {msg.detail && <div className="banner-detail">서버 메시지: {msg.detail}</div>}
      </div>
      {onClose && <button className="banner-close" onClick={onClose} aria-label="닫기">×</button>}
    </div>
  );
}

const BADGE_CLASS: Record<string, string> = {
  ACTIVE: 'ok', COMPLETED: 'ok', PASSED: 'ok', SUCCESS: 'ok',
  LOCKED: 'warn', PENDING: 'info', PROCESSING: 'info', MISMATCH_FOUND: 'bad', FAILED: 'bad', FAILURE: 'bad',
  CLOSED: 'muted', CANCELED: 'muted',
};
export function StatusBadge({ status, label }: { status: string; label?: string }) {
  return <span className={`badge badge-${BADGE_CLASS[status] ?? 'muted'}`}>{label ?? status}</span>;
}

export function Pager({ page, totalPages, totalElements, onChange }: {
  page: number; totalPages: number; totalElements: number; onChange: (p: number) => void;
}) {
  return (
    <div className="pager">
      <button className="btn btn-ghost" disabled={page <= 0} onClick={() => onChange(page - 1)}>이전</button>
      <span>{totalPages === 0 ? 0 : page + 1} / {totalPages} 페이지 · 총 {totalElements}건</span>
      <button className="btn btn-ghost" disabled={page + 1 >= totalPages} onClick={() => onChange(page + 1)}>다음</button>
    </div>
  );
}

export function Field({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return (
    <label className="field">
      <span className="field-label">{label}</span>
      {children}
      {hint && <span className="field-hint">{hint}</span>}
    </label>
  );
}

export function Card({ title, actions, children }: { title?: ReactNode; actions?: ReactNode; children: ReactNode }) {
  return (
    <section className="card">
      {(title || actions) && (
        <div className="card-head">
          {title && <h3>{title}</h3>}
          {actions && <div className="card-actions">{actions}</div>}
        </div>
      )}
      {children}
    </section>
  );
}

export function Empty({ children }: { children: ReactNode }) {
  return <div className="empty">{children}</div>;
}

/**
 * 금전 요청 1건 = 멱등키 1개.
 * 실패(키 미소비)나 네트워크 오류 시에는 같은 키를 유지해 재시도가 중복 실행되지 않게 하고, 성공 후에만 새 키를 발급한다.
 */
export function useIdempotencyKey() {
  const [key, setKey] = useState(newIdempotencyKey);
  const renew = useCallback(() => setKey(newIdempotencyKey()), []);
  return { key, renew };
}

export function IdempotencyKeyView({ value, onRenew }: { value: string; onRenew: () => void }) {
  const [show, setShow] = useState(false);
  return (
    <div className="idem">
      <label className="inline">
        <input type="checkbox" checked={show} onChange={(e) => setShow(e.target.checked)} /> 멱등키(Idempotency-Key) 표시
      </label>
      {show && (
        <span className="idem-value">
          <code>{value}</code>
          <button type="button" className="btn btn-ghost btn-sm" onClick={onRenew}>새 키 발급</button>
        </span>
      )}
    </div>
  );
}

export function accountOptionLabel(a: Account) {
  return `${a.accountNumber} · ${formatWon(a.balance)} · ${ACCOUNT_STATUS_LABEL[a.status]}`;
}

export function AccountSelect({ accounts, value, onChange, onlyActive }: {
  accounts: Account[]; value: number | ''; onChange: (id: number | '') => void; onlyActive?: boolean;
}) {
  const list = onlyActive ? accounts.filter((a) => a.status === 'ACTIVE') : accounts;
  return (
    <select value={value} onChange={(e) => onChange(e.target.value ? Number(e.target.value) : '')}>
      <option value="">계좌를 선택하세요</option>
      {list.map((a) => <option key={a.id} value={a.id}>{accountOptionLabel(a)}</option>)}
    </select>
  );
}

export function PinInput({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  return (
    <input type="password" inputMode="numeric" autoComplete="off" maxLength={6} placeholder="숫자 4~6자리"
      value={value} onChange={(e) => onChange(e.target.value.replace(/\D/g, ''))} />
  );
}
