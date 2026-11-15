import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { renderApp, server } from './renderApp';

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
    pending: false, out_of_order: false, removed: false, edited: false, ...over,
  });
  /** As {@link base}, with the planning figures following the plan unless the test sets them. */
  const mline = (ref: string, row: number | null, step: string | null, summary: string, total: number, over: Record<string, unknown> = {}) => {
    const l = base(ref, row, step, summary, total, over);
    return { plan_estimate_h: l.total_h, plan_h: l.planned_h, plan_not_yet_h: l.not_yet_h, ...l };
  };
  const LINES = [mline('1:create', 1, 'create', '[PAY] Login – Create', 8), mline('1:balr', 1, 'balr', '[PAY] Login – BAL Review', 2),
    mline('2', 2, null, '[PAY] Run', 16)];
  // today is 2026-10-07 (meta): the 6th is past
  const WINDOW = ['2026-10-06', '2026-10-07', '2026-10-08', '2026-10-09', '2026-10-12', '2026-10-13', '2026-10-14'];
  const REFUND = mline('PAY-1201', null, 'create', '[PAY] Refund – Create', 3, { issue_key: 'PAY-1201', function: 'Refund', pic_id: 'an',
    spent_h: 1, planned_h: 3, not_yet_h: 0, start: '2026-10-06', end: '2026-10-08',
    cells: [{ day: '2026-10-06', hours: 1, auto: false }, { day: '2026-10-08', hours: 2, auto: false }], logged: [{ day: '2026-10-06', hours: 1.5, auto: false }] });
  const detailView = (over: Record<string, unknown> = {}) => ({
    rows: [LOGIN, RUN], tasks: [TASK], window: WINDOW, lines: LINES, over_budget: [], allocated: [], over_day: [], checks: [], days: [],
    stale_phases: [], problems: [], push_blockers: [], by_hand: [], last_version_at: null, ...over,
  });
  const run = (over: Record<string, unknown>) => mline('2', 2, null, '[PAY] Run', 16, over);
  const planned = {
    lines: [...LINES.slice(0, 2), run({ planned_h: 16, not_yet_h: 0, start: '2026-10-12', end: '2026-10-14', pic_id: 'an',
      cells: [{ day: '2026-10-12', hours: 8, auto: true }, { day: '2026-10-14', hours: 8, auto: true }] })],
    checks: [{ phase: 'ITA', master_end: '2026-10-13', plan_end: '2026-10-14', days_late: 1 }],
    days: [{ member_id: 'an', date: '2026-10-11', planned_h: 0, allocated_h: 0, off: 'Weekend', other_h: 0, items: [] },
      { member_id: 'an', date: '2026-10-12', planned_h: 8, allocated_h: 8, off: null, other_h: 0, items: [{ key: null, title: '[PAY] Run', hours: 8, other: false }] },
      { member_id: 'an', date: '2026-10-13', planned_h: 9, allocated_h: 8, off: null, other_h: 3, items: [{ key: null, title: '[PAY] Run', hours: 8, other: false },
        { key: 'PAY-1290', title: 'Refund – Create', hours: 1, other: false }, { key: 'PAY-1300', title: 'Refund', hours: 3, other: true }] }],
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
    const ba = await screen.findByLabelText('BA, Login');
    expect(ba.getAttribute('data-by-hand')).toBe('true');
    expect(screen.getByLabelText('BAL, Login').getAttribute('data-by-hand')).toBeNull(); // Auto allocate's pick
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
      http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView({ problems: ['Row 1 (Login): pick a BA'] }))),
      http.put('*/api/v1/planning/PAY-1100/detail', async ({ request }) => {
        saved = await request.json() as typeof saved;
        return HttpResponse.json(detailView({ rows: [{ ...LOGIN, ba_id: 'an' }, RUN], ...planned }));
      }),
    );
    renderApp('/planning/detail?anken=PAY-1100');
    const ba = await screen.findByLabelText('BA, Login');
    expect(screen.getAllByLabelText('BA, Login')).toHaveLength(1); // picked once for the function, not on each of its lines
    expect(screen.queryByText('Row 1 (Login): pick a BA')).toBeNull(); // not on the first visit, only counted
    expect(screen.getByRole('button', { name: '1 problem before the push' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Fix 1 problem to push' })).toBeTruthy();
    expect([...ba.querySelectorAll('option')].map((o) => o.textContent)).toEqual(['–', 'An (BA)', 'Minh (BAL)']);
    expect([...screen.getByLabelText('JM, Login').querySelectorAll('option')].map((o) => o.textContent)).toEqual(['–']); // no BA or BAL
    expect(screen.getByLabelText('Member, Run')).toBeTruthy(); // a ticket has one member
    expect(screen.queryByRole('button', { name: 'Check' })).toBeNull();
    expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual(['Gantt', 'Master schedule', 'Detail plan']); // the effort per day is always above the matrix
    fireEvent.click(screen.getByRole('button', { name: 'Fix 1 problem to push' }));
    expect(await screen.findByText('Row 1 (Login): pick a BA')).toBeTruthy();
    expect(screen.getByRole('row', { name: '[PAY] Login – BAL Review' })).toBeTruthy(); // one line per step, named by the name rules
    fireEvent.change(screen.getByLabelText('BA, Login'), { target: { value: 'an' } });
    await waitFor(() => expect(saved).toEqual({ order: [1, 2], assigns: [{ row_id: 1, ba_id: 'an', bal_id: null, jm_id: null }, { row_id: 2, ba_id: 'an', bal_id: null, jm_id: null }] }));
    const runRow = await screen.findByRole('row', { name: '[PAY] Run' });
    await waitFor(() => expect(within(runRow).getByLabelText('Start, [PAY] Run').textContent).toBe('10/12'));
    expect(within(runRow).getByLabelText('End, [PAY] Run').textContent).toBe('10/14');
    expect(within(runRow).getByLabelText('Planned, [PAY] Run').textContent).toBe('16.00');
    expect((within(runRow).getByLabelText('[PAY] Run 2026-10-14') as HTMLInputElement).value).toBe('8');
    expect(within(runRow).getByLabelText('PIC, [PAY] Run').textContent).toBe('An'); // who carries the line
    expect(screen.getByText('ITA ends 10/14 · master 10/13 · 1 day late')).toBeTruthy();
    const days = screen.getByRole('region', { name: 'Effort per member per day' });
    const tue = within(days).getByLabelText('An 2026-10-13');
    expect(tue.textContent).toBe('9.00+3.00 other'); // the planned hours only, red above the 8h allocation
    expect(within(days).getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['Member', 'Mon', 'Tue', '10/12', '10/13']); // no weekend
    fireEvent.mouseEnter(tue);
    const tip = screen.getByRole('tooltip');
    expect(days.contains(tip)).toBe(false); // above the page, not clipped by the section
    expect(tip.textContent).toContain('PAY-1290 · Refund – Create'); // a task of this anken
    expect(tip.textContent).toContain('Other anken');
    expect(tip.textContent).toContain('Refund3.00h'); // another anken: its name and hours
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
    const cell = await screen.findByLabelText('[PAY] Run 2026-10-12') as HTMLInputElement;
    expect(within(screen.getByRole('row', { name: '[PAY] Run' })).getByLabelText('Not yet plan, [PAY] Run').textContent).toBe('16.00');
    fireEvent.change(cell, { target: { value: '8' } });
    expect(sent).toEqual([]); // not while it is typed
    expect(cell.value).toBe('8');
    fireEvent.blur(cell);
    await waitFor(() => expect(sent).toEqual([{ cells: [{ ref: '2', day: '2026-10-12', hours: 8 }] }]));
    const row = screen.getByRole('row', { name: '[PAY] Run' });
    await waitFor(() => expect(within(row).getByLabelText('Not yet plan, [PAY] Run').textContent).toBe('8.00'));
    expect(within(row).getByLabelText('Start, [PAY] Run').textContent).toBe('10/12');
    expect((screen.getByLabelText('[PAY] Run 2026-10-12') as HTMLInputElement).value).toBe('8');
    const next = screen.getByLabelText('[PAY] Run 2026-10-13');
    fireEvent.change(next, { target: { value: '' } });
    fireEvent.keyDown(next, { key: 'Enter' });
    fireEvent.blur(next); // nothing changed: nothing sent
    expect(sent).toHaveLength(1);
  });

  it('locks the past days of a Jira line and shows the hours logged', async () => {
    onboarding({ step: 5 });
    members();
    server.use(http.get('*/api/v1/planning/PAY-1100/detail', () => HttpResponse.json(detailView({ lines: [...LINES, { ...REFUND, pending: true }] }))));
    renderApp('/planning/detail?anken=PAY-1100');
    const row = await screen.findByRole('row', { name: '[PAY] Refund – Create' });
    expect(screen.queryByLabelText('[PAY] Refund – Create 2026-10-06')).toBeNull();
    const past = row.querySelector('[data-day="2026-10-06"]') as HTMLElement;
    expect(within(past).getByText('1')).toBeTruthy(); // the plan
    expect(within(past).getByText('1.5h')).toBeTruthy(); // the hours logged
    expect((screen.getByLabelText('[PAY] Refund – Create 2026-10-07') as HTMLInputElement).disabled).toBe(false); // today stays open
    expect((screen.getByLabelText('[PAY] Refund – Create 2026-10-08') as HTMLInputElement).value).toBe('2');
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
    const bold = (summary: string) => (screen.getByLabelText(`Not yet plan, ${summary}`) as HTMLElement).style.fontWeight;
    expect(bold('[PAY] Login – Create')).toBe('700'); // 8h left: red
    expect(bold('[PAY] Run')).toBe(''); // a hundredth is planned enough, as the push sees it
    expect(bold('[PAY] Refund – Create')).toBe(''); // Closed: done
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
    }))));
    renderApp('/planning/detail?anken=PAY-1100');
    const row = await screen.findByRole('row', { name: '[PAY] Login – BAL Review' });
    expect(row.getAttribute('data-out-of-order')).toBe('true');
    const cell = within(row).getByLabelText('[PAY] Login – BAL Review 2026-10-08').closest('td');
    expect(cell?.getAttribute('data-out-of-order')).toBe('true');
    expect(screen.getByRole('row', { name: '[PAY] Run' }).getAttribute('data-out-of-order')).toBeNull();
    expect((screen.getByRole('button', { name: 'Review push' }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: 'Fix 1 problem to push' }));
    expect(await screen.findByText('[PAY] Login – BAL Review: starts before the step before it ends')).toBeTruthy();
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
        await new Promise<void>((r) => { release = r; });
        order.push('cells done');
        return HttpResponse.json(detailView());
      }),
      http.post('*/api/v1/planning/PAY-1100/detail/versions', () => { order.push('version'); return HttpResponse.json(detailView()); }),
    );
    renderApp('/planning/detail?anken=PAY-1100');
    const cell = await screen.findByLabelText('[PAY] Run 2026-10-12');
    fireEvent.change(cell, { target: { value: '8' } });
    fireEvent.blur(cell);
    await waitFor(() => expect(order).toEqual(['cells']));
    fireEvent.click(screen.getByRole('button', { name: 'Save version' }));
    await new Promise((r) => setTimeout(r, 100));
    expect(order).toEqual(['cells']); // the version waits for the cell
    release();
    await waitFor(() => expect(order).toEqual(['cells', 'cells done', 'version']));
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
    const cell = await screen.findByLabelText('[PAY] Run 2026-10-12') as HTMLInputElement;
    fireEvent.change(cell, { target: { value: '8' } });
    fireEvent.blur(cell);
    await waitFor(() => expect(sent).toHaveLength(1));
    fireEvent.change(cell, { target: { value: '6' } }); // typed again before the 8 is saved
    release();
    await waitFor(() => expect((screen.getByLabelText('[PAY] Run 2026-10-12') as HTMLInputElement).value).toBe('6'));
    await new Promise((r) => setTimeout(r, 50));
    expect(cell.value).toBe('6'); // the answer to the 8 does not wipe the 6
    fireEvent.blur(cell);
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
    await waitFor(() => expect((screen.getByLabelText('BA, Login') as HTMLSelectElement).value).toBe('an'));
    expect((screen.getByLabelText('[PAY] Run 2026-10-12') as HTMLInputElement).value).toBe('8');
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
    await screen.findByLabelText('BA, Login');
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
    expect((screen.getByLabelText('BA, Login') as HTMLSelectElement).value).toBe('minh');
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
    expect(await screen.findByLabelText('BA, Login')).toBeTruthy(); // planned all the same
  });
});
