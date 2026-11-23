import { useState, type FormEvent } from 'react';
import { api } from '../api';
import { ACCOUNT_STATUS_LABEL, formatDateTime, formatWon, toBigIntMoney, normalizeAmount, toErrorBanner, type BannerMessage } from '../format';
import type { Account } from '../types';
import { Banner, Card, Empty, Field, PinInput, StatusBadge } from './common';

export function AccountsPanel({ accounts, loading, reload }: { accounts: Account[]; loading: boolean; reload: () => Promise<void> }) {
  const [msg, setMsg] = useState<BannerMessage | null>(null);
  const [showCreate, setShowCreate] = useState(false);
  const [pin, setPin] = useState('');
  const [pin2, setPin2] = useState('');
  const [perTx, setPerTx] = useState('');
  const [daily, setDaily] = useState('');
  const [busy, setBusy] = useState(false);
  const [confirmCloseId, setConfirmCloseId] = useState<number | null>(null);

  // 금액 합산은 부동소수 오차가 없도록 BigInt 로 계산한다.
  const total = accounts.filter((a) => a.status !== 'CLOSED').reduce((s, a) => s + (toBigIntMoney(a.balance) ?? 0n), 0n);
  // 잔액 0원 판정도 BigInt 로 한다("0" 문자열 비교·Number 변환 금지).
  const isZero = (a: Account) => toBigIntMoney(a.balance) === 0n;

  async function create(e: FormEvent) {
    e.preventDefault();
    if (!/^\d{4,6}$/.test(pin)) return setMsg({ kind: 'error', text: 'PIN 은 숫자 4~6자리여야 합니다.' });
    if (pin !== pin2) return setMsg({ kind: 'error', text: 'PIN 확인 값이 일치하지 않습니다.' });
    const p = perTx.trim() ? normalizeAmount(perTx) : undefined;
    const d = daily.trim() ? normalizeAmount(daily) : undefined;
    if (p === null || d === null) return setMsg({ kind: 'error', text: '한도는 1원 이상의 정수로 입력하거나 비워 두세요(기본값 적용).' });
    setBusy(true);
    try {
      const acc = await api.createAccount({ pin, perTxLimit: p, dailyLimit: d });
      setMsg({ kind: 'success', text: `신규 원화 계좌 ${acc.accountNumber} 가 개설되었습니다.` });
      setPin(''); setPin2(''); setPerTx(''); setDaily(''); setShowCreate(false);
      await reload();
    } catch (err) {
      setMsg(toErrorBanner(err));
    } finally {
      setBusy(false);
    }
  }

  async function close(id: number) {
    setBusy(true);
    try {
      const acc = await api.closeAccount(id);
      setMsg({ kind: 'success', text: `계좌 ${acc.maskedAccountNumber} 가 해지되었습니다.` });
      await reload();
    } catch (err) {
      setMsg(toErrorBanner(err));
    } finally {
      setBusy(false);
      setConfirmCloseId(null);
    }
  }

  return (
    <div className="stack">
      <Banner msg={msg} onClose={() => setMsg(null)} />
      <div className="summary">
        <div>
          <div className="summary-label">총 보유 잔액 (해지 계좌 제외)</div>
          <div className="summary-value">{formatWon(total)}</div>
        </div>
        <div className="row">
          <button className="btn btn-ghost" onClick={() => void reload()} disabled={loading}>새로고침</button>
          <button className="btn btn-primary" onClick={() => setShowCreate((v) => !v)}>
            {showCreate ? '개설 취소' : '+ 신규 원화 계좌 개설'}
          </button>
        </div>
      </div>

      {showCreate && (
        <Card title="신규 원화 계좌 개설">
          <form onSubmit={create} className="form grid2">
            <Field label="계좌 비밀번호(PIN)" hint="숫자 4~6자리, 서버에는 BCrypt 해시로만 저장됩니다.">
              <PinInput value={pin} onChange={setPin} />
            </Field>
            <Field label="PIN 확인"><PinInput value={pin2} onChange={setPin2} /></Field>
            <Field label="1회 이체 한도(원)" hint="비워 두면 기본값">
              <input inputMode="numeric" value={perTx} onChange={(e) => setPerTx(e.target.value)} placeholder="예: 1000000" />
            </Field>
            <Field label="1일 이체 한도(원)" hint="비워 두면 기본값">
              <input inputMode="numeric" value={daily} onChange={(e) => setDaily(e.target.value)} placeholder="예: 5000000" />
            </Field>
            <div className="span2"><button className="btn btn-primary" disabled={busy}>{busy ? '개설 중…' : '계좌 개설'}</button></div>
          </form>
        </Card>
      )}

      {loading && accounts.length === 0 ? <Empty>계좌를 불러오는 중…</Empty> : accounts.length === 0 ? (
        <Empty>보유 계좌가 없습니다. 신규 원화 계좌를 개설해 보세요.</Empty>
      ) : (
        <div className="acc-grid">
          {accounts.map((a) => (
            <div key={a.id} className={`acc-card ${a.status !== 'ACTIVE' ? 'acc-inactive' : ''}`}>
              <div className="acc-top">
                <span className="acc-name">CoreBank 입출금통장</span>
                <StatusBadge status={a.status} label={`${a.status} · ${ACCOUNT_STATUS_LABEL[a.status]}`} />
              </div>
              <div className="acc-number">{a.accountNumber}</div>
              <div className="acc-sub">마스킹: {a.maskedAccountNumber} · 계좌 ID {a.id}</div>
              <div className="acc-balance">{formatWon(a.balance)}</div>
              <dl className="acc-limits">
                <div><dt>1회 한도</dt><dd>{formatWon(a.perTxLimit)}</dd></div>
                <div><dt>1일 한도</dt><dd>{formatWon(a.dailyLimit)}</dd></div>
                <div><dt>개설일</dt><dd>{formatDateTime(a.createdAt).slice(0, 10)}</dd></div>
              </dl>
              {a.status !== 'CLOSED' && (
                <div className="acc-actions">
                  {confirmCloseId === a.id ? (
                    <>
                      <span className="warn-text">정말 해지할까요?</span>
                      <button className="btn btn-danger btn-sm" disabled={busy} onClick={() => void close(a.id)}>해지 확정</button>
                      <button className="btn btn-ghost btn-sm" onClick={() => setConfirmCloseId(null)}>취소</button>
                    </>
                  ) : (
                    <button className="btn btn-ghost btn-sm" disabled={!isZero(a)}
                      title={!isZero(a) ? '잔액이 0원인 계좌만 해지할 수 있습니다.' : undefined}
                      onClick={() => setConfirmCloseId(a.id)}>
                      계좌 해지{!isZero(a) ? ' (잔액 0원만 가능)' : ''}
                    </button>
                  )}
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
