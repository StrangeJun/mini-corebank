import { useCallback, useEffect, useState } from 'react';
import { api } from '../api';
import { toErrorBanner, type BannerMessage } from '../format';
import type { AuditLog, Page } from '../types';
import { AuditTable } from './AuditTable';
import { Banner, Card, Empty, Pager } from './common';

export function MyAuditPanel() {
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page<AuditLog> | null>(null);
  const [msg, setMsg] = useState<BannerMessage | null>(null);

  const load = useCallback(async () => {
    try { setData(await api.myAuditLogs(page, 20)); } catch (err) { setMsg(toErrorBanner(err)); }
  }, [page]);
  useEffect(() => { void load(); }, [load]);

  return (
    <div className="stack">
      <Banner msg={msg} onClose={() => setMsg(null)} />
      <Card title="내 보안·거래 감사 로그" actions={<button className="btn btn-ghost btn-sm" onClick={() => void load()}>새로고침</button>}>
        <p className="muted small">로그인 실패·PIN 오류·잔액 부족 등 실패 이벤트도 거래 롤백과 무관하게 기록됩니다. 계좌번호·비밀번호는 마스킹되어 저장됩니다.</p>
        {data ? <AuditTable logs={data.content} /> : <Empty>불러오는 중…</Empty>}
        {data && <Pager page={data.page} totalPages={data.totalPages} totalElements={data.totalElements} onChange={setPage} />}
      </Card>
    </div>
  );
}
