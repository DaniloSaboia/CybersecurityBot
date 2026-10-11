// SettingsModal.test.jsx — browser preferences must not cross user boundaries.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import SettingsModal, { getStoredModel, getStoredRagParams } from './SettingsModal.jsx';
import { retireDemoContent } from '../lib/api.js';
import { queryClient } from '../lib/queries.js';

const demoPreview = vi.hoisted(() => ({ value: { isSuccess: false, isError: true, error: { status: 403 } } }));
vi.mock('../lib/api.js', () => ({ retireDemoContent: vi.fn() }));

vi.mock('../lib/queries.js', () => ({
  useSettings: () => ({ data: {
    defaultEmbedModel: 'vendor/embed:free',
    availableModels: [{ id: 'openrouter/free', name: 'Chat router', provider: 'OpenRouter', contextWindow: 200000 }],
    availableEmbeddingModels: [{ id: 'vendor/embed:free', name: 'Free embedding', provider: 'OpenRouter', contextWindow: 512 }],
    openRouterApiKeyConfigured: true,
  } }),
  useVectorStoreStatus: () => ({ data: { dim: 1024, healthy: true } }),
  useCategories: () => ({ data: [] }),
  useDemoContent: () => demoPreview.value,
  queryClient: { invalidateQueries: vi.fn() },
}));

afterEach(cleanup);

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

describe('admin demo-content maintenance', () => {
  beforeEach(() => {
    demoPreview.value = { isSuccess: true, data: { eligibleIds: ['PB-118', 'IR-204'], preservedIds: ['PB-EDITED'], retiredIds: [] } };
    retireDemoContent.mockReset();
    queryClient.invalidateQueries.mockReset().mockResolvedValue(undefined);
  });

  it('hides maintenance controls when the server denies the admin preview', () => {
    demoPreview.value = { isSuccess: false, isError: true, error: { status: 403 } };
    render(<SettingsModal isOpen onClose={() => {}} userId="analyst" />);
    fireEvent.click(screen.getByRole('button', { name: 'Retrieval' }));
    expect(screen.queryByRole('button', { name: 'Retire demo content' })).toBeNull();
    expect(retireDemoContent).not.toHaveBeenCalled();
  });

  it('retires eligible samples, reports preserved edits, and refreshes knowledge views', async () => {
    let finish;
    retireDemoContent.mockImplementation(() => new Promise((resolve) => { finish = resolve; }));
    render(<SettingsModal isOpen onClose={() => {}} userId="admin" />);
    fireEvent.click(screen.getByRole('button', { name: 'Retrieval' }));
    expect(screen.getByText(/2 unchanged sample articles eligible/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Retire demo content' }));
    expect(screen.getByRole('button', { name: 'Retiring demo content…' }).disabled).toBe(true);
    finish({ eligibleIds: [], retiredIds: ['PB-118', 'IR-204'], preservedIds: ['PB-EDITED'] });
    await waitFor(() => expect(screen.getByRole('status').textContent).toBe('Retired 2 sample articles. Preserved 1 edited or protected article. History remains available.'));
    expect(retireDemoContent).toHaveBeenCalledTimes(1);
    for (const key of ['demo-content', 'articles', 'article', 'article-index', 'categories', 'vectorstore']) {
      expect(queryClient.invalidateQueries).toHaveBeenCalledWith({ queryKey: [key] });
    }
  });

  it('reports a failed retirement without claiming that sample articles were removed', async () => {
    retireDemoContent.mockRejectedValue(new Error('Permission denied. Your account does not have access to this resource.'));
    render(<SettingsModal isOpen onClose={() => {}} userId="admin" />);
    fireEvent.click(screen.getByRole('button', { name: 'Retrieval' }));
    fireEvent.click(screen.getByRole('button', { name: 'Retire demo content' }));
    await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('Permission denied'));
    expect(screen.queryByRole('status')).toBeNull();
    expect(queryClient.invalidateQueries).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Retire demo content' }).disabled).toBe(false);
  });
});
