import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { fixtures, renderApp, server } from './renderApp';

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => { cleanup(); server.resetHandlers(); localStorage.clear(); });
afterAll(() => server.close());

const rollup = (over: object = {}) => ({
  plan_to_date_h: 0, actual_to_date_h: 0, diff_h: 0, effort: 'on_plan', overdue: 0,
  late_start: 0, early_start: 0, late_finish: 0, early_finish: 0, state: 'ON_TRACK', ...over,
});
const issue = (over: object = {}) => ({
  start: 'on_time_start', start_days: 0, finish: 'open', finish_days: 0, effort: 'on_plan',
  plan_to_date_h: 0, actual_to_date_h: 0, diff_h: 0, ...over,
});
const phase = (name: string, schedule: object) => ({
  name, pct: 40, closed: 2, total: 5, start: '2026-10-01', end: '2026-10-20', spent_h: 4, remaining_h: 6, schedule,
});

/** PAY-1100's card and detail with the given schedules. */
function serveAnken(card: object, phases: ReturnType<typeof phase>[]) {
  const c = { ...fixtures.anken[0], ...card, phases };
  server.use(
    http.get('*/api/v1/anken', () => HttpResponse.json([c])),
    http.get('*/api/v1/anken/PAY-1100', () => HttpResponse.json({ card: c, attention: [], tasks: [], not_members: [] })),
  );
}

const late = { ...fixtures.myWork.delay[0], schedule: issue({ finish: 'overdue', finish_days: 3 }) };
const tasks = [
  late,
  { ...late, key: 'PAY-1013', summary: 'UT – M10 Points expiry', assignee_id: 'tanaka', schedule: issue({ finish: 'overdue', finish_days: 1 }) },
];

describe('schedule state on the overview', () => {
  it('card shows state, effort and overdue link', async () => {
    serveAnken(
      { schedule: rollup({ state: 'LATE', diff_h: -14, effort: 'under_burn', overdue: 3 }) },
      [phase('ITA', rollup())],
    );
    renderApp('/anken');
    expect(await screen.findByText('Late')).toBeTruthy();
    expect(screen.getByText('Effort: −14h (Under-burn)')).toBeTruthy();
    const link = screen.getByRole('link', { name: 'Overdue 3' });
    expect(link.getAttribute('href')).toBe('/anken/PAY-1100/tickets?label=overdue');
  });

  it('shows a positive effort deviation with its sign', async () => {
    serveAnken({ schedule: rollup({ diff_h: 1, effort: 'over_burn' }) }, [phase('ITA', rollup())]);
    renderApp('/anken');
    expect(await screen.findByText('Effort: +1h (Over-burn)')).toBeTruthy();
  });

  it('phase chips link to the ticket list', async () => {
    serveAnken(
      { schedule: rollup() },
      [phase('ITA', rollup({ plan_to_date_h: 6, actual_to_date_h: 7.5, diff_h: 1.5, late_start: 2, early_finish: 0 }))],
    );
    renderApp('/anken/PAY-1100');
    const detail = await screen.findByRole('region', { name: 'PAY-1100 details' });
    expect(within(detail).getByText('plan to date 6h · actual 7.5h · +1.5h')).toBeTruthy();
    const chip = within(detail).getByRole('link', { name: 'Late start 2' });
    expect(chip.getAttribute('href')).toBe('/anken/PAY-1100/tickets?label=late_start&phase=ITA');
    expect(within(detail).getByText('Early finish 0')).toBeTruthy();
    expect(within(detail).queryByRole('link', { name: 'Early finish 0' })).toBeNull();
  });
});

