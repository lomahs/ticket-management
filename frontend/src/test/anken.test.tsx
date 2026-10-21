import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse, type JsonBodyType } from 'msw';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import meBa from './fixtures/me-ba.json';
import { fixtures, renderApp, server } from './renderApp';

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => { cleanup(); server.resetHandlers(); localStorage.clear(); });
afterAll(() => server.close());

const two = fixtures.anken.slice(0, 2); // PAY-1100, PAY-1300
const hidden = (key: string) => HttpResponse.json(
  { code: 'not_found', detail: `Anken ${key} is not tracked`, errors: [{ field: 'anken', message: `Anken ${key} is not tracked` }] },
  { status: 404, headers: { 'Content-Type': 'application/problem+json' } },
);
const switcher = async () => (await screen.findByRole('combobox', { name: 'Anken' })) as HTMLSelectElement;
const selectedText = (s: HTMLSelectElement) => s.options[s.selectedIndex]?.text;

describe('global anken switcher', () => {
  it('switcher lists all my anken and narrows the overview', async () => {
    const asked: (string | null)[] = [];
    server.use(http.get('*/api/v1/anken', ({ request }) => {
      const anken = new URL(request.url).searchParams.get('anken');
      asked.push(anken);
      return HttpResponse.json(anken ? two.filter((a) => a.key === anken) : two);
    }));
    const { router } = renderApp('/anken');
    const s = await switcher();
    await waitFor(() => expect([...s.options].map((o) => o.text)).toEqual(['All my anken', 'PAY-1100 · Payment flow revision', 'PAY-1300 · e-KYC integration']));
    expect(selectedText(s)).toBe('All my anken');
    fireEvent.change(s, { target: { value: 'PAY-1300' } });
    await waitFor(() => expect(asked).toContain('PAY-1300'));
    expect((router.state.location.search as { anken?: string }).anken).toBe('PAY-1300');
    expect(localStorage.getItem('tt.anken')).toBe('PAY-1300');
  });

  it('an explicit ?anken= wins over the stored one and is stored', async () => {
    localStorage.setItem('tt.anken', 'PAY-1100');
    const asked: (string | null)[] = [];
    server.use(http.get('*/api/v1/planning', ({ request }) => {
      asked.push(new URL(request.url).searchParams.get('anken'));
      return HttpResponse.json({ start: '2026-10-05', end: '2026-10-18', projects: ['PAY'], capacity: [], days: [], rows: [] });
    }));
    renderApp('/planning?anken=PAY-1300');
    await waitFor(() => expect(asked).toContain('PAY-1300'));
    expect(asked).not.toContain('PAY-1100');
    await waitFor(() => expect(localStorage.getItem('tt.anken')).toBe('PAY-1300'));
  });

  it('burndown asks for an anken under All', async () => {
    const asked: (string | null)[] = [];
    server.use(http.get('*/api/v1/effort/burndown', ({ request }) => {
      asked.push(new URL(request.url).searchParams.get('anken'));
      return HttpResponse.json({ code: 'not_found', detail: 'stop here', errors: [] }, { status: 404, headers: { 'Content-Type': 'application/problem+json' } });
    }));
    const { router } = renderApp('/effort/burndown');
    expect(await screen.findByText('Pick an anken to see this view.')).toBeTruthy();
    expect(asked).toEqual([]); // nothing is asked under All
    fireEvent.change(await screen.findByRole('combobox', { name: 'Pick an anken' }), { target: { value: 'PAY-1300' } });
    await waitFor(() => expect(asked).toEqual(['PAY-1300']));
    expect((router.state.location.search as { anken?: string }).anken).toBe('PAY-1300');
    expect(selectedText(await switcher())).toBe('PAY-1300 · e-KYC integration');
  });

  it('analysis asks for an anken under All', async () => {
    renderApp('/effort/analysis');
    expect(await screen.findByText('Pick an anken to see this view.')).toBeTruthy();
  });

  it('opening an anken\'s matrix selects it', async () => {
    server.use(http.get('*/api/v1/anken/PAY-1300/matrix', () => HttpResponse.json(fixtures.matrix)));
    const { router } = renderApp('/anken/PAY-1300/matrix');
    const s = await switcher();
    await waitFor(() => expect(s.value).toBe('PAY-1300'));
    expect(localStorage.getItem('tt.anken')).toBe('PAY-1300');
    // Another anken in the switcher opens that anken's matrix.
    server.use(http.get('*/api/v1/anken/PAY-1100/matrix', () => HttpResponse.json(fixtures.matrix)));
    fireEvent.change(s, { target: { value: 'PAY-1100' } });
    await waitFor(() => expect(router.state.location.pathname).toBe('/anken/PAY-1100/matrix'));
  });

  it('a hidden stored anken resets to All', async () => {
    localStorage.setItem('tt.anken', 'CRM-400');
    server.use(http.get('*/api/v1/anken', ({ request }) => {
      const anken = new URL(request.url).searchParams.get('anken');
      return anken === 'CRM-400' ? hidden('CRM-400') : HttpResponse.json(two);
    }));
    renderApp('/anken');
    const s = await switcher();
    await waitFor(() => expect(localStorage.getItem('tt.anken')).toBe(''));
    expect(selectedText(s)).toBe('All my anken');
    expect((await screen.findAllByText('Payment flow revision')).length).toBeGreaterThan(0); // the overview of every visible anken
  });

  it('scoped requests send the switcher\'s anken', async () => {
    const seen: Record<string, (string | null)[]> = { members: [], myWork: [] };
    const record = (name: string, body: JsonBodyType) => ({ request }: { request: Request }) => {
      seen[name].push(new URL(request.url).searchParams.get('anken'));
      return HttpResponse.json(body);
    };
    server.use(http.get('*/api/v1/members', record('members', fixtures.members)), http.get('*/api/v1/my-work', record('myWork', fixtures.myWork)));
    localStorage.setItem('tt.anken', 'PAY-1100');
    renderApp('/my-work');
    await waitFor(() => expect(seen.myWork).toEqual(['PAY-1100']));
    expect(seen.members).toEqual(['PAY-1100']);
  });

  it('without read on the Overview the switcher lists the anken from /me', async () => {
    server.use(http.get('*/api/v1/me', () => HttpResponse.json({ ...meBa, permissions: { ...meBa.permissions, overview: 'none' } })));
    renderApp('/planning');
    const s = await switcher();
    await waitFor(() => expect([...s.options].map((o) => o.text)).toEqual(['All my anken', 'PAY-1100']));
  });

  it('leave for a member outside the anken shows the server\'s message', async () => {
    server.use(
      http.get('*/api/v1/calendar', () => HttpResponse.json({ month: '2026-10', days: [{ date: '2026-10-07', extra_note: null, is_today: true }], rows: [] })),
      http.post('*/api/v1/leaves', () => HttpResponse.json(
        { code: 'not_found', detail: 'Member binh was not found', errors: [{ field: 'member_id', message: 'Member binh was not found' }] },
        { status: 404, headers: { 'Content-Type': 'application/problem+json' } },
      )),
    );
    renderApp('/calendar/leave/new?member=binh&date=2026-10-07');
    const dialog = await screen.findByRole('dialog', { name: 'Add leave' });
    await waitFor(() => expect((within(dialog).getByLabelText('Member') as HTMLSelectElement).value).toBe('binh'));
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }));
    expect((await within(dialog).findByRole('alert')).textContent).toBe('Member binh was not found');
    expect(localStorage.getItem('tt.anken')).toBeNull(); // not about the anken: the switcher is left alone
  });
});
