import { useState, type FormEvent } from 'react';
import { api } from '../api';
import { formatDateTime, formatWon, normalizeAmount, toErrorBanner, TX_TYPE_LABEL, type BannerMessage } from '../format';
import type { Account, TransactionResponse } from '../types';
import { AccountSelect, Banner, Card, Field, IdempotencyKeyView, PinInput, useIdempotencyKey } from './common';

type Mode = 'deposit' | 'withdraw' | 'transfer';
const MODE_LABEL: Record<Mode, string> = { deposit: '입금', withdraw: '출금', transfer: '즉시 이체' };

interface LastRequest { mode: Mode; key: string; body: Record<string, unknown> }

export function TransactionsPanel({ accounts, reload }: { accounts: Account[]; reload: () => Promise<void> }) {
  const [mode, setMode] = useState<Mode>('deposit');
  const [accountId, setAccountId] = useState<number | ''>('');
  const [toAccountNumber, setToAccountNumber] = useState('');
  const [amount, setAmount] = useState('');
  const [pin, setPin] = useState('');
  const [description, setDescription] = useState('');
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<BannerMessage | null>(null);
  const [result, setResult] = useState<TransactionResponse | null>(null);
  const [last, setLast] = useState<LastRequest | null>(null);
  const { key, renew } = useIdempotencyKey();

  function send(m: Mode, k: string, body: Record<string, unknown>): Promise<TransactionResponse> {
    if (m === 'deposit') return api.deposit(k, body as Parameters<typeof api.deposit>[1]);
    if (m === 'withdraw') return api.withdraw(k, body as Parameters<typeof api.withdraw>[1]);
    return api.transfer(k, body as Parameters<typeof api.transfer>[1]);
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (busy) return; // 중복 클릭 방지
    setMsg(null);
    if (accountId === '') return setMsg({ kind: 'error', text: '계좌를 선택해 주세요.' });
    const amt = normalizeAmount(amount);
    if (!amt) return setMsg({ kind: 'error', code: 'INVALID_AMOUNT', text: '금액은 1원 이상의 정수(원 단위)로 입력해 주세요.' });
    if (mode !== 'deposit' && !/^\d{4,6}$/.test(pin)) return setMsg({ kind: 'error', text: '계좌 비밀번호(PIN) 4~6자리를 입력해 주세요.' });
    if (mode === 'transfer' && !toAccountNumber.trim()) return setMsg({ kind: 'error', text: '입금 계좌번호를 입력해 주세요.' });

    const desc = description.trim() || undefined;
    const body: Record<string, unknown> =
      mode === 'deposit' ? { accountId, amount: amt, description: desc }
        : mode === 'withdraw' ? { accountId, amount: amt, pin, description: desc }
          : { fromAccountId: accountId, toAccountNumber: toAccountNumber.trim(), amount: amt, pin, description: desc };

    setBusy(true);
    try {
      const r = await send(mode, key, body);
      setResult(r);
      setLast({ mode, key, body });
      setMsg({ kind: 'success', text: `${MODE_LABEL[mode]} ${formatWon(r.amount)} 처리 완료 · 거래 후 잔액 ${formatWon(r.balanceAfter)}` });
      setAmount(''); setPin(''); setDescription('');
      renew(); // 성공한 요청의 키는 소비됐으므로 다음 요청용 새 키 발급
      await reload();
    } catch (err) {
      // 실패 시 서버는 키를 소비하지 않는다 → 같은 키를 유지해 재시도
      setMsg(toErrorBanner(err));
    } finally {
      setBusy(false);
    }
  }

  async function replay() {
    if (!last || busy) return;
    setBusy(true);
    try {
      const r = await send(last.mode, last.key, last.body);
      setResult(r);
      setMsg({
        kind: 'info',
        text: `같은 멱등키로 재전송 → 서버가 기존 거래 #${r.transactionId} 결과를 그대로 돌려줬습니다(중복 출금 없음).`,
      });
      await reload();
    } catch (err) {
      setMsg(toErrorBanner(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="stack">
      <div className="seg seg-wide">
        {(Object.keys(MODE_LABEL) as Mode[]).map((m) => (
          <button key={m} className={mode === m ? 'on' : ''} onClick={() => { setMode(m); setMsg(null); }}>{MODE_LABEL[m]}</button>
        ))}
      </div>
      <Banner msg={msg} onClose={() => setMsg(null)} />
      <Card title={MODE_LABEL[mode]}>
        <form className="form grid2" onSubmit={submit}>
          <Field label={mode === 'deposit' ? '입금 계좌' : '출금 계좌'}>
            <AccountSelect accounts={accounts} value={accountId} onChange={setAccountId} onlyActive={mode === 'deposit'} />
          </Field>
          {mode === 'transfer' && (
            <Field label="입금 계좌번호" hint="예: 110-123-456789 (다른 고객 계좌도 가능)">
              <input value={toAccountNumber} onChange={(e) => setToAccountNumber(e.target.value)} maxLength={30} />
            </Field>
          )}
          <Field label="금액(원)" hint={normalizeAmount(amount) ? formatWon(normalizeAmount(amount)) : '원 단위 정수'}>
            <input inputMode="numeric" value={amount} onChange={(e) => setAmount(e.target.value)} placeholder="예: 50000" />
          </Field>
          {mode !== 'deposit' && (
            <Field label="계좌 비밀번호(PIN)" hint="5회 연속 오류 시 계좌 거래정지(LOCKED)">
              <PinInput value={pin} onChange={setPin} />
            </Field>
          )}
          <Field label="적요(메모)">
            <input value={description} maxLength={255} onChange={(e) => setDescription(e.target.value)} placeholder="선택" />
          </Field>
          <div className="span2 row">
            <button className="btn btn-primary" disabled={busy}>{busy ? '처리 중…' : `${MODE_LABEL[mode]} 실행`}</button>
            <IdempotencyKeyView value={key} onRenew={renew} />
          </div>
        </form>
      </Card>

      {result && (
        <Card title="최근 거래 결과" actions={last && (
          <button className="btn btn-ghost btn-sm" disabled={busy} onClick={() => void replay()}
            title="직전 성공 요청을 같은 Idempotency-Key 로 다시 보내 중복 실행이 차단되는지 확인합니다.">
            같은 멱등키로 재전송(멱등성 시연)
          </button>
        )}>
          <dl className="kv">
            <div><dt>거래번호</dt><dd>#{result.transactionId}</dd></div>
            <div><dt>구분</dt><dd>{TX_TYPE_LABEL[result.txType]}</dd></div>
            <div><dt>금액</dt><dd>{formatWon(result.amount)}</dd></div>
            <div><dt>거래 후 잔액</dt><dd>{formatWon(result.balanceAfter)}</dd></div>
            {result.toAccountNumberMasked && <div><dt>입금 계좌</dt><dd>{result.toAccountNumberMasked}</dd></div>}
            <div><dt>일시</dt><dd>{formatDateTime(result.createdAt)}</dd></div>
            {last && <div><dt>사용한 멱등키</dt><dd><code>{last.key}</code></dd></div>}
          </dl>
        </Card>
      )}
    </div>
  );
}