describe('ticket list', () => {
  it('opens the ticket panel from a row', async () => {
    server.use(http.get('*/api/v1/anken/PAY-1100/tickets', () => HttpResponse.json(tasks)));
    const { router } = renderApp('/anken/PAY-1100/tickets?label=overdue');
    expect(await screen.findByRole('heading', { name: 'Overdue · PAY-1100' })).toBeTruthy();
    const rows = await screen.findAllByRole('button', { name: /PAY-10/ });
    expect(rows[0].textContent).toContain('3 working days');
    fireEvent.click(rows[0]);
    await waitFor(() => expect(router.state.location.search).toMatchObject({ ticket: 'PAY-1012' }));
  });

  it('shows effort labels as hours and the phase in the heading', async () => {
    const asked: string[] = [];
    server.use(http.get('*/api/v1/anken/PAY-1100/tickets', ({ request }) => {
      asked.push(new URL(request.url).search);
      return HttpResponse.json([{ ...late, schedule: issue({ effort: 'over_burn', diff_h: 2.5 }) }]);
    }));
    renderApp('/anken/PAY-1100/tickets?label=over_burn&phase=ITA');
    expect(await screen.findByRole('heading', { name: 'Over-burn · PAY-1100 · ITA' })).toBeTruthy();
    expect(await screen.findByText('+2.5h')).toBeTruthy();
    expect(asked).toEqual(['?label=over_burn&phase=ITA']);
  });

  it('says so when no ticket has the label', async () => {
    server.use(http.get('*/api/v1/anken/PAY-1100/tickets', () => HttpResponse.json([])));
    renderApp('/anken/PAY-1100/tickets?label=late_start');
    expect(await screen.findByText('No tickets with this label.')).toBeTruthy();
  });

  it('rejects an unknown label', async () => {
    const asked: (string | null)[] = [];
    server.use(http.get('*/api/v1/anken/PAY-1100/tickets', ({ request }) => {
      asked.push(new URL(request.url).searchParams.get('label'));
      return HttpResponse.json([]);
    }));
    const { router } = renderApp('/anken/PAY-1100/tickets?label=bogus');
    expect(await screen.findByRole('heading', { name: 'Overdue · PAY-1100' })).toBeTruthy();
    expect(asked).toEqual(['overdue']);
    expect(router.state.location.search).toMatchObject({ label: 'overdue' });
  });

  it('keeps the anken switcher on All', async () => {
    server.use(
      http.get('*/api/v1/anken', () => HttpResponse.json(fixtures.anken.slice(0, 2))),
      http.get('*/api/v1/anken/PAY-1100/tickets', () => HttpResponse.json([])),
    );
    renderApp('/anken/PAY-1100/tickets?label=overdue');
    const s = (await screen.findByRole('combobox', { name: 'Anken' })) as HTMLSelectElement;
    await screen.findByText('No tickets with this label.');
    expect(s.options[s.selectedIndex].text).toBe('All my anken');
    expect(localStorage.getItem('tt.anken')).toBeNull();
  });
});

describe('planning actual bar', () => {
  it('is red when the card is overdue or late-finished, not by comparing dates', async () => {
    const card = (key: string, finish: string) => ({
      ...fixtures.myWork.delay[0], key, summary: key, function_id: null, planned_start: '2026-10-05', planned_end: '2026-10-05',
      actual_start: '2026-10-05', actual_end: '2026-10-07', status: 'C', schedule: issue({ finish }),
    });
    const dates = ['2026-10-05', '2026-10-06', '2026-10-07'];
    const lane = (member: string, c: object) => ({
      member_id: member, days: dates.map((date) => ({ date, kind: null, label: null, tentative: false })), items: [{ card: c, color_step: null }],
    });
    server.use(http.get('*/api/v1/planning', () => HttpResponse.json({
      start: '2026-10-05', end: '2026-10-11', projects: ['PAY'], capacity: [],
      days: dates.map((date) => ({ date, is_today: date === '2026-10-07', extra_note: null })),
      rows: [lane('binh', card('PAY-9001', 'on_time_finish')), lane('tanaka', card('PAY-9002', 'late_finish')), lane('an', card('PAY-9003', 'overdue'))],
    })));
    const { container } = renderApp('/planning?start=2026-10-05&weeks=1');
    await screen.findByRole('button', { name: 'PAY-9003 PAY-9003' });
    const bars = [...container.querySelectorAll<HTMLElement>('div[style*="height: 3px"]')].map((b) => b.style.background);
    expect(bars).toHaveLength(3);
    expect(bars.filter((b) => b.includes('hl-red-fg'))).toHaveLength(2);
    expect(bars[0]).toContain('text-heading');
  });
});
