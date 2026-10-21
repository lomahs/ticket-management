import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { errorMessage } from '../../../shared/api/errors';
import type { Schemas } from '../../../shared/api/types';
import { Button, IconButton, Select, TextField } from '../../../shared/ds';
import { dm, dow, f1, WDN } from '../../../shared/format/dates';
import { useMeta } from '../../../shared/api/queries';
import { useToast } from '../../../shared/state/toast';
import { Seg } from '../../../shared/ui/bits';
import { Loading, QueryError } from '../../../shared/ui/states';
import {
  alertsQuery, extraDaysQuery, holidaysQuery, hoursQuery, useAddExtraDay, useAddHoliday, useDeleteExtraDay, useDeleteHoliday, useSaveAlerts, useSaveHours,
} from '../api';
import { ErrText, Section } from './common';

const COUNTRIES = [{ value: 'VN', label: 'Vietnam' }, { value: 'JP', label: 'Japan' }, { value: 'VN JP', label: 'Both' }];

export function HolidaysTab() {
  const q = useQuery(holidaysQuery);
  const today = useMeta().data?.today ?? '';
  const toast = useToast();
  const add = useAddHoliday();
  const del = useDeleteHoliday();
  const [flt, setFlt] = useState<'all' | 'VN' | 'JP'>('all');
  const [nh, setNh] = useState({ date: '', name: '', c: 'VN' });
  if (q.isPending) return <Loading />;
  if (q.isError) return <QueryError error={q.error} />;
  const list = q.data.filter((h) => flt === 'all' || h.countries.includes(flt));
  const submit = () => add.mutate({ date: nh.date, name: nh.name.trim(), countries: nh.c.split(' ') as ('VN' | 'JP')[] }, {
    onSuccess: () => { toast('Holiday added · ' + dm(nh.date)); setNh({ date: '', name: '', c: nh.c }); },
    onError: (e) => toast(errorMessage(e), true),
  });
  return (
    <section className="card card-clip" aria-label="Public holidays">
      <div style={{ display: 'flex', alignItems: 'flex-end', gap: 12, flexWrap: 'wrap', padding: '16px 20px' }}>
        <div style={{ flex: 1, minWidth: 240 }}>
          <div className="title">Public holidays</div>
          <div className="eyebrow">Applied to members by country in Planning, capacity, timesheet and date suggestions.</div>
        </div>
        <Seg items={[['all', 'All'], ['VN', 'Vietnam'], ['JP', 'Japan']]} value={flt} onPick={setFlt} />
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: '160px minmax(0,1fr) 150px auto', gap: 10, alignItems: 'flex-end', padding: '12px 20px', background: 'var(--surface-hover)' }}>
        <TextField label="Date" type="date" value={nh.date} onChange={(v) => setNh({ ...nh, date: v })} />
        <TextField label="Name" placeholder="e.g. Hung Kings Festival (VN)" value={nh.name} onChange={(v) => setNh({ ...nh, name: v })} />
        <Select label="Country" options={COUNTRIES} value={nh.c} onChange={(v) => setNh({ ...nh, c: v })} />
        <Button iconBefore="plus" disabled={!(nh.date && nh.name.trim()) || add.isPending} onClick={submit}>Add</Button>
      </div>
      {list.map((h) => (
        <div key={h.date} style={{ display: 'grid', gridTemplateColumns: '110px 50px minmax(0,1fr) 80px auto', gap: 12, alignItems: 'center', padding: '8px 20px', borderTop: '1px solid var(--border-subtle)', opacity: h.date < today ? 0.55 : 1 }}>
          <b className="heading">{dm(h.date)}/{h.date.slice(0, 4)}</b>
          <span className="eyebrow">{WDN[dow(h.date)]}</span>
          <span className="heading">{h.name}</span>
          <span style={{ font: '600 11px/20px var(--font-text)', padding: '0 8px', borderRadius: 6, background: 'var(--surface-hover)', color: 'var(--text-heading)', width: 'max-content' }}>{h.countries.join(' + ')}</span>
          <IconButton icon="trash-2" label={`Delete ${h.name}`} size="sm" onClick={() => del.mutate(h.date, { onError: (e) => toast(errorMessage(e), true) })} />
        </div>
      ))}
    </section>
  );
}

