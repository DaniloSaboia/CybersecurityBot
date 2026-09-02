// views/Login.jsx — Red Noir access terminal: secure sign-in / registration.

import { useState } from 'react';
import { Icon } from '../icons.jsx';
import { Logo, CursorCoordinateHUD } from '../ui.jsx';
import { useAuth } from '../lib/auth.jsx';

export default function Login() {
  const { signIn, signUp } = useAuth();
  const [mode, setMode] = useState('signin'); // 'signin' | 'signup'
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  async function submit(e) {
    e.preventDefault();
    if (!email || !password || busy) return;
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const fn = mode === 'signin' ? signIn : signUp;
      const { data, error: err } = await fn(email.trim(), password);
      if (err) {
        setError(err.message || 'Authentication failed');
      } else if (mode === 'signup' && !data.session) {
        setNotice('Account created. Check your email for any required confirmation, then sign in.');
        setMode('signin');
      }
    } catch (err) {
      setError(String(err.message || err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div style={wrap}>
      <div style={hud}><CursorCoordinateHUD /></div>
      <form onSubmit={submit} style={card} aria-busy={busy}>
        <div style={{ display: 'flex', justifyContent: 'center', marginBottom: 10 }}><Logo /></div>

        <h1 style={title}>{mode === 'signin' ? 'Enter the Console' : 'Request Access'}</h1>
        <p style={sub}>Sentinel KMS · SecOps Knowledge System</p>

        <label htmlFor="auth-email" style={lbl}>Operator email</label>
        <input
          id="auth-email"
          style={field}
          type="email"
          required
          autoComplete="email"
          value={email}
          aria-invalid={!!error}
          aria-describedby={error || notice ? 'auth-feedback' : undefined}
          onChange={(e) => { setEmail(e.target.value); if (error) setError(''); }}
          placeholder="analyst@sentinel.io"
        />

        <label htmlFor="auth-password" style={lbl}>Passcode</label>
        <input
          id="auth-password"
          style={field}
          type="password"
          required
          autoComplete={mode === 'signin' ? 'current-password' : 'new-password'}
          value={password}
          aria-invalid={!!error}
          aria-describedby={error || notice ? 'auth-feedback' : undefined}
          onChange={(e) => { setPassword(e.target.value); if (error) setError(''); }}
          placeholder="••••••••"
        />

        {error && (
          <div id="auth-feedback" role="alert" aria-live="assertive" style={errBox}>
            <Icon name="alert" size={14} />
            <span>Access denied — {error}</span>
          </div>
        )}
        {notice && (
          <div id="auth-feedback" role="status" aria-live="polite" style={noticeBox}>
            <Icon name="check" size={14} />
            <span>{notice}</span>
          </div>
        )}

        <button type="submit" className="shiny-cta" style={{ ...primary, opacity: busy ? 0.6 : 1 }} disabled={busy}>
          <span style={primaryInner}>
            <Icon name="shield" size={15} />
            {busy ? 'Verifying…' : mode === 'signin' ? 'Authenticate' : 'Create Account'}
          </span>
        </button>

        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 10, marginTop: 14 }}>
          <button
            type="button"
            style={switchBtn}
            onClick={() => { setMode(mode === 'signin' ? 'signup' : 'signin'); setError(''); setNotice(''); }}
          >
            {mode === 'signin' ? 'No account? Request access' : 'Have access? Sign in'}
          </button>
          <span style={{ fontFamily: 'var(--mono)', fontSize: 9.5, color: 'var(--text-faint)', letterSpacing: '.08em' }}>REV 2.4 // AUTH</span>
        </div>
      </form>
    </div>
  );
}

const wrap = {
  minHeight: '100dvh',
  display: 'grid',
  placeItems: 'center',
  padding: 24,
  position: 'relative',
  overflowY: 'auto',
};

const hud = {
  position: 'fixed',
  top: 14,
  right: 14,
  zIndex: 2,
};

const card = {
  width: 400,
  maxWidth: '100%',
  display: 'flex',
  flexDirection: 'column',
  gap: 8,
  background: 'rgba(12, 12, 14, 0.82)',
  border: '1px solid rgba(255, 255, 255, 0.12)',
  borderRadius: 20,
  padding: '30px 28px 26px',
  position: 'relative',
  backdropFilter: 'blur(18px)',
  boxShadow: '0 0 60px rgba(239, 35, 60, 0.14), 0 30px 60px rgba(0, 0, 0, 0.85)',
  animation: 'fade-in-up 0.7s cubic-bezier(0.2, 0.8, 0.25, 1) both',
};

const title = {
  fontFamily: 'var(--display)',
  fontSize: 24,
  fontWeight: 800,
  textAlign: 'center',
  marginTop: 6,
  color: 'var(--text)',
  letterSpacing: '-0.01em',
};

const sub = {
  fontFamily: 'var(--mono)',
  fontSize: 10,
  color: 'var(--text-faint)',
  textAlign: 'center',
  marginBottom: 14,
  letterSpacing: '.14em',
  textTransform: 'uppercase',
};

const lbl = {
  fontFamily: 'var(--mono)',
  fontSize: 9.5,
  letterSpacing: '.14em',
  textTransform: 'uppercase',
  color: 'var(--accent-bright)',
  marginTop: 8,
  fontWeight: 600,
};

const field = {
  background: 'rgba(255, 255, 255, 0.05)',
  border: '1px solid rgba(255, 255, 255, 0.14)',
  borderRadius: 999,
  color: 'var(--text)',
  padding: '11px 16px',
  fontSize: 13,
  outline: 'none',
  fontFamily: 'var(--mono)',
  transition: 'border-color .15s ease, box-shadow .15s ease',
};

const primary = {
  marginTop: 20,
};

const primaryInner = {
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  gap: 8,
  fontFamily: 'var(--mono)',
  fontWeight: 700,
  fontSize: 12,
  color: '#fff',
  letterSpacing: '.1em',
  textTransform: 'uppercase',
};

const switchBtn = {
  fontFamily: 'var(--mono)',
  fontSize: 10.5,
  color: 'var(--text-3)',
  textDecoration: 'underline',
};

const errBox = {
  display: 'flex',
  alignItems: 'center',
  gap: 7,
  marginTop: 12,
  padding: '9px 12px',
  fontSize: 11,
  fontFamily: 'var(--mono)',
  color: 'var(--redline-bright)',
  background: 'var(--redline-bg)',
  border: '1px solid var(--accent-dim)',
  borderRadius: 10,
};

const noticeBox = {
  display: 'flex',
  alignItems: 'center',
  gap: 7,
  marginTop: 12,
  padding: '9px 12px',
  fontSize: 11,
  fontFamily: 'var(--mono)',
  color: 'var(--ok)',
  background: 'rgba(52, 211, 153, 0.1)',
  border: '1px solid rgba(52, 211, 153, 0.4)',
  borderRadius: 10,
};
