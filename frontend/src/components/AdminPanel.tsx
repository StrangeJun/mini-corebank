import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { api } from '../api';
import { formatDateTime, formatWon, toErrorBanner, type BannerMessage } from '../format';
import type { AuditLog, AuditResult, Page, ReconciliationRun, ReconciliationSummary, ScheduledExecutionSummary } from '../types';
import { AuditTable } from './AuditTable';
import { Banner, Card, Empty, Field, Pager, StatusBadge } from './common';

const MISMATCH_LABEL: Record<string, string> = {
  TX_BALANCE_MISMATCH: '① 거래별 차변합=대변합=거래금액',
  ACCOUNT_LEDGER_MISMATCH: '② 계좌 잔액 = 원장(대변−차변)',
  ZERO_SUM_MISMATCH: '③ 전체 제로섬(잔액합 0, 차변합=대변합)',
};

export function AdminPanel({ onAccountsChanged }: { onAccountsChanged: () => Promise<void> }) {
  return (
    <div className="stack">
      <div className="admin-note">운영/감사 전용 화면 — <code>/api/admin/**</code> 는 ADMIN 권한 JWT 로만 호출됩니다.</div>
      <ReconciliationSection />
      <div className="grid2-cards">
        <ExecuteDueSection onDone={onAccountsChanged} />
        <UnlockSection onDone={onAccountsChanged} />
      </div>
      <AdminAuditSection />
    </div>
  );
}

function ReconciliationSection() {
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<BannerMessage | null>(null);
  const [page, setPage] = useState(0);
  const [list, setList] = useState<Page<ReconciliationSummary> | null>(null);
  const [detail, setDetail] = useState<ReconciliationRun | null>(null);

  const load = useCallback(async () => {
    try { setList(await api.listReconciliations(page, 10)); } catch (err) { setMsg(toErrorBanner(err)); }
  }, [page]);
  useEffect(() => { void load(); }, [load]);

  async function run() {
    setBusy(true);
    setMsg(null);
    try {
      const r = await api.runReconciliation();
      setDetail(r);
      setMsg(r.status === 'PASSED'
        ? { kind: 'success', text: `대사 #${r.id} 통과: 계좌 ${r.checkedAccounts}개 · 거래 ${r.checkedTransactions}건 모두 3대 불변식을 만족합니다.` }
        : { kind: 'error', code: 'MISMATCH_FOUND', text: `대사 #${r.id} 에서 불일치 ${r.mismatchCount}건이 발견되었습니다. 아래 상세를 확인하세요.` });
      if (page === 0) await load(); else setPage(0);
    } catch (err) {
      setMsg(toErrorBanner(err));
    } finally {
      setBusy(false);
    }
  }

  async function open(id: number) {
    try { setDetail(await api.getReconciliation(id)); } catch (err) { setMsg(toErrorBanner(err)); }
  }

  return (
    <Card title="원장-잔액 정합성 대사 (Reconciliation)" actions={
      <button className="btn btn-primary" disabled={busy} onClick={() => void run()}>{busy ? '대사 실행 중…' : '지금 정합성 대사 실행'}</button>
    }>
      <Banner msg={msg} onClose={() => setMsg(null)} />
      <ol className="invariants">
        {Object.values(MISMATCH_LABEL).map((l) => <li key={l}>{l.slice(2)}</li>)}
      </ol>
      {detail && (
        <div className="recon-detail">
          <div className="row between">
            <strong>대사 #{detail.id} 결과</strong>
            <button className="btn btn-ghost btn-sm" onClick={() => setDetail(null)}>닫기</button>
          </div>
          <div className="stats">
            <div><span>결과</span><StatusBadge status={detail.status} /></div>
            <div><span>검사 계좌 수</span><b>{detail.checkedAccounts}</b></div>
            <div><span>검사 거래 수</span><b>{detail.checkedTransactions}</b></div>
            <div><span>불일치 건수</span><b className={detail.mismatchCount ? 'bad-text' : ''}>{detail.mismatchCount}</b></div>
            <div><span>실행 방식</span><b>{detail.triggerType}</b></div>
            <div><span>실행 일시</span><b>{formatDateTime(detail.executedAt)}</b></div>
          </div>
          {detail.details.length > 0 && (
            <div className="table-wrap">
              <table>
                <thead><tr><th>불변식</th><th>설명</th><th>거래/계좌</th><th className="num">기대값</th><th className="num">실제값</th><th className="num">대변합</th><th className="num">차변합</th><th className="num">차이</th></tr></thead>
                <tbody>
                  {detail.details.map((d, i) => (
                    <tr key={i}>
                      <td className="small">{MISMATCH_LABEL[d.type] ?? d.type}</td>
                      <td className="small wrap">{d.description}</td>
                      <td className="small">{d.transactionId ? `거래 #${d.transactionId}` : d.accountId ? `계좌 ${d.accountNumber ?? ''} (ID ${d.accountId})` : '-'}</td>
                      <td className="num">{formatWon(d.expected)}</td>
                      <td className="num">{formatWon(d.actual)}</td>
                      <td className="num">{formatWon(d.creditTotal)}</td>
                      <td className="num">{formatWon(d.debitTotal)}</td>
                      <td className="num bad-text">{formatWon(d.difference)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {detail.mismatchCount > detail.details.length && <p className="muted small">상세는 최대 {detail.details.length}건까지만 저장됩니다.</p>}
            </div>
          )}
        </div>
      )}
      <h4>실행 이력</h4>
      {!list ? <Empty>불러오는 중…</Empty> : list.content.length === 0 ? <Empty>대사 실행 이력이 없습니다.</Empty> : (
        <div className="table-wrap">
          <table>
            <thead><tr><th>번호</th><th>실행 일시</th><th>방식</th><th>결과</th><th className="num">검사 계좌</th><th className="num">검사 거래</th><th className="num">불일치</th><th>실행자</th><th></th></tr></thead>
            <tbody>
              {list.content.map((r) => (
                <tr key={r.id} className={detail?.id === r.id ? 'selected' : ''}>
                  <td>#{r.id}</td>
                  <td>{formatDateTime(r.executedAt)}</td>
                  <td>{r.triggerType === 'MANUAL' ? '수동' : '스케줄'}</td>
                  <td><StatusBadge status={r.status} /></td>
                  <td className="num">{r.checkedAccounts}</td>
                  <td className="num">{r.checkedTransactions}</td>
                  <td className="num">{r.mismatchCount}</td>
                  <td>{r.executedBy ?? '시스템'}</td>
                  <td><button className="btn btn-ghost btn-sm" onClick={() => void open(r.id)}>상세</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {list && <Pager page={list.page} totalPages={list.totalPages} totalElements={list.totalElements} onChange={setPage} />}
    </Card>
  );
}

function ExecuteDueSection({ onDone }: { onDone: () => Promise<void> }) {
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<BannerMessage | null>(null);
  const [result, setResult] = useState<ScheduledExecutionSummary | null>(null);

  async function run() {
    setBusy(true);
    setMsg(null);
    try {
      const r = await api.executeDue();
      setResult(r);
      setMsg({ kind: r.failed > 0 ? 'info' : 'success', text: `도래 ${r.due}건 처리: 완료 ${r.completed} · 실패 ${r.failed} · 건너뜀 ${r.skipped}` });
      await onDone();
    } catch (err) {
      setMsg(toErrorBanner(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card title="예약 이체 도래 건 수동 배치">
      <p className="muted small">예약 일시가 지난 PENDING 건을 즉시 실행합니다(1회 최대 100건, 건별 독립 트랜잭션, 멱등키 SCHED-TX-id).</p>
      <Banner msg={msg} onClose={() => setMsg(null)} />
      <button className="btn btn-primary" disabled={busy} onClick={() => void run()}>{busy ? '실행 중…' : '도래 예약이체 즉시 실행'}</button>
      {result && (
        <div className="stats">
          <div><span>도래</span><b>{result.due}</b></div>
          <div><span>완료</span><b>{result.completed}</b></div>
          <div><span>실패</span><b className={result.failed ? 'bad-text' : ''}>{result.failed}</b></div>
          <div><span>건너뜀</span><b>{result.skipped}</b></div>
        </div>
      )}
    </Card>
  );
}

function UnlockSection({ onDone }: { onDone: () => Promise<void> }) {
  const [id, setId] = useState('');
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<BannerMessage | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!/^\d+$/.test(id)) return setMsg({ kind: 'error', text: '계좌 ID(숫자)를 입력해 주세요.' });
    setBusy(true);
    setMsg(null);
    try {
      const r = await api.unlockAccount(Number(id));
      setMsg({ kind: 'success', text: `계좌 ${r.maskedAccountNumber} (ID ${r.id}) 잠금 해제 → ${r.status}, PIN 오류 횟수 ${r.pinFailCount}` });
      setId('');
      await onDone();
    } catch (err) {
      setMsg(toErrorBanner(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card title="계좌 거래정지(LOCKED) 해제">
      <p className="muted small">PIN 5회 연속 오류로 거래정지된 계좌를 정상(ACTIVE)으로 되돌리고 오류 횟수를 0으로 초기화합니다. 계좌 ID 는 계좌 카드에 표시됩니다.</p>
      <Banner msg={msg} onClose={() => setMsg(null)} />
      <form className="row" onSubmit={submit}>
        <input inputMode="numeric" placeholder="계좌 ID" value={id} onChange={(e) => setId(e.target.value.replace(/\D/g, ''))} />
        <button className="btn btn-primary" disabled={busy}>{busy ? '처리 중…' : '잠금 해제'}</button>
      </form>
    </Card>
  );
}

function AdminAuditSection() {
  const [actionType, setActionType] = useState('');
  const [result, setResult] = useState<AuditResult | ''>('');
  const [actorUserId, setActorUserId] = useState('');
  const [applied, setApplied] = useState({ actionType: '', result: '' as AuditResult | '', actorUserId: '' });
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page<AuditLog> | null>(null);
  const [msg, setMsg] = useState<BannerMessage | null>(null);

  const load = useCallback(async () => {
    try {
      setData(await api.adminAuditLogs({ ...applied, actionType: applied.actionType.trim(), page, size: 20 }));
    } catch (err) {
      setMsg(toErrorBanner(err));
    }
  }, [applied, page]);
  useEffect(() => { void load(); }, [load]);

  function search(e: FormEvent) {
    e.preventDefault();
    setMsg(null);
    setApplied({ actionType, result, actorUserId });
    setPage(0);
  }

  return (
    <Card title="전체 감사 로그 조회">
      <Banner msg={msg} onClose={() => setMsg(null)} />
      <form className="filters" onSubmit={search}>
        <Field label="행위 유형(actionType)">
          <input list="audit-actions" value={actionType} onChange={(e) => setActionType(e.target.value)} placeholder="예: TRANSFER" />
          <datalist id="audit-actions">
            {['USER_SIGNUP', 'LOGIN_SUCCESS', 'LOGIN_FAIL', 'ACCOUNT_CREATE', 'ACCOUNT_CLOSE', 'DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'PIN_FAIL',
              'ACCOUNT_LOCKED', 'ACCOUNT_UNLOCKED', 'RECONCILIATION_RUN', 'SCHEDULED_TRANSFER_CREATE', 'SCHEDULED_TRANSFER_CANCEL', 'SCHEDULED_TRANSFER_EXEC']
              .map((a) => <option key={a} value={a} />)}
          </datalist>
        </Field>
        <Field label="결과">
          <select value={result} onChange={(e) => setResult(e.target.value as AuditResult | '')}>
            <option value="">전체</option><option value="SUCCESS">SUCCESS</option><option value="FAILURE">FAILURE</option>
          </select>
        </Field>
        <Field label="사용자 ID">
          <input inputMode="numeric" value={actorUserId} onChange={(e) => setActorUserId(e.target.value.replace(/\D/g, ''))} placeholder="전체" />
        </Field>
        <div className="filters-btn"><button className="btn btn-primary">조회</button></div>
      </form>
      {data ? <AuditTable logs={data.content} showActor /> : <Empty>불러오는 중…</Empty>}
      {data && <Pager page={data.page} totalPages={data.totalPages} totalElements={data.totalElements} onChange={setPage} />}
    </Card>
  );
}
