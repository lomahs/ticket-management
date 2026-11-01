import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { renderApp, server } from './renderApp';

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => { cleanup(); server.resetHandlers(); localStorage.clear(); });
afterAll(() => server.close());

const WEIGHTS = { create: 1, balr: 0.25, fixb: 0.3, jmr: 0.25, fixj: 0.2 };

describe('detail planning', () => {
  it('edits step weights of an anken', async () => {
    let saved: unknown = null;
    server.use(
      http.get('*/api/v1/settings/step-weights', () => HttpResponse.json({ weights: WEIGHTS, own: false })),
      http.put('*/api/v1/settings/step-weights', async ({ request }) => {
        saved = { anken: new URL(request.url).searchParams.get('anken'), body: await request.json() };
        return HttpResponse.json({ weights: { ...WEIGHTS, balr: 0.5 }, own: true });
      }),
    );
    renderApp('/settings/step-weights?for=PAY-1100');
    const bal = await screen.findByLabelText('BAL Review');
    fireEvent.change(bal, { target: { value: '0.5' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(saved).toEqual({ anken: 'PAY-1100', body: { weights: { ...WEIGHTS, balr: 0.5 } } }));
  });

  const LOGIN = { id: 1, epic_key: 'PAY-1100', position: 0, phase: 'ITA', subphase: 'Create test case', function_name: 'Login', steps: 5, effort_h: 8, ba_id: null, bal_id: null, jm_id: null, pushed_at: null };

  it('edits estimation rows and shows phase totals', async () => {
    let saved: { rows: Record<string, unknown>[] } | null = null;
    server.use(
      http.get('*/api/v1/planning/PAY-1100/estimation', () => HttpResponse.json({ rows: [LOGIN], phases: [{ phase: 'ITA', total_effort_h: 14 }] })),
      http.put('*/api/v1/planning/PAY-1100/estimation', async ({ request }) => {
        saved = await request.json() as typeof saved;
        return HttpResponse.json({ rows: [LOGIN], phases: [{ phase: 'ITA', total_effort_h: 30 }] });
      }),
    );
    renderApp('/planning/estimation?anken=PAY-1100');
    const totals = await screen.findByRole('region', { name: 'Effort per phase' });
    expect(within(totals).getByText('ITA')).toBeTruthy();
    expect(within(totals).getByText('14h')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Add row' }));
    fireEvent.change(screen.getByLabelText('Phase, row 2'), { target: { value: 'ITA' } });
    fireEvent.change(screen.getByLabelText('Subphase, row 2'), { target: { value: 'Execute' } });
    fireEvent.change(screen.getByLabelText('Function, row 2'), { target: { value: 'Run' } });
    fireEvent.change(screen.getByLabelText('Steps, row 2'), { target: { value: '' } });
    fireEvent.change(screen.getByLabelText('Effort (h), row 2'), { target: { value: '16' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(saved?.rows).toHaveLength(2));
    expect(saved!.rows[1]).toMatchObject({ id: 0, phase: 'ITA', subphase: 'Execute', function_name: 'Run', steps: null, effort_h: 16 });
    expect(await within(totals).findByText('30h')).toBeTruthy();
  });

  it('adds valid imported rows and lists the bad ones', async () => {
    let puts = 0;
    server.use(
      http.get('*/api/v1/planning/PAY-1100/estimation', () => HttpResponse.json({ rows: [], phases: [] })),
      http.put('*/api/v1/planning/PAY-1100/estimation', () => { puts++; return HttpResponse.json({ rows: [], phases: [] }); }),
      http.post('*/api/v1/planning/PAY-1100/estimation/import', () => HttpResponse.json([
        { line: 2, phase: 'ITA', subphase: 'Create test case', function: 'Login', steps: 5, effort_h: 8, errors: [] },
        { line: 3, phase: 'ITA', subphase: 'Create test case', function: '', steps: 5, effort_h: 8, errors: ['Function is required'] },
      ])),
    );
    renderApp('/planning/estimation?anken=PAY-1100');
    const file = await screen.findByLabelText('Import file');
    fireEvent.change(file, { target: { files: [new File(['x'], 'e.csv', { type: 'text/csv' })] } });
    expect(await screen.findByText('Line 3: Function is required')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Add 1 row' }));
    expect((screen.getByLabelText('Function, row 1') as HTMLInputElement).value).toBe('Login');
    expect(puts).toBe(0);
  });

  it('previews a master schedule and saves a version with a reason', async () => {
    let previewed: unknown = null;
    let saved: unknown = null;
    const row = { phase: 'ITA', total_effort_h: 30, member_ids: ['an'], start: '2026-10-12', end: '2026-10-16', plan_md: 5 };
    server.use(
      http.get('*/api/v1/planning/PAY-1100/master-schedule', () => HttpResponse.json({ estimation_phases: ['ITA'], current: null, versions: [], shifts: [] })),
      http.post('*/api/v1/planning/PAY-1100/master-schedule/preview', async ({ request }) => { previewed = await request.json(); return HttpResponse.json([row]); }),
      http.post('*/api/v1/planning/PAY-1100/master-schedule', async ({ request }) => {
        saved = await request.json();
        return HttpResponse.json({ estimation_phases: ['ITA'], current: { id: 1, epic_key: 'PAY-1100', saved_at: '2026-10-07T09:00:00Z', saved_by: 'minh.hoang@acme.vn', reason: 'Kick-off', phases: [row] }, versions: [], shifts: [] });
      }),
    );
    renderApp('/planning/schedule?anken=PAY-1100');
    fireEvent.change(await screen.findByLabelText('Start, ITA'), { target: { value: '2026-10-12' } });
    fireEvent.click(screen.getByLabelText('ITA: An'));
    await waitFor(() => expect(previewed).toEqual({ phases: [{ phase: 'ITA', start: '2026-10-12', member_ids: ['an'] }] }));
    const phases = screen.getByRole('region', { name: 'Master schedule phases' });
    expect(await within(phases).findByText('16/10')).toBeTruthy();
    expect(within(phases).getByText('30h')).toBeTruthy();
    fireEvent.change(screen.getByLabelText('Reason for this version'), { target: { value: 'Kick-off' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save version' }));
    await waitFor(() => expect(saved).toEqual({ phases: [{ phase: 'ITA', start: '2026-10-12', member_ids: ['an'] }], reason: 'Kick-off' }));
  });

  it('shows phases moved since the baseline', async () => {
    const v = (id: number, reason: string, start: string, end: string) => ({ id, epic_key: 'PAY-1100', saved_at: '2026-10-0' + id + 'T09:00:00Z', saved_by: 'minh.hoang@acme.vn', reason,
      phases: [{ phase: 'ITA', total_effort_h: 30, member_ids: ['an'], start, end, plan_md: 5 }] });
    server.use(http.get('*/api/v1/planning/PAY-1100/master-schedule', () => HttpResponse.json({
      estimation_phases: ['ITA'], current: v(2, 'Client moved ITA', '2026-10-14', '2026-10-20'), versions: [v(1, 'Kick-off', '2026-10-12', '2026-10-16'), v(2, 'Client moved ITA', '2026-10-14', '2026-10-20')],
      shifts: [{ phase: 'ITA', baseline_start: '2026-10-12', baseline_end: '2026-10-16', start: '2026-10-14', end: '2026-10-20', start_days: 2, end_days: 4 }],
    })), http.post('*/api/v1/planning/PAY-1100/master-schedule/preview', () => HttpResponse.json([])));
    renderApp('/planning/schedule?anken=PAY-1100');
    const shifts = await screen.findByRole('region', { name: 'Changes since the baseline' });
    expect(within(shifts).getByText('4 days later')).toBeTruthy();
    const versions = screen.getByRole('region', { name: 'Versions' });
    expect(within(versions).getByText('Kick-off')).toBeTruthy();
    expect(within(versions).getByText('Baseline')).toBeTruthy();
  });
});

