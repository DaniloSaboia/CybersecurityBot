// @vitest-environment jsdom
// App.test.jsx — shell smoke tests: login gate + authenticated shell render.

import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { act, render, screen, waitFor, cleanup } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter } from 'react-router-dom';
import App from './App.jsx';
import { queryClient } from './lib/queries.js';
import { AuthProvider } from './lib/auth.jsx';

const assistantProbe = vi.hoisted(() => ({ autoSends: vi.fn() }));

// The Supabase client is mocked: no network, full control of the session.
vi.mock('./lib/supabase.js', () => {
  const listeners = [];
  const session = { value: null };
  return {
    supabase: {
      __setSession: (s) => {
        session.value = s;
        listeners.forEach((l) => l('SIGNED_IN', s));
      },
      auth: {
        getSession: vi.fn(async () => ({ data: { session: session.value } })),
        onAuthStateChange: vi.fn((cb) => {
          listeners.push(cb);
          return { data: { subscription: { unsubscribe: () => {} } } };
        }),
      },
    },
  };
});

// Network-bound hooks: return inert data so the shell renders its chrome.
vi.mock('./lib/queries.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    queryClient: actual.queryClient,
    useMe: () => ({ data: { env: 'TEST', user: 'tester', role: 'Analyst' } }),
    useSuggestions: () => ({ data: [] }),
    useConversations: () => ({ data: [] }),
    useArticleIndex: () => ({ index: new Map(), isPending: false, isError: false }),
    useVectorStoreStatus: () => ({ data: { chunks: 42, dim: 1024 } }),
  };
});

// The probe intentionally stores its first user's transcript in component
// state. It will leak A's transcript into B's shell unless the protected
// subtree is recreated when the authenticated user id changes.
vi.mock('./views/Assistant.jsx', async () => {
  const React = await import('react');
  return {
    default: function AssistantSessionProbe({ userId, pendingAsk }) {
      const [snapshot] = React.useState(() => `transcript:${userId}; citation:CVE-${userId}; conversation:${userId}`);
      React.useEffect(() => {
        if (pendingAsk?.text) assistantProbe.autoSends({ userId, text: pendingAsk.text });
      }, [pendingAsk?.n, pendingAsk?.text, userId]);
      return React.createElement(React.Fragment, null,
        React.createElement('output', null, snapshot),
        React.createElement('output', { 'data-testid': 'pending-assistant-ask' }, pendingAsk?.text || 'NO_PENDING_ASK')
      );
    },
  };
});

function renderApp() {
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <BrowserRouter>
          <App />
        </BrowserRouter>
      </AuthProvider>
    </QueryClientProvider>
  );
}

