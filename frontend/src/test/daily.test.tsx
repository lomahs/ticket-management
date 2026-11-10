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

function content(date: string, note: string) {
  return {
    epic: 'PAY-1100', title: 'Payment flow revision', date, pct: 62, plan_to_date_h: 100, actual_to_date_h: 106, diff_h: 6,
    burndowns: [{ phase: null, chart }, { phase: 'Implement_Coding', chart }],
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
  it('shows the live day, switches to a captured day and saves a note', async () => {
    const asked: (string | null)[] = [];
    let note: unknown;
    server.use(
      http.get('*/api/v1/daily', ({ request }) => {
        const date = new URL(request.url).searchParams.get('date');
        asked.push(date);
        return HttpResponse.json(date
          ? { content: content(date, 'Asked the card team'), live: false, captured_at: snapshots[0].captured_at, captured_by: 'auto', today: '2026-10-07', auto_capture: '18:00', snapshots, daily_phases: [] }
          : live([]));
      }),
      http.put('*/api/v1/daily/notes/:key', async ({ request }) => { note = await request.json(); return HttpResponse.json({ key: 'PAY-1173', note: 'Send today' }); }),
    );
    const { router } = renderApp('/daily?anken=PAY-1100');
    expect(await screen.findByRole('heading', { name: 'Payment flow revision' })).toBeTruthy();
    expect(screen.getByText('62%')).toBeTruthy();
    expect(screen.getByText('+6h')).toBeTruthy();
    expect(screen.getByText(/Live · not captured yet/)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Capture' })).toBeTruthy();

    const fo = screen.getByRole('region', { name: 'Function overview' });
    expect(within(fo).getAllByRole('tab').map((t) => t.textContent)).toEqual(['Prepare 100%', 'Implement_Coding 48%']);
    expect(within(fo).getByRole('tab', { name: 'Implement_Coding 48%' }).getAttribute('aria-selected')).toBe('true'); // the first current
    expect(within(fo).getByRole('tab', { name: 'Prepare 100%' }).getAttribute('aria-selected')).toBe('false');
    expect(within(fo).getByText('48% · 20/60 done')).toBeTruthy();
    expect(within(fo).getByText('· 1 late')).toBeTruthy();
    expect(within(fo).getByText('Domestic card payment')).toBeTruthy();
    expect(within(fo).getByRole('button', { name: 'WIP · 2d late' })).toBeTruthy();
    expect(within(fo).queryByText(/R3 · Team/)).toBeNull();
    expect(within(fo).getAllByText('Requirement study')).toHaveLength(3); // the Task heading and one row per ticket
    expect(within(fo).getByRole('button', { name: 'Resolved · 1d late' })).toBeTruthy(); // a late single ticket: as a late cell
    // one column header per tab, the Tasks as heading rows under it
    expect(within(fo).getAllByRole('button', { name: 'Function' })).toHaveLength(1);
    expect(within(fo).getAllByRole('button', { name: 'Ticket' })).toHaveLength(1);
    expect(within(fo).getByText('Receipt screens')).toBeTruthy();
    // a done sub-task shows just Done: no Closed, no dates
    expect(within(fo).queryByText('Closed')).toBeNull();
    expect(within(fo).queryByText('09/04')).toBeNull();
    // the JM round: the JM Review's assignee tells whether JM is reviewing or the team is fixing their comments
    expect(within(fo).getByTitle(/^PAY-1234 .* now: waiting for the JM review$/)).toBeTruthy();
    expect(within(fo).getByTitle(/^PAY-1225 .* now: fixing JM comments$/)).toBeTruthy();
    expect(within(fo).queryAllByTitle(/now:/)).toHaveLength(2); // F03's JM Review has not started
    expect(within(fo).getByText('No JM steps')).toBeTruthy(); // F06 runs the 3-step flow
    expect(within(fo).getByText(/2 sub-tasks outside the name rule, not listed here/)).toBeTruthy();
    expect(within(fo).getByRole('link', { name: 'See them in Ticket Check' }).getAttribute('href')).toBe('/ticket-check?check=rule');
    // a done group of an older snapshot has no Tasks: one Done row
    fireEvent.click(within(fo).getByRole('tab', { name: 'Prepare 100%' }));
    await waitFor(() => expect(router.state.location.search).toMatchObject({ tab: 'Prepare' }));
    const panel = within(fo).getByRole('tabpanel', { name: 'Prepare' });
    expect(within(panel).getByText('Prepare')).toBeTruthy();
    expect(within(panel).getAllByText('Done')).toHaveLength(1);
    expect(within(fo).queryByText('Domestic card payment')).toBeNull();

    const bug = screen.getByRole('region', { name: 'Bug' });
    const typed = screen.getByRole('region', { name: 'QnA' });
    const wr = screen.getByRole('region', { name: 'WorkRequest' });
    expect(bug.compareDocumentPosition(typed) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(typed.compareDocumentPosition(wr) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(within(bug).getByText('No bugs yet')).toBeTruthy();
    expect(within(bug).queryByText(/with JM|with the team/)).toBeNull(); // no counts on an empty section
    expect(within(typed).getByText('1 with JM')).toBeTruthy();
    expect(within(typed).getByText('1 with the team')).toBeTruthy();
    expect(within(typed).getByText('JM · 4d')).toBeTruthy();
    expect(within(typed).getByText('Team · 1d')).toBeTruthy();
    expect(within(typed).getByText('Tanaka')).toBeTruthy(); // the assignee: plain text
    const field = within(typed).getByLabelText('Note on PAY-1173');
    fireEvent.change(field, { target: { value: 'Send today' } });
    fireEvent.blur(field);
    await waitFor(() => expect(note).toEqual({ note: 'Send today' }));

    fireEvent.change(screen.getByRole('combobox', { name: 'Phase' }), { target: { value: 'Implement_Coding' } });
    await waitFor(() => expect(router.state.location.search).toMatchObject({ phase: 'Implement_Coding' }));

    fireEvent.change(screen.getByRole('combobox', { name: 'Day' }), { target: { value: '2026-10-06' } });
    expect(await screen.findByText('Past snapshot')).toBeTruthy();
    expect(asked).toContain('2026-10-06');
    expect(screen.queryByRole('button', { name: 'Capture' })).toBeNull();
    expect(within(screen.getByRole('region', { name: 'QnA' })).getByText('Asked the card team')).toBeTruthy(); // read-only
    expect(screen.queryByLabelText('Note on PAY-1171')).toBeNull();
  });

  it('shows only the Daily phases as tabs, and an unknown tab falls back to the first current', async () => {
    server.use(http.get('*/api/v1/daily', () => HttpResponse.json(live(['Implement_Coding']))));
    renderApp('/daily?anken=PAY-1100&tab=Nope');
    const fo = await screen.findByRole('region', { name: 'Function overview' });
    expect(within(fo).getAllByRole('tab').map((t) => t.textContent)).toEqual(['Implement_Coding 48%']);
    expect(within(fo).getByRole('tab', { name: 'Implement_Coding 48%' }).getAttribute('aria-selected')).toBe('true');
    expect(within(fo).getByText('Domestic card payment')).toBeTruthy();
  });

  it('says so when no Daily phase is in the anken', async () => {
    server.use(http.get('*/api/v1/daily', () => HttpResponse.json(live(['UAT']))));
    renderApp('/daily?anken=PAY-1100');
    const fo = await screen.findByRole('region', { name: 'Function overview' });
    expect(within(fo).getByText('No phase to show. Pick the phases in Settings → Structure.')).toBeTruthy();
    expect(within(fo).queryByRole('tab')).toBeNull();
  });

  it('says so when no Daily phase had started on a past day', async () => {
    server.use(http.get('*/api/v1/daily', () => HttpResponse.json({ ...live(['UAT']), live: false, captured_at: snapshots[0].captured_at, captured_by: 'auto' })));
    renderApp('/daily?anken=PAY-1100&date=2026-10-06');
    const fo = await screen.findByRole('region', { name: 'Function overview' });
    expect(within(fo).getByText('None of the Daily phases had started on this day.')).toBeTruthy();
    expect(within(fo).queryByRole('tab')).toBeNull();
  });

  it('asks for an anken under "All my anken"', async () => {
    renderApp('/daily');
    expect(await screen.findByText('Pick an anken to see this view.')).toBeTruthy();
  });
});
