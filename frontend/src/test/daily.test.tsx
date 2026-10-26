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
const ticket = (key: string, who: string, name = 'Requirement study') => ({ key, name, assignee_id: who, status: 'C', planned_start: '2026-09-03', planned_end: '2026-09-04', late: false, late_days: 0 });
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
          { id: 'F03', name: 'Domestic card payment', owner_id: 'binh', steps: [
            step('PAY-1211', 'create', 'C'), step('PAY-1212', 'balr', 'C'), step('PAY-1213', 'fixb', 'W', { late: true, late_days: 2, rounds: 3 }),
            step('PAY-1214', 'jmr', 'N', { assignee_id: 'tanaka', with_jm: true }), null] },
          { id: 'F04', name: 'Refund', owner_id: 'an', steps: [
            ...done('PAY-122'), step('PAY-1224', 'jmr', 'W', { assignee_id: 'an' }), step('PAY-1225', 'fixj', 'W', { assignee_id: 'an', rounds: 1 })] },
          { id: 'F05', name: 'Installments', owner_id: 'an', steps: [
            ...done('PAY-123'), step('PAY-1234', 'jmr', 'W', { assignee_id: 'tanaka', with_jm: true }), step('PAY-1235', 'fixj', 'N')] }] }),
        task('PAY-1160', 'Requirement study', { unmatched: 2, tickets: [ticket('PAY-1161', 'an'), ticket('PAY-1162', 'binh')] }),
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

describe('Daily Overview', () => {
  it('shows the live day, switches to a captured day and saves a note', async () => {
    const asked: (string | null)[] = [];
    let note: unknown;
    server.use(
      http.get('*/api/v1/daily', ({ request }) => {
        const date = new URL(request.url).searchParams.get('date');
        asked.push(date);
        return HttpResponse.json(date
          ? { content: content(date, 'Asked the card team'), live: false, captured_at: snapshots[0].captured_at, captured_by: 'auto', today: '2026-10-07', auto_capture: '18:00', snapshots }
          : { content: content('2026-10-07', 'Waiting for the card team'), live: true, captured_at: null, captured_by: null, today: '2026-10-07', auto_capture: '18:00', snapshots });
      }),
      http.put('*/api/v1/daily/notes/:key', async ({ request }) => { note = await request.json(); return HttpResponse.json({ key: 'PAY-1173', note: 'Send today' }); }),
    );
    const { router } = renderApp('/daily?anken=PAY-1100');
    expect(await screen.findByRole('heading', { name: 'Payment flow revision' })).toBeTruthy();
    expect(screen.getByText('62%')).toBeTruthy();
    expect(screen.getByText('+6h')).toBeTruthy();
    expect(screen.getByText(/Live · not captured yet/)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Capture' })).toBeTruthy();

    const table = screen.getByRole('region', { name: 'Phases and functions' });
    expect(within(table).getByText('Prepare')).toBeTruthy(); // a done group of an older snapshot, as one row
    expect(within(table).getByText('Domestic card payment')).toBeTruthy();
    expect(within(table).getByText('Late 2d')).toBeTruthy();
    expect(within(table).queryByText(/R3 · Team/)).toBeNull();
    expect(within(table).getAllByText('Requirement study')).toHaveLength(3); // the Task and one row per ticket
    // a done sub-task shows just Done: no Closed, no dates
    expect(within(table).queryByText('Closed')).toBeNull();
    expect(within(table).queryByText('04/09')).toBeNull();
    // the JM round: the JM Review's assignee tells whether JM is reviewing or the team is fixing their comments
    expect(within(table).getByTitle(/^PAY-1234 .* now: waiting for the JM review$/)).toBeTruthy();
    expect(within(table).getByTitle(/^PAY-1225 .* now: fixing JM comments$/)).toBeTruthy();
    expect(within(table).queryAllByTitle(/now:/)).toHaveLength(2); // F03's JM Review has not started
    // a done Task starts collapsed
    expect(within(table).getAllByText('Design review')).toHaveLength(1);
    fireEvent.click(within(table).getByRole('button', { name: 'Expand Design review' }));
    expect(within(table).getAllByText('Design review')).toHaveLength(2);
    fireEvent.click(within(table).getByRole('button', { name: 'Collapse Payment flow revision' }));
    expect(within(table).queryByText('Domestic card payment')).toBeNull();
    expect(within(table).getByText(/2 sub-tasks outside the name rule, not listed here/)).toBeTruthy();
    expect(within(table).getByRole('link', { name: 'See them in Ticket Check' }).getAttribute('href')).toBe('/ticket-check?check=rule');

    const typed = screen.getByRole('region', { name: 'QnA, Bug and WorkRequest' });
    expect(within(typed).getByText('1 with JM · 1 with the team')).toBeTruthy();
    expect(within(typed).getByText('with JM · 4d')).toBeTruthy();
    expect(within(typed).getByText('No bugs yet')).toBeTruthy();
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
    expect(within(screen.getByRole('region', { name: 'QnA, Bug and WorkRequest' })).getByText('Asked the card team')).toBeTruthy(); // read-only
    expect(screen.queryByLabelText('Note on PAY-1171')).toBeNull();
  });

  it('asks for an anken under "All my anken"', async () => {
    renderApp('/daily');
    expect(await screen.findByText('Pick an anken to see this view.')).toBeTruthy();
  });
});