export function ExtraDaysTab() {
  const q = useQuery(extraDaysQuery);
  const today = useMeta().data?.today ?? '';
  const toast = useToast();
  const add = useAddExtraDay();
  const del = useDeleteExtraDay();
  const [ot, setOt] = useState({ date: '', note: '' });
  if (q.isPending) return <Loading />;
  if (q.isError) return <QueryError error={q.error} />;
  const submit = () => add.mutate(ot, {
    onSuccess: () => { toast(`${WDN[dow(ot.date)]} ${dm(ot.date)} now shows on all calendars`); setOt({ date: '', note: '' }); },
  });
  const days = Object.entries(q.data);
  return (
    <Section pad={false} title="Extra working days" sub="Weekends are hidden on every calendar. Add a weekend date here when the team works that day; it then appears in Planning, Effort and Team Calendar and counts toward capacity.">
      <div style={{ display: 'grid', gridTemplateColumns: '180px minmax(0,1fr) auto', gap: 10, alignItems: 'flex-end', padding: '12px 20px', background: 'var(--surface-hover)' }}>
        <TextField label="Date" type="date" value={ot.date} onChange={(v) => { setOt({ ...ot, date: v }); add.reset(); }} />
        <TextField label="Reason" placeholder="e.g. Release support" value={ot.note} onChange={(v) => setOt({ ...ot, note: v })} />
        <Button iconBefore="plus" disabled={!ot.date || add.isPending} onClick={submit}>Add</Button>
      </div>
      {add.isError && <div style={{ padding: '8px 20px 0' }}><ErrText>{errorMessage(add.error)}</ErrText></div>}
      {days.map(([d, note]) => (
        <div key={d} style={{ display: 'grid', gridTemplateColumns: '110px 50px minmax(0,1fr) auto', gap: 12, alignItems: 'center', padding: '8px 20px', borderTop: '1px solid var(--border-subtle)', opacity: d < today ? 0.55 : 1 }}>
          <b className="heading">{dm(d)}/{d.slice(0, 4)}</b>
          <span className="eyebrow">{WDN[dow(d)]}</span>
          <span className="heading">{note}</span>
          <IconButton icon="trash-2" label={`Delete ${dm(d)}`} size="sm" onClick={() => del.mutate(d, { onError: (e) => toast(errorMessage(e), true) })} />
        </div>
      ))}
      {!days.length && <div className="empty-row">No extra working days.</div>}
    </Section>
  );
}

export function HoursTab() {
  const q = useQuery(hoursQuery);
  if (q.isPending) return <Loading />;
  if (q.isError) return <QueryError error={q.error} />;
  return <HoursForm wh={q.data} />;
}

