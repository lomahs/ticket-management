import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { errorMessage } from '../../../shared/api/errors';
import type { Schemas } from '../../../shared/api/types';
import { Button, Select, TextField, type Option } from '../../../shared/ds';
import { f1 } from '../../../shared/format/dates';
import { STATUS } from '../../../shared/format/labels';
import { useToast } from '../../../shared/state/toast';
import { Loading, QueryError } from '../../../shared/ui/states';
import { fieldMappingQuery, statusMappingQuery, useCheckFieldMapping, useSaveFieldMapping, useSaveStatusMapping } from '../api';
import { Busy, ErrText, Section } from './common';

const RESULT: Record<string, [string, string]> = {
  not_mapped: ['Not mapped', 'var(--hl-red-fg)'], not_found: ['Field not found in Jira', 'var(--hl-red-fg)'],
  type_mismatch: ['Type mismatch', 'var(--hl-red-fg)'],
};

export function FieldMappingTab() {
  const q = useQuery(fieldMappingQuery);
  if (q.isPending) return <Loading />;
  if (q.isError) return <QueryError error={q.error} />;
  return <FieldMappingForm v={q.data} />;
}

type Target = 'planned_start' | 'planned_end' | 'effort' | 'remaining';

/** The Jira fields of the site for one tracker field: those of the expected type first, then every other field. */
function fieldOptions(f: Schemas['AppField'], catalog: Schemas['JiraField'][]): Option[] {
  const fits = (j: Schemas['JiraField']) => !f.types.length || f.types.includes(j.type);
  const opts: Option[] = [{ value: '', label: '— Not mapped —' }];
  if (f.field_id && !catalog.some((j) => j.id === f.field_id)) opts.push({ value: f.field_id, label: `${f.field_id} (not found in Jira)` });
  catalog.filter(fits).forEach((j) => opts.push({ value: j.id, label: `${j.name} · ${j.id}`, group: `${f.expects} fields` }));
  catalog.filter((j) => !fits(j)).forEach((j) => opts.push({ value: j.id, label: `${j.name} · ${j.id} · ${j.type || 'no type'}`, group: 'Other fields' }));
  return opts;
}

function FieldMappingForm({ v }: { v: Schemas['FieldMappingView'] }) {
  const toast = useToast();
  const save = useSaveFieldMapping();
  const check = useCheckFieldMapping();
  const mappable = v.fields.filter((f) => f.mappable);
  const fixed = v.fields.filter((f) => !f.mappable);
  const [map, setMap] = useState(() => Object.fromEntries(mappable.map((f) => [f.key, f.field_id])) as Record<Target, string>);
  const [sample, setSample] = useState('');
  const results = Object.fromEntries((check.data?.results ?? []).map((r) => [r.target, r]));
  const runCheck = () => check.mutate({ sample, ...map });
  const names = Object.fromEntries(v.catalog.map((j) => [j.id, j.name]));
  const GRID = 'minmax(200px,1.2fr) 80px minmax(240px,1.4fr) minmax(150px,1fr)';
  const row = { display: 'grid', gridTemplateColumns: GRID, gap: 12, alignItems: 'center', padding: '8px 14px', borderTop: '1px solid var(--border-subtle)', minWidth: 700 } as const;
  const group = (text: string) => (
    <div style={{ padding: '6px 14px', borderTop: '1px solid var(--border-subtle)', background: 'var(--surface-hover)', fontSize: 11, fontWeight: 600, color: 'var(--text-muted)', minWidth: 700 }}>{text}</div>
  );
  const name = (f: Schemas['AppField']) => (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 2, minWidth: 0 }}>
      <b className="heading">{f.label}</b>
      <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>{f.used_for}</span>
    </div>
  );
  return (
    <Section title="Field mapping" sub="Every Jira field the tracker reads to manage and display work. Pick the Jira field for the ones you map; the others are Jira system fields. One mapping for the whole Jira site. Saving re-reads every tracked issue.">
      {!v.catalog.length && <ErrText>Could not read the field list from Jira. Check Settings → Jira, or enter field IDs by hand.</ErrText>}
      <div style={{ border: '1px solid var(--border-subtle)', borderRadius: 12, overflowX: 'auto' }}>
        <div style={{ display: 'grid', gridTemplateColumns: GRID, gap: 12, padding: '8px 14px', background: 'var(--surface-hover)', borderRadius: '12px 12px 0 0', fontSize: 11, fontWeight: 600, color: 'var(--text-muted)', minWidth: 700 }}>
          <span>Tracker field</span><span>Expects</span><span>Jira field</span><span>Value on sample ticket</span>
        </div>
        {group('Mapped by you')}
        {mappable.map((f) => {
          const k = f.key as Target;
          const r = results[k];
          const [text, fg] = !r ? ['', 'var(--text-muted)'] : r.status === 'ok'
            ? ['✓ ' + (r.value == null ? '(empty)' : f.expects === 'Hours' ? f1(Number(r.value)) + 'h' : String(r.value)), 'var(--text-heading)']
            : RESULT[r.status] ?? [r.status, 'var(--hl-red-fg)'];
          const set = (val: string) => { setMap((m) => ({ ...m, [k]: val })); check.reset(); };
          return (
            <div key={k} style={row}>
              {name(f)}
              <span className="eyebrow">{f.expects}</span>
              {v.catalog.length
                ? <Select aria-label={`${f.label} Jira field`} options={fieldOptions({ ...f, field_id: map[k] }, v.catalog)} value={map[k]} onChange={set} />
                : <TextField mono aria-label={`${f.label} Jira field`} placeholder="e.g. customfield_10020" value={map[k]} onChange={set} />}
              <span style={{ fontSize: 12, fontWeight: 600, color: fg }}>{text}</span>
            </div>
          );
        })}
        {group('Jira system fields · always read')}
        {fixed.map((f) => (
          <div key={f.key} style={row}>
            {name(f)}
            <span className="eyebrow">{f.expects}</span>
            <span style={{ fontSize: 12 }}>
              {names[f.field_id] ?? f.label} <span style={{ font: '11px var(--font-mono)', color: 'var(--text-muted)' }}>· {f.field_id}</span>
            </span>
            <span />
          </div>
        ))}
      </div>
      <div style={{ display: 'flex', alignItems: 'flex-end', gap: 10, flexWrap: 'wrap' }}>
        <TextField label="Sample ticket" value={sample} placeholder="e.g. PAY-1203" onChange={(s) => { setSample(s); check.reset(); }} style={{ width: 200 }}
          onKeyDown={(e) => e.key === 'Enter' && runCheck()} />
        <Button appearance="secondary" iconBefore="scan-search" disabled={check.isPending || !sample.trim()} onClick={runCheck}>Check</Button>
        {check.isPending && <Busy>Reading ticket from Jira…</Busy>}
        {check.data && <Busy>Read from {check.data.key} · {check.data.summary}</Busy>}
        {check.isError && <ErrText>{errorMessage(check.error)}</ErrText>}
        <span className="spacer" />
        <Button disabled={save.isPending} onClick={() => save.mutate(map, { onSuccess: () => toast('Field mapping saved · synced'), onError: (e) => toast(errorMessage(e), true) })}>Save</Button>
      </div>
    </Section>
  );
}

