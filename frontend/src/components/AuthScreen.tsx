import { useState, type FormEvent } from 'react';
import { api } from '../api';
import { toErrorBanner, type BannerMessage } from '../format';
import type { LoginResponse } from '../types';
import { Banner, Field } from './common';

const DEMO_ADMIN = { email: 'admin@corebank.com', password: 'Admin1234!' };

export function AuthScreen({ onLogin }: { onLogin: (r: LoginResponse) => void }) {
  const [mode, setMode] = useState<'login' | 'signup'>('login');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<BannerMessage | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setMsg(null);
    try {
      if (mode === 'signup') {
        await api.signup({ email, password, name });
        setMode('login');
        setMsg({ kind: 'success', text: '회원가입이 완료되었습니다. 같은 정보로 로그인해 주세요.' });
      } else {
        onLogin(await api.login({ email, password }));
      }
    } catch (err) {
      setMsg(toErrorBanner(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="auth-wrap">
      <div className="auth-brand">
        <div className="logo">CB</div>
        <h1>CoreBank 인터넷뱅킹</h1>
        <p>복식부기 원장 기반 코어뱅킹 데모</p>
      </div>
      <div className="card auth-card">
        <div className="seg">
          <button className={mode === 'login' ? 'on' : ''} onClick={() => { setMode('login'); setMsg(null); }}>로그인</button>
          <button className={mode === 'signup' ? 'on' : ''} onClick={() => { setMode('signup'); setMsg(null); }}>회원가입</button>
        </div>
        <Banner msg={msg} onClose={() => setMsg(null)} />
        <form onSubmit={submit} className="form">
          <Field label="이메일">
            <input type="email" required value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="username" />
          </Field>
          <Field label="비밀번호" hint={mode === 'signup' ? '8~64자' : undefined}>
            <input type="password" required minLength={mode === 'signup' ? 8 : undefined} maxLength={64}
              value={password} onChange={(e) => setPassword(e.target.value)}
              autoComplete={mode === 'signup' ? 'new-password' : 'current-password'} />
          </Field>
          {mode === 'signup' && (
            <Field label="이름">
              <input required maxLength={100} value={name} onChange={(e) => setName(e.target.value)} />
            </Field>
          )}
          <button className="btn btn-primary btn-block" disabled={busy}>
            {busy ? '처리 중…' : mode === 'login' ? '로그인' : '가입하기'}
          </button>
        </form>
        {mode === 'login' && (
          <div className="demo-hint">
            <div><strong>데모 관리자 계정</strong></div>
            <div><code>{DEMO_ADMIN.email}</code> / <code>{DEMO_ADMIN.password}</code></div>
            <button type="button" className="btn btn-ghost btn-sm"
              onClick={() => { setEmail(DEMO_ADMIN.email); setPassword(DEMO_ADMIN.password); }}>
              관리자 계정 자동 입력
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
