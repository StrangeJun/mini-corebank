import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { api } from '../api';
import { formatDateTime, formatWon, normalizeAmount, SCHEDULED_STATUS_LABEL, toErrorBanner, type BannerMessage } from '../format';
import type { Account, Page, ScheduledStatus, ScheduledTransfer } from '../types';
import { AccountSelect, Banner, Card, Empty, Field, IdempotencyKeyView, Pager, PinInput, StatusBadge, useIdempotencyKey } from './common';

const STATUSES: ScheduledStatus[] = ['PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELED'];
const PAGE_SIZE = 10;

/** datetime-local 기본값: 지금부터 5분 뒤(로컬 시각). */
function defaultScheduledAt() {
  const d = new Date(Date.now() + 5 * 60 * 1000);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export function ScheduledPanel({ accounts, reload }: { accounts: Account[]; reload: () => Promise<void> }) {
  const [fromId, setFromId] = useState<number | ''>('');
  const [toAccountNumber, setToAccountNumber] = useState('');
  const [amount, setAmount] = useState('');
  const [scheduledAt, setScheduledAt] = useState(defaultScheduledAt);
  const [pin, setPin] = useState('');
  const [memo, setMemo] = useState('');
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<BannerMessage | null>(null);
  const { key, renew } = useIdempotencyKey();

  const [status, setStatus] = useState<ScheduledStatus | ''>('');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page<ScheduledTransfer> | null>(null);
  const [cancelId, setCancelId] = useState<number | null>(null);

  const load = useCallback(async () => {
    try {
      setData(await api.listScheduled({ status, page, size: PAGE_SIZE }));
    } catch (err) {
      setMsg(toErrorBanner(err));
    }
  }, [status, page]);
  useEffect(() => { void load(); }, [load]);

  const accNo = (id: number) => accounts.find((a) => a.id === id)?.maskedAccountNumber ?? `ID ${id}`;

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (busy) return;
    setMsg(null);
    if (fromId === '') return setMsg({ kind: 'error', text: '출금 계좌를 선택해 주세요.' });
    if (!toAccountNumber.trim()) return setMsg({ kind: 'error', text: '입금 계좌번호를 입력해 주세요.' });
    const amt = normalizeAmount(amount);
    if (!amt) return setMsg({ kind: 'error', code: 'INVALID_AMOUNT', text: '금액은 1원 이상의 정수(원 단위)로 입력해 주세요.' });
    if (!scheduledAt || new Date(scheduledAt).getTime() <= Date.now()) {
      return setMsg({ kind: 'error', text: '예약 일시는 현재 이후여야 합니다.' });
    }
    if (!/^\d{4,6}$/.test(pin)) return setMsg({ kind: 'error', text: '계좌 비밀번호(PIN) 4~6자리를 입력해 주세요.' });
    setBusy(true);
    try {
      const r = await api.createScheduled(key, {
        fromAccountId: fromId,
        toAccountNumber: toAccountNumber.trim(),
        amount: amt,
        pin,
        scheduledAt: scheduledAt.length === 16 ? `${scheduledAt}:00` : scheduledAt,
        description: memo.trim() || undefined,
      });
      setMsg({ kind: 'success', text: `예약 이체 #${r.id} 등록 완료 · ${formatDateTime(r.scheduledAt)} 에 ${formatWon(r.amount)} 이체 예정` });
      setAmount(''); setPin(''); setMemo(''); setScheduledAt(defaultScheduledAt());
      renew();
      if (page === 0) await load(); else setPage(0);
      await reload(); // PIN 오류로 계좌 잠금 상태가 바뀌었을 수 있다
    } catch (err) {
      setMsg(toErrorBanner(err));
      await reload();
    } finally {
      setBusy(false);
    }
  }

  async function cancel(id: number) {
    setBusy(true);
    try {
      await api.cancelScheduled(id);
      setMsg({ kind: 'success', text: `예약 이체 #${id} 가 취소되었습니다.` });
      await load();
    } catch (err) {
      setMsg(toErrorBanner(err));
    } finally {
      setBusy(false);
      setCancelId(null);
    }
  }

  return (
    <div className="stack">
      <Banner msg={msg} onClose={() => setMsg(null)} />
      <Card title="예약 이체 등록">
        <form className="form grid2" onSubmit={submit}>
          <Field label="출금 계좌"><AccountSelect accounts={accounts} value={fromId} onChange={setFromId} /></Field>
          <Field label="입금 계좌번호"><input value={toAccountNumber} maxLength={30} onChange={(e) => setToAccountNumber(e.target.value)} placeholder="110-123-456789" /></Field>
          <Field label="금액(원)" hint="등록 시 1회 한도 검증, 실행 시 잔액·1일 한도 재검증">
            <input inputMode="numeric" value={amount} onChange={(e) => setAmount(e.target.value)} placeholder="예: 30000" />
          </Field>
          <Field label="예약 일시"><input type="datetime-local" value={scheduledAt} onChange={(e) => setScheduledAt(e.target.value)} /></Field>
          <Field label="계좌 비밀번호(PIN)"><PinInput value={pin} onChange={setPin} /></Field>
          <Field label="메모" hint="요청 해시에만 반영되며 실행 거래 적요는 '예약이체 #id'로 기록됩니다.">
            <input value={memo} maxLength={255} onChange={(e) => setMemo(e.target.value)} placeholder="선택" />
          </Field>
          <div className="span2 row">
            <button className="btn btn-primary" disabled={busy}>{busy ? '처리 중…' : '예약 등록'}</button>
            <IdempotencyKeyView value={key} onRenew={renew} />
          </div>
        </form>
      </Card>

      <Card title="내 예약 이체 목록" actions={
        <>
          <select value={status} onChange={(e) => { setStatus(e.target.value as ScheduledStatus | ''); setPage(0); }}>
            <option value="">전체 상태</option>
            {STATUSES.map((s) => <option key={s} value={s}>{s} · {SCHEDULED_STATUS_LABEL[s]}</option>)}
          </select>
          <button className="btn btn-ghost btn-sm" onClick={() => void load()}>새로고침</button>
        </>
      }>
        {!data ? <Empty>불러오는 중…</Empty> : data.content.length === 0 ? <Empty>예약 이체가 없습니다.</Empty> : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr><th>번호</th><th>출금 계좌</th><th>입금 계좌</th><th className="num">금액</th><th>예약 일시</th><th>상태</th><th>실행/실패 정보</th><th></th></tr>
              </thead>
              <tbody>
                {data.content.map((s) => (
                  <tr key={s.id}>
                    <td>#{s.id}</td>
                    <td>{accNo(s.fromAccountId)}</td>
                    <td>{s.toAccountNumberMasked}</td>
                    <td className="num">{formatWon(s.amount)}</td>
                    <td>{formatDateTime(s.scheduledAt)}</td>
                    <td><StatusBadge status={s.status} label={`${s.status} · ${SCHEDULED_STATUS_LABEL[s.status]}`} /></td>
                    <td className="small">
                      {s.status === 'FAILED' && <span className="bad-text">실패사유: {s.failureReason}</span>}
                      {s.status === 'COMPLETED' && <>거래 #{s.executedTransactionId} · {formatDateTime(s.executedAt)}</>}
                      {s.status !== 'FAILED' && s.status !== 'COMPLETED' && '-'}
                    </td>
                    <td>
                      {s.status === 'PENDING' && (cancelId === s.id ? (
                        <span className="row">
                          <button className="btn btn-danger btn-sm" disabled={busy} onClick={() => void cancel(s.id)}>취소 확정</button>
                          <button className="btn btn-ghost btn-sm" onClick={() => setCancelId(null)}>닫기</button>
                        </span>
                      ) : <button className="btn btn-ghost btn-sm" onClick={() => setCancelId(s.id)}>취소</button>)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {data && <Pager page={data.page} totalPages={data.totalPages} totalElements={data.totalElements} onChange={setPage} />}
      </Card>
    </div>
  );
}