describe('App shell', () => {
  beforeEach(() => {
    queryClient.clear();
    assistantProbe.autoSends.mockClear();
    window.history.pushState({}, '', '/');
  });

  afterEach(() => cleanup());

  it('shows the login gate while signed out', async () => {
    renderApp();
    expect(screen.getByRole('status').textContent).toBe('Loading Sentinel…');
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Enter the Console' })).toBeTruthy());
  });

  it('renders the console shell (rail + theme + palette affordances) when signed in', async () => {
    const { supabase } = await import('./lib/supabase.js');
    supabase.__setSession({ user: { id: 'u1', email: 'analyst@test' } });

    renderApp();

    await waitFor(() => expect(screen.getAllByText('Assistant').length).toBeGreaterThan(0));
    expect(screen.getAllByText('Knowledge Base').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Ingestion').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Watchlists').length).toBeGreaterThan(0);
    expect(screen.getAllByRole('button', { name: 'Commands', exact: true })).toHaveLength(1);
    expect(screen.getAllByRole('button', { name: 'Settings', exact: true })).toHaveLength(1);
    expect(screen.getAllByTitle(/Theme:/).length).toBeGreaterThan(0);
    expect(screen.getAllByTitle('Command palette (Ctrl+K)').length).toBeGreaterThan(0);
  });

  it('opens the command palette with Ctrl+K', async () => {
    const { supabase } = await import('./lib/supabase.js');
    supabase.__setSession({ user: { id: 'u1', email: 'analyst@test' } });

    renderApp();
    await waitFor(() => expect(screen.getAllByText('Watchlists').length).toBeGreaterThan(0));

    // The palette shortcut listens on document; dispatch there (events fired on
    // window do not propagate down to document).
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', ctrlKey: true, bubbles: true }));
    await waitFor(() => expect(screen.getByRole('dialog', { name: 'Command palette' })).toBeTruthy());
    expect(screen.getByText('Go to Knowledge Base')).toBeTruthy();
  });

  it('applies the dark theme to <html> by default', async () => {
    const { supabase } = await import('./lib/supabase.js');
    supabase.__setSession({ user: { id: 'u1', email: 'analyst@test' } });
    renderApp();
    await waitFor(() => expect(screen.getAllByText('Assistant').length).toBeGreaterThan(0));
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
  });

  it('drops A-local transcripts, citation ids, and conversation ids before rendering B', async () => {
    const { supabase } = await import('./lib/supabase.js');
    supabase.__setSession({ user: { id: 'analyst-a', email: 'a@test' } });
    renderApp();

    await waitFor(() => expect(screen.getByText('transcript:analyst-a; citation:CVE-analyst-a; conversation:analyst-a')).toBeTruthy());

    supabase.__setSession({ user: { id: 'analyst-b', email: 'b@test' } });

    await waitFor(() => expect(screen.getByText('transcript:analyst-b; citation:CVE-analyst-b; conversation:analyst-b')).toBeTruthy());
    expect(screen.queryByText('transcript:analyst-a; citation:CVE-analyst-a; conversation:analyst-a')).toBeNull();
  });

  it('does not carry an A-owned router ask into B after an auth switch', async () => {
    const { supabase } = await import('./lib/supabase.js');
    window.history.replaceState({
      usr: { ask: 'A-only incident question', askOwnerId: 'analyst-a' },
      key: 'a-pending-ask',
    }, '', '/');
    supabase.__setSession({ user: { id: 'analyst-a', email: 'a@test' } });

    renderApp();
    await waitFor(() => expect(screen.getByText('transcript:analyst-a; citation:CVE-analyst-a; conversation:analyst-a')).toBeTruthy());

    supabase.__setSession({ user: { id: 'analyst-b', email: 'b@test' } });
    await waitFor(() => expect(screen.getByText('transcript:analyst-b; citation:CVE-analyst-b; conversation:analyst-b')).toBeTruthy());
    await act(async () => { await Promise.resolve(); });

    expect(screen.getByTestId('pending-assistant-ask').textContent).toBe('NO_PENDING_ASK');
    expect(screen.queryByText('A-only incident question')).toBeNull();
    expect(assistantProbe.autoSends).not.toHaveBeenCalledWith({ userId: 'analyst-b', text: 'A-only incident question' });
    await waitFor(() => expect(window.history.state?.usr?.ask).toBeUndefined());
    expect(window.history.state?.usr?.askOwnerId).toBeUndefined();
    expect(JSON.stringify(window.history.state?.usr || {})).not.toContain('A-only incident question');
    expect(JSON.stringify(window.history.state?.usr || {})).not.toContain('analyst-a');
  });

  it('rejects an untagged router ask even when the current account matches the route', async () => {
    const { supabase } = await import('./lib/supabase.js');
    window.history.replaceState({
      usr: { ask: 'Untyped router question' },
      key: 'untagged-pending-ask',
    }, '', '/');
    supabase.__setSession({ user: { id: 'analyst-b', email: 'b@test' } });

    renderApp();
    await waitFor(() => expect(screen.getByText('transcript:analyst-b; citation:CVE-analyst-b; conversation:analyst-b')).toBeTruthy());
    await act(async () => { await Promise.resolve(); });

    expect(screen.getByTestId('pending-assistant-ask').textContent).toBe('NO_PENDING_ASK');
    expect(screen.queryByText('Untyped router question')).toBeNull();
    await waitFor(() => expect(window.history.state?.usr?.ask).toBeUndefined());
    expect(JSON.stringify(window.history.state?.usr || {})).not.toContain('Untyped router question');
  });

  it('delivers a tagged router ask to its owning account', async () => {
    const { supabase } = await import('./lib/supabase.js');
    window.history.replaceState({
      usr: { ask: 'B-owned incident question', askOwnerId: 'analyst-b' },
      key: 'b-pending-ask',
    }, '', '/');
    supabase.__setSession({ user: { id: 'analyst-b', email: 'b@test' } });

    renderApp();
    await waitFor(() => expect(screen.getByText('transcript:analyst-b; citation:CVE-analyst-b; conversation:analyst-b')).toBeTruthy());
    await waitFor(() => expect(screen.getByTestId('pending-assistant-ask').textContent).toBe('B-owned incident question'));
    await waitFor(() => expect(window.history.state?.usr?.ask).toBeUndefined());
    expect(window.history.state?.usr?.askOwnerId).toBeUndefined();
  });
});
