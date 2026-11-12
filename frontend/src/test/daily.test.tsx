import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { renderApp, server } from './renderApp';

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => { cleanup(); server.resetHandlers(); localStorage.clear(); });
afterAll(() => server.close());

const day = (date: string, plan: number, act: number | null) => ({ date, planned_h: 4, logged_h: 3, plan_remaining_h: plan, actual_remaining_h: act });
const chart = { estimate_h: 12, start: '2026-10-05', end: '2026-10-08', plan_remaining_h: 4, actual_remaining_h: 6,
  days: [day('2026-10-05', 12, 12), day('2026-10-06', 8, 9), day('2026-10-07', 4, 6), day('2026-10-08', 0, null)] };
const step = (key: string, s: string, status: string, extra: object = {}) => ({
  key, step: s, status, assignee_id: 'binh', planned_start: '2026-10-01', planned_end: '2026-10-02', late: false, late_days: 0, rounds: 0, with_jm: false, ...extra,
});
const ticket = (key: string, who: string, name = 'Requirement study', extra: object = {}) => ({
  key, name, assignee_id: who, status: 'C', planned_start: '2026-09-03', planned_end: '2026-09-04', late: false, late_days: 0, ...extra,
});
const task = (key: string, name: string, extra: object) => ({ key, name, state: 'current', pct: 40, closed: 2, total: 5, late: 0, unmatched: 0, functions: [], tickets: [], ...extra });
const done = (pre: string) => [step(`${pre}1`, 'create', 'C'), step(`${pre}2`, 'balr', 'C'), step(`${pre}3`, 'fixb', 'C')];

const progress = (pct: number, plan: number, act: number) => ({ pct, plan_to_date_h: plan, actual_to_date_h: act, diff_h: act - plan });

/** `kept`: whether the burndowns keep their progress; a snapshot captured before has it for the whole anken only. */
function content(date: string, note: string, kept = true) {
  return {
    epic: 'PAY-1100', title: 'Payment flow revision', date, pct: 62, plan_to_date_h: 100, actual_to_date_h: 106, diff_h: 6,
    burndowns: [{ phase: null, chart, progress: kept ? progress(62, 100, 106) : null },
      { phase: 'Implement_Coding', chart, progress: kept ? progress(48, 40, 37.5) : null }],
    groups: [
      { name: 'Prepare', state: 'done', pct: 100, closed: 9, total: 9, late: 0, sub_phases: [] }, // captured before Tasks were rolled up
      { name: 'Implement_Coding', state: 'current', pct: 48, closed: 20, total: 60, late: 1, sub_phases: [
        task('PAY-1102', 'Payment flow revision', { late: 1, functions: [
          { id: 'F03', name: 'Domestic card payment', ba_id: 'binh', steps: [
            step('PAY-1211', 'create', 'C'), step('PAY-1212', 'balr', 'C'), step('PAY-1213', 'fixb', 'W', { late: true, late_days: 2, rounds: 3 }),
            step('PAY-1214', 'jmr', 'N', { assignee_id: 'tanaka', with_jm: true }), null] },
          { id: 'F04', name: 'Refund', ba_id: 'an', steps: [
            ...done('PAY-122'), step('PAY-1224', 'jmr', 'W', { assignee_id: 'an' }), step('PAY-1225', 'fixj', 'W', { assignee_id: 'an', rounds: 1 })] },
          { id: 'F05', name: 'Installments', ba_id: 'an', steps: [
            ...done('PAY-123'), step('PAY-1234', 'jmr', 'W', { assignee_id: 'tanaka', with_jm: true }), step('PAY-1235', 'fixj', 'N')] },
          { id: 'F06', name: 'Execute test case', ba_id: 'an', steps: [
            step('PAY-1241', 'create', 'C'), step('PAY-1242', 'balr', 'W'), step('PAY-1243', 'fixb', 'N')] }] }),
        task('PAY-1300', 'Receipt screens', { functions: [
          { id: 'F07', name: 'Receipt screen', ba_id: 'binh', steps: [
            ...done('PAY-130'), step('PAY-1304', 'jmr', 'N', { assignee_id: 'tanaka', with_jm: true }), step('PAY-1305', 'fixj', 'N')] }] }),
        task('PAY-1160', 'Requirement study', { unmatched: 2, tickets: [ticket('PAY-1161', 'an'), ticket('PAY-1162', 'binh'),
          ticket('PAY-1163', 'an', 'Glossary', { status: 'R', planned_end: '2026-10-06', late: true, late_days: 1 })] }),
        task('PAY-1150', 'Design review', { state: 'done', pct: 100, closed: 1, total: 1, tickets: [ticket('PAY-1151', 'an', 'Design review')] }),
      ] },
    ],
    typed: [
      { type: 'qna', key: 'PAY-1171', summary: 'Q12 · Card limit per transaction', assignee_id: 'tanaka', with_jm: true, status: 'W', created: '2026-10-01', held_days: 4, closed_on: null, note },
      { type: 'qna', key: 'PAY-1173', summary: 'Q14 · OTP resend limit', assignee_id: 'an', with_jm: false, status: 'W', created: '2026-10-06', held_days: 1, closed_on: null, note: '' },
    ],
  };
}
const snapshots = [{ date: '2026-10-06', captured_at: '2026-10-06T18:00:00+07:00', captured_by: 'auto' }];
/** Today, live, with the anken's Daily phases. */
const live = (daily: string[]) => ({
  content: content('2026-10-07', 'Waiting for the card team'), live: true, captured_at: null, captured_by: null, today: '2026-10-07', auto_capture: '18:00', snapshots, daily_phases: daily,
});

