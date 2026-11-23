import { formatDateTime } from '../format';
import type { AuditLog } from '../types';
import { Empty, StatusBadge } from './common';

export function AuditTable({ logs, showActor }: { logs: AuditLog[]; showActor?: boolean }) {
  if (logs.length === 0) return <Empty>감사 로그가 없습니다.</Empty>;
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr><th>일시</th>{showActor && <th>사용자 ID</th>}<th>행위</th><th>대상</th><th>결과</th><th>요약(마스킹)</th><th>IP</th></tr>
        </thead>
        <tbody>
          {logs.map((l) => (
            <tr key={l.id}>
              <td>{formatDateTime(l.createdAt)}</td>
              {showActor && <td>{l.actorUserId ?? '-'}</td>}
              <td><code>{l.actionType}</code></td>
              <td className="small">{l.targetType ?? '-'}{l.targetId ? ` · ${l.targetId}` : ''}</td>
              <td><StatusBadge status={l.result} /></td>
              <td className="small wrap">{l.maskedSummary ?? '-'}</td>
              <td className="small">{l.clientIp ?? '-'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
