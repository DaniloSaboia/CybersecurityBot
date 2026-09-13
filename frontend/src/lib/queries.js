// lib/queries.js — React Query hooks + shared client for the Sentinel KMS app.

import { QueryClient, useQuery, useMutation } from '@tanstack/react-query';
import * as api from './api.js';

// shared client (also passed to <QueryClientProvider> in main.jsx)
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: { refetchOnWindowFocus: false, retry: 1, staleTime: 30_000 },
  },
});

// ── Session / shell & Settings ──────────────────────────────────
export function useMe(enabled = true) {
  return useQuery({ queryKey: ['me'], queryFn: api.getMe, enabled });
}

export function useSettings() {
  return useQuery({
    queryKey: ['settings'],
    queryFn: api.getSettings,
    staleTime: 60_000,
  });
}

// ── Knowledge Base ───────────────────────────────────────────────
export function useCategories() {
  return useQuery({ queryKey: ['categories'], queryFn: api.getCategories });
}

export function useArticles(params = {}) {
  return useQuery({
    queryKey: ['articles', params],
    queryFn: () => api.getArticles(params),
  });
}

export function useArticle(id) {
  return useQuery({
    queryKey: ['article', id],
    queryFn: () => api.getArticle(id),
    enabled: !!id,
  });
}

// Loads every article once and exposes a Map id->article for resolving
// citation titles (used by SourceChip / inline [ID] tokens across views).
export function useArticleIndex() {
  const q = useQuery({
    queryKey: ['article-index'],
    queryFn: () => api.getArticles({}),
    staleTime: 5 * 60_000,
  });
  const map = new Map();
  const items = (q.data && q.data.items) || [];
  for (const a of items) map.set(a.id, a);
  return { ...q, index: map };
}

// ── Assistant ────────────────────────────────────────────────────
export function useSuggestions() {
  return useQuery({ queryKey: ['suggestions'], queryFn: api.getSuggestions });
}

// chat mutation wrapper — { message, conversationId } -> { conversationId, blocks, citations }
export function useChat() {
  return useMutation({ mutationFn: api.chat });
}

// ── Conversations (memory) ───────────────────────────────────────
export function useConversations() {
  return useQuery({ queryKey: ['conversations'], queryFn: api.getConversations });
}

// ── Ingestion ────────────────────────────────────────────────────
// poll every 1.5s for live stage/pct/chunks progress
export function useJobs() {
  return useQuery({
    queryKey: ['jobs'],
    queryFn: api.getJobs,
    refetchInterval: 1500,
  });
}

export function useConnectors() {
  return useQuery({ queryKey: ['connectors'], queryFn: api.getConnectors, refetchInterval: 5000 });
}

export function useVectorStoreStatus() {
  return useQuery({ queryKey: ['vectorstore'], queryFn: api.getVectorStoreStatus });
}

// ── Watchlists & Alerts ──────────────────────────────────────────
export function useWatchlists() {
  return useQuery({ queryKey: ['watchlists'], queryFn: api.getWatchlists });
}

export function useAlerts({ unread = false } = {}) {
  return useQuery({
    queryKey: ['alerts', unread],
    queryFn: () => api.getAlerts({ unread }),
    refetchInterval: 60_000,
  });
}

export function useCreateWatchlist() {
  return useMutation({
    mutationFn: api.createWatchlist,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['watchlists'] }),
  });
}

export function useDeleteWatchlist() {
  return useMutation({
    mutationFn: api.deleteWatchlist,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['watchlists'] });
      queryClient.invalidateQueries({ queryKey: ['alerts'] });
    },
  });
}

export function useMarkAlertRead() {
  return useMutation({
    mutationFn: api.markAlertRead,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['alerts'] }),
  });
}

export function useMarkAllAlertsRead() {
  return useMutation({
    mutationFn: api.markAllAlertsRead,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['alerts'] }),
  });
}
