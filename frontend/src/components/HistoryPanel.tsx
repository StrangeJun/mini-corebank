import { useCallback, useEffect, useState } from 'react';
import { api } from '../api';
import { ENTRY_LABEL, formatDateTime, formatWon, toErrorBanner, TX_TYPE_LABEL, type BannerMessage } from '../format';
import type { Account, EntryType, HistoryItem, Page, TransactionType } from '../types';
import { AccountSelect, Banner, Card, Empty, Field, Pager } from './common';

const PAGE_SIZE = 20;

export function HistoryPanel({ accounts }: { accounts: Account[] }) {
  const [accountId, setAccountId] = useState<number | ''>(accounts[0]?.id ?? '');
  const [type, setType] = useState<TransactionType | ''>('');
  const [entryType, setEntryType] = useState<EntryType | ''>('');
  const [fromDate, setFromDate] = useState('');
  const [toDate, setToDate] = useState('');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page<HistoryItem> | null>(null);
  const [msg, setMsg] = useState<BannerMessage | null>(null);

  useEffect(() => {
    if (accountId === '' && accounts.length > 0) setAccountId(accounts[0].id);
  }, [accounts, accountId]);

  const load = useCallback(async () => {
    if (accountId === '') { setData(null); return; }
    if (fromDate && toDate && fromDate > toDate) { setMsg({ kind: 'error', text: '조회 시작일이 종료일보다 늦습니다.' }); return; }
    setMsg(null);
    try {
      setData(await api.history(accountId, { type, entryType, fromDate, toDate, page, size: PAGE_SIZE }));
    } catch (err) {
      setMsg(toErrorBanner(err));
    }
  }, [accountId, type, entryType, fromDate, toDate, page]);
  useEffect(() => { void load(); }, [load]);

  const resetPage = <T,>(set: (v: T) => void) => (v: T) => { set(v); setPage(0); };

  return (
    <div className="stack">
      <Banner msg={msg} onClose={() => setMsg(null)} />
      <Card title="계좌별 복식부기 거래내역">
        <div className="filters">
          <Field label="계좌"><AccountSelect accounts={accounts} value={accountId} onChange={resetPage(setAccountId)} /></Field>
          <Field label="거래 유형">
            <select value={type} onChange={(e) => resetPage(setType)(e.target.value as TransactionType | '')}>
              <option value="">전체</option><option value="DEPOSIT">입금</option><option value="WITHDRAWAL">출금</option><option value="TRANSFER">이체</option>
            </select>
          </Field>
          <Field label="차변/대변">
            <select value={entryType} onChange={(e) => resetPage(setEntryType)(e.target.value as EntryType | '')}>
              <option value="">전체</option><option value="CREDIT">대변(+, 잔액 증가)</option><option value="DEBIT">차변(−, 잔액 감소)</option>
            </select>
          </Field>
          <Field label="시작일"><input type="date" value={fromDate} onChange={(e) => resetPage(setFromDate)(e.target.value)} /></Field>
          <Field label="종료일"><input type="date" value={toDate} onChange={(e) => resetPage(setToDate)(e.target.value)} /></Field>
          <div className="filters-btn">
            <button className="btn btn-ghost" onClick={() => { setType(''); setEntryType(''); setFromDate(''); setToDate(''); setPage(0); }}>초기화</button>
            <button className="btn btn-primary" onClick={() => void load()}>조회</button>
          </div>
        </div>
        <p className="muted small">고객 계좌 기준: 대변(CREDIT)=잔액 증가, 차변(DEBIT)=잔액 감소. 모든 거래는 상대 계정(외부 입출금은 시스템 시재 계좌)과 차변·대변 2줄로 기록됩니다.</p>
        {accountId === '' ? <Empty>계좌를 먼저 개설하거나 선택하세요.</Empty> : !data ? <Empty>불러오는 중…</Empty> : data.content.length === 0 ? (
          <Empty>조건에 맞는 거래내역이 없습니다.</Empty>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr><th>일시</th><th>거래번호</th><th>구분</th><th>차변/대변</th><th className="num">거래금액</th><th className="num">거래 후 잔액</th><th>상대 계좌</th><th>적요</th></tr>
              </thead>
              <tbody>
                {data.content.map((h, i) => (
                  <tr key={`${h.transactionId}-${h.entryType}-${i}`}>
                    <td>{formatDateTime(h.createdAt)}</td>
                    <td>#{h.transactionId}</td>
                    <td>{TX_TYPE_LABEL[h.txType] ?? h.txType}</td>
                    <td><span className={h.entryType === 'CREDIT' ? 'plus' : 'minus'}>{ENTRY_LABEL[h.entryType]}</span></td>
                    <td className={`num ${h.entryType === 'CREDIT' ? 'plus' : 'minus'}`}>{h.entryType === 'CREDIT' ? '+' : '−'}{formatWon(h.amount)}</td>
                    <td className="num">{formatWon(h.balanceAfter)}</td>
                    <td>{h.counterpartyMasked ?? '-'}</td>
                    <td>{h.description ?? '-'}</td>
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
