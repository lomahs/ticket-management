import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import meBa from './fixtures/me-ba.json';
import meMembersRead from './fixtures/me-members-read.json';
import mePim from './fixtures/me-pim.json';
import { fixtures, renderApp, server } from './renderApp';

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => { cleanup(); server.resetHandlers(); localStorage.clear(); });
afterAll(() => server.close());

const [an, binh] = fixtures.members;
const view = (over: object = {}) => ({
  epic_key: 'PAY-1100',
  members: [{ member: an, access: 'active', added_at: '2026-10-01', added_by: 'minh.hoang@acme.vn' }],
  not_members: [],
  candidates: [binh],
  ...over,
});
const detail = (over: object = {}) => ({
  card: { ...fixtures.anken[0], pim_ids: [] }, attention: [], tasks: [], not_members: [], ...over,
});
const serve = (d: object = detail(), v: object = view()) => server.use(
  http.get('*/api/v1/anken/PAY-1100', () => HttpResponse.json(d)),
  http.get('*/api/v1/anken/PAY-1100/members', () => HttpResponse.json(v)),
);
const panel = async () => within(await screen.findByRole('region', { name: 'Members' }));

describe('anken members', () => {
  it('lists members and adds a not-member', async () => {
    const posted: unknown[] = [];
    serve(detail({ not_members: [{ member_id: 'binh', open_subtasks: 3, logged_h: 12.5 }] }));
    server.use(http.post('*/api/v1/anken/PAY-1100/members', async ({ request }) => {
      posted.push(await request.json());
      return HttpResponse.json(view());
    }));
    renderApp('/anken/PAY-1100');
    expect(await screen.findByText('Working here but not a member')).toBeTruthy();
    expect(screen.getByText(/Bình — 3 open sub-tasks, 12\.5h logged/)).toBeTruthy();
    expect(within(await screen.findByRole('region', { name: 'Members' })).getByText(/added by Minh on 2026-10-01/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Add to anken' }));
    await waitFor(() => expect(posted).toEqual([{ member_id: 'binh' }]));
  });

  it('says who added each member', async () => {
    serve(detail(), view({ members: [
      { member: an, access: 'active', added_at: '2026-10-01', added_by: 'seed' },
      { member: binh, access: 'none', added_at: '2026-10-02', added_by: 'someone@client.co.jp' },
    ], candidates: [] }));
    renderApp('/anken/PAY-1100');
    const p = await panel();
    expect(await p.findByText(/added from Jira activity on 2026-10-01/)).toBeTruthy();
    expect(p.getByText(/added by someone@client\.co\.jp on 2026-10-02/)).toBeTruthy();
    expect(p.queryByText(/added by seed/)).toBeNull();
  });

  it('pim sees no PM option', async () => {
    serve();
    server.use(http.get('*/api/v1/me', () => HttpResponse.json(mePim)));
    renderApp('/anken/PAY-1100');
    const p = await panel();
    const select = (await p.findByRole('combobox', { name: 'Role of An' })) as HTMLSelectElement;
    const options = [...select.options].map((o) => o.text);
    expect(options).toContain('BAL');
    expect(options).not.toContain('PM');
    expect(options).not.toContain('PIM');
  });

  it('admin can pick PM and the change is patched', async () => {
    const patched: unknown[] = [];
    serve();
    server.use(http.patch('*/api/v1/people/an', async ({ request }) => {
      patched.push(await request.json());
      return HttpResponse.json({ ...an, role: 'PM' });
    }));
    renderApp('/anken/PAY-1100');
    const select = (await (await panel()).findByRole('combobox', { name: 'Role of An' })) as HTMLSelectElement;
    expect([...select.options].map((o) => o.text)).toContain('PM');
    fireEvent.change(select, { target: { value: 'PM' } });
    await waitFor(() => expect(patched).toEqual([{ role: 'PM' }]));
  });

  it('add person sends the form', async () => {
    const posted: unknown[] = [];
    serve();
    server.use(http.post('*/api/v1/people', async ({ request }) => {
      posted.push(await request.json());
      return HttpResponse.json({ ...an, id: 'new', name: 'Nam' }, { status: 201 });
    }));
    renderApp('/anken/PAY-1100');
    fireEvent.click(await (await panel()).findByRole('button', { name: 'Add person…' }));
    const dialog = within(await screen.findByRole('dialog', { name: 'Add person' }));
    fireEvent.change(dialog.getByLabelText('E-mail'), { target: { value: 'nam@acme.vn' } });
    fireEvent.change(dialog.getByLabelText('Name'), { target: { value: 'Nam' } });
    expect((dialog.getByRole('checkbox', { name: 'PAY-1100' }) as HTMLInputElement).checked).toBe(true);
    fireEvent.click(dialog.getByRole('button', { name: 'Add' }));
    await waitFor(() => expect(posted).toEqual([{ email: 'nam@acme.vn', name: 'Nam', role: 'BA', epics: ['PAY-1100'] }]));
    expect(await screen.findByText('Added Nam. Access request sent to the admin.')).toBeTruthy();
  });

  it('add person as JM sends no access request', async () => {
    serve();
    server.use(http.post('*/api/v1/people', () => HttpResponse.json({ ...an, id: 'new', name: 'Jun' }, { status: 201 })));
    renderApp('/anken/PAY-1100');
    fireEvent.click(await (await panel()).findByRole('button', { name: 'Add person…' }));
    const dialog = within(await screen.findByRole('dialog', { name: 'Add person' }));
    fireEvent.change(dialog.getByLabelText('E-mail'), { target: { value: 'jun@client.jp' } });
    fireEvent.change(dialog.getByLabelText('Name'), { target: { value: 'Jun' } });
    fireEvent.change(dialog.getByLabelText('Role'), { target: { value: 'JM' } });
    fireEvent.click(dialog.getByRole('button', { name: 'Add' }));
    expect(await screen.findByText('Added Jun.')).toBeTruthy();
  });

  it('ba does not see the panel', async () => {
    serve();
    server.use(http.get('*/api/v1/me', () => HttpResponse.json(meBa)));
    renderApp('/anken/PAY-1100');
    await screen.findByRole('region', { name: 'PAY-1100 details' });
    expect(screen.queryByRole('region', { name: 'Members' })).toBeNull();
    expect(screen.queryByText('Members')).toBeNull();
  });

  it('function pickers only offer anken members', async () => {
    server.use(
      http.get('*/api/v1/anken/PAY-1100/matrix', () => HttpResponse.json({ ...fixtures.matrix, member_ids: ['an', 'minh', 'tanaka'] })),
      http.post('*/api/v1/anken/PAY-1100/functions/preview', () => HttpResponse.json({}, { status: 500 })),
    );
    renderApp('/anken/PAY-1100/matrix/new');
    const ba = (await screen.findByRole('combobox', { name: 'BA' })) as HTMLSelectElement;
    await waitFor(() => expect([...ba.options].map((o) => o.text)).toEqual(['An']));
  });

  it('card names the PIM', async () => {
    server.use(http.get('*/api/v1/anken', () => HttpResponse.json([{ ...fixtures.anken[0], pim_ids: ['minh'] }])));
    serve();
    renderApp('/anken');
    expect(await screen.findByText('PIM: Minh')).toBeTruthy();
  });

  describe('allocation dialog', () => {
    const timeline = (over: object = {}) => ({
      member_id: 'an', epics: ['X', 'Y'], hours_per_day: 8,
      steps: [
        { from: '2026-09-01', hours: { X: 4, Y: 4 }, set_by: 'pim@acme.vn', set_at: '2026-09-01T00:00:00Z' },
        { from: '2026-10-01', hours: { X: 5, Y: 2 }, set_by: 'pim@acme.vn', set_at: '2026-10-01T00:00:00Z' },
      ],
      ...over,
    });
    const open = async () => {
      serve();
      server.use(http.get('*/api/v1/people/an/allocations', () => HttpResponse.json(timeline())));
      renderApp('/anken/PAY-1100');
      fireEvent.click(await (await panel()).findByRole('button', { name: 'Allocation… An' }));
      return within(await screen.findByRole('dialog', { name: 'Allocation · An' }));
    };

    it('shows history and saves a new step', async () => {
      const put: unknown[] = [];
      server.use(http.put('*/api/v1/people/an/allocations', async ({ request }) => {
        put.push(await request.json());
        return HttpResponse.json(timeline());
      }));
      const dialog = await open();
      expect(await dialog.findByText('2026-09-01')).toBeTruthy();
      expect(dialog.getAllByRole('row')).toHaveLength(3);
      expect(dialog.getByText('current')).toBeTruthy();
      expect((dialog.getByLabelText('X') as HTMLInputElement).value).toBe('5');
      expect((dialog.getByLabelText('From') as HTMLInputElement).value).toBe('2026-10-07');
      fireEvent.change(dialog.getByLabelText('X'), { target: { value: '6' } });
      fireEvent.change(dialog.getByLabelText('Y'), { target: { value: '2' } });
      fireEvent.click(dialog.getByRole('button', { name: 'Save' }));
      await waitFor(() => expect(put).toEqual([{ from: '2026-10-07', hours: { X: 6, Y: 2 } }]));
    });

    it('refetches the history after a save', async () => {
      let puts = 0;
      const longer = timeline({ steps: [...timeline().steps, { from: '2026-10-07', hours: { X: 6, Y: 2 }, set_by: 'pim@acme.vn', set_at: '2026-10-07T00:00:00Z' }] });
      const dialog = await open();
      server.use(
        http.get('*/api/v1/people/an/allocations', () => HttpResponse.json(puts ? longer : timeline())),
        http.put('*/api/v1/people/an/allocations', () => { puts += 1; return HttpResponse.json(longer); }),
      );
      expect(await dialog.findByText('2026-09-01')).toBeTruthy();
      expect(dialog.getAllByRole('row')).toHaveLength(3);
      fireEvent.click(dialog.getByRole('button', { name: 'Save' }));
      await waitFor(() => expect(dialog.getAllByRole('row')).toHaveLength(4));
      expect(dialog.getByText('2026-10-07', { selector: 'td' })).toBeTruthy();
    });

    it('a negative value disables save', async () => {
      const dialog = await open();
      await dialog.findByLabelText('X');
      fireEvent.change(dialog.getByLabelText('X'), { target: { value: '-2' } });
      fireEvent.change(dialog.getByLabelText('Y'), { target: { value: '9' } });
      expect((dialog.getByRole('button', { name: 'Save' }) as HTMLButtonElement).disabled).toBe(true);
    });

    it('total above the day disables save', async () => {
      const dialog = await open();
      await dialog.findByLabelText('X');
      fireEvent.change(dialog.getByLabelText('X'), { target: { value: '6' } });
      fireEvent.change(dialog.getByLabelText('Y'), { target: { value: '3' } });
      expect(dialog.getByText('Total 9h of 8h')).toBeTruthy();
      expect((dialog.getByRole('button', { name: 'Save' }) as HTMLButtonElement).disabled).toBe(true);
    });

    it('shows the backend refusal in a toast', async () => {
      server.use(http.put('*/api/v1/people/an/allocations', () => HttpResponse.json(
        { title: 'Invalid', detail: 'Total above the working day on 2026-10-20', errors: [{ field: 'hours', code: 'invalid' }] }, { status: 422 })));
      const dialog = await open();
      await dialog.findByLabelText('X');
      fireEvent.click(dialog.getByRole('button', { name: 'Save' }));
      expect(await screen.findByText(/Total above the working day/)).toBeTruthy();
    });

    it('read-only for members read', async () => {
      server.use(http.get('*/api/v1/me', () => HttpResponse.json(meMembersRead)));
      const dialog = await open();
      expect(await dialog.findByText('2026-09-01')).toBeTruthy();
      expect(dialog.queryByRole('button', { name: 'Save' })).toBeNull();
      expect(dialog.queryByLabelText('X')).toBeNull();
    });
  });
});
