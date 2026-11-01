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

  const SCHEDULE = { estimation_phases: ['ITA'], current: { id: 1, epic_key: 'PAY-1100', saved_at: '2026-10-07T09:00:00Z', saved_by: 'minh.hoang@acme.vn', reason: 'Kick-off',
    phases: [{ phase: 'ITA', total_effort_h: 30, member_ids: ['an'], start: '2026-10-12', end: '2026-10-16', plan_md: 5 }] }, versions: [], shifts: [] };
  const replanView = (to: string) => ({
    items: [{ key: 'PAY-1502', summary: '[ITA] Run', step: null, from_member: 'an', to_member: to, from_start: '2026-10-12', from_end: '2026-10-13',
      to_start: '2026-10-14', to_end: '2026-10-15', hours: 16, started: false }],
    over_budget: [], over_day: [], check: { phase: 'ITA', master_end: '2026-10-16', plan_end: '2026-10-15', days_late: -1 }, problems: [],
  });

  it('re-plans a phase and pushes the reviewed changes', async () => {
    let applied: unknown = null;
    server.use(
      http.get('*/api/v1/planning/PAY-1100/master-schedule', () => HttpResponse.json(SCHEDULE)),
      http.post('*/api/v1/planning/PAY-1100/replan', () => HttpResponse.json(replanView('an'))),
      http.post('*/api/v1/planning/PAY-1100/replan/preview', () => HttpResponse.json({ creates: [], updates: [{ key: 'PAY-1502', summary: '[ITA] Run',
        changes: [{ field: 'planned_start', jira_field: 'customfield_10020', before: '2026-10-12', after: '2026-10-14' }], requests: [], transition: null, jira_status: null, estimate_tracking: false }] })),
      http.post('*/api/v1/planning/PAY-1100/replan/apply', async ({ request }) => { applied = await request.json(); return HttpResponse.json({ updated: ['PAY-1502'], created: {} }); }),
    );
    renderApp('/planning/replan?anken=PAY-1100&phase=ITA');
    expect(await screen.findByText('12/10 – 13/10 → 14/10 – 15/10')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Review' }));
    const review = await screen.findByRole('region', { name: 'Re-plan review' });
    expect(within(review).getByText('Planned Start 12/10 → 14/10')).toBeTruthy();
    fireEvent.click(within(review).getByRole('button', { name: 'Push to Jira' }));
    await waitFor(() => expect(applied).toEqual({ phase: 'ITA', assignees: {}, updates: [{ key: 'PAY-1502', changes: [{ field: 'planned_start', before: '2026-10-12', after: '2026-10-14' }] }] }));
    expect(await screen.findByText('Re-planned 1 sub-task in Jira')).toBeTruthy();
  });

  it('changing a member recomputes the plan', async () => {
    const bodies: unknown[] = [];
    server.use(
      http.get('*/api/v1/planning/PAY-1100/master-schedule', () => HttpResponse.json(SCHEDULE)),
      http.post('*/api/v1/planning/PAY-1100/replan', async ({ request }) => {
        const b = await request.json() as { assignees: Record<string, string> };
        bodies.push(b);
        return HttpResponse.json(replanView(b.assignees['PAY-1502'] ?? 'an'));
      }),
    );
    renderApp('/planning/replan?anken=PAY-1100&phase=ITA');
    fireEvent.change(await screen.findByLabelText('Member, PAY-1502'), { target: { value: 'binh' } });
    await waitFor(() => expect(bodies).toContainEqual({ phase: 'ITA', assignees: { 'PAY-1502': 'binh' } }));
  });
});
