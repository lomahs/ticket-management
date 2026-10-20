import { cleanup, fireEvent, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import meAdmin from './fixtures/me-admin.json';
import meBa from './fixtures/me-ba.json';
import meNone from './fixtures/me-none.json';
import { renderApp, server } from './renderApp';

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => { cleanup(); server.resetHandlers(); localStorage.clear(); });
afterAll(() => server.close());

const unauthenticated = () => HttpResponse.json({ status: 401, code: 'unauthenticated', detail: 'Sign in first' }, { status: 401 });
const info = http.get('*/api/v1/auth/info', () => HttpResponse.json({ mode: 'demo', demo_password: 'demo', demo_users: ['an', 'minh'] }));

describe('sign-in and the route gate', () => {
  it('redirects to login when the session is missing', async () => {
    server.use(http.get('*/api/v1/me', unauthenticated), info);
    const { router } = renderApp('/planning');
    expect(await screen.findByRole('button', { name: 'Sign in' })).toBeTruthy();
    expect(router.state.location.pathname).toBe('/login');
    expect((router.state.location.search as { next?: string }).next).toBe('/planning');
    expect(await screen.findByText(/Demo · users: an, minh · password: demo/)).toBeTruthy();
  });

  it('signs in and goes to next', async () => {
    let signedIn = false;
    let body: unknown;
    server.use(
      http.get('*/api/v1/me', () => (signedIn ? HttpResponse.json(meAdmin) : unauthenticated())),
      info,
      http.post('*/api/v1/auth/login', async ({ request }) => { body = await request.json(); signedIn = true; return HttpResponse.json(meAdmin); }),
    );
    const { router } = renderApp('/login?next=%2Fanken');
    fireEvent.change(await screen.findByLabelText('Username'), { target: { value: 'an' } });
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'demo' } });
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByRole('heading', { name: 'Anken Overview' })).toBeTruthy();
    expect(body).toEqual({ username: 'an', password: 'demo' });
    expect(router.state.location.pathname).toBe('/anken');
  });

  it('restores the query of a deep link after sign-in', async () => {
    let signedIn = false;
    server.use(
      http.get('*/api/v1/me', () => (signedIn ? HttpResponse.json(meAdmin) : unauthenticated())),
      info,
      http.post('*/api/v1/auth/login', () => { signedIn = true; return HttpResponse.json(meAdmin); }),
    );
    const { router } = renderApp('/login?next=%2Fplanning%3Fstart%3D2026-10-05%26weeks%3D2');
    fireEvent.change(await screen.findByLabelText('Username'), { target: { value: 'an' } });
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'demo' } });
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/planning'));
    expect((router.state.location.search as { start?: string }).start).toBe('2026-10-05');
  });

  it('sends a screen query that loses its session to login, without re-requesting /me in a loop', async () => {
    let meCalls = 0;
    server.use(
      // the session is gone for good: /me answers admin once (the gate), then 401 like the real backend
      http.get('*/api/v1/me', () => { meCalls++; return meCalls === 1 ? HttpResponse.json(meAdmin) : unauthenticated(); }),
      info,
      http.get('*/api/v1/anken', unauthenticated),
    );
    const { router } = renderApp('/anken');
    expect(await screen.findByRole('button', { name: 'Sign in' })).toBeTruthy();
    expect(router.state.location.pathname).toBe('/login');
    expect((router.state.location.search as { next?: string }).next).toBe('/anken');
    await new Promise((r) => setTimeout(r, 200));
    expect(meCalls).toBeLessThanOrEqual(2);
  });

  it('shows the error of a bad password', async () => {
    server.use(
      http.get('*/api/v1/me', unauthenticated), info,
      http.post('*/api/v1/auth/login', () => HttpResponse.json({ status: 401, code: 'bad_credentials', detail: 'Wrong username or password' }, { status: 401 })),
    );
    renderApp('/login');
    fireEvent.change(await screen.findByLabelText('Username'), { target: { value: 'an' } });
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'nope' } });
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByText('Wrong username or password')).toBeTruthy();
  });

  it('no-access page requests access', async () => {
    let me: Record<string, unknown> = meNone;
    let sent: unknown;
    server.use(
      http.get('*/api/v1/me', () => HttpResponse.json(me)),
      http.post('*/api/v1/me/access-request', async ({ request }) => { sent = await request.json(); me = { ...meNone, access: 'requested' }; return HttpResponse.json(me); }),
    );
    const { router } = renderApp('/my-work');
    expect(await screen.findByText("You don't have access to Team Tracker yet.")).toBeTruthy();
    expect(router.state.location.pathname).toBe('/no-access');
    fireEvent.click(screen.getByRole('button', { name: 'Request access' }));
    expect(await screen.findByText(/Your request was sent/)).toBeTruthy();
    expect(sent).toEqual({ note: null });
  });

  it('sidebar shows the signed-in user and signs out', async () => {
    let out = false;
    let xsrf: string | null = null;
    document.cookie = 'XSRF-TOKEN=tok123; path=/';
    server.use(
      http.get('*/api/v1/me', () => (out ? unauthenticated() : HttpResponse.json(meBa))),
      info,
      http.post('*/api/v1/auth/logout', ({ request }) => { xsrf = request.headers.get('X-XSRF-TOKEN'); out = true; return new HttpResponse(null, { status: 204 }); }),
    );
    const { router } = renderApp('/my-work');
    const side = await screen.findByRole('complementary', { name: 'Sidebar' });
    expect(side.textContent).toContain('An');
    expect(side.textContent).toContain('BA');
    fireEvent.click(await screen.findByRole('button', { name: 'Sign out' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/login'));
    expect(xsrf).toBe('tok123');
  });
});
