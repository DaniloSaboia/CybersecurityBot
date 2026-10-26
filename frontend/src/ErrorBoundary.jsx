// ErrorBoundary.jsx — catches render errors so a crash shows a readable card
// instead of a blank (black) screen, and surfaces the message/stack for debugging.

import { Component } from 'react';

export class ErrorBoundary extends Component {
  constructor(props) {
    super(props);
    this.state = { error: null };
  }

  static getDerivedStateFromError(error) {
    return { error };
  }

  componentDidCatch(error, info) {
    // eslint-disable-next-line no-console
    console.error('Sentinel KMS render error:', error, info);
  }

  render() {
    const { error } = this.state;
    if (error) {
      const detail = String((error && (error.stack || error.message)) || error);
      return (
        <div style={wrap}>
          <div style={card}>
            <div style={title}>This view hit a rendering error</div>
            <pre style={pre}>{detail}</pre>
            <button style={btn} onClick={() => this.setState({ error: null })}>Dismiss</button>
          </div>
        </div>
      );
    }
    return this.props.children;
  }
}

const wrap = { height: '100%', display: 'grid', placeItems: 'center', padding: 24, overflow: 'auto' };
const card = {
  maxWidth: 680, width: '100%', background: 'var(--panel-solid)', border: '1px solid var(--sev-critical)',
  borderRadius: 'var(--radius)', padding: '20px 22px',
  boxShadow: '0 0 40px rgba(239, 35, 60, 0.15), 0 20px 50px rgba(0, 0, 0, 0.8)',
};
const title = { fontFamily: 'var(--mono)', fontSize: 13, color: 'var(--sev-critical)', marginBottom: 12, letterSpacing: '.04em' };
const pre = {
  fontFamily: 'var(--mono)', fontSize: 11.5, color: 'var(--text-2)', whiteSpace: 'pre-wrap',
  wordBreak: 'break-word', background: 'var(--bg-2)', border: '1px solid var(--border)',
  borderRadius: 'var(--radius-sm)', padding: 12, maxHeight: 320, overflow: 'auto', margin: 0,
};
const btn = {
  marginTop: 14, fontFamily: 'var(--mono)', fontSize: 12, fontWeight: 700, color: '#fff', background: 'var(--accent)',
  borderRadius: 'var(--radius-pill)', padding: '9px 16px',
};
