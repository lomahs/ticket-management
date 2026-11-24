import { act, cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { renderApp, server } from './renderApp';

// how many times each step line renders, by its ref: the real line, behind a memo with the same props
const renders = vi.hoisted(() => new Map<string, number>());
vi.mock('../features/planning/detail/StepLine', async (importOriginal) => {
  const m = await importOriginal<typeof import('../features/planning/detail/StepLine')>();
  const { createElement, memo } = await import('react');
  type Props = Parameters<typeof m.StepLine>[0];
  const Line = (m.StepLine as unknown as { type: (p: Props) => React.ReactNode }).type;
  return { ...m, StepLine: memo((p: Props) => { renders.set(p.l.ref, (renders.get(p.l.ref) ?? 0) + 1); return createElement(Line, p); }) };
});

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => { cleanup(); server.resetHandlers(); localStorage.clear(); vi.restoreAllMocks(); });
afterAll(() => server.close());

const ONB = { epic_key: 'PAY-1100', title: 'Payment flow revision', anken_prefix: 'PAY', labels: [] as string[], step: 3, started_at: '2026-10-05T09:00:00Z',
  started_by: 'minh.hoang@acme.vn', saved_at: '2026-10-07T09:00:00Z', saved_by: 'minh.hoang@acme.vn', finished_at: null, draft: false };
const DRAFT = { ...ONB, epic_key: 'draft-1', title: 'Refund automation', anken_prefix: '', step: 2, draft: true };
const PEOPLE = [
  { member_id: 'an', name: 'An', role: 'BA', country: 'VN', in_anken: true, hours_per_day: 8, elsewhere: [], working_day_h: 8 },
  { member_id: 'minh', name: 'Minh', role: 'BAL', country: 'VN', in_anken: true, hours_per_day: null,
    elsewhere: [{ epic_key: 'PAY-1300', title: 'Refund', hours: 4 }], working_day_h: 8 },
  { member_id: 'tanaka', name: 'Tanaka', role: 'JM', country: 'JP', in_anken: false, hours_per_day: null, elsewhere: [], working_day_h: 8 },
];
const TASK = { key: 'PAY-1102', summary: '[PAY][LocalTask] ITA Create test case', name: 'ITA Create test case', phase: 'ITA' };
const LOGIN = { id: 1, epic_key: 'PAY-1100', position: 0, phase: 'ITA', subphase: 'Create test case', function_name: 'Login', steps: 5, effort_h: 8,
  ba_id: null, bal_id: null, jm_id: null, task_key: 'PAY-1102', pushed_at: null };
const RUN = { ...LOGIN, id: 2, position: 1, subphase: 'Execute', function_name: 'Run', steps: null, effort_h: 16, ba_id: 'an' };

/** The onboarding record and its members, plus whatever the test adds; progress saves are recorded. */
function onboarding(over: Partial<typeof ONB> = {}) {
  const progress: unknown[] = [];
  server.use(
    http.get('*/api/v1/onboarding/PAY-1100', () => HttpResponse.json({ ...ONB, ...over })),
    http.get('*/api/v1/onboarding/PAY-1100/members', () => HttpResponse.json(PEOPLE)),
    http.put('*/api/v1/onboarding/PAY-1100', async ({ request }) => {
      const b = await request.json() as { step: number };
      progress.push(b);
      return HttpResponse.json({ ...ONB, ...over, step: Math.max(ONB.step, b.step) });
    }),
  );
  return progress;
}

/** The members of the anken (Planning reads them from /members): those in the anken can be picked. */
function members() {
  const named = [...PEOPLE, { member_id: 'binh', name: 'Bình', role: 'BA', country: 'VN', in_anken: false },
    { member_id: 'dung', name: 'Dũng', role: 'BA', country: 'VN', in_anken: false }];
  server.use(http.get('*/api/v1/members', () => HttpResponse.json(named.map((p) => ({ id: p.member_id, name: p.name, full_name: p.name, role: p.role,
    country: p.country, color: 'red', email: `${p.member_id}@acme.vn`, permission: 'user', external: false, jira_account_id: null, people: p.in_anken })))));
}

