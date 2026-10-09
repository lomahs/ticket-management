import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { errorMessage } from '../../../shared/api/errors';
import type { Schemas } from '../../../shared/api/types';
import { Button, Select, TextField } from '../../../shared/ds';
import { f1 } from '../../../shared/format/dates';
import { STATUS } from '../../../shared/format/labels';
import { useToast } from '../../../shared/state/toast';
import { Loading, QueryError } from '../../../shared/ui/states';
import { fieldMappingQuery, statusMappingQuery, useCheckFieldMapping, useSaveFieldMapping, useSaveStatusMapping } from '../api';
import { Busy, ErrText, Section } from './common';

const TARGETS = [['planned_start', 'Planned Start', 'Date'], ['planned_end', 'Planned End', 'Date'], ['effort', 'Planned Effort', 'Hours']] as const;
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

function FieldMappingForm({ v }: { v: Schemas['FieldMappingView'] }) {
  const toast = useToast();
  const save = useSaveFieldMapping();
  const check = useCheckFieldMapping();
  const [map, setMap] = useState({ planned_start: v.planned_start, planned_end: v.planned_end, effort: v.effort });
  const [sample, setSample] = useState('');
  const results = Object.fromEntries((check.data?.results ?? []).map((r) => [r.target, r]));
  const runCheck = () => check.mutate({ sample, ...map });
  const GRID = '160px 80px minmax(220px,1.4fr) minmax(160px,1fr)';
  return (
    <Section title="Field mapping" sub="One mapping for the whole Jira site. Enter the field ID as shown in Jira, e.g. customfield_10020. Saving re-reads every tracked issue.">
      <datalist id="jira-fields">{v.catalog.map((f) => <option key={f.id} value={f.id}>{f.name} · {f.type || 'any'}</option>)}</datalist>
      <div style={{ border: '1px solid var(--border-subtle)', borderRadius: 12, overflowX: 'auto' }}>
        <div style={{ display: 'grid', gridTemplateColumns: GRID, gap: 12, padding: '8px 14px', background: 'var(--surface-hover)', borderRadius: '12px 12px 0 0', fontSize: 11, fontWeight: 600, color: 'var(--text-muted)', minWidth: 640 }}>
          <span>Tracker field</span><span>Expects</span><span>Jira field ID</span><span>Value on sample ticket</span>
        </div>
        {TARGETS.map(([k, label, expects]) => {
          const r = results[k];
          const [text, fg] = !r ? ['', 'var(--text-muted)'] : r.status === 'ok'
            ? ['✓ ' + (r.value == null ? '(empty)' : k === 'effort' ? f1(Number(r.value)) + 'h' : String(r.value)), 'var(--text-heading)']
            : RESULT[r.status] ?? [r.status, 'var(--hl-red-fg)'];
          return (
            <div key={k} style={{ display: 'grid', gridTemplateColumns: GRID, gap: 12, alignItems: 'center', padding: '8px 14px', borderTop: '1px solid var(--border-subtle)', minWidth: 640 }}>
              <b className="heading">{label}</b>
              <span className="eyebrow">{expects}</span>
              <TextField mono list="jira-fields" aria-label={`${label} field ID`} placeholder={k === 'effort' ? 'e.g. timeoriginalestimate' : 'e.g. customfield_10020'}
                value={map[k]} onChange={(val) => { setMap((m) => ({ ...m, [k]: val })); check.reset(); }} />
              <span style={{ fontSize: 12, fontWeight: 600, color: fg }}>{text}</span>
            </div>
          );
        })}
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
