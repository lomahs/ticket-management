import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { fixtures, renderApp, server } from './renderApp';

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => { cleanup(); server.resetHandlers(); localStorage.clear(); });
afterAll(() => server.close());

const WEIGHTS = { create: 1, balr: 0.25, fixb: 0.3, jmr: 0.25, fixj: 0.2 };

describe('detail planning', () => {
  it('edits step weights, PIC and effort shares of an anken', async () => {
    let saved: unknown = null;
    const PIC = { create: 'BA', balr: 'BA', fixb: 'BAL', jmr: 'BAL', fixj: 'BAL' };
    const SHARES = { create: { ba: 100, bal: 0 }, balr: { ba: 0, bal: 100 }, fixb: { ba: 70, bal: 30 }, jmr: { ba: 0, bal: 0 }, fixj: { ba: 70, bal: 30 } };
    server.use(
      http.get('*/api/v1/settings/step-weights', () => HttpResponse.json({ weights: WEIGHTS, pic: PIC, shares: SHARES, day_split: { create: 2, fix: 1 }, own: false })),
      http.put('*/api/v1/settings/step-weights', async ({ request }) => {
        saved = { anken: new URL(request.url).searchParams.get('anken'), body: await request.json() };
        return HttpResponse.json({ weights: { ...WEIGHTS, balr: 0.5 }, pic: PIC, shares: SHARES, day_split: { create: 1, fix: 1 }, own: true });
      }),
    );
    renderApp('/settings/step-weights?for=PAY-1100');
    fireEvent.change(await screen.findByLabelText('BAL Review weight'), { target: { value: '0.5' } });
    fireEvent.change(screen.getByLabelText('Fix comment BAL BA share'), { target: { value: '80' } });
    fireEvent.change(screen.getByLabelText('Fix comment BAL BAL share'), { target: { value: '30' } });
    expect(screen.getByText(/100 % at most together/)).toBeTruthy(); // 80 + 30
    expect((screen.getByRole('button', { name: 'Save' }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(screen.getByLabelText('Fix comment BAL BAL share'), { target: { value: '20' } });
    expect(screen.getByText('= 67 % and 33 %')).toBeTruthy(); // a BA's day: 2 : 1 by default
    fireEvent.change(screen.getByLabelText("Create part of a BA's day"), { target: { value: '0' } });
    fireEvent.change(screen.getByLabelText("Fix comment part of a BA's day"), { target: { value: '0' } });
    expect(screen.getByText(/not both 0/)).toBeTruthy();
    fireEvent.change(screen.getByLabelText("Create part of a BA's day"), { target: { value: '1' } });
    fireEvent.change(screen.getByLabelText("Fix comment part of a BA's day"), { target: { value: '1' } });
    expect(screen.getByText('= 50 % and 50 %')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(saved).toEqual({ anken: 'PAY-1100', body: { weights: { ...WEIGHTS, balr: 0.5 }, pic: PIC,
      shares: { ...SHARES, fixb: { ba: 80, bal: 20 } }, day_split: { create: 1, fix: 1 } } }));
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
    expect(await within(phases).findByText('10/16')).toBeTruthy();
    expect(within(phases).getByText('30.00h')).toBeTruthy();
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

describe('planning tabs', () => {
  const line = { ref: '2', row_id: 2, issue_key: null, phase: 'ITA', function: 'Run', step: null, summary: '[PAY] Run', pic_id: null, reporter_id: null,
    total_h: 16, spent_h: 0, planned_h: 0, not_yet_h: 16, start: null, end: null, cells: [], logged: [], closed: false, pending: false,
    out_of_order: false, removed: false, edited: false, plan_estimate_h: 16, plan_h: 0, plan_not_yet_h: 16, subphase: 'Execute', pic_slot: 'ba' as string | null };
  const view = { rows: [{ id: 2, epic_key: 'PAY-1100', position: 1, phase: 'ITA', subphase: 'Execute', function_name: 'Run', steps: null, effort_h: 16,
    ba_id: null, bal_id: null, jm_id: null, task_key: 'PAY-1102', pushed_at: null }],
  tasks: [{ key: 'PAY-1102', summary: '[PAY][LocalTask] ITA Execute', name: 'ITA Execute', phase: 'ITA' }], window: ['2026-10-07', '2026-10-08'], lines: [line],
  over_budget: [], allocated: [], over_day: [], checks: [], days: [], stale_phases: [], problems: [], push_blockers: [], by_hand: [], last_version_at: null };

  it('lists the Detail plan tab and no Re-plan tab', async () => {
    renderApp('/planning/schedule?anken=PAY-1100');
    const tabs = await screen.findByRole('tablist', { name: 'Planning' });
    expect(within(tabs).getAllByRole('tab').map((t) => t.textContent)).toEqual(['Gantt', 'Master schedule', 'Detail plan']);
    expect(within(tabs).queryByText('Re-plan')).toBeNull();
  });

  it('shows the matrix of the picked anken', async () => {
    server.use(http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(view)));
    renderApp('/planning/detail?anken=PAY-1100');
    expect((await screen.findAllByText('[PAY] Run')).length).toBeGreaterThan(0);
  });

  // Export: Original Estimate 30h (approved from 3h), 10h planned; 5h logged and 7h remaining are actuals the screen leaves out
  const exportLine = { ...line, ref: 'PAY-1301', row_id: null, issue_key: 'PAY-1301', pic_slot: null, summary: '[PAY] Export', total_h: 12, spent_h: 5,
    planned_h: 12, not_yet_h: 0, plan_estimate_h: 30, plan_h: 10, plan_not_yet_h: 20, cells: [{ day: '2026-10-08', hours: 10, auto: false }] };

  it('shows the planning figures of each line, actuals left out', async () => {
    server.use(http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json({ ...view, rows: [], lines: [exportLine] })));
    renderApp('/planning/detail?anken=PAY-1100');
    const table = await screen.findByRole('region', { name: 'Detail plan matrix' });
    expect(within(table).getAllByRole('columnheader').map((c) => c.textContent).slice(1, 5)).toEqual(['PIC', 'Plan Estimation', 'Planned', 'Not yet plan']);
    expect(within(table).getByLabelText('Plan Estimation, [PAY] Export').textContent).toBe('30.00');
    expect(within(table).queryByRole('spinbutton', { name: 'Total, [PAY] Export' })).toBeNull();
    expect(within(table).getByLabelText('Planned, [PAY] Export').textContent).toBe('10.00');
    const notYet = within(table).getByLabelText('Not yet plan, [PAY] Export');
    expect(notYet.textContent).toBe('20.00');
    expect(notYet.getAttribute('data-left')).toBe('true'); // left to plan: a plain figure, counted in the problems above
  });

  it('saves a version for the baseline and shows why it is refused', async () => {
    let search: string | null = null;
    server.use(
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json({ ...view, rows: [], lines: [exportLine] })),
      http.post('*/api/v1/planning/PAY-1100/detail/versions', ({ request }) => {
        search = new URL(request.url).search;
        return HttpResponse.json({ status: 400, code: 'invalid', detail: 'A version is saved once the plan is in Jira. 1 row is not in Jira yet: push first' }, { status: 400 });
      }),
    );
    renderApp('/planning/detail?anken=PAY-1100');
    fireEvent.click(await screen.findByRole('button', { name: 'Save version' }));
    expect(await screen.findByText(/1 row is not in Jira yet/)).toBeTruthy();
    expect(search).toBe('?baseline=true');
  });

  it('asks for an anken and shows no matrix without one', async () => {
    renderApp('/planning/detail');
    expect(await screen.findByText('Pick an anken in the switcher to plan it.')).toBeTruthy();
    expect(screen.queryByText('[PAY] Run')).toBeNull();
  });

  it('offers only the members of the anken as people', async () => {
    const m = (id: string, name: string, people: boolean) => ({ id, name, full_name: name, role: 'BA', country: 'VN', color: 'red', email: '', permission: 'user',
      external: false, jira_account_id: null, people });
    server.use(
      http.get('*/api/v1/members', () => HttpResponse.json([m('an', 'An', true), m('ext', 'Outsider', false)])),
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json({ ...view, lines: [line, { ...line, ref: 'PAY-1201', row_id: null,
        issue_key: 'PAY-1201', summary: '[PAY] Refund', function: 'Refund', pic_id: 'ext', closed: true, not_yet_h: 0 }] })),
    );
    renderApp('/planning/detail?anken=PAY-1100');
    const pick = await screen.findByLabelText('PIC, [PAY] Run');
    expect([...pick.querySelectorAll('option')].map((o) => o.textContent)).toEqual(['–', 'An']);
    expect(screen.getByLabelText('PIC, [PAY] Refund').textContent).toBe('Outsider'); // named all the same
  });

  it('shows the matrix without write controls to a viewer with read only', async () => {
    const me = { ...fixtures.me, admin: false, permissions: { ...fixtures.me.permissions, planning: 'read' } };
    server.use(
      http.get('*/api/v1/me', () => HttpResponse.json(me)),
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(view)),
    );
    renderApp('/planning/detail?anken=PAY-1100');
    expect((await screen.findAllByText('[PAY] Run')).length).toBeGreaterThan(0);
    for (const n of ['Review push', 'Save version', 'Auto allocate']) expect(screen.queryByRole('button', { name: n })).toBeNull();
  });

  it('shows the not-found page for the removed Re-plan address', async () => {
    renderApp('/planning/replan?phase=ITA');
    expect(await screen.findByText('Page not found')).toBeTruthy();
  });
});