export function StatusMappingTab() {
  const q = useQuery(statusMappingQuery);
  if (q.isPending) return <Loading />;
  if (q.isError) return <QueryError error={q.error} />;
  return <StatusMappingForm rows={q.data} />;
}

function StatusMappingForm({ rows }: { rows: Schemas['StatusRow'][] }) {
  const toast = useToast();
  const save = useSaveStatusMapping();
  const [map, setMap] = useState<Record<string, string>>(Object.fromEntries(rows.map((r) => [r.name, r.mapped ?? ''])));
  const opts = [{ value: '', label: 'Automatic (by Jira category)' }, ...(['N', 'W', 'R', 'C'] as const).map((k) => ({ value: k, label: STATUS[k].label }))];
  const GRID = 'minmax(160px,1fr) 140px minmax(220px,1.2fr) 120px';
  return (
    <Section pad={false} title="Status mapping" sub="How each Jira status counts in the tracker. Statuses appear here after a sync. Saving re-reads every tracked issue.">
      <div style={{ overflowX: 'auto' }}>
        <div style={{ minWidth: 640 }}>
          <div style={{ display: 'grid', gridTemplateColumns: GRID, gap: 12, padding: '8px 20px', background: 'var(--surface-hover)', fontSize: 11, fontWeight: 600, color: 'var(--text-muted)' }}>
            <span>Jira status</span><span>Jira category</span><span>Counts as</span><span>Now</span>
          </div>
          {rows.map((r) => (
            <div key={r.name} style={{ display: 'grid', gridTemplateColumns: GRID, gap: 12, alignItems: 'center', padding: '8px 20px', borderTop: '1px solid var(--border-subtle)' }}>
              <b className="heading">{r.name}</b>
              <span className="eyebrow">{r.category || '—'}</span>
              <Select aria-label={`${r.name} counts as`} options={opts} value={map[r.name] ?? ''} onChange={(v) => setMap((m) => ({ ...m, [r.name]: v }))} />
              <span style={{ fontSize: 12 }}>{STATUS[r.effective as 'N'].label}</span>
            </div>
          ))}
          {!rows.length && <div className="empty-row">No statuses seen yet. Run a sync first.</div>}
        </div>
      </div>
      <div style={{ display: 'flex', justifyContent: 'flex-end', padding: '12px 20px', borderTop: '1px solid var(--border-subtle)' }}>
        <Button disabled={save.isPending || !rows.length}
          onClick={() => save.mutate(Object.fromEntries(Object.entries(map).map(([k, v]) => [k, (v || null) as 'N' | null])), {
            onSuccess: () => toast('Status mapping saved · synced'), onError: (e) => toast(errorMessage(e), true),
          })}>Save</Button>
      </div>
    </Section>
  );
}