function HoursForm({ wh }: { wh: Schemas['WorkingHours'] }) {
  const toast = useToast();
  const save = useSaveHours();
  const [f, setF] = useState({ hours: String(wh.hours_per_day), tol: String(wh.timesheet_tolerance_h), days: [...wh.days] });
  const hours = parseFloat(f.hours), tol = parseFloat(f.tol);
  const invalid = !(hours > 0 && hours <= 24) || !(tol >= 0) || !f.days.length;
  return (
    <Section title="Standard working hours" sub="Used for capacity, timesheet colours and date suggestions.">
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(220px,1fr))', gap: 12 }}>
        <TextField label="Hours per working day" type="number" min={0.5} max={24} step={0.5} value={f.hours} onChange={(v) => setF({ ...f, hours: v })} />
        <TextField label="Over-log tolerance (hours)" type="number" min={0} step={0.5} value={f.tol} onChange={(v) => setF({ ...f, tol: v })} />
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--text-heading)' }}>Working days · {Number.isFinite(hours) ? f1(hours * f.days.length) : '–'}h / week</span>
        <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
          {[1, 2, 3, 4, 5, 6, 0].map((w) => {
            const on = f.days.includes(w);
            return (
              <button key={w} aria-pressed={on} onClick={() => setF({ ...f, days: on ? f.days.filter((x) => x !== w) : [...f.days, w] })} style={{
                width: 52, height: 36, border: '1px solid var(--border-subtle)', borderRadius: 10, cursor: 'pointer', font: '600 12px/1 var(--font-text)',
                background: on ? 'var(--button-primary-bg)' : 'transparent', color: on ? 'var(--button-primary-fg)' : 'var(--text-heading)',
              }}>{WDN[w]}</button>
            );
          })}
        </div>
      </div>
      <div style={{ display: 'flex', justifyContent: 'flex-end', alignItems: 'center', gap: 10 }}>
        {invalid && <ErrText>Hours must be 0–24, tolerance ≥ 0 and at least one working day.</ErrText>}
        <Button disabled={invalid || save.isPending} onClick={() => save.mutate({ hours_per_day: hours, timesheet_tolerance_h: tol, days: f.days }, {
          onSuccess: () => toast('Working hours saved'), onError: (e) => toast(errorMessage(e), true),
        })}>Save</Button>
      </div>
    </Section>
  );
}

const ALERTS: [keyof Schemas['Thresholds'], string, string][] = [
  ['soon_days', 'Due soon (working days)', 'Amber when Planned End is this close'],
  ['effort_warn_pct', 'Effort warning (% of estimate)', 'Amber in Effort view of the matrix'],
  ['held_days', 'Held too long (days)', 'Stuck in Resolved or waiting on JM'],
  ['review_rounds', 'Review rounds', 'Flags ping-pong Fix comment tickets'],
  ['capacity_pct', 'Capacity overload (%)', 'Red in the Planning heatmap above this'],
  ['effort_diff_h', 'Effort deviation (hours)', 'A sub-task is under- or over-burn when logged hours differ from plan-to-date by more than this.'],
];

export function AlertsTab() {
  const q = useQuery(alertsQuery);
  if (q.isPending) return <Loading />;
  if (q.isError) return <QueryError error={q.error} />;
  return <AlertsForm t={q.data} />;
}

function AlertsForm({ t }: { t: Schemas['Thresholds'] }) {
  const toast = useToast();
  const save = useSaveAlerts();
  const [f, setF] = useState<Record<string, string>>(Object.fromEntries(ALERTS.map(([k]) => [k, String(t[k])])));
  const nums = Object.fromEntries(ALERTS.map(([k]) => [k, parseFloat(f[k])])) as Schemas['AlertsIn'];
  const invalid = Object.values(nums).some((n) => !(n >= 0));
  return (
    <Section title="Alert thresholds" sub="Controls highlighting in Function Matrix, My Work, Anken Overview and Planning.">
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(260px,1fr))', gap: '16px 14px' }}>
        {ALERTS.map(([k, label, hint]) => (
          <div key={k} style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            <TextField label={label} type="number" min={0} value={f[k]} onChange={(v) => setF({ ...f, [k]: v })} />
            <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>{hint}</span>
          </div>
        ))}
      </div>
      <div style={{ display: 'flex', justifyContent: 'flex-end', alignItems: 'center', gap: 10 }}>
        {invalid && <ErrText>Thresholds must be numbers ≥ 0.</ErrText>}
        <Button disabled={invalid || save.isPending} onClick={() => save.mutate(nums, { onSuccess: () => toast('Alert thresholds saved'), onError: (e) => toast(errorMessage(e), true) })}>Save</Button>
      </div>
    </Section>
  );
}