describe('Daily Overview', () => {
  it('shows the live day: Process, burndown, the Tasks as cards, the issues; a phase filters them; a note is saved', async () => {
    const asked: (string | null)[] = [];
    let note: unknown;
    server.use(
      http.get('*/api/v1/daily', ({ request }) => {
        const date = new URL(request.url).searchParams.get('date');
        asked.push(date);
        return HttpResponse.json(date
          ? { content: content(date, 'Asked the card team', false), live: false, captured_at: snapshots[0].captured_at, captured_by: 'auto', today: '2026-10-07', auto_capture: '18:00', snapshots, daily_phases: [] }
          : live([]));
      }),
      http.put('*/api/v1/daily/notes/:key', async ({ request }) => { note = await request.json(); return HttpResponse.json({ key: 'PAY-1173', note: 'Send today' }); }),
    );
    const { router } = renderApp('/daily?anken=PAY-1100');
    expect(await screen.findByRole('heading', { name: 'Payment flow revision' })).toBeTruthy();
    expect(screen.getAllByText('62%').length).toBeGreaterThan(0);
    expect(screen.getByText('+6.00h')).toBeTruthy();
    expect(screen.getByText(/Live · not captured yet/)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Capture' })).toBeTruthy();

    // Process: All phases, then each phase; All is picked
    const proc = screen.getByRole('region', { name: 'Process' });
    const hubs = within(proc).getAllByRole('button', { pressed: false }).concat(within(proc).getAllByRole('button', { pressed: true }));
    expect(hubs).toHaveLength(3);
    expect(within(proc).getByRole('button', { name: /All phases/ }).getAttribute('aria-pressed')).toBe('true');
    expect(within(proc).getByRole('button', { name: /Implement_Coding/ }).textContent).toContain('▼ −2.50h behind');
    expect(within(screen.getByRole('region', { name: 'Effort burndown' })).getByText('All phases')).toBeTruthy();

    // Function overview: every phase; a Task opens by itself when something in it is late or in progress
    const fo = screen.getByRole('region', { name: 'Function overview' });
    const card = (name: RegExp) => within(fo).getByRole('button', { name });
    expect(card(/^Payment flow revision/).getAttribute('aria-expanded')).toBe('true');
    expect(card(/^Payment flow revision/).textContent).toContain('1 late');
    expect(card(/^Requirement study/).getAttribute('aria-expanded')).toBe('true'); // a late ticket
    expect(card(/^Design review/).getAttribute('aria-expanded')).toBe('false'); // done
    expect(within(fo).getByText('Domestic card payment')).toBeTruthy();
    expect(within(fo).getByRole('button', { name: 'Fix Comment BAL · WIP · 2d late' })).toBeTruthy();
    expect(within(fo).getAllByRole('button', { name: 'Create · Done' }).length).toBeGreaterThan(0);
    // the JM round: the JM Review's assignee tells whether JM is reviewing or the team is fixing their comments
    expect(within(fo).getByTitle(/^PAY-1234 .* now: waiting for the JM review$/)).toBeTruthy();
    expect(within(fo).getByTitle(/^PAY-1225 .* now: fixing JM comments$/)).toBeTruthy();
    expect(within(fo).getAllByTitle('Last step of this flow')).toHaveLength(1); // F06 runs the 3-step flow
    expect(within(fo).getByRole('button', { name: 'Resolved · 1d late' })).toBeTruthy(); // a late single ticket
    expect(within(fo).getByText(/do not follow the name rule/).textContent).toContain('2 sub-tasks');
    expect(within(fo).getByRole('link', { name: 'Review in Ticket Check →' }).getAttribute('href')).toBe('/ticket-check?check=rule');
    // functions are sorted by their Create start
    const rows = within(fo).getAllByText(/^(Domestic card payment|Refund|Installments|Execute test case)$/).map((e) => e.textContent);
    expect(rows).toHaveLength(4);
    fireEvent.click(within(fo).getByRole('button', { name: /Collapse all/ }));
    expect(within(fo).queryByText('Domestic card payment')).toBeNull();
    fireEvent.click(within(fo).getByRole('button', { name: /Expand all/ }));
    expect(within(fo).getByText('Domestic card payment')).toBeTruthy();
    expect(card(/^Design review/).getAttribute('aria-expanded')).toBe('true');

    // Issues: one card, a tab per type, totals, status and holder in one pill
    const issues = screen.getByRole('region', { name: 'Issues' });
    expect(within(issues).getByRole('tab', { name: /QnA/ }).getAttribute('aria-selected')).toBe('true');
    expect(within(issues).getByTitle('With JM (the client)').textContent).toBe('WIP · JM · 4d');
    expect(within(issues).getByTitle('With the team').textContent).toBe('WIP · FPT · 1d');
    expect(within(issues).getByText('Waiting for the card team', { selector: '.do-note' })).toBeTruthy();
    fireEvent.click(within(issues).getByRole('button', { name: 'Edit note on PAY-1173' }));
    const dialog = screen.getByRole('dialog', { name: 'Note on PAY-1173' });
    fireEvent.change(within(dialog).getByLabelText('Note on PAY-1173'), { target: { value: 'Send today' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save note' }));
    await waitFor(() => expect(note).toEqual({ note: 'Send today' }));
    fireEvent.click(within(issues).getByRole('tab', { name: /Bugs/ }));
    expect(within(issues).getByText('No open bugs right now')).toBeTruthy();

    // a phase picked in Process filters the header, the burndown and the Function overview
    fireEvent.click(within(proc).getByRole('button', { name: /Implement_Coding/ }));
    await waitFor(() => expect(router.state.location.search).toMatchObject({ phase: 'Implement_Coding' }));
    expect(screen.getByText('Progress · Implement_Coding')).toBeTruthy();
    expect(screen.getByText('−2.50h')).toBeTruthy();
    expect(screen.getByText('40.00h')).toBeTruthy();
    expect(screen.getByText('37.50h')).toBeTruthy();
    expect(within(screen.getByRole('region', { name: 'Effort burndown' })).getByText('Implement_Coding')).toBeTruthy();
    expect(within(screen.getByRole('region', { name: 'Function overview' })).queryByText('Design review')).toBeTruthy();

    fireEvent.change(screen.getByRole('combobox', { name: 'Day' }), { target: { value: '2026-10-06' } });
    expect(await screen.findByText('Past snapshot')).toBeTruthy();
    expect(screen.getByText('not captured for this phase')).toBeTruthy();
    expect(asked).toContain('2026-10-06');
    expect(screen.queryByRole('button', { name: 'Capture' })).toBeNull();
    const past = screen.getByRole('region', { name: 'Issues' });
    fireEvent.click(within(past).getByRole('tab', { name: /QnA/ }));
    expect(within(past).getByText('Asked the card team', { selector: '.do-note' })).toBeTruthy(); // read-only
    expect(within(past).queryByRole('button', { name: /Edit note/ })).toBeNull();
  });

  it('shows only the Daily phases in Process, and an unknown phase falls back to all phases', async () => {
    server.use(http.get('*/api/v1/daily', () => HttpResponse.json(live(['Implement_Coding']))));
    renderApp('/daily?anken=PAY-1100&phase=Nope');
    const proc = await screen.findByRole('region', { name: 'Process' });
    expect(within(proc).queryByRole('button', { name: /Prepare/ })).toBeNull();
    expect(within(proc).getByRole('button', { name: /All phases/ }).getAttribute('aria-pressed')).toBe('true');
    expect(within(screen.getByRole('region', { name: 'Function overview' })).getByText('Domestic card payment')).toBeTruthy();
  });

  it('says so when no Daily phase is in the anken', async () => {
    server.use(http.get('*/api/v1/daily', () => HttpResponse.json(live(['UAT']))));
    renderApp('/daily?anken=PAY-1100');
    const fo = await screen.findByRole('region', { name: 'Function overview' });
    expect(within(fo).getByText('No phase to show. Pick the phases in Settings → Structure.')).toBeTruthy();
    expect(within(fo).queryByRole('button', { name: /^Payment flow revision/ })).toBeNull();
  });

  it('says so when no Daily phase had started on a past day', async () => {
    server.use(http.get('*/api/v1/daily', () => HttpResponse.json({ ...live(['UAT']), live: false, captured_at: snapshots[0].captured_at, captured_by: 'auto' })));
    renderApp('/daily?anken=PAY-1100&date=2026-10-06');
    const fo = await screen.findByRole('region', { name: 'Function overview' });
    expect(within(fo).getByText('None of the Daily phases had started on this day.')).toBeTruthy();
  });

  it('asks for an anken under "All my anken"', async () => {
    renderApp('/daily');
    expect(await screen.findByText('Pick an anken to see this view.')).toBeTruthy();
  });
});
