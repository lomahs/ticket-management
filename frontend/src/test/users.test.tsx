import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import meBa from './fixtures/me-ba.json';
import members from './fixtures/members.json';
import { renderApp, server } from './renderApp';

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => { cleanup(); server.resetHandlers(); localStorage.clear(); });
afterAll(() => server.close());

const LEVELS = ['my_work', 'overview', 'matrix', 'ticket', 'planning', 'effort', 'qa', 'calendar', 'members'];
const effective = (over: Record<string, string> = {}) => Object.fromEntries(LEVELS.map((f) => [f, over[f] ?? (f === 'members' ? 'none' : 'read')]));
const row = (id: string, over: Record<string, string> = {}, extra: Record<string, unknown> = {}) => ({
  member: members.find((m) => m.id === id)!, access: 'active', bootstrap_admin: false, overrides: over, effective: effective(over),
  anken: [], anken_scope: 'default', all_anken: false, ...extra,
});
const request = (over: Record<string, unknown> = {}) => ({
  id: 7, email: 'lan.vo@acme.vn', name: 'Lan', note: 'Please add me', requested_at: '2026-10-06T08:00:00Z', status: 'pending',
  decided_by: null, decided_at: null, member_id: null, ...over,
});

describe('User management', () => {
  it('lists users and changes a permission', async () => {
    const bodies: unknown[] = [];
    server.use(
      http.get('*/api/v1/admin/users', () => HttpResponse.json([row('an')])),
      http.patch('*/api/v1/admin/users/an', async ({ request: r }) => { bodies.push(await r.json()); return HttpResponse.json(row('an', { planning: 'write' })); }),
    );
    renderApp('/admin/users');
    expect(await screen.findByRole('heading', { name: 'Users' })).toBeTruthy();
    const select = (await screen.findByLabelText('An Planning')) as HTMLSelectElement;
    expect(select.value).toBe('read');
    fireEvent.change(select, { target: { value: 'write' } });
    await waitFor(() => expect(bodies).toEqual([{ overrides: { planning: 'write' } }]));
  });

  it('resets an override', async () => {
    const bodies: unknown[] = [];
    server.use(
      http.get('*/api/v1/admin/users', () => HttpResponse.json([row('an', { planning: 'write' })])),
      http.patch('*/api/v1/admin/users/an', async ({ request: r }) => { bodies.push(await r.json()); return HttpResponse.json(row('an')); }),
    );
    renderApp('/admin/users');
    fireEvent.click(await screen.findByRole('button', { name: 'Reset to role default' }));
    await waitFor(() => expect(bodies).toEqual([{ overrides: { planning: null } }]));
  });

  it('shows an admin as full access without per-feature levels', async () => {
    server.use(http.get('*/api/v1/admin/users', () => HttpResponse.json([row('an', {}, { bootstrap_admin: true })])));
    renderApp('/admin/users');
    const sel = (await screen.findByLabelText('An Planning')) as HTMLSelectElement;
    expect(sel.disabled).toBe(true);
    expect(sel.value).toBe('all');
    expect(screen.queryByRole('button', { name: 'Reset to role default' })).toBeNull();
    const admin = screen.getByLabelText('An admin') as HTMLInputElement;
    expect(admin.checked).toBe(true);
    expect(admin.disabled).toBe(true);
    expect(admin.title).toBe('Set in TRACKER_ADMINS');
    const access = screen.getByLabelText('An access') as HTMLSelectElement;
    expect(access.value).toBe('active');
    expect(access.disabled).toBe(true);
    expect(access.title).toBe('Set in TRACKER_ADMINS');
  });

  it('keeps the access of other users editable', async () => {
    server.use(http.get('*/api/v1/admin/users', () => HttpResponse.json([row('an')])));
    renderApp('/admin/users');
    const access = (await screen.findByLabelText('An access')) as HTMLSelectElement;
    expect(access.disabled).toBe(false);
    expect(access.title).toBe('');
  });

  it('shows the problem detail when a change is rejected', async () => {
    server.use(
      http.get('*/api/v1/admin/users', () => HttpResponse.json([row('an')])),
      http.patch('*/api/v1/admin/users/an', () => HttpResponse.json({ status: 400, code: 'bad_request', detail: 'A JM cannot be active' }, { status: 400 })),
    );
    renderApp('/admin/users');
    fireEvent.change(await screen.findByLabelText('An Planning'), { target: { value: 'write' } });
    expect(await screen.findByText('A JM cannot be active')).toBeTruthy();
  });

  it('approves a request as PIM', async () => {
    const bodies: unknown[] = [];
    let approved = false;
    let usersFetches = 0;
    server.use(
      http.get('*/api/v1/admin/users', () => { usersFetches++; return HttpResponse.json([row('an')]); }),
      http.get('*/api/v1/admin/access-requests', ({ request: r }) => {
        expect(new URL(r.url).searchParams.get('status')).toBe('pending');
        return HttpResponse.json(approved ? [] : [request()]);
      }),
      http.post('*/api/v1/admin/access-requests/7/approve', async ({ request: r }) => {
        bodies.push(await r.json()); approved = true;
        return HttpResponse.json(row('an'));
      }),
    );
    renderApp('/admin/users');
    const section = await screen.findByRole('region', { name: 'Access requests' });
    expect(within(section).getByText('lan.vo@acme.vn')).toBeTruthy();
    expect(within(section).getByText('Please add me')).toBeTruthy();
    await screen.findByLabelText('An Planning');
    const before = usersFetches;
    fireEvent.click(within(section).getByRole('button', { name: 'Approve…' }));
    const dialog = await screen.findByRole('dialog', { name: 'Approve access' });
    expect((within(dialog).getByLabelText('Role') as HTMLSelectElement).value).toBe('BA');
    fireEvent.change(within(dialog).getByLabelText('Role'), { target: { value: 'PIM' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Approve' }));
    await waitFor(() => expect(bodies).toEqual([{ role: 'PIM', member_id: null }]));
    await waitFor(() => expect(usersFetches).toBeGreaterThan(before));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Approve access' })).toBeNull());
  });

  it('notes when the matched member is an admin', async () => {
    const an = members.find((m) => m.id === 'an')!;
    server.use(
      http.get('*/api/v1/admin/users', () => HttpResponse.json([row('an', {}, { member: { ...an, permission: 'admin' }, access: 'none' }), row('binh', {}, { access: 'none' })])),
      http.get('*/api/v1/admin/access-requests', () => HttpResponse.json([request({ member_id: 'an' })])),
    );
    renderApp('/admin/users');
    const section = await screen.findByRole('region', { name: 'Access requests' });
    await screen.findByLabelText('An Planning');
    fireEvent.click(within(section).getByRole('button', { name: 'Approve…' }));
    const dialog = await screen.findByRole('dialog', { name: 'Approve access' });
    expect(within(dialog).getByText(/^Admin:/)).toBeTruthy();
    fireEvent.change(within(dialog).getByLabelText('Member'), { target: { value: 'binh' } });
    expect(within(dialog).queryByText(/^Admin:/)).toBeNull();
  });

  it('rejects a request', async () => {
    let called = false;
    server.use(
      http.get('*/api/v1/admin/users', () => HttpResponse.json([row('an')])),
      http.get('*/api/v1/admin/access-requests', () => HttpResponse.json([request()])),
      http.post('*/api/v1/admin/access-requests/7/reject', () => { called = true; return HttpResponse.json(request({ status: 'rejected' })); }),
    );
    renderApp('/admin/users');
    fireEvent.click(await screen.findByRole('button', { name: 'Reject' }));
    await waitFor(() => expect(called).toBe(true));
  });

  it("edits a user's anken", async () => {
    const bodies: unknown[] = [];
    server.use(
      http.get('*/api/v1/admin/users', () => HttpResponse.json([row('an', {}, { anken: ['PAY-1100'] })])),
      http.patch('*/api/v1/admin/users/an', async ({ request: r }) => { bodies.push(await r.json()); return HttpResponse.json(row('an')); }),
    );
    renderApp('/admin/users');
    expect(await screen.findByText('PAY-1100')).toBeTruthy();
    fireEvent.click(await screen.findByRole('button', { name: 'An anken' }));
    const dialog = await screen.findByRole('dialog', { name: 'Edit anken' });
    const pay1100 = (await within(dialog).findByLabelText('PAY-1100')) as HTMLInputElement;
    expect(pay1100.checked).toBe(true);
    fireEvent.click(pay1100);
    fireEvent.click(within(dialog).getByLabelText('PAY-1300'));
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(bodies).toEqual([{ anken: ['PAY-1300'] }]));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Edit anken' })).toBeNull());
  });

  it('shows All anken for a user who sees everything', async () => {
    server.use(http.get('*/api/v1/admin/users', () => HttpResponse.json([row('an', {}, { all_anken: true })])));
    renderApp('/admin/users');
    expect(await screen.findByText('All anken', { selector: 'span' })).toBeTruthy();
  });

  it('sets scope to all', async () => {
    const bodies: unknown[] = [];
    server.use(
      http.get('*/api/v1/admin/users', () => HttpResponse.json([row('an')])),
      http.patch('*/api/v1/admin/users/an', async ({ request: r }) => { bodies.push(await r.json()); return HttpResponse.json(row('an')); }),
    );
    renderApp('/admin/users');
    fireEvent.change(await screen.findByLabelText('An anken scope'), { target: { value: 'all' } });
    await waitFor(() => expect(bodies).toEqual([{ anken_scope: 'all' }]));
  });

  it('disables the scope of an admin', async () => {
    server.use(http.get('*/api/v1/admin/users', () => HttpResponse.json([row('an', {}, { bootstrap_admin: true })])));
    renderApp('/admin/users');
    expect(((await screen.findByLabelText('An anken scope')) as HTMLSelectElement).disabled).toBe(true);
  });

  it('merges two members', async () => {
    const bodies: unknown[] = [];
    let usersFetches = 0;
    const an2 = { ...members.find((m) => m.id === 'an')!, id: 'an-2', name: 'An Two' };
    server.use(
      http.get('*/api/v1/admin/users', () => { usersFetches++; return HttpResponse.json([row('an'), row('minh'), row('an-2', {}, { member: an2 })]); }),
      http.post('*/api/v1/admin/members/merge', async ({ request: r }) => { bodies.push(await r.json()); return HttpResponse.json(row('an')); }),
    );
    const { queryClient } = renderApp('/admin/users');
    await screen.findByLabelText('An Planning');
    const elsewhere = [['members', 'PAY-9'], ['anken', 'list', 'PAY-9'], ['planning', '2026-10-05', 6, 'PAY-9'], ['calendar', '2026-10', 'PAY-9']];
    elsewhere.forEach((k) => queryClient.setQueryData(k, {}));
    const before = usersFetches;
    fireEvent.click(screen.getByRole('button', { name: 'Merge An…' }));
    const dialog = await screen.findByRole('dialog', { name: 'Merge members' });
    const pick = within(dialog).getByLabelText('Member to merge') as HTMLSelectElement;
    // The actor (Minh) cannot be the one removed, and a member cannot be merged into itself.
    expect([...pick.options].map((o) => o.value)).toEqual(['an-2']);
    expect(within(dialog).getByText(/This moves An Two's anken, leave and Jira history to An and deletes An Two\./)).toBeTruthy();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Merge' }));
    await waitFor(() => expect(bodies).toEqual([{ keep_id: 'an', remove_id: 'an-2' }]));
    await waitFor(() => expect(usersFetches).toBeGreaterThan(before));
    // The merged member's anken, leave and work move too, so the screens that list people reload.
    elsewhere.forEach((k) => expect(queryClient.getQueryState(k)?.isInvalidated, String(k[0])).toBe(true));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Merge members' })).toBeNull());
  });

  it('sends a non-admin away', async () => {
    server.use(http.get('*/api/v1/me', () => HttpResponse.json(meBa)));
    const { router } = renderApp('/admin/users');
    expect(await screen.findByRole('heading', { name: 'My Work' })).toBeTruthy();
    expect(router.state.location.pathname).toBe('/my-work');
  });

  it('sends a non-admin away from Settings', async () => {
    server.use(http.get('*/api/v1/me', () => HttpResponse.json(meBa)));
    const { router } = renderApp('/settings/structure');
    expect(await screen.findByRole('heading', { name: 'My Work' })).toBeTruthy();
    expect(router.state.location.pathname).toBe('/my-work');
  });

  it('shows the pending count on the Users link', async () => {
    server.use(http.get('*/api/v1/admin/access-requests', () => HttpResponse.json([request(), request({ id: 8, email: 'x@acme.vn' })])));
    renderApp('/my-work');
    const link = await screen.findByRole('link', { name: /Users/ });
    await waitFor(() => expect(within(link).getByText('2')).toBeTruthy());
  });
});
