import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { renderApp, server } from './renderApp';

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
