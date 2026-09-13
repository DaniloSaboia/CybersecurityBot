// views/Login.jsx — Analyst access to the security knowledge workspace.

import { useState } from 'react';
import { Icon } from '../icons.jsx';
import { Logo } from '../ui.jsx';
import { useAuth } from '../lib/auth.jsx';
import './access.css';

export default function Login({ sessionExpired = false }) {
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
    <main className="access-login">
      <div className="access-layout">
        <header className="access-brand"><Logo /></header>
        <div className="access-grid">
          <section className="access-intro" aria-labelledby="access-purpose">
            <p className="access-eyebrow">Security research workspace</p>
            <h2 id="access-purpose">Keep the source<br /> in view.</h2>
            <p className="access-description">Search your knowledge base, investigate with the assistant, and trace answers back to their evidence.</p>
          </section>

          <figure className="access-workflow" aria-labelledby="access-workflow-caption">
            <div className="access-source-stack">
              <div className="access-source"><Icon name="doc" size={16} /><span>Documents</span></div>
              <div className="access-source"><Icon name="link" size={16} /><span>URLs</span></div>
              <div className="access-source"><Icon name="ingest" size={16} /><span>Feeds</span></div>
            </div>
            <div className="access-connector" aria-hidden="true"><span><Icon name="arrowright" size={15} /></span></div>
            <div className="access-evidence">
              <Icon name="book" size={20} />
              <strong>Knowledge base</strong>
              <span>Indexed passages</span>
            </div>
            <div className="access-answer">
              <span className="access-answer-link" aria-hidden="true"><Icon name="arrowdown" size={13} /></span>
              <Icon name="chat" size={17} />
              <div><strong>Cited answers</strong><span>Return to the source</span></div>
            </div>
            <figcaption id="access-workflow-caption">From source material to evidence you can inspect.</figcaption>
          </figure>

          <form onSubmit={submit} className="access-form" aria-busy={busy}>
            <div className="access-form-icon" aria-hidden="true"><Icon name="shield" size={22} /></div>
            <h1 className="access-title">{mode === 'signin' ? 'Enter the Console' : 'Request Access'}</h1>
            <p className="access-subtitle">Sentinel KMS · SecOps Knowledge System</p>

            {sessionExpired && (
              <div role="status" aria-live="polite" className="access-feedback access-notice">
                <Icon name="alert" size={14} />
                <span>Session expired — sign in again to continue.</span>
              </div>
            )}

            <label htmlFor="auth-email" className="access-label">Operator email</label>
            <input
              id="auth-email"
              className="access-field"
              type="email"
              required
              autoComplete="email"
              value={email}
              aria-invalid={!!error}
              aria-describedby={error || notice ? 'auth-feedback' : undefined}
              onChange={(e) => { setEmail(e.target.value); if (error) setError(''); }}
              placeholder="analyst@sentinel.io"
            />

            <label htmlFor="auth-password" className="access-label">Passcode</label>
            <input
              id="auth-password"
              className="access-field"
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
              <div id="auth-feedback" role="alert" aria-live="assertive" className="access-feedback access-error">
                <Icon name="alert" size={14} />
                <span>Access denied — {error}</span>
              </div>
            )}
            {notice && (
              <div id="auth-feedback" role="status" aria-live="polite" className="access-feedback access-notice">
                <Icon name="check" size={14} />
                <span>{notice}</span>
              </div>
            )}

            <button type="submit" className="shiny-cta access-submit" disabled={busy}>
              <span className="access-submit-inner">
                <Icon name="shield" size={15} />
                {busy ? 'Verifying…' : mode === 'signin' ? 'Authenticate' : 'Create Account'}
              </span>
            </button>

            <div className="access-switch">
              <button
                type="button"
                className="access-switch-button"
                onClick={() => { setMode(mode === 'signin' ? 'signup' : 'signin'); setError(''); setNotice(''); }}
              >
                {mode === 'signin' ? 'No account? Request access' : 'Have access? Sign in'}
              </button>

            </div>
          </form>
        </div>
      </div>
    </main>
  );
}
