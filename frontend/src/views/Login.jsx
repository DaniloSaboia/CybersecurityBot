// views/Login.jsx — sign in / sign up gate (Supabase auth).

import { useState } from 'react';
import { Icon } from '../icons.jsx';
import { Logo } from '../ui.jsx';
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
        // Confirmations enabled — let the user know to confirm (local Inbucket/Mailpit).
        setNotice('Account created. If email confirmation is on, check Inbucket at http://localhost:54324, then sign in.');
        setMode('signin');
      }
      // On success the AuthProvider's onAuthStateChange swaps the gate for the app.
    } catch (err) {
      setError(String(err.message || err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div style={wrap}>
      <form onSubmit={submit} style={card}>
        <div style={{ display: 'flex', justifyContent: 'center', marginBottom: 6 }}><Logo /></div>
        <h1 style={title}>{mode === 'signin' ? 'Sign in' : 'Create account'}</h1>
        <p style={sub}>Access the SecOps knowledge console</p>

        <label style={lbl}>Email</label>
        <input style={field} type="email" autoComplete="email" value={email}
               onChange={(e) => setEmail(e.target.value)} placeholder="analyst@sentinel.local" />

        <label style={lbl}>Password</label>
        <input style={field} type="password" autoComplete={mode === 'signin' ? 'current-password' : 'new-password'}
               value={password} onChange={(e) => setPassword(e.target.value)} placeholder="••••••••" />

        {error && <div style={errBox}><Icon name="alert" size={14} /> <span>{error}</span></div>}
        {notice && <div style={noticeBox}><Icon name="check" size={14} /> <span>{notice}</span></div>}

        <button type="submit" style={{ ...primary, opacity: busy ? 0.6 : 1 }} disabled={busy}>
          <Icon name="shield" size={15} /> {busy ? 'Working…' : mode === 'signin' ? 'Sign in' : 'Sign up'}
        </button>

        <button type="button" style={switchBtn}
                onClick={() => { setMode(mode === 'signin' ? 'signup' : 'signin'); setError(''); setNotice(''); }}>
          {mode === 'signin' ? "No account? Create one" : 'Have an account? Sign in'}
        </button>
      </form>
    </div>
  );
}

const wrap = { height: '100vh', display: 'grid', placeItems: 'center', padding: 24 };
const card = {
  width: 360, maxWidth: '100%', display: 'flex', flexDirection: 'column', gap: 8,
  background: 'var(--panel)', border: '1px solid var(--border-2)', borderRadius: 'var(--radius-lg)',
  padding: '26px 24px 22px', boxShadow: '0 1px 0 rgba(255,255,255,.02) inset, 0 18px 50px -24px rgba(0,0,0,.9)',
};
const title = { fontSize: 19, fontWeight: 700, textAlign: 'center', marginTop: 6 };
const sub = { fontFamily: 'var(--mono)', fontSize: 11, color: 'var(--text-3)', textAlign: 'center', marginBottom: 14, letterSpacing: '.02em' };
const lbl = { fontFamily: 'var(--mono)', fontSize: 10, letterSpacing: '.1em', textTransform: 'uppercase', color: 'var(--text-faint)', marginTop: 8 };
const field = {
  background: 'var(--panel-2)', border: '1px solid var(--border-2)', borderRadius: 'var(--radius-sm)',
  color: 'var(--text)', padding: '10px 12px', fontSize: 14, outline: 'none',
};
const primary = {
  marginTop: 16, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8,
  fontFamily: 'var(--mono)', fontWeight: 600, fontSize: 13, color: '#04130d', background: 'var(--accent)',
  borderRadius: 'var(--radius-sm)', padding: '11px 15px', boxShadow: '0 0 16px -5px var(--accent)',
};
const switchBtn = { marginTop: 10, fontFamily: 'var(--mono)', fontSize: 11, color: 'var(--text-3)' };
const errBox = { display: 'flex', alignItems: 'center', gap: 7, marginTop: 12, padding: '9px 11px', fontSize: 12, color: 'var(--sev-critical)', background: 'var(--sev-critical-bg)', border: '1px solid color-mix(in srgb,var(--sev-critical) 40%,transparent)', borderRadius: 'var(--radius-sm)' };
const noticeBox = { display: 'flex', alignItems: 'center', gap: 7, marginTop: 12, padding: '9px 11px', fontSize: 12, color: 'var(--accent-dim)', background: 'var(--accent-soft)', border: '1px solid var(--accent-dim)', borderRadius: 'var(--radius-sm)' };
