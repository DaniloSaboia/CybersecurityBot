// lib/queries.js — React Query hooks + shared client for the Sentinel KMS app.

import { QueryClient, useQuery, useMutation } from '@tanstack/react-query';
import * as api from './api.js';

// shared client (also passed to <QueryClientProvider> in main.jsx)
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: { refetchOnWindowFocus: false, retry: 1, staleTime: 30_000 },
  },
});

// ── Session / shell ──────────────────────────────────────────────
export function useMe() {
  return useQuery({ queryKey: ['me'], queryFn: api.getMe });
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
  return useQuery({ queryKey: ['connectors'], queryFn: api.getConnectors });
}

export function useVectorStoreStatus() {
  return useQuery({ queryKey: ['vectorstore'], queryFn: api.getVectorStoreStatus });
}
