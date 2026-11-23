import { useCallback, useEffect, useState } from 'react';
import { api, getToken, setToken, setUnauthorizedHandler } from './api';
import { AccountsPanel } from './components/AccountsPanel';
import { AdminPanel } from './components/AdminPanel';
import { AuthScreen } from './components/AuthScreen';
import { Banner } from './components/common';
import { HistoryPanel } from './components/HistoryPanel';
import { MyAuditPanel } from './components/MyAuditPanel';
import { ScheduledPanel } from './components/ScheduledPanel';
import { TransactionsPanel } from './components/TransactionsPanel';
import { toErrorBanner, type BannerMessage } from './format';
import type { Account, LoginResponse, User } from './types';

type Tab = 'accounts' | 'tx' | 'scheduled' | 'history' | 'audit' | 'admin';
const TABS: { id: Tab; label: string; admin?: boolean }[] = [
  { id: 'accounts', label: '내 계좌' },
  { id: 'tx', label: '입금 · 출금 · 이체' },
  { id: 'scheduled', label: '예약 이체' },
  { id: 'history', label: '거래내역' },
  { id: 'audit', label: '내 감사로그' },
  { id: 'admin', label: '관리자', admin: true },
];

/** 화면 표시용 JWT 마스킹: 앞 12자 + ... + 뒤 8자 (전체 토큰 평문은 표시하지 않는다). */
export function maskToken(t: string): string {
  return t.length <= 20 ? '*'.repeat(t.length) : `${t.slice(0, 12)}...${t.slice(-8)}`;
}

/** JWT payload 의 exp(초)를 읽어 만료 시각을 표시한다(서명 검증은 서버 책임). */
function tokenExpiry(t: string): string | null {
  try {
    const payload = JSON.parse(atob(t.split('.')[1].replace(/-/g, '+').replace(/_/g, '/'))) as { exp?: number };
    return payload.exp ? new Date(payload.exp * 1000).toLocaleString('ko-KR') : null;
  } catch {
    return null;
  }
}

export function App() {
  const [user, setUser] = useState<User | null>(null);
  const [booting, setBooting] = useState(() => !!getToken());
  const [tab, setTab] = useState<Tab>('accounts');
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [loadingAccounts, setLoadingAccounts] = useState(false);
  const [msg, setMsg] = useState<BannerMessage | null>(null);
  const [showToken, setShowToken] = useState(false);

  const logout = useCallback((reason?: string) => {
    setToken(null);
    setUser(null);
    setAccounts([]);
    setTab('accounts');
    setShowToken(false);
    setMsg(reason ? { kind: 'info', text: reason } : null);
  }, []);

  useEffect(() => {
    setUnauthorizedHandler(() => logout('로그인 세션이 만료되었습니다. 다시 로그인해 주세요.'));
    return () => setUnauthorizedHandler(null);
  }, [logout]);

  // 새로고침 시 저장된 토큰으로 사용자 정보 복원
  useEffect(() => {
    if (!getToken()) return;
    api.me().then(setUser).catch(() => setToken(null)).finally(() => setBooting(false));
  }, []);

  const reloadAccounts = useCallback(async () => {
    setLoadingAccounts(true);
    try {
      setAccounts(await api.listAccounts());
    } catch (err) {
      setMsg(toErrorBanner(err));
    } finally {
      setLoadingAccounts(false);
    }
  }, []);

  useEffect(() => { if (user) void reloadAccounts(); }, [user, reloadAccounts]);

  function handleLogin(r: LoginResponse) {
    setToken(r.accessToken);
    setUser(r.user);
    setMsg(null);
  }

  if (booting) return <div className="boot">불러오는 중…</div>;
  if (!user) {
    return (
      <>
        {msg && <div className="auth-top-banner"><Banner msg={msg} onClose={() => setMsg(null)} /></div>}
        <AuthScreen onLogin={handleLogin} />
      </>
    );
  }

  const token = getToken() ?? '';
  const isAdmin = user.role === 'ADMIN';
  const visibleTabs = TABS.filter((t) => !t.admin || isAdmin);

  return (
    <div className="app">
      <header className="topbar">
        <div className="topbar-inner">
          <div className="brand"><span className="logo sm">CB</span> CoreBank <span className="brand-sub">인터넷뱅킹 데모</span></div>
          <div className="who">
            <span><strong>{user.name}</strong> 님</span>
            <span className={`role role-${user.role.toLowerCase()}`}>{user.role}</span>
            <span className="muted small">{user.email}</span>
            <button className="btn btn-ghost btn-sm on-dark" onClick={() => setShowToken((v) => !v)}>{showToken ? 'JWT 숨기기' : 'JWT 보기'}</button>
            <button className="btn btn-light btn-sm" onClick={() => logout()}>로그아웃</button>
          </div>
        </div>
        {showToken && (
          <div className="token-box">
            <div className="small">Bearer 토큰 (마스킹 표시 · localStorage 저장 · 만료 {tokenExpiry(token) ?? '알 수 없음'})</div>
            <code>{maskToken(token)}</code>
          </div>
        )}
        <nav className="tabs">
          {visibleTabs.map((t) => (
            <button key={t.id} className={`${tab === t.id ? 'on' : ''} ${t.admin ? 'tab-admin' : ''}`} onClick={() => setTab(t.id)}>
              {t.label}
            </button>
          ))}
        </nav>
      </header>
      <main className="content">
        <Banner msg={msg} onClose={() => setMsg(null)} />
        {tab === 'accounts' && <AccountsPanel accounts={accounts} loading={loadingAccounts} reload={reloadAccounts} />}
        {tab === 'tx' && <TransactionsPanel accounts={accounts} reload={reloadAccounts} />}
        {tab === 'scheduled' && <ScheduledPanel accounts={accounts} reload={reloadAccounts} />}
        {tab === 'history' && <HistoryPanel accounts={accounts} />}
        {tab === 'audit' && <MyAuditPanel />}
        {tab === 'admin' && isAdmin && <AdminPanel onAccountsChanged={reloadAccounts} />}
      </main>
      <footer className="foot">CoreBank 데모 · 금액은 원 단위 정수 · 모든 거래는 복식부기 원장(차변/대변 2줄)으로 기록됩니다.</footer>
    </div>
  );
}
