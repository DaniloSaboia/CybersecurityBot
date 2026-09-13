// lib/auth.jsx — Supabase auth context. Exposes the current session/user and
// sign-in / sign-up / sign-out, and keeps them in sync via onAuthStateChange.

import { createContext, useContext, useEffect, useRef, useState } from 'react';
import { supabase } from './supabase.js';
import { SESSION_EXPIRED_EVENT, setActiveSessionExpiryGeneration } from './api.js';
import { queryClient } from './queries.js';

const AuthCtx = createContext(null);

export function AuthProvider({ children }) {
  const [session, setSession] = useState(null);
  const [loading, setLoading] = useState(true);
  const [sessionExpired, setSessionExpired] = useState(false);
  const activeUserId = useRef(undefined);
  const activeSessionGeneration = useRef(null);

  // The backend's response cache is user-scoped. Clear it *before* exposing a
  // new session so a previous operator's conversations, jobs, or alerts cannot
  // be rendered while React Query refetches for the successor.
  function applySession(nextSession, event) {
    activeSessionGeneration.current = setActiveSessionExpiryGeneration(nextSession);
    const nextUserId = nextSession?.user?.id || null;
    if (activeUserId.current !== nextUserId) {
      queryClient.clear();
      activeUserId.current = nextUserId;
    }
    if (nextSession && (event === 'SIGNED_IN' || event === 'INITIAL_SESSION')) {
      setSessionExpired(false);
    }
    setSession(nextSession);
    setLoading(false);
  }

  useEffect(() => {
    let mounted = true;
    let sessionVersion = 0;

    const initialVersion = ++sessionVersion;
    supabase.auth.getSession()
      .then(({ data }) => {
        if (mounted && initialVersion === sessionVersion) applySession(data.session, 'INITIAL_SESSION');
      })
      .catch(() => {
        if (mounted && initialVersion === sessionVersion) applySession(null, 'INITIAL_SESSION');
      });

    const { data: sub } = supabase.auth.onAuthStateChange((event, nextSession) => {
      sessionVersion += 1;
      if (mounted) applySession(nextSession, event);
    });

    function handleSessionExpiry(event) {
      if (!mounted) return;
      const generation = event.detail?.generation;
      if (!Number.isInteger(generation) || generation !== activeSessionGeneration.current) return;
      // Ignore an old getSession() promise that may otherwise restore the
      // rejected credential after the API already returned 401.
      sessionVersion += 1;
      setSessionExpired(true);
      applySession(null, 'SESSION_EXPIRED');
      Promise.resolve(supabase.auth.signOut()).catch(() => undefined);
    }

    window.addEventListener(SESSION_EXPIRED_EVENT, handleSessionExpiry);
    return () => {
      mounted = false;
      window.removeEventListener(SESSION_EXPIRED_EVENT, handleSessionExpiry);
      sub.subscription.unsubscribe();
    };
  }, []);

  async function signOut() {
    // Clear synchronously so no protected response remains visible if the
    // provider's auth callback arrives after navigation to the login view.
    applySession(null, 'SIGNED_OUT');
    setSessionExpired(false);
    return supabase.auth.signOut();
  }

  const value = {
    session,
    user: session?.user || null,
    loading,
    sessionExpired,
    signIn: (email, password) => supabase.auth.signInWithPassword({ email, password }),
    signUp: (email, password) => supabase.auth.signUp({ email, password }),
    signOut,
  };

  return <AuthCtx.Provider value={value}>{children}</AuthCtx.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthCtx);
  if (!ctx) throw new Error('useAuth must be used within <AuthProvider>');
  return ctx;
}
