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
    expect(screen.getByRole('link', { name: 'Effort: −14h (Under-burn)' }).getAttribute('href'))
      .toBe('/anken/PAY-1100/tickets?label=under_burn');
  });

  it('links an over-burn effort but not an on-plan one', async () => {
    serveAnken({ schedule: rollup({ diff_h: 1, effort: 'over_burn' }) }, [phase('ITA', rollup())]);
    renderApp('/anken');
    expect((await screen.findByRole('link', { name: 'Effort: +1h (Over-burn)' })).getAttribute('href'))
      .toBe('/anken/PAY-1100/tickets?label=over_burn');
    cleanup();
    serveAnken({ schedule: rollup() }, [phase('ITA', rollup())]);
    renderApp('/anken');
    expect(await screen.findByText('Effort: +0h (On plan)')).toBeTruthy();
    expect(screen.queryByRole('link', { name: /^Effort:/ })).toBeNull();
  });

  it('tones the chips by label: red for overdue and late start, green for early, plain for late finish', async () => {
    serveAnken({ schedule: rollup({ overdue: 3 }) }, [phase('ITA', rollup({ late_start: 1, early_start: 1, late_finish: 1, early_finish: 2 }))]);
    renderApp('/anken/PAY-1100');
    const detail = await screen.findByRole('region', { name: 'PAY-1100 details' });
    const fg = (name: string) => within(detail).getByRole('link', { name }).style.color;
    expect(fg('Late start 1')).toBe('var(--hl-red-fg)');
    expect(fg('Early finish 2')).toBe('var(--hl-ok-fg)');
    expect(fg('Early start 1')).toBe('var(--hl-ok-fg)');
    expect(fg('Late finish 1')).toBe('var(--text-heading)');
    expect(screen.getByRole('link', { name: 'Overdue 3' }).style.color).toBe('var(--hl-red-fg)');
  });

  it('shows a positive effort deviation with its sign', async () => {
    serveAnken({ schedule: rollup({ diff_h: 1, effort: 'over_burn' }) }, [phase('ITA', rollup())]);
    renderApp('/anken');
    expect(await screen.findByText('Effort: +1h (Over-burn)')).toBeTruthy();
  });

  it('phase chips link to the ticket list', async () => {
    serveAnken(
      { schedule: rollup() },
      [phase('ITA', rollup({ plan_to_date_h: 6, actual_to_date_h: 7.5, diff_h: 1.5, effort: 'over_burn', state: 'LATE', late_start: 2, early_finish: 0 }))],
    );
    renderApp('/anken/PAY-1100');
    const detail = await screen.findByRole('region', { name: 'PAY-1100 details' });
    expect(within(detail).getByText('plan to date 6h · actual 7.5h · +1.5h (Over-burn)')).toBeTruthy();
    const chip = within(detail).getByRole('link', { name: 'Late start 2' });
    expect(chip.getAttribute('href')).toBe('/anken/PAY-1100/tickets?label=late_start&phase=ITA');
    expect(within(detail).getByText('Late')).toBeTruthy();
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

  it('says one working day in the singular, hides a zero and opens a row with Space', async () => {
    server.use(http.get('*/api/v1/anken/PAY-1100/tickets', () => HttpResponse.json([
      { ...late, schedule: issue({ finish: 'overdue', finish_days: 1 }) },
      { ...late, key: 'PAY-1013', schedule: issue({ finish: 'overdue', finish_days: 0 }) },
    ])));
    const { router } = renderApp('/anken/PAY-1100/tickets?label=overdue');
    const rows = await screen.findAllByRole('button', { name: /PAY-10/ });
    expect(rows[0].textContent).toContain('1 working day');
    expect(rows[0].textContent).not.toContain('1 working days');
    expect(rows[1].textContent).not.toContain('working day');
    fireEvent.keyDown(rows[1], { key: ' ' });
    await waitFor(() => expect(router.state.location.search).toMatchObject({ ticket: 'PAY-1013' }));
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

describe('ticket panel', () => {
  const serveTicket = (schedule: object) => server.use(
    http.get('*/api/v1/tickets/PAY-1213', () => HttpResponse.json({ ...fixtures.ticket, card: { ...fixtures.ticket.card, schedule } })),
  );

  it('ticket panel shows the three labels', async () => {
    serveTicket(issue({
      start: 'late_start', start_days: 2, finish: 'overdue', finish_days: 1, effort: 'under_burn',
      plan_to_date_h: 6, actual_to_date_h: 2, diff_h: -4,
    }));
    renderApp('/my-work?member=binh&ticket=PAY-1213');
    expect(await screen.findByText('Start: Late start · 2 working days')).toBeTruthy();
    expect(screen.getByText('Finish: Overdue · 1 working day')).toBeTruthy();
    expect(screen.getByText('Effort: Under-burn · plan to date 6h · actual 2h · −4h')).toBeTruthy();
  });

  it('says so without a plan or an estimate', async () => {
    serveTicket(issue({ start: 'no_plan', finish: 'no_plan', effort: 'no_estimate' }));
    renderApp('/my-work?member=binh&ticket=PAY-1213');
    expect(await screen.findByText('Start: No planned dates')).toBeTruthy();
    expect(screen.getByText('Finish: No planned dates')).toBeTruthy();
    expect(screen.getByText('Effort: No estimate')).toBeTruthy();
  });
});
