import { afterEach, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { ConnectorRow } from './Ingest.jsx';

afterEach(cleanup);
const connector = {
  id: 'kev', name: 'CISA KEV', kind: 'API', status: 'partial',
  sync: { status: 'partial', runId: 'run-1', created: 3, updated: 2, unchanged: 4, checked: 9,
    remaining: 7, maxChangesPerRun: 5, dailyEnabled: true,
    index: { total: 5, searchable: 1, pending: 3, failed: 1 } },
};
it('distinguishes imported records from searchable content and lets refresh resume', () => {
  const refresh = vi.fn();
  render(<ConnectorRow c={connector} onSync={refresh} />);
  expect(screen.getByText(/3 new · 2 updated · 4 unchanged/)).toBeTruthy();
  expect(screen.getByText(/1 searchable · 3 awaiting indexing · 1 indexing failures/)).toBeTruthy();
  expect(screen.getByText(/7 source records still to check/)).toBeTruthy();
  fireEvent.click(screen.getByTitle('Refresh CISA KEV'));
  expect(refresh).toHaveBeenCalledTimes(1);
});
it('disables duplicate submissions while a persisted run is active', () => {
  render(<ConnectorRow c={{ ...connector, sync: { ...connector.sync, status: 'queued' } }} onSync={() => {}} />);
  expect(screen.getByTitle('Refresh CISA KEV').disabled).toBe(true);
});
it('makes source failures visible', () => {
  render(<ConnectorRow c={{ ...connector, sync: { ...connector.sync, error: 'Source returned HTTP 429' } }} onSync={() => {}} />);
  expect(screen.getByRole('alert').textContent).toContain('HTTP 429');
});
