import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { errorMessage } from '../../../shared/api/errors';
import type { Schemas } from '../../../shared/api/types';
import { Button, IconButton, Select, TextField } from '../../../shared/ds';
import { dm } from '../../../shared/format/dates';
import { useToast } from '../../../shared/state/toast';
import { Loading, QueryError } from '../../../shared/ui/states';
import { dailyCaptureQuery, jiraQuery, useAddEpic, useRemoveEpic, useSaveDailyCapture, useSaveJira, useTestJira } from '../api';
import { Busy, ErrText, Section } from './common';

const INTERVALS = [['5', 'Every 5 minutes'], ['15', 'Every 15 minutes'], ['60', 'Every hour'], ['manual', 'Manual only']] as const;
const CAPTURE_TIMES = ['off', '16:00', '16:30', '17:00', '17:30', '18:00', '18:30', '19:00', '20:00'];

export function JiraTab() {
  const q = useQuery(jiraQuery);
  if (q.isPending) return <Loading />;
  if (q.isError) return <QueryError error={q.error} />;
  return (
    <>
      <JiraForm j={q.data} />
      <DailyCapture />
    </>
  );
}

/** When the Daily Overview of every tracked anken is captured on working days (after a sync); "Capture" works any time. */
function DailyCapture() {
  const q = useQuery(dailyCaptureQuery);
  const save = useSaveDailyCapture();
  const toast = useToast();
  if (q.isPending) return <Loading />;
  if (q.isError) return <QueryError error={q.error} />;
  const times = CAPTURE_TIMES.includes(q.data.time) ? CAPTURE_TIMES : [...CAPTURE_TIMES, q.data.time];
  return (
    <Section title="Daily Overview snapshot" sub="Each working day at this time (team time zone) the tracker syncs, then captures the Daily Overview of every tracked anken that has no capture yet that day. Capture on the Daily Overview takes one by hand.">
      <Select label="Capture daily at" style={{ width: 240 }} value={q.data.time} disabled={save.isPending}
        options={times.map((t) => ({ value: t, label: t === 'off' ? 'Off · by hand only' : t }))}
        onChange={(t) => save.mutate(t, { onSuccess: () => toast(t === 'off' ? 'Daily capture turned off' : `Daily capture at ${t}`), onError: (e) => toast(errorMessage(e), true) })} />
    </Section>
  );
}

function JiraForm({ j }: { j: Schemas['JiraSettingsView'] }) {
  const toast = useToast();
  const save = useSaveJira();
  const test = useTestJira();
  const addEpic = useAddEpic();
  const removeEpic = useRemoveEpic();
  const [form, setForm] = useState({ url: j.url, email: j.email, token: '', interval: j.interval as Schemas['JiraIn']['interval'] });
  const [status, setStatus] = useState<string | null>(j.status ?? null);
  const [epicIn, setEpicIn] = useState('');
  const set = (k: keyof typeof form) => (v: string) => { setForm((f) => ({ ...f, [k]: v })); if (k !== 'interval') setStatus(null); };
  const synced = j.last_full_sync ? `${dm(j.last_full_sync.slice(0, 10))} ${new Date(j.last_full_sync).toTimeString().slice(0, 5)}` : 'never';
  const label = test.isPending ? ['Testing connection…', 'var(--text-muted)']
    : status === 'ok' ? [`Connected · ${j.epics.length} epics readable, last full sync ${synced}`, 'var(--tn-clo)']
    : status === 'fail' ? ['Authentication failed · check site URL and API token', 'var(--hl-red-fg)']
    : ['Not tested since last change', 'var(--text-muted)'];

  const runTest = () => test.mutate({ url: form.url, email: form.email, token: form.token || null }, {
    onSuccess: (r) => setStatus(r.status), onError: (e) => { setStatus('fail'); toast(errorMessage(e), true); },
  });
  const runSave = () => save.mutate({ ...form, token: form.token || null }, {
    onSuccess: (r) => { setStatus(r.status ?? null); setForm((f) => ({ ...f, token: '' })); toast('Jira connection saved'); },
    onError: (e) => toast(errorMessage(e), true),
  });
  const add = () => epicIn.trim() && addEpic.mutate(epicIn, { onSuccess: () => { setEpicIn(''); toast(`Tracking ${epicIn.trim().toUpperCase()} · synced`); } });

  return (
    <Section title="Jira Cloud connection" sub="A service account reads issues and worklogs, and writes Planned Start/End, assignee and status changes.">
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(260px,1fr))', gap: 12 }}>
        <TextField label="Site URL" value={form.url} onChange={set('url')} />
        <TextField label="Account email" value={form.email} onChange={set('email')} />
        <TextField label="API token" type="password" value={form.token} placeholder={j.token_masked || 'Paste an API token'} onChange={set('token')} autoComplete="off" />
        <Select label="Sync interval" options={INTERVALS.map(([value, label]) => ({ value, label }))} value={form.interval} onChange={set('interval')} />
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        <div>
          <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--text-heading)' }}>Tracked epics</div>
          <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>Each epic is one anken; its project and title are read from Jira. Adding or removing one runs a full sync.</div>
        </div>
        <div style={{ border: '1px solid var(--border-subtle)', borderRadius: 12 }}>
          {j.epics.map((e) => (
            <div key={e.key} style={{ display: 'grid', gridTemplateColumns: '100px minmax(0,1fr) minmax(0,1fr) auto', gap: 12, alignItems: 'center', padding: '6px 8px 6px 14px', borderBottom: '1px solid var(--border-subtle)' }}>
              <span className="key">{e.key}</span>
              <b className="ellipsis heading">{e.title || '—'}</b>
              <span style={{ fontSize: 12, color: 'var(--text-muted)' }}>{e.project}</span>
              <IconButton icon="x" label={`Stop tracking ${e.key}`} size="sm" disabled={removeEpic.isPending}
                onClick={() => removeEpic.mutate(e.key, { onError: (err) => toast(errorMessage(err), true) })} />
            </div>
          ))}
          <div style={{ display: 'flex', alignItems: 'flex-end', gap: 8, padding: '10px 14px', flexWrap: 'wrap' }}>
            <TextField label="Epic key" placeholder="e.g. PAY-1150" value={epicIn} onChange={(v) => { setEpicIn(v); addEpic.reset(); }} style={{ width: 220 }}
              onKeyDown={(e) => e.key === 'Enter' && add()} />
            <Button appearance="secondary" iconBefore="plus" disabled={addEpic.isPending} onClick={add}>Add epic</Button>
            {addEpic.isPending && <Busy>Reading epic from Jira…</Busy>}
            {addEpic.isError && <ErrText>{errorMessage(addEpic.error)}</ErrText>}
          </div>
        </div>
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap', paddingTop: 14, borderTop: '1px solid var(--border-subtle)' }}>
        <span className="dot" style={{ width: 8, height: 8, background: label[1] }} />
        <span style={{ flex: 1, fontSize: 12, color: label[1], fontWeight: 600 }}>{label[0]}</span>
        <Button appearance="secondary" size="sm" iconBefore="plug" disabled={test.isPending} onClick={runTest}>Test connection</Button>
        <Button size="sm" disabled={save.isPending} onClick={runSave}>Save</Button>
      </div>
    </Section>
  );
}
