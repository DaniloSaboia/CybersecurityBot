// @vitest-environment jsdom
// auth.test.jsx — session transitions must not leak query data between users.

import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider, useAuth } from './auth.jsx';
import { getMe } from './api.js';
import { __authTest, supabase } from './supabase.js';
import { queryClient } from './queries.js';

vi.mock('./supabase.js', () => {
  let session = { access_token: 'test-token-analyst-a', user: { id: 'analyst-a', email: 'analyst-a@example.test' } };
  let listener = null;
  const auth = {
    getSession: vi.fn(async () => ({ data: { session } })),
    onAuthStateChange: vi.fn((callback) => {
      listener = callback;
      return { data: { subscription: { unsubscribe: vi.fn() } } };
    }),
    signOut: vi.fn(async () => ({ error: null })),
  };
  return {
    supabase: { auth },
    __authTest: {
      reset(nextSession = { access_token: 'test-token-analyst-a', user: { id: 'analyst-a', email: 'analyst-a@example.test' } }) {
        session = nextSession;
        listener = null;
        Object.values(auth).forEach((fn) => fn.mockClear?.());
      },
      emit(event, nextSession) {
        session = nextSession;
        listener?.(event, nextSession);
      },
    },
  };
});

vi.mock('./queries.js', () => ({
  queryClient: {
    clear: vi.fn(),
  },
}));

function SessionProbe() {
  const { user, sessionExpired } = useAuth();
  return <output>{user ? `${user.id}:${sessionExpired ? 'expired' : 'active'}` : `signed-out:${sessionExpired ? 'expired' : 'normal'}`}</output>;
}

function renderAuth() {
  return render(<AuthProvider><SessionProbe /></AuthProvider>);
}

function sessionFor(id) {
  return { access_token: `test-token-${id}`, user: { id, email: `${id}@example.test` } };
}

describe('AuthProvider session boundaries', () => {
  beforeEach(() => {
    __authTest.reset();
    queryClient.clear.mockClear();
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('clears cached server data before showing a different signed-in user', async () => {
    renderAuth();
    await screen.findByText('analyst-a:active');
    queryClient.clear.mockClear();

    act(() => {
      __authTest.emit('SIGNED_IN', sessionFor('analyst-b'));
    });

    await waitFor(() => expect(screen.getByText('analyst-b:active')).toBeTruthy());
    expect(queryClient.clear).toHaveBeenCalledTimes(1);
  });

  it('signs out and exposes a session-expired state after a current-session API 401', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('token is expired', {
      status: 401,
      statusText: 'Unauthorized',
    })));
    renderAuth();
    await screen.findByText('analyst-a:active');
    queryClient.clear.mockClear();

    await getMe().catch(() => undefined);

    await waitFor(() => expect(screen.getByText('signed-out:expired')).toBeTruthy());
    expect(supabase.auth.signOut).toHaveBeenCalledTimes(1);
    expect(queryClient.clear).toHaveBeenCalledTimes(1);
  });

  it('keeps the current session after a 403 API response', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('admin role required', {
      status: 403,
      statusText: 'Forbidden',
    })));
    renderAuth();
    await screen.findByText('analyst-a:active');

    await getMe().catch(() => undefined);

    expect(screen.getByText('analyst-a:active')).toBeTruthy();
    expect(supabase.auth.signOut).not.toHaveBeenCalled();
  });

  it('ignores a late A-session 401 after B has signed in', async () => {
    let resolveFetch;
    vi.stubGlobal('fetch', vi.fn(() => new Promise((resolve) => { resolveFetch = resolve; })));
    renderAuth();
    await screen.findByText('analyst-a:active');

    const lateARequest = getMe().catch(() => undefined);
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(1));

    act(() => {
      __authTest.emit('SIGNED_IN', sessionFor('analyst-b'));
    });
    await screen.findByText('analyst-b:active');

    resolveFetch(new Response('token is expired', { status: 401, statusText: 'Unauthorized' }));
    await lateARequest;
    await act(async () => { await Promise.resolve(); });

    expect(screen.getByText('analyst-b:active')).toBeTruthy();
    expect(supabase.auth.signOut).not.toHaveBeenCalled();
  });
});
