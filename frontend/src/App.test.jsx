// @vitest-environment jsdom
// App.test.jsx — shell smoke tests: login gate + authenticated shell render.

import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, cleanup } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter } from 'react-router-dom';
import App from './App.jsx';
import { queryClient } from './lib/queries.js';
import { AuthProvider } from './lib/auth.jsx';

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
    window.history.pushState({}, '', '/');
  });

  afterEach(() => cleanup());

  it('shows the login gate while signed out', async () => {
    renderApp();
    await waitFor(() => expect(screen.getByText(/INITIALIZING SENTINEL/i)).toBeTruthy());
  });

  it('renders the console shell (rail + theme + palette affordances) when signed in', async () => {
    const { supabase } = await import('./lib/supabase.js');
    supabase.__setSession({ user: { id: 'u1', email: 'analyst@test' } });

    renderApp();

    await waitFor(() => expect(screen.getAllByText('Assistant').length).toBeGreaterThan(0));
    expect(screen.getAllByText('Knowledge Base').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Ingestion').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Watchlists').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Commands').length).toBeGreaterThan(0);
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
});