describe('detail plan', () => {
  /** A line of the matrix: a step of a row not in Jira yet (row set) or a sub-task in Jira (key set). */
  const base = (ref: string, row: number | null, step: string | null, summary: string, total: number, over: Record<string, unknown> = {}) => ({
    ref, row_id: row, issue_key: null as string | null, phase: 'ITA', function: row === 2 ? 'Run' : 'Login', step, summary, pic_id: null as string | null,
    reporter_id: null as string | null, total_h: total, spent_h: 0, planned_h: 0, not_yet_h: total, start: null as string | null, end: null as string | null,
    cells: [] as { day: string; hours: number; auto: boolean }[], logged: [] as { day: string; hours: number; auto: boolean }[], closed: false,
    pending: false, out_of_order: false, removed: false, edited: false,
    subphase: row === 2 ? 'Execute' : 'Create test case', pic_slot: (row == null ? null : step === 'balr' ? 'bal' : step === 'jmr' ? 'jm' : 'ba') as string | null, ...over,
  });
  /** As {@link base}, with the planning figures following the plan unless the test sets them. */
  const mline = (ref: string, row: number | null, step: string | null, summary: string, total: number, over: Record<string, unknown> = {}) => {
    const l = base(ref, row, step, summary, total, over);
    return { plan_estimate_h: l.total_h, plan_h: l.planned_h, plan_not_yet_h: l.not_yet_h, ...l };
  };
  /** A day cell's box: a cell becomes one once it has focus. */
  const cellInput = (label: string) => {
    const c = screen.getByLabelText(label);
    if (!(c instanceof HTMLInputElement)) fireEvent.focus(c);
    return screen.getByLabelText(label) as HTMLInputElement;
  };
  const findCell = async (label: string) => { await screen.findByLabelText(label); return cellInput(label); };
  /** What a day cell shows, at rest or typed in. */
  const shown = (el: HTMLElement) => (el instanceof HTMLInputElement ? el.value : el.textContent);
  const LINES = [mline('1:create', 1, 'create', '[PAY] Login – Create', 8), mline('1:balr', 1, 'balr', '[PAY] Login – BAL Review', 2),
    mline('2', 2, null, '[PAY] Run', 16)];
  // today is 2026-10-07 (meta): the 6th is past
  const WINDOW = ['2026-10-06', '2026-10-07', '2026-10-08', '2026-10-09', '2026-10-12', '2026-10-13', '2026-10-14'];
  const REFUND = mline('PAY-1201', null, 'create', '[PAY] Refund – Create', 3, { issue_key: 'PAY-1201', function: 'Refund', pic_id: 'an', subphase: 'Create test case',
    spent_h: 1, planned_h: 3, not_yet_h: 0, start: '2026-10-06', end: '2026-10-08',
    cells: [{ day: '2026-10-06', hours: 1, auto: false }, { day: '2026-10-08', hours: 2, auto: false }], logged: [{ day: '2026-10-06', hours: 1.5, auto: false }] });
  const detailView = (over: Record<string, unknown> = {}) => ({
    rows: [LOGIN, RUN], tasks: [TASK], window: WINDOW, lines: LINES, over_budget: [], allocated: [], over_day: [], checks: [], days: [],
    stale_phases: [], problems: [], push_blockers: [], by_hand: [], last_version_at: null, issues: [], ...over,
  });
  const run = (over: Record<string, unknown>) => mline('2', 2, null, '[PAY] Run', 16, over);
  const planned = {
    lines: [...LINES.slice(0, 2), run({ planned_h: 16, not_yet_h: 0, start: '2026-10-12', end: '2026-10-14', pic_id: 'an',
      cells: [{ day: '2026-10-12', hours: 8, auto: true }, { day: '2026-10-14', hours: 8, auto: true }] })],
    checks: [{ phase: 'ITA', master_end: '2026-10-13', plan_end: '2026-10-14', days_late: 1 }],
    days: [{ member_id: 'an', date: '2026-10-11', planned_h: 0, allocated_h: 0, off: 'Weekend', other_h: 0, items: [] },
      { member_id: 'an', date: '2026-10-12', planned_h: 8, allocated_h: 8, off: null, other_h: 0, items: [{ key: null, title: '[PAY] Run', subphase: 'ITA Execute', function: 'Run', hours: 8, other: false }] },
      { member_id: 'an', date: '2026-10-13', planned_h: 9, allocated_h: 8, off: null, other_h: 3, items: [{ key: null, title: '[PAY] Run', subphase: 'ITA Execute', function: 'Run', hours: 8, other: false },
        { key: 'PAY-1290', title: 'Refund – Create', subphase: 'ITA Prepare test environment and data', function: 'SCR-0042 Refund', hours: 1, other: false },
        { key: 'PAY-1300', title: 'Refund', subphase: null, function: null, hours: 3, other: true }] }],
  };

  it('highlights the people picked by hand and clears the plan with or without them', async () => {
    const clears: string[] = [];
    onboarding({ step: 5 });
    members();
    const view = detailView({ rows: [{ ...LOGIN, ba_id: 'an', bal_id: 'minh', jm_id: 'tanaka' }, RUN], ...planned,
      by_hand: [{ row_id: 1, ba: true, bal: false, jm: false }] });
    server.use(
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(view)),
      http.post('*/api/v1/planning/PAY-1100/detail/clear', ({ request }) => {
        clears.push(new URL(request.url).searchParams.get('keep_input') ?? '');
        return HttpResponse.json(view);
      }),
    );
    renderApp('/planning/detail?anken=PAY-1100');
    // the first test of the file waits for the lazy Planning route too
    const ba = await screen.findByLabelText('PIC, [PAY] Login – Create', {}, { timeout: 5000 }); // Create's PIC is the function's BA
    expect(ba.getAttribute('data-by-hand')).toBe('true');
    expect(screen.getByLabelText('PIC, [PAY] Login – BAL Review').getAttribute('data-by-hand')).toBeNull(); // the BAL: Auto allocate's pick
    fireEvent.click(screen.getByRole('button', { name: 'More' }));
    fireEvent.click(screen.getByRole('menuitem', { name: 'Clear, keep my input' }));
    await waitFor(() => expect(clears).toEqual(['true']));
    expect(screen.queryByRole('menu')).toBeNull(); // a pick closes the menu
    fireEvent.click(screen.getByRole('button', { name: 'More' }));
    fireEvent.click(screen.getByRole('menuitem', { name: 'Clear all' }));
    const dialog = await screen.findByRole('dialog', { name: 'Clear the plan' });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Clear all' }));
    await waitFor(() => expect(clears).toEqual(['true', 'false']));
    expect(await screen.findByText('Plan cleared')).toBeTruthy();
  });

  it('assigns members and shows the plan with the effort per member per day above it', async () => {
    let saved: { assigns: Record<string, unknown>[] } | null = null;
    onboarding({ step: 5 });
    members();
    server.use(
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView({ problems: ['Row 1 (Login): pick a BA'],
        issues: [{ row_id: 1, ref: null, field: 'pic_ba', message: 'Row 1 (Login): pick a BA' }] }))),
      http.put('*/api/v1/planning/PAY-1100/detail', async ({ request }) => {
        saved = await request.json() as typeof saved;
        return HttpResponse.json(detailView({ rows: [{ ...LOGIN, ba_id: 'an' }, RUN], ...planned }));
      }),
    );
    renderApp('/planning/detail?anken=PAY-1100');
    const ba = await screen.findByLabelText('PIC, [PAY] Login – Create');
    expect(screen.queryByLabelText('BA, Login')).toBeNull(); // picked on the steps' PIC cells, not on the function
    expect(screen.queryByText('Row 1 (Login): pick a BA')).toBeNull(); // not on the first visit, only counted
    expect(screen.getByRole('button', { name: '1 problem before the push' })).toBeTruthy();
    expect([...ba.querySelectorAll('option')].map((o) => o.textContent)).toEqual(['–', 'An', 'Minh']); // anyone but a JM
    expect((screen.getByLabelText('PIC, [PAY] Run') as HTMLSelectElement).value).toBe('an'); // a ticket: its member
    expect(screen.queryByRole('button', { name: 'Check' })).toBeNull();
    expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual(['Gantt', 'Master schedule', 'Detail plan']); // the effort per day is always above the matrix
    fireEvent.click(screen.getByRole('button', { name: 'Review push' })); // what stands before the push, as a table
    const table = await screen.findByRole('dialog', { name: 'Before the push' });
    const create = within(table).getByRole('row', { name: 'Login – Create' });
    expect(within(create).getByLabelText('X: pick a BA')).toBeTruthy();
    expect(within(create).getAllByLabelText('OK')).toHaveLength(3); // Task, Not yet plan, Step order
    expect(within(table).queryByRole('row', { name: 'Login – BAL Review' })).toBeNull(); // its slot is the BAL's
    fireEvent.click(within(table).getByRole('button', { name: 'Close' }));
    expect(screen.getByRole('row', { name: '[PAY] Login – BAL Review' })).toBeTruthy(); // one line per step, named by the name rules
    fireEvent.change(screen.getByLabelText('PIC, [PAY] Login – Create'), { target: { value: 'an' } });
    await waitFor(() => expect(saved).toEqual({ order: [1, 2], assigns: [{ row_id: 1, ba_id: 'an', bal_id: null, jm_id: null }, { row_id: 2, ba_id: 'an', bal_id: null, jm_id: null }] }));
    const runRow = await screen.findByRole('row', { name: '[PAY] Run' });
    await waitFor(() => expect(within(runRow).getByLabelText('Start, [PAY] Run').textContent).toBe('10/12'));
    expect(within(runRow).getByLabelText('End, [PAY] Run').textContent).toBe('10/14');
    expect(within(runRow).getByLabelText('Planned, [PAY] Run').textContent).toBe('16.00');
    expect(shown(within(runRow).getByLabelText('[PAY] Run 2026-10-14'))).toBe('8');
    expect((within(runRow).getByLabelText('PIC, [PAY] Run') as HTMLSelectElement).value).toBe('an'); // who carries the line
    expect(screen.getByText('ITA ends 10/14 · master 10/13 · 1 day late')).toBeTruthy();
    const days = screen.getByRole('region', { name: 'Effort per member per day' });
    const tue = within(days).getByLabelText('An 2026-10-13');
    expect(tue.textContent).toBe('9.00+3.00 other'); // the planned hours only, red above the 8h allocation
    expect(within(days).getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['Member', 'Mon', 'Tue', '10/12', '10/13']); // no weekend
    fireEvent.mouseEnter(tue);
    const tip = screen.getByRole('tooltip');
    expect(days.contains(tip)).toBe(false); // above the page, not clipped by the section
    expect(tip.textContent).toContain('PAY-1290 · Refund – Create'); // a task of this anken
    expect(within(tip).getByText('ITA Prepare test environment and data › SCR-0042 Refund')).toBeTruthy(); // its sub-phase and function in full
    expect(tip.textContent).toContain('Other anken');
    expect(tip.textContent).toContain('Refund3.00h'); // another anken: its name and hours
  });

  it('groups the plan by phase, Task and function; a folded level shows its hours per day', async () => {
    onboarding({ step: 5 });
    members();
    server.use(http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView({ ...planned }))));
    renderApp('/planning/detail?anken=PAY-1100');
    const phase = await screen.findByRole('row', { name: 'Phase ITA' });
    expect(within(phase).getByText('2 functions')).toBeTruthy();
    expect(within(phase).getByText('ends 10/14 · master 10/13 · 1 day late')).toBeTruthy(); // the master schedule check, on the right
    expect(screen.getByRole('row', { name: 'Sub-phase Create test case' })).toBeTruthy();
    expect(screen.getByRole('row', { name: 'Function Login' })).toBeTruthy();
    // a step shows its step's name; the sub-task's whole name is the row's
    expect(within(screen.getByRole('row', { name: '[PAY] Login – BAL Review' })).getByText('BAL Review')).toBeTruthy();
    expect(within(screen.getByRole('row', { name: '[PAY] Login – BAL Review' })).getByText('New')).toBeTruthy(); // its ticket ID once it is in Jira
    const execute = screen.getByRole('row', { name: 'Sub-phase Execute' });
    expect(within(execute).getByText('16.00h estimated')).toBeTruthy();
    fireEvent.click(within(execute).getByRole('button', { name: 'Hide the functions of Execute' }));
    expect(screen.queryByRole('row', { name: '[PAY] Run' })).toBeNull();
    expect(screen.queryByRole('row', { name: 'Function Run' })).toBeNull();
    const folded = screen.getByRole('row', { name: 'Sub-phase Execute' });
    expect([...folded.querySelectorAll('td.pm-sum')].map((c) => c.textContent)).toEqual(['–', '–', '–', '–', '8.00', '–', '8.00']); // its hours per day
    fireEvent.click(within(folded).getByRole('button', { name: 'Show the functions of Execute' }));
    fireEvent.click(screen.getByRole('button', { name: 'Collapse all' }));
    expect(screen.queryByRole('row', { name: '[PAY] Login – Create' })).toBeNull(); // functions only
    const run = screen.getByRole('row', { name: 'Function Run' });
    expect(within(run).getAllByText('16.00')).toHaveLength(2); // its Plan Estimation and Planned on the folded row
    fireEvent.click(screen.getByRole('button', { name: 'Expand all' }));
    expect(screen.getByRole('row', { name: '[PAY] Login – Create' })).toBeTruthy();
  });

  it('filters the steps by words, PIC and status, and clears the filters', async () => {
    onboarding({ step: 5 });
    members();
    server.use(http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView({ ...planned }))));
    renderApp('/planning/detail?anken=PAY-1100');
    const search = await screen.findByLabelText('Search steps or functions');
    fireEvent.keyDown(document, { key: 'k', ctrlKey: true });
    expect(document.activeElement).toBe(search);
    fireEvent.change(search, { target: { value: 'review' } });
    expect(screen.getByText('1 of 3 steps')).toBeTruthy();
    expect(screen.getByRole('row', { name: '[PAY] Login – BAL Review' })).toBeTruthy();
    expect(screen.queryByRole('row', { name: '[PAY] Run' })).toBeNull();
    expect(screen.queryByRole('row', { name: 'Sub-phase Execute' })).toBeNull(); // nothing left under it
    fireEvent.click(screen.getByRole('button', { name: 'Clear all' }));
    expect(screen.getByRole('row', { name: '[PAY] Run' })).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'PIC' }));
    const pic = screen.getByRole('group', { name: 'PIC' });
    fireEvent.click(within(pic).getByLabelText('Unassigned'));
    expect(screen.getByRole('button', { name: /^PIC/, expanded: true }).textContent).toBe('PIC (1)');
    expect(screen.queryByRole('row', { name: '[PAY] Run' })).toBeNull(); // An carries it
    expect(screen.getByRole('row', { name: '[PAY] Login – Create' })).toBeTruthy();
    fireEvent.click(within(pic).getByRole('button', { name: 'Unselect all' }));
    expect(screen.getByRole('row', { name: '[PAY] Run' })).toBeTruthy();
    expect((within(pic).getByRole('button', { name: 'Unselect all' }) as HTMLButtonElement).disabled).toBe(true);

    fireEvent.keyDown(document, { key: 'Escape' });
    fireEvent.click(screen.getByRole('button', { name: 'Status' }));
    fireEvent.click(within(screen.getByRole('group', { name: 'Status' })).getByLabelText('Planned'));
    expect(screen.getByText('1 of 3 steps')).toBeTruthy(); // only Run has hours on its days
    expect(screen.getByRole('row', { name: '[PAY] Run' })).toBeTruthy();
  });

  it('shifts the picked functions by working days', async () => {
    const sent: unknown[] = [];
    onboarding({ step: 5 });
    members();
    const view = detailView({ lines: [LINES[0], mline('1:balr', 1, 'balr', '[PAY] Login – BAL Review', 2, { removed: true }), LINES[2]] });
    server.use(
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(view)),
      http.post('*/api/v1/planning/PAY-1100/detail/shift', async ({ request }) => { sent.push(await request.json()); return HttpResponse.json(view); }),
    );
    renderApp('/planning/detail?anken=PAY-1100');
    fireEvent.click(await screen.findByLabelText('Pick Login'));
    const bar = screen.getByRole('group', { name: 'Picked functions and steps' });
    expect(within(bar).getByText('1 function selected')).toBeTruthy();
    const n = within(bar).getByLabelText('Working days') as HTMLInputElement;
    fireEvent.change(n, { target: { value: '' } });
    expect(n.value).toBe(''); // cleared while it is typed
    fireEvent.change(n, { target: { value: '2' } });
    fireEvent.change(within(bar).getByLabelText('Direction'), { target: { value: 'earlier' } });
    fireEvent.click(within(bar).getByRole('button', { name: 'Shift' }));
    await waitFor(() => expect(sent).toEqual([{ refs: ['1:create'], days: -2 }])); // a removed step stays
    await waitFor(() => expect(screen.queryByRole('group', { name: 'Picked functions and steps' })).toBeNull());
  });

  it('forgets a picked function that is no longer in the plan', async () => {
    onboarding({ step: 5 });
    members();
    server.use(
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView())),
      http.put('*/api/v1/planning/PAY-1100/detail/cells', () => HttpResponse.json(detailView({ rows: [RUN], lines: [LINES[2]] }))),
    );
    renderApp('/planning/detail?anken=PAY-1100');
    fireEvent.click(await screen.findByLabelText('Pick Login'));
    expect(screen.getByRole('group', { name: 'Picked functions and steps' })).toBeTruthy();
    const cell = cellInput('[PAY] Run 2026-10-12');
    fireEvent.change(cell, { target: { value: '8' } });
    fireEvent.blur(cell);
    await waitFor(() => expect(screen.queryByRole('group', { name: 'Picked functions and steps' })).toBeNull());
  });

  it('shows the remaining in Jira of a sub-task, for reference only', async () => {
    onboarding({ step: 5 });
    members();
    server.use(http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView({
      lines: [...LINES, { ...REFUND, actual_remaining_h: 2.5 }] }))));
    renderApp('/planning/detail?anken=PAY-1100');
    expect((await screen.findByLabelText('Actual remaining, [PAY] Refund – Create')).textContent).toBe('2.50');
    expect(screen.getByLabelText('Actual remaining, [PAY] Run').textContent).toBe('–'); // not in Jira yet
    expect(screen.getByRole('columnheader', { name: 'Actual remaining' })).toBeTruthy();
  });

  it('force pushes past what stands before the push', async () => {
    const sent: unknown[] = [];
    onboarding({ step: 5 });
    members();
    server.use(
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView({ push_blockers: ['[PAY] Run: 16h not planned yet'],
        issues: [{ row_id: null, ref: '2', field: 'not_yet', message: '[PAY] Run: 16h not planned yet' }] }))),
      http.post('*/api/v1/planning/PAY-1100/detail/push/preview', async ({ request }) => { sent.push(['preview', await request.json()]); return HttpResponse.json({ updates: [], creates: [] }); }),
      http.post('*/api/v1/planning/PAY-1100/detail/push', async ({ request }) => { sent.push(['push', await request.json()]); return HttpResponse.json({ updated: [], created: {} }); }),
    );
    renderApp('/planning/detail?anken=PAY-1100');
    fireEvent.click(await screen.findByRole('button', { name: 'Review push' }));
    const table = await screen.findByRole('dialog', { name: 'Before the push' });
    expect(within(within(table).getByRole('row', { name: 'Run' })).getByLabelText('X: 16h not planned yet')).toBeTruthy();
    expect(sent).toEqual([]); // nothing asked of the server while the problems show
    fireEvent.click(within(table).getByRole('button', { name: 'Force push' }));
    const review = await screen.findByRole('dialog', { name: 'Push review' });
    expect(within(review).getByRole('note', { name: 'Forced' }).textContent).toContain('Run: 16h not planned yet');
    fireEvent.click(within(review).getByRole('button', { name: 'Push to Jira' }));
    await waitFor(() => expect(sent).toEqual([['preview', { refs: null, force: true }], ['push', { refs: null, force: true }]]));
  });

  it('pushes only the picked functions and steps', async () => {
    const sent: unknown[] = [];
    onboarding({ step: 5 });
    members();
    server.use(
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView())),
      http.post('*/api/v1/planning/PAY-1100/detail/push/preview', async ({ request }) => { sent.push(await request.json()); return HttpResponse.json({ updates: [], creates: [] }); }),
    );
    renderApp('/planning/detail?anken=PAY-1100');
    fireEvent.click(await screen.findByLabelText('Pick [PAY] Login – BAL Review'));
    fireEvent.click(screen.getByLabelText('Pick Run'));
    const bar = screen.getByRole('group', { name: 'Picked functions and steps' });
    expect(within(bar).getByText('1 function, 1 step selected')).toBeTruthy();
    fireEvent.click(within(bar).getByRole('button', { name: 'Push' }));
    await waitFor(() => expect(sent).toEqual([{ refs: ['2', '1:balr'], force: false }]));
    expect(await screen.findByRole('dialog', { name: 'Push review' })).toBeTruthy();
  });

  it('opens a sub-task in Jira in the ticket panel', async () => {
    onboarding({ step: 5 });
    members();
    server.use(http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView({ lines: [...LINES, REFUND] }))));
    const { router } = renderApp('/planning/detail?anken=PAY-1100');
    fireEvent.click(await screen.findByRole('button', { name: 'Open PAY-1201' }));
    await waitFor(() => expect((router.state.location.search as { ticket?: string }).ticket).toBe('PAY-1201'));
    expect(screen.queryByRole('button', { name: /^Open New/ })).toBeNull(); // not in Jira yet: nothing to open
  });

  it('shows a done sub-task as Done and locks it', async () => {
    onboarding({ step: 5 });
    members();
    server.use(http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView({ lines: [...LINES, { ...REFUND, closed: true }] }))));
    renderApp('/planning/detail?anken=PAY-1100');
    const row = await screen.findByRole('row', { name: '[PAY] Refund – Create' });
    expect(within(row).getByText('Done')).toBeTruthy();
    expect(within(row).queryByRole('textbox')).toBeNull(); // no day can be typed
    expect(within(row).queryByRole('combobox')).toBeNull(); // nor its PIC picked
    expect(within(row).queryByRole('checkbox')).toBeNull(); // nor picked to shift or push
  });

  it('deletes a removed step for good next to its Restore', async () => {
    const sent: string[] = [];
    onboarding({ step: 5 });
    members();
    const view = detailView({ lines: [LINES[0], mline('1:balr', 1, 'balr', '[PAY] Login – BAL Review', 2, { removed: true }), LINES[2]] });
    server.use(
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(view)),
      http.delete('*/api/v1/planning/PAY-1100/detail/step', ({ request }) => {
        sent.push(new URL(request.url).search);
        return HttpResponse.json(detailView({ lines: [LINES[0], LINES[2]] }));
      }),
    );
    renderApp('/planning/detail?anken=PAY-1100');
    expect(await screen.findByRole('button', { name: 'Restore [PAY] Login – BAL Review' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Delete [PAY] Login – Create' })).toBeNull(); // removed first, then deleted
    fireEvent.click(screen.getByRole('button', { name: 'Delete [PAY] Login – BAL Review' }));
    await waitFor(() => expect(sent).toEqual(['?row_id=1&step=balr']));
    await waitFor(() => expect(screen.queryByRole('row', { name: '[PAY] Login – BAL Review' })).toBeNull());
  });

  it('marks each problem on its cell and each warning on its day, with what is wrong on hover', async () => {
    onboarding({ step: 5 });
    members();
    server.use(http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView({ ...planned,
      issues: [
        { row_id: 1, ref: null, field: 'pic_ba', message: 'Row 1 (Login): pick a BA' },
        { row_id: 1, ref: null, field: 'task', message: 'Row 1 (Login): choose a Task' },
        { row_id: null, ref: '1:create', field: 'not_yet', message: '[PAY] Login – Create: 8h not planned yet' },
      ],
      over_day: [{ member_id: 'an', date: '2026-10-12', planned_h: 9, allocation_h: 8 }] }))));
    renderApp('/planning/detail?anken=PAY-1100');
    const create = await screen.findByRole('row', { name: '[PAY] Login – Create' });
    const pic = within(create).getByLabelText('PIC, [PAY] Login – Create').closest('td') as HTMLElement;
    expect(pic.className).toContain('pm-bad');
    expect(within(pic).getByRole('img', { name: 'Invalid: Row 1 (Login): pick a BA' })).toBeTruthy();
    const notYet = within(create).getByLabelText('Not yet plan, [PAY] Login – Create');
    expect(notYet.className).toContain('pm-bad');
    const mark = within(notYet).getByRole('img', { name: 'Invalid: [PAY] Login – Create: 8h not planned yet' });
    fireEvent.mouseEnter(mark);
    expect(screen.getByRole('tooltip').textContent).toBe('[PAY] Login – Create: 8h not planned yet');
    expect(within(screen.getByRole('row', { name: '[PAY] Login – BAL Review' })).getByLabelText('PIC, [PAY] Login – BAL Review').closest('td')?.className)
      .not.toContain('pm-bad'); // the BAL's slot is fine
    expect(within(screen.getByRole('row', { name: 'Function Login' })).getByRole('img', { name: 'Invalid: Row 1 (Login): choose a Task' })).toBeTruthy();
    const day = within(screen.getByRole('row', { name: '[PAY] Run' })).getByLabelText('[PAY] Run 2026-10-12').closest('td') as HTMLElement;
    expect(day.className).toContain('warn'); // An is over the day's allocation
    expect(within(day).getByRole('img', { name: /^Warning: An has 9/ })).toBeTruthy();
  });

  it('types hours into a day and sends them when the cell is left', async () => {
    const sent: unknown[] = [];
    onboarding({ step: 5 });
    members();
    server.use(
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView())),
      http.put('*/api/v1/planning/PAY-1100/detail/cells', async ({ request }) => {
        sent.push(await request.json());
        return HttpResponse.json(detailView({ lines: [...LINES.slice(0, 2),
          run({ planned_h: 8, not_yet_h: 8, start: '2026-10-12', end: '2026-10-12', cells: [{ day: '2026-10-12', hours: 8, auto: false }] })] }));
      }),
    );
    renderApp('/planning/detail?anken=PAY-1100');
    const cell = await findCell('[PAY] Run 2026-10-12');
    expect(within(screen.getByRole('row', { name: '[PAY] Run' })).getByLabelText('Not yet plan, [PAY] Run').textContent).toBe('16.00');
    fireEvent.change(cell, { target: { value: '8' } });
    expect(sent).toEqual([]); // not while it is typed
    expect(cell.value).toBe('8');
    fireEvent.blur(cell);
    await waitFor(() => expect(sent).toEqual([{ cells: [{ ref: '2', day: '2026-10-12', hours: 8 }] }]));
    const row = screen.getByRole('row', { name: '[PAY] Run' });
    await waitFor(() => expect(within(row).getByLabelText('Not yet plan, [PAY] Run').textContent).toBe('8.00'));
    expect(within(row).getByLabelText('Start, [PAY] Run').textContent).toBe('10/12');
    expect(shown(screen.getByLabelText('[PAY] Run 2026-10-12'))).toBe('8');
    const next = cellInput('[PAY] Run 2026-10-13');
    fireEvent.change(next, { target: { value: '' } });
    fireEvent.keyDown(next, { key: 'Enter' });
    fireEvent.blur(next); // nothing changed: nothing sent
    expect(sent).toHaveLength(1);
  });

  it('renders again only the line whose plan changed', async () => {
    const sent: unknown[] = [];
    onboarding({ step: 5 });
    members();
    server.use(
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView())),
      http.put('*/api/v1/planning/PAY-1100/detail/cells', async ({ request }) => {
        sent.push(await request.json());
        return HttpResponse.json(detailView({ lines: [...LINES.slice(0, 2),
          run({ planned_h: 8, not_yet_h: 8, cells: [{ day: '2026-10-12', hours: 8, auto: false }] })] }));
      }),
    );
    renderApp('/planning/detail?anken=PAY-1100');
    await screen.findByLabelText('[PAY] Run 2026-10-12');
    await new Promise((r) => setTimeout(r, 100)); // everything the screen reads has come
    const login = renders.get('1:create') ?? 0;
    const runs = renders.get('2') ?? 0;
    expect(login).toBeGreaterThan(0);
    const cell = cellInput('[PAY] Run 2026-10-12');
    fireEvent.change(cell, { target: { value: '8' } });
    fireEvent.blur(cell);
    await waitFor(() => expect(sent).toHaveLength(1));
    await waitFor(() => expect(within(screen.getByRole('row', { name: '[PAY] Run' })).getByLabelText('Not yet plan, [PAY] Run').textContent).toBe('8.00'));
    await new Promise((r) => setTimeout(r, 100)); // the plan that follows has come back
    expect(renders.get('2')).toBeGreaterThan(runs);
    expect(renders.get('1:create')).toBe(login); // Login's lines did not change
  });

  it('has one box in the matrix, the day cell being typed', async () => {
    onboarding({ step: 5 });
    members();
    server.use(http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView())));
    renderApp('/planning/detail?anken=PAY-1100');
    const cell = await screen.findByLabelText('[PAY] Run 2026-10-12');
    const boxes = () => document.querySelectorAll('.pm-table .pm-cell input');
    expect(boxes()).toHaveLength(0);
    expect(cell.getAttribute('role')).toBe('button');
    fireEvent.focus(cell);
    expect(boxes()).toHaveLength(1);
    expect(document.activeElement).toBe(screen.getByLabelText('[PAY] Run 2026-10-12'));
    fireEvent.blur(document.activeElement as HTMLElement);
    expect(boxes()).toHaveLength(0);
  });

  it('sends a cell when the next one takes the focus, as Tab does, and types in the next', async () => {
    const sent: unknown[] = [];
    onboarding({ step: 5 });
    members();
    server.use(
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView())),
      http.put('*/api/v1/planning/PAY-1100/detail/cells', async ({ request }) => { sent.push(await request.json()); return HttpResponse.json(detailView()); }),
    );
    renderApp('/planning/detail?anken=PAY-1100');
    const cell = await findCell('[PAY] Run 2026-10-12');
    fireEvent.change(cell, { target: { value: '8' } });
    act(() => (screen.getByLabelText('[PAY] Run 2026-10-13') as HTMLElement).focus());
    await waitFor(() => expect(sent).toEqual([{ cells: [{ ref: '2', day: '2026-10-12', hours: 8 }] }]));
    const next = screen.getByLabelText('[PAY] Run 2026-10-13');
    expect(next.tagName).toBe('INPUT');
    expect(document.activeElement).toBe(next);
  });

  it('plans the past days of a Jira line too and shows the hours logged', async () => {
    onboarding({ step: 5 });
    members();
    server.use(http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView({ lines: [...LINES, { ...REFUND, pending: true }] }))));
    renderApp('/planning/detail?anken=PAY-1100');
    const row = await screen.findByRole('row', { name: '[PAY] Refund – Create' });
    expect(shown(screen.getByLabelText('[PAY] Refund – Create 2026-10-06'))).toBe('1'); // a past day: its plan, editable
    const past = row.querySelector('[data-day="2026-10-06"]') as HTMLElement;
    expect(within(past).getByText('1.5h')).toBeTruthy(); // the hours logged
    expect(screen.getByLabelText('[PAY] Refund – Create 2026-10-07')).toBeTruthy(); // today stays open: a locked day has no box
    expect(shown(screen.getByLabelText('[PAY] Refund – Create 2026-10-08'))).toBe('2');
    expect(within(row).getByText('Not pushed')).toBeTruthy();
    expect(within(row).getByText('PAY-1201')).toBeTruthy();
  });

  it('names a PIC outside the anken and marks only a real Not yet of an open line', async () => {
    onboarding({ step: 5 });
    members();
    server.use(http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView({ lines: [LINES[0], LINES[1], run({ not_yet_h: 0.01 }),
      { ...REFUND, pic_id: 'binh', closed: true, not_yet_h: -2 },
      { ...REFUND, ref: 'PAY-1202', issue_key: 'PAY-1202', summary: '[PAY] Refund – BAL Review', step: 'balr', pic_id: 'dung' }] }))));
    renderApp('/planning/detail?anken=PAY-1100');
    const closed = await screen.findByRole('row', { name: '[PAY] Refund – Create' });
    expect(within(closed).getByLabelText('PIC, [PAY] Refund – Create').textContent).toBe('Bình'); // by name, though not in the anken
    const pic = screen.getByLabelText('PIC, [PAY] Refund – BAL Review') as HTMLSelectElement;
    expect([...pic.querySelectorAll('option')].map((o) => o.textContent)).toEqual(['An', 'Minh', 'Dũng']); // the anken's members, and who has it
    const left = (summary: string) => screen.getByLabelText(`Not yet plan, ${summary}`).getAttribute('data-left');
    expect(left('[PAY] Login – Create')).toBe('true'); // 8h left
    expect(left('[PAY] Run')).toBeNull(); // a hundredth is planned enough, as the push sees it
    expect(left('[PAY] Refund – Create')).toBeNull(); // Closed: done
  });

  it('changes the PIC of a Jira line; its Plan Estimation is changed in Jira', async () => {
    const sent: unknown[] = [];
    onboarding({ step: 5 });
    members();
    const view = detailView({ lines: [...LINES, REFUND] });
    server.use(
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(view)),
      http.put('*/api/v1/planning/PAY-1100/detail/jira/PAY-1201', async ({ request }) => {
        sent.push(await request.json());
        return HttpResponse.json(view);
      }),
    );
    renderApp('/planning/detail?anken=PAY-1100');
    const total = await screen.findByLabelText('Plan Estimation, [PAY] Refund – Create');
    expect(total.tagName).toBe('SPAN'); // the Original Estimate: not typed here
    expect(total.textContent).toBe('3.00');
    const pic = screen.getByLabelText('PIC, [PAY] Refund – Create') as HTMLSelectElement;
    expect(pic.value).toBe('an');
    expect([...pic.querySelectorAll('option')].map((o) => o.textContent)).toEqual(['An', 'Minh']); // the anken's members
    fireEvent.change(pic, { target: { value: 'minh' } });
    await waitFor(() => expect(sent).toEqual([{ total_h: null, pic_id: 'minh' }]));
  });

  it('marks a step out of order and blocks the push', async () => {
    onboarding({ step: 5 });
    members();
    server.use(http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView({
      lines: [LINES[0], { ...LINES[1], out_of_order: true, cells: [{ day: '2026-10-08', hours: 2, auto: false }] }, LINES[2]],
      push_blockers: ['[PAY] Login – BAL Review: starts before the step before it ends'],
      issues: [{ row_id: null, ref: '1:balr', field: 'order', message: '[PAY] Login – BAL Review: starts before the step before it ends' }],
    }))));
    renderApp('/planning/detail?anken=PAY-1100');
    const row = await screen.findByRole('row', { name: '[PAY] Login – BAL Review' });
    expect(row.getAttribute('data-out-of-order')).toBe('true');
    const cell = within(row).getByLabelText('[PAY] Login – BAL Review 2026-10-08').closest('td');
    expect(cell?.getAttribute('data-out-of-order')).toBe('true');
    expect(screen.getByRole('row', { name: '[PAY] Run' }).getAttribute('data-out-of-order')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '1 problem before the push' }));
    const table = await screen.findByRole('dialog', { name: 'Before the push' });
    expect(within(table).getByLabelText('X: starts before the step before it ends')).toBeTruthy();
    fireEvent.click(within(table).getByRole('button', { name: /Login – BAL Review/ })); // goes to the line on the plan
    expect(screen.queryByRole('dialog', { name: 'Before the push' })).toBeNull();
  });

  it('sends a write after the cell saved before it, not alongside it', async () => {
    const order: string[] = [];
    let release = () => {};
    onboarding({ step: 5 });
    members();
    server.use(
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView())),
      http.put('*/api/v1/planning/PAY-1100/detail/cells', async () => {
        order.push('cells');
        if (order.length > 1) return HttpResponse.json(detailView());
        await new Promise<void>((r) => { release = r; });
        order.push('cells done');
        return HttpResponse.json(detailView());
      }),
      http.post('*/api/v1/planning/PAY-1100/detail/versions', () => { order.push('version'); return HttpResponse.json(detailView()); }),
    );
    renderApp('/planning/detail?anken=PAY-1100');
    const cell = await findCell('[PAY] Run 2026-10-12');
    fireEvent.change(cell, { target: { value: '8' } });
    fireEvent.blur(cell);
    await waitFor(() => expect(order).toEqual(['cells']));
    const next = cellInput('[PAY] Run 2026-10-13'); // typed while the first cell is being saved
    fireEvent.change(next, { target: { value: '4' } });
    fireEvent.blur(next);
    fireEvent.click(screen.getByRole('button', { name: 'Save version' }));
    await new Promise((r) => setTimeout(r, 100));
    expect(order).toEqual(['cells']); // the version waits for the cell
    release();
    // the cell typed before the version goes before it
    await waitFor(() => expect(order).toEqual(['cells', 'cells done', 'cells', 'version']));
  });

  it('sends the cells typed while a write is in flight in one request', async () => {
    const sent: unknown[] = [];
    let release = () => {};
    onboarding({ step: 5 });
    members();
    server.use(
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView())),
      http.put('*/api/v1/planning/PAY-1100/detail/cells', async ({ request }) => {
        sent.push(await request.json());
        if (sent.length === 1) await new Promise<void>((r) => { release = r; });
        return HttpResponse.json(detailView());
      }),
    );
    renderApp('/planning/detail?anken=PAY-1100');
    const type = (day: string, value: string) => {
      const c = cellInput(`[PAY] Run ${day}`);
      fireEvent.change(c, { target: { value } });
      fireEvent.blur(c);
    };
    await screen.findByLabelText('[PAY] Run 2026-10-12');
    type('2026-10-12', '8');
    await waitFor(() => expect(sent).toHaveLength(1));
    type('2026-10-13', '4');
    type('2026-10-13', '2'); // the same day again: only the last goes
    type('2026-10-14', '1');
    release();
    await waitFor(() => expect(sent).toEqual([{ cells: [{ ref: '2', day: '2026-10-12', hours: 8 }] },
      { cells: [{ ref: '2', day: '2026-10-13', hours: 2 }, { ref: '2', day: '2026-10-14', hours: 1 }] }]));
    await new Promise((r) => setTimeout(r, 50));
    expect(sent).toHaveLength(2);
  });

  it('shows the typed hours at once, before the plan comes back', async () => {
    let release = () => {};
    onboarding({ step: 5 });
    members();
    server.use(
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView())),
      http.put('*/api/v1/planning/PAY-1100/detail/cells', async () => {
        await new Promise<void>((r) => { release = r; });
        return HttpResponse.json(detailView());
      }),
    );
    renderApp('/planning/detail?anken=PAY-1100');
    const cell = await findCell('[PAY] Run 2026-10-12');
    fireEvent.change(cell, { target: { value: '8' } });
    fireEvent.blur(cell);
    const row = screen.getByRole('row', { name: '[PAY] Run' });
    await waitFor(() => expect(within(row).getByLabelText('Planned, [PAY] Run').textContent).toBe('8.00'));
    expect(within(row).getByLabelText('Not yet plan, [PAY] Run').textContent).toBe('8.00');
    const foot = document.querySelector('tr.pm-foot') as HTMLElement;
    expect(foot.children[8 + WINDOW.indexOf('2026-10-12')].textContent).toBe('8.00'); // the total of the day
    release();
  });

  it('puts the figures back when the write is refused', async () => {
    onboarding({ step: 5 });
    members();
    server.use(
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView())),
      http.put('*/api/v1/planning/PAY-1100/detail/cells', () => HttpResponse.json(
        { code: 'invalid', detail: 'At most 24 hours a day', errors: [] }, { status: 400, headers: { 'Content-Type': 'application/problem+json' } })),
    );
    renderApp('/planning/detail?anken=PAY-1100');
    const cell = await findCell('[PAY] Run 2026-10-12');
    fireEvent.change(cell, { target: { value: '30' } });
    fireEvent.blur(cell);
    expect(await screen.findByText('At most 24 hours a day')).toBeTruthy();
    const row = screen.getByRole('row', { name: '[PAY] Run' });
    await waitFor(() => expect(within(row).getByLabelText('Planned, [PAY] Run').textContent).toBe('0.00'));
    expect(within(row).getByLabelText('Not yet plan, [PAY] Run').textContent).toBe('16.00');
  });

  it('keeps hours typed again while the earlier ones are being saved', async () => {
    const sent: unknown[] = [];
    let release = () => {};
    onboarding({ step: 5 });
    members();
    server.use(
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView())),
      http.put('*/api/v1/planning/PAY-1100/detail/cells', async ({ request }) => {
        const b = await request.json() as { cells: { hours: number }[] };
        sent.push(b);
        if (sent.length === 1) await new Promise<void>((r) => { release = r; });
        return HttpResponse.json(detailView({ lines: [...LINES.slice(0, 2), run({ cells: [{ day: '2026-10-12', hours: b.cells[0].hours, auto: false }] })] }));
      }),
    );
    renderApp('/planning/detail?anken=PAY-1100');
    const cell = await findCell('[PAY] Run 2026-10-12');
    fireEvent.change(cell, { target: { value: '8' } });
    fireEvent.blur(cell);
    await waitFor(() => expect(sent).toHaveLength(1));
    const again = cellInput('[PAY] Run 2026-10-12');
    fireEvent.change(again, { target: { value: '6' } }); // typed again before the 8 is saved
    release();
    await waitFor(() => expect(shown(screen.getByLabelText('[PAY] Run 2026-10-12'))).toBe('6'));
    await new Promise((r) => setTimeout(r, 50));
    expect(again.value).toBe('6'); // the answer to the 8 does not wipe the 6
    fireEvent.blur(again);
    await waitFor(() => expect(sent).toEqual([{ cells: [{ ref: '2', day: '2026-10-12', hours: 8 }] }, { cells: [{ ref: '2', day: '2026-10-12', hours: 6 }] }]));
  });

  it('can clear the plan when every line is removed', async () => {
    onboarding({ step: 5 });
    members();
    server.use(http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView({ lines: LINES.map((l) => ({ ...l, removed: true })) }))));
    renderApp('/planning/detail?anken=PAY-1100');
    fireEvent.click(await screen.findByRole('button', { name: 'More' }));
    expect(screen.getByRole('menuitem', { name: 'Clear all' })).toBeTruthy(); // brings the removed lines back
  });

  it('is ready to push lines not planned yet', async () => {
    onboarding({ step: 5 });
    members();
    server.use(http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView())));
    renderApp('/planning/detail?anken=PAY-1100');
    expect(await screen.findByText('Ready to push')).toBeTruthy(); // nothing stands before the push: created without dates
    expect((screen.getByRole('button', { name: 'Review push' }) as HTMLButtonElement).disabled).toBe(false);
  });

  it('auto allocates the empty slots and shows the plan that follows', async () => {
    let posts = 0;
    onboarding({ step: 5 });
    members();
    server.use(
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView())),
      http.post('*/api/v1/planning/PAY-1100/detail/auto-allocate', () => {
        posts++;
        return HttpResponse.json(detailView({ rows: [{ ...LOGIN, ba_id: 'an' }, { ...RUN, ba_id: 'an' }], ...planned }));
      }),
    );
    renderApp('/planning/detail?anken=PAY-1100');
    fireEvent.click(await screen.findByRole('button', { name: 'Auto allocate' }));
    await waitFor(() => expect((screen.getByLabelText('PIC, [PAY] Login – Create') as HTMLSelectElement).value).toBe('an'));
    expect(shown(screen.getByLabelText('[PAY] Run 2026-10-12'))).toBe('8');
    expect(posts).toBe(1);
  });

  it('edits, removes and restores a line, and adds a function to the estimation', async () => {
    const steps: Record<string, unknown>[] = [];
    let added: Record<string, unknown> | null = null;
    onboarding({ step: 5 });
    members();
    server.use(
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView())),
      http.put('*/api/v1/planning/PAY-1100/detail/step', async ({ request }) => {
        const b = await request.json() as Record<string, unknown>;
        steps.push(b);
        return HttpResponse.json(detailView({ lines: LINES.map((l) => (l.ref === '1:balr' ? { ...l, removed: !!b.removed } : l)) }));
      }),
      http.post('*/api/v1/planning/PAY-1100/detail/rows', async ({ request }) => {
        added = await request.json() as Record<string, unknown>;
        return HttpResponse.json(detailView());
      }),
    );
    renderApp('/planning/detail?anken=PAY-1100');
    const effort = await screen.findByLabelText('Plan Estimation, [PAY] Login – BAL Review');
    fireEvent.change(effort, { target: { value: '3' } });
    fireEvent.blur(effort);
    await waitFor(() => expect(steps[0]).toEqual({ row_id: 1, step: 'balr', effort_h: 3, removed: false }));
    fireEvent.click(screen.getByRole('button', { name: 'Remove [PAY] Login – BAL Review' }));
    const restore = await screen.findByRole('button', { name: 'Restore [PAY] Login – BAL Review' });
    // greyed, its days not editable
    expect(screen.getByRole('row', { name: '[PAY] Login – BAL Review' }).getAttribute('data-removed')).toBe('true');
    expect(screen.queryByLabelText('[PAY] Login – BAL Review 2026-10-12')).toBeNull();
    fireEvent.click(restore);
    await waitFor(() => expect(steps.map((b) => b.removed)).toEqual([false, true, false]));

    fireEvent.click(screen.getByRole('button', { name: 'Add function' }));
    const dialog = await screen.findByRole('dialog', { name: 'Add function' });
    fireEvent.change(within(dialog).getByLabelText('Function'), { target: { value: 'Export' } });
    fireEvent.change(within(dialog).getByLabelText('Create effort (h)'), { target: { value: '6' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Add' }));
    await waitFor(() => expect(added).toEqual({ task_key: 'PAY-1102', function_name: 'Export', steps: 5, effort_h: 6 }));
  });

  it('imports a filled template: shows what changes and what is wrong, then applies the good lines', async () => {
    let applied: { lines: Record<string, unknown>[] } | null = null;
    onboarding({ step: 5 });
    members();
    const imported = [
      { line: 2, ref: '1:create', function: 'Login', step: 'create', ba_id: 'minh', bal_id: null, jm_id: null, effort_h: 10, cells: {},
        changes: [{ field: 'ba', before: 'an', after: 'minh' }, { field: 'effort_h', before: '8', after: '10' }], errors: [] },
      { line: 3, ref: '1:balr', function: 'Login', step: 'balr', ba_id: null, bal_id: null, jm_id: null, effort_h: 2, cells: {},
        changes: [], errors: [] },
      { line: 4, ref: '2', function: 'Run', step: null, ba_id: null, bal_id: null, jm_id: null, effort_h: 16, cells: { '2026-10-12': 0, '2026-10-13': 8 },
        changes: [{ field: 'cells', before: '2026-10-12 8, 2026-10-13 0', after: '2026-10-12 0, 2026-10-13 8' }], errors: [] },
      { line: 5, ref: '9', function: 'Ghost', step: null, ba_id: null, bal_id: null, jm_id: null, effort_h: null, cells: {},
        changes: [], errors: ['9 is not a sub-task of the plan'] },
    ];
    server.use(
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView({ rows: [{ ...LOGIN, ba_id: 'an' }, RUN], ...planned }))),
      http.post('*/api/v1/planning/PAY-1100/detail/import/preview', () => HttpResponse.json(imported)),
      http.post('*/api/v1/planning/PAY-1100/detail/import', async ({ request }) => {
        applied = await request.json() as typeof applied;
        return HttpResponse.json(detailView({ rows: [{ ...LOGIN, ba_id: 'minh' }, RUN], ...planned }));
      }),
    );
    renderApp('/planning/detail?anken=PAY-1100');
    await screen.findByLabelText('PIC, [PAY] Login – Create');
    fireEvent.click(screen.getByRole('button', { name: 'More' }));
    expect(screen.getByRole('menuitem', { name: 'Download template' })).toBeTruthy();
    fireEvent.click(screen.getByRole('menuitem', { name: 'Import Excel / CSV' }));
    fireEvent.change(screen.getByLabelText('Import file'), { target: { files: [new File(['x'], 'plan.xlsx')] } });
    const dialog = await screen.findByRole('dialog', { name: 'Import the detail plan' });
    expect(within(dialog).getByRole('status').textContent).toBe('plan.xlsx: 2 lines to change · 1 unchanged · 1 with errors, left out');
    expect(within(dialog).getByText('Line 5 · Ghost: 9 is not a sub-task of the plan')).toBeTruthy();
    expect(within(dialog).getByText('Line 2 · Login · Create')).toBeTruthy();
    expect(within(dialog).getByText('BA An → Minh')).toBeTruthy();
    expect(within(dialog).getByText('Effort 8.00h → 10.00h')).toBeTruthy();
    expect(within(dialog).getByText('Hours 10/12 8.00h → 0.00h · 10/13 0.00h → 8.00h')).toBeTruthy();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Apply 2 lines' }));
    await waitFor(() => expect(applied?.lines.map((l) => l.ref)).toEqual(['1:create', '2'])); // the unchanged and wrong lines stay out
    expect(applied!.lines[0]).toEqual({ ref: '1:create', ba_id: 'minh', bal_id: null, jm_id: null, effort_h: 10, cells: {} });
    expect(applied!.lines[1].cells).toEqual({ '2026-10-12': 0, '2026-10-13': 8 });
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect((screen.getByLabelText('PIC, [PAY] Login – Create') as HTMLSelectElement).value).toBe('minh');
  });

  it('warns about days above the allocation and pushes anyway', async () => {
    let allowed: string | null = 'not sent';
    onboarding({ step: 5 });
    members();
    server.use(
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView({
        ...planned, over_day: [{ member_id: 'an', date: '2026-10-13', planned_h: 9, allocation_h: 8 }],
      }))),
      http.post('*/api/v1/planning/PAY-1100/detail/push/preview', ({ request }) => {
        allowed = new URL(request.url).searchParams.get('allow_over_day');
        return HttpResponse.json({ updates: [], creates: [] });
      }),
    );
    renderApp('/planning/detail?anken=PAY-1100');
    expect(await screen.findByRole('button', { name: '1 day above the allocation' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Review push' }));
    const dialog = await screen.findByRole('dialog', { name: 'Push review' }); // the warnings show again in the review
    expect(allowed).toBeNull(); // nothing to confirm
    expect(within(dialog).getByRole('note', { name: 'Days above the allocation' }).textContent).toContain('An has 9.00h planned on 10/13, above the 8.00h allocation');
    const warn = screen.getByRole('region', { name: 'Over the allocation' });
    expect(warn.textContent).toContain('An has 9.00h planned on 10/13, above the 8.00h allocation');
    expect(screen.queryByRole('alert', { name: 'Before the push' })).toBeNull(); // a warning, not a block
  });

  it('warns while a member is over budget, without blocking the push', async () => {
    onboarding({ step: 5 });
    members();
    server.use(http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView({
      ...planned, over_budget: [{ member_id: 'an', phase: 'ITA', planned_h: 40, budget_h: 36 }],
      allocated: [{ member_id: 'an', phase: 'ITA', date: '2026-10-12', ankens: [
        { epic_key: 'PAY-1100', title: 'Payment', hours_per_day: 3 }, { epic_key: 'CRM-400', title: 'CRM', hours_per_day: 5 }] }],
    }))), http.post('*/api/v1/planning/PAY-1100/detail/push/preview', () => HttpResponse.json({ updates: [], creates: [] })));
    renderApp('/planning/detail?anken=PAY-1100');
    fireEvent.click(await screen.findByRole('button', { name: 'Review push' })); // the push is tried: shows what stands before
    expect(await screen.findByText('An is over budget in ITA: 40.00h of 36.00h')).toBeTruthy();
    expect(screen.getByRole('region', { name: 'Over the allocation' }).textContent).toContain('An is over budget in ITA');
    expect(screen.queryByRole('alert', { name: 'Before the push' })).toBeNull();
    const matrix = screen.getByRole('table', { name: 'Allocated per anken' });
    expect(matrix.textContent).toContain('PAY-1100 (this)');
    expect(screen.getByRole('row', { name: /An · ITA \(from 10\/12\)/ }).textContent).toBe('An · ITA (from 10/12)3.00h5.00h8.00h');
    expect((screen.getByRole('button', { name: 'Review push' }) as HTMLButtonElement).disabled).toBe(false);
  });

  it('pushes the hours per day alone when Jira has nothing to change', async () => {
    let pushes = 0;
    onboarding({ step: 5 });
    members();
    const view = detailView({ rows: [{ ...LOGIN, pushed_at: '2026-10-07T09:00:00Z' }, { ...RUN, pushed_at: '2026-10-07T09:00:00Z' }],
      lines: [{ ...REFUND, pending: true }] });
    server.use(
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(view)),
      http.post('*/api/v1/planning/PAY-1100/detail/push/preview', () => HttpResponse.json({ updates: [], creates: [] })),
      http.post('*/api/v1/planning/PAY-1100/detail/push', () => { pushes++; return HttpResponse.json({ updated: [], created: {} }); }),
    );
    renderApp('/planning/detail?anken=PAY-1100');
    fireEvent.click(await screen.findByRole('button', { name: 'Review push' })); // a pending Jira line is something to push
    const review = await screen.findByRole('dialog', { name: 'Push review' });
    expect(within(review).getByText('Nothing changes in Jira')).toBeTruthy();
    fireEvent.click(within(review).getByRole('button', { name: 'Push to Jira' }));
    await waitFor(() => expect(pushes).toBe(1));
    expect(await screen.findByText('Pushed · the hours per day are kept here')).toBeTruthy();
  });


  it('plans an anken without its epic and waits for the epic to push', async () => {
    members();
    server.use(
      http.get('*/api/v1/onboarding/draft-1', () => HttpResponse.json({ ...DRAFT, step: 5 })),
      http.get('*/api/v1/planning/draft-1/detail', () => HttpResponse.json(detailView({ rows: [{ ...LOGIN, epic_key: 'draft-1' }], lines: LINES.slice(0, 2) }))),
    );
    renderApp('/planning/detail?anken=draft-1');
    expect(await screen.findByText(/Link the epic first/)).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Link epic' }).getAttribute('href')).toBe('/onboarding/draft-1/epic');
    expect(screen.queryByRole('button', { name: 'Review push' })).toBeNull();
    expect(await screen.findByLabelText('PIC, [PAY] Login – Create')).toBeTruthy(); // planned all the same
  });

  it('keeps a function in Jira under its own Task, apart from the same name under another Task', async () => {
    onboarding({ step: 5 });
    members();
    const jira = (key: string, task: string, step: string) => mline(key, null, step, `[PAY][${task}]_Book-Search_${step}`, 4,
      { issue_key: key, function: 'Book-Search', subphase: task });
    server.use(http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView({ rows: [], lines: [
      jira('PAY-1301', 'ITA Prepare', 'create'), jira('PAY-1302', 'ITA Execute', 'create'), jira('PAY-1303', 'ITA Prepare', 'balr')] }))));
    renderApp('/planning/detail?anken=PAY-1100');
    const fns = await screen.findAllByRole('row', { name: 'Function Book-Search' });
    expect(fns).toHaveLength(2); // one per Task
    const rows = [...document.querySelectorAll('tr[aria-label]')].map((r) => r.getAttribute('aria-label'));
    const after = (label: string) => rows.indexOf(label);
    // ITA Prepare's function holds its two steps, ITA Execute's its one
    expect(after('[PAY][ITA Prepare]_Book-Search_balr')).toBeLessThan(after('[PAY][ITA Execute]_Book-Search_create'));
    expect(rows.filter((r) => r === 'Function Book-Search')).toHaveLength(2);
  });

  it('shows a function once when some of its steps are in Jira: Jira\'s, then the steps still to create', async () => {
    onboarding({ step: 5 });
    members();
    const jira = (key: string, step: string, summary: string) => mline(key, null, step, summary, 4,
      { issue_key: key, function: 'Login', subphase: 'Create test case', linked_row_id: 1 });
    const lines = [mline('1:fixb', 1, 'fixb', '[PAY] Login – Fix comment BAL', 2, { subphase: 'Create test case' }),
      jira('PAY-1302', 'balr', '[PAY] Login – BAL Review'), jira('PAY-1301', 'create', '[PAY] Login – Create')];
    server.use(http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView({
      rows: [{ ...LOGIN, subphase: 'Create test case' }], lines }))));
    renderApp('/planning/detail?anken=PAY-1100');
    expect(await screen.findAllByRole('row', { name: 'Function Login' })).toHaveLength(1);
    const order = [...document.querySelectorAll('tr[aria-label]')].map((r) => r.getAttribute('aria-label'))
      .filter((l) => l?.startsWith('[PAY] Login'));
    expect(order).toEqual(['[PAY] Login – Create', '[PAY] Login – BAL Review', '[PAY] Login – Fix comment BAL']); // in step order
    expect(screen.queryByText('In Jira')).toBeNull(); // the function is the row's, with its steps in Jira inside
  });
});

