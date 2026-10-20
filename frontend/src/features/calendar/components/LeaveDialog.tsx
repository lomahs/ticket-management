import { useQuery } from '@tanstack/react-query';
import { useNavigate, useParams, useSearch } from '@tanstack/react-router';
import { useState } from 'react';
import { ApiError, errorMessage } from '../../../shared/api/errors';
import type { Schemas } from '../../../shared/api/types';
import { useCan, useMe, useMemberMap, useMeta, VIEW_ONLY } from '../../../shared/api/queries';
import type { LeaveTypeKey } from '../../../shared/api/types';
import { Button, IconButton, Select, TextField } from '../../../shared/ds';
import { LEAVE, LEAVE_KEYS } from '../../../shared/format/labels';
import { useToast } from '../../../shared/state/toast';
import { calendarQuery, useDeleteLeave, useSaveLeave } from '../api';
import { useCalendarMonth } from './CalendarPage';

/** /calendar/leave/new?member=&date= and /calendar/leave/$id */
export function LeaveDialogRoute() {
  const params = useParams({ strict: false }) as { id?: string };
  const search = useSearch({ strict: false }) as { member?: string; date?: string };
  const month = useCalendarMonth();
  const q = useQuery(calendarQuery(month));
  const today = useMeta().data?.today ?? '';
  if (params.id) {
    if (q.isPending) return null;
    const lv = q.data?.rows.flatMap((r) => r.cells).find((c) => c.leave?.id === Number(params.id))?.leave;
    return <LeaveDialog key={params.id} existing={lv ?? null} missingId={lv ? undefined : params.id} defaults={{ member: '', date: today }} />;
  }
  return <LeaveDialog key="new" existing={null} defaults={{ member: search.member ?? '', date: search.date ?? today }} />;
}

function LeaveDialog({ existing, defaults, missingId }: { existing: Schemas['Leave'] | null; defaults: { member: string; date: string }; missingId?: string }) {
  const navigate = useNavigate();
  const members = useMemberMap();
  const toast = useToast();
  const save = useSaveLeave();
  const del = useDeleteLeave();
  const canAll = useCan('calendar', 'write');
  const myId = useMe().data?.member_id ?? '';
  const firstMember = Object.keys(members)[0] ?? '';
  const [f, setF] = useState({
    member: existing?.member_id ?? defaults.member, type: (existing?.type ?? 'full') as LeaveTypeKey,
    from: existing?.date ?? defaults.date, to: existing?.date ?? defaults.date, note: existing?.note ?? '',
  });
  const [err, setErr] = useState<ApiError | null>(null);
  // Without calendar write a member can only book their own leave; another member's leave is shown read-only.
  // With calendar write the member can be changed, also on an existing leave (f.member starts at its member).
  const member = existing && !canAll ? existing.member_id : canAll ? f.member || firstMember : myId;
  const readOnly = !!existing && !canAll && existing.member_id !== myId;
  const close = () => navigate({ to: '/calendar', search: (p) => ({ month: (p as { month?: string }).month }) });
  const rangeBad = !!(f.from && f.to && f.to < f.from);
  const submit = () => save.mutate({ id: existing?.id, body: { member_id: member, type: f.type, date_from: f.from, date_to: f.to, note: f.note } }, {
    onSuccess: () => { toast(`${members[member]?.name ?? member} · ${LEAVE[f.type].label} saved`); close(); },
    onError: (e) => (e instanceof ApiError && e.errors.length ? setErr(e) : toast(errorMessage(e), true)),
  });
  const remove = () => existing && del.mutate(existing.id, { onSuccess: () => { toast('Leave removed'); close(); }, onError: (e) => toast(errorMessage(e), true) });
  const set = (k: keyof typeof f) => (v: string) => { setErr(null); setF((x) => ({ ...x, [k]: v })); };

  return (
    <div className="scrim" onClick={close}>
      <div className="dialog" role="dialog" aria-modal="true" aria-label={existing ? 'Edit leave' : 'Add leave'} onClick={(e) => e.stopPropagation()} style={{ width: 'min(520px,100%)' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span className="dialog-title">{existing ? 'Edit leave' : 'Add leave'}</span>
          <IconButton icon="x" label="Close" size="sm" onClick={close} />
        </div>
        {missingId ? <div role="alert" style={{ color: 'var(--hl-red-fg)', fontWeight: 600 }}>Leave {missingId} is not in this month.</div> : (
          <>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
              <Select label="Member" options={Object.values(members).map((m) => ({ value: m.id, label: `${m.name}${m.role ? ` (${m.role})` : ''}` }))} value={member} disabled={!canAll} onChange={set('member')} />
              <Select label="Type" disabled={readOnly} options={LEAVE_KEYS.map((k) => ({ value: k, label: LEAVE[k].label }))} value={f.type} onChange={set('type')} />
              <TextField label="From" type="date" disabled={readOnly} value={f.from} onChange={set('from')} />
              <div>
                <TextField label="To" type="date" disabled={readOnly} value={f.to} onChange={set('to')} aria-invalid={rangeBad} />
                {(rangeBad || err?.field('date_to') || err?.field('to')) && (
                  <div role="alert" style={{ fontSize: 12, fontWeight: 600, color: 'var(--hl-red-fg)', marginTop: 4 }}>{err?.field('to') ?? 'To date is before From date'}</div>
                )}
              </div>
            </div>
            <TextField label="Note" placeholder="Optional" disabled={readOnly} value={f.note} onChange={set('note')} />
            {err && !err.field('to') && <div role="alert" style={{ fontSize: 12, fontWeight: 600, color: 'var(--hl-red-fg)' }}>{err.message}</div>}
            {readOnly && <div className="eyebrow">{VIEW_ONLY}</div>}
            <div className="eyebrow">Non-working days inside the range are skipped. All-day leave blocks the day in Planning and half days count as 50% capacity. Tentative leave is shown hatched.</div>
          </>
        )}
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          {existing && !readOnly && <Button appearance="subtle" iconBefore="trash-2" disabled={del.isPending} onClick={remove}>Delete</Button>}
          <span className="spacer" />
          <Button appearance="subtle" onClick={close}>Cancel</Button>
          {!missingId && !readOnly && <Button disabled={!f.from || !f.to || rangeBad || save.isPending} onClick={submit}>Save</Button>}
        </div>
      </div>
    </div>
  );
}
