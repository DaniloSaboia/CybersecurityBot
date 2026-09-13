// SettingsModal.test.jsx — browser preferences must not cross user boundaries.

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import SettingsModal, { getStoredModel, getStoredRagParams } from './SettingsModal.jsx';

vi.mock('../lib/queries.js', () => ({
  useSettings: () => ({ data: {
    defaultEmbedModel: 'vendor/embed:free',
    availableModels: [{ id: 'openrouter/free', name: 'Chat router', provider: 'OpenRouter', contextWindow: 200000 }],
    availableEmbeddingModels: [{ id: 'vendor/embed:free', name: 'Free embedding', provider: 'OpenRouter', contextWindow: 512 }],
    openRouterApiKeyConfigured: true,
  } }),
  useVectorStoreStatus: () => ({ data: { dim: 1024, healthy: true } }),
  useCategories: () => ({ data: [] }),
}));

it('shows the active embedding separately without a per-user index switch', () => {
  render(<SettingsModal isOpen onClose={() => {}} userId="analyst" />);
  expect(screen.getByRole('button', { name: /Chat router/ })).toBeTruthy();
  expect(screen.getByText('Free embedding')).toBeTruthy();
  expect(screen.queryByRole('button', { name: /Free embedding/ })).toBeNull();
  expect(screen.getByText('ACTIVE')).toBeTruthy();
  expect(screen.getByText('512 token context')).toBeTruthy();
  cleanup();
});

describe('user-scoped local settings', () => {
  beforeEach(() => localStorage.clear());

  it('reads model and RAG preferences only from the active user namespace', () => {
    localStorage.setItem('sentinel_ai_model:analyst-a', 'model-a');
    localStorage.setItem('sentinel_ai_model:analyst-b', 'model-b');
    localStorage.setItem('sentinel_rag_params:analyst-a', JSON.stringify({ topK: 2 }));
    localStorage.setItem('sentinel_rag_params:analyst-b', JSON.stringify({ topK: 12 }));

    expect(getStoredModel('analyst-b')).toBe('model-b');
    expect(getStoredRagParams('analyst-b')).toMatchObject({ topK: 12 });
  });
});
