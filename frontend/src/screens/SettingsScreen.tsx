import { useEffect, useRef, useState, type ReactNode } from 'react';
import { api, ApiError } from '../api/client';
import type { Config, StepKey, Task } from '../api/types';
import { Avatar, Seg } from '../components/bits';
import { Button, IconButton, Select, Tabs, TextField } from '../ds';
import { dm, dow, f1, WDN } from '../lib/dates';
import { allCells, av, title } from '../lib/domain';
import { useApp } from '../state/AppContext';

type Tab = 'jira' | 'map' | 'parse' | 'members' | 'hol' | 'hours' | 'ot' | 'alerts';
const TABS: { value: Tab; label: string }[] = [
  { value: 'jira', label: 'Jira' }, { value: 'map', label: 'Field mapping' }, { value: 'parse', label: 'Name rules' },
  { value: 'members', label: 'Members' }, { value: 'hol', label: 'Holidays' }, { value: 'hours', label: 'Hours' },
  { value: 'ot', label: 'Extra working days' }, { value: 'alerts', label: 'Alerts' },
];

/** Calls `save` once `value` has been stable for `ms` and differs from what was last saved. */
function useAutosave<T>(value: T, save: (v: T) => void, ms = 600) {
  const last = useRef(JSON.stringify(value));
  const saveRef = useRef(save);
  saveRef.current = save;
  const json = JSON.stringify(value);
  useEffect(() => {
    if (json === last.current) return;
    const t = setTimeout(() => { last.current = json; saveRef.current(JSON.parse(json)); }, ms);
    return () => clearTimeout(t);
  }, [json, ms]);
}

function Section({ title: t, sub, children, pad = true }: { title: string; sub: ReactNode; children: ReactNode; pad?: boolean }) {
  return (
    <section className={'card' + (pad ? '' : ' card-clip')} style={pad ? { padding: '18px 20px', display: 'flex', flexDirection: 'column', gap: 16 } : undefined}>
      <div style={pad ? undefined : { padding: '16px 20px' }}>
        <div className="title">{t}</div>
        <div className="eyebrow">{sub}</div>
      </div>
      {children}
    </section>
  );
}

const ErrText = ({ children }: { children: ReactNode }) => (
  <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--hl-red-fg)', alignSelf: 'center' }}>{children}</span>
);
const Busy = ({ children }: { children: ReactNode }) => <span style={{ fontSize: 12, color: 'var(--text-muted)', alignSelf: 'center' }}>{children}</span>;

export function SettingsScreen() {
  const [tab, setTab] = useState<Tab>('jira');
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14, width: '100%' }}>
      <div>
        <div className="eyebrow">Admin</div>
        <h1 className="h1" style={{ marginTop: 2 }}>Settings</h1>
      </div>
      <Tabs tabs={TABS} value={tab} onChange={setTab} />
      {tab === 'jira' && <JiraTab />}
      {tab === 'map' && <MapTab />}
      {tab === 'parse' && <ParseTab />}
      {tab === 'members' && <MembersTab />}
      {tab === 'hol' && <HolidaysTab />}
      {tab === 'hours' && <HoursTab />}
      {tab === 'ot' && <ExtraDaysTab />}
      {tab === 'alerts' && <AlertsTab />}
    </div>
  );
}

function JiraTab() {
  const { snap, write, setSnap, showToast } = useApp();
  const j = snap.settings.jira;
  const [form, setForm] = useState({ url: j.url, email: j.email, token: '', interval: j.interval });
  const [status, setStatus] = useState<'ok' | 'fail' | 'testing' | null>(j.status);
  const [epicIn, setEpicIn] = useState('');
  const [epicErr, setEpicErr] = useState('');
  const [epicBusy, setEpicBusy] = useState(false);
  const set = (k: keyof typeof form) => (v: string) => { setForm((f) => ({ ...f, [k]: v })); if (k !== 'interval') setStatus(null); };

  const JS: Record<string, [string, string]> = {
    ok: [`Connected · ${j.epics.length} epics readable, last full sync ${j.lastFullSync}`, 'var(--tn-clo)'],
    fail: ['Authentication failed · check site URL and API token', 'var(--hl-red-fg)'],
    testing: ['Testing connection…', 'var(--text-muted)'],
  };
  const [jsLabel, jsFg] = (status && JS[status]) || ['Not tested since last change', 'var(--text-muted)'];

  const test = async () => {
    setStatus('testing');
    try { setStatus((await api.testJira(form.url, form.token || undefined)).status); } catch { setStatus('fail'); }
  };
  const save = async () => {
    const s = await write(api.saveJira({ url: form.url, email: form.email, token: form.token || undefined, interval: form.interval }));
    if (s) { setStatus(s.settings.jira.status); setForm((f) => ({ ...f, token: '' })); showToast('Jira connection saved'); }
  };
  const addEpic = async () => {
    if (!epicIn.trim()) return;
    setEpicBusy(true);
    setEpicErr('');
    try {
      const s = await api.addEpic(epicIn);
      setEpicIn('');
      setSnap(s);
    } catch (e) {
      setEpicErr(e instanceof ApiError ? e.message : String(e));
    } finally {
      setEpicBusy(false);
    }
  };
  return (
    <Section title="Jira Cloud connection" sub="A service account reads issues and worklogs, and writes Planned Start/End, assignee and status changes.">
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(260px,1fr))', gap: 12 }}>
        <TextField label="Site URL" value={form.url} onChange={set('url')} />
        <TextField label="Account email" value={form.email} onChange={set('email')} />
        <TextField label="API token" type="password" value={form.token} placeholder={j.tokenMasked} onChange={set('token')} autoComplete="off" />
        <Select label="Sync interval" options={[['5', 'Every 5 minutes'], ['15', 'Every 15 minutes'], ['60', 'Every hour'], ['manual', 'Manual only']].map(([value, label]) => ({ value, label }))}
          value={form.interval} onChange={set('interval')} />
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        <div>
          <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--text-heading)' }}>Tracked epics</div>
          <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>Each epic is one anken; its project and title are read from Jira.</div>
        </div>
        <div style={{ border: '1px solid var(--border-subtle)', borderRadius: 12 }}>
          {j.epics.map((k) => {
            const info = j.epicInfo[k];
            const prj = info ? snap.projects[info.prj] : null;
            return (
              <div key={k} style={{ display: 'grid', gridTemplateColumns: '100px minmax(0,1fr) minmax(0,1fr) auto', gap: 12, alignItems: 'center', padding: '6px 8px 6px 14px', borderBottom: '1px solid var(--border-subtle)' }}>
                <span className="key">{k}</span>
                <b className="ellipsis heading">{info?.title ?? '—'}</b>
                <span style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, color: 'var(--text-muted)', minWidth: 0 }}>
                  <span style={{ width: 10, height: 10, borderRadius: 3, background: prj?.c, flex: 'none' }} />{prj?.name}
                </span>
                <IconButton icon="x" label="Stop tracking" size="sm" onClick={() => write(api.deleteEpic(k))} />
              </div>
            );
          })}
          <div style={{ display: 'flex', alignItems: 'flex-end', gap: 8, padding: '10px 14px', flexWrap: 'wrap' }}>
            <TextField label="Epic key" placeholder="e.g. PAY-1150" value={epicIn} onChange={(v) => { setEpicIn(v); setEpicErr(''); }} style={{ width: 220 }}
              onKeyDown={(e) => e.key === 'Enter' && addEpic()} />
            <Button appearance="secondary" iconBefore="plus" disabled={epicBusy} onClick={addEpic}>Add epic</Button>
            {epicBusy && <Busy>Reading epic from Jira…</Busy>}
            {epicErr && <ErrText>{epicErr}</ErrText>}
          </div>
        </div>
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap', paddingTop: 14, borderTop: '1px solid var(--border-subtle)' }}>
        <span className="dot" style={{ width: 8, height: 8, background: jsFg }} />
        <span style={{ flex: 1, fontSize: 12, color: jsFg, fontWeight: 600 }}>{jsLabel}</span>
        <Button appearance="secondary" size="sm" iconBefore="plug" disabled={status === 'testing'} onClick={test}>Test connection</Button>
        <Button size="sm" onClick={save}>Save</Button>
      </div>
    </Section>
  );
}

function MapTab() {
  const { snap, setSnap } = useApp();
  const [map, setMap] = useState(snap.settings.map);
  const [sample, setSample] = useState('PAY-1203');
  const [checking, setChecking] = useState(false);
  const [res, setRes] = useState<{ c?: Task; err?: string } | null>(null);
  useAutosave(map, (m) => { api.patchSettings({ map: m }).then(setSnap).catch(() => {}); });

  const check = async () => {
    setChecking(true);
    setRes(null);
    try { setRes({ c: await api.ticket(sample.trim().toUpperCase() || '-') }); }
    catch (e) { setRes({ err: e instanceof ApiError ? e.message : String(e) }); }
    finally { setChecking(false); }
  };
  const rows: ['ps' | 'pe' | 'eff', string, 'date' | 'number'][] = [['ps', 'Planned Start', 'date'], ['pe', 'Planned End', 'date'], ['eff', 'Planned Effort', 'number']];
  const GRID = '160px 80px minmax(220px,1.4fr) minmax(160px,1fr)';
  return (
    <Section title="Field mapping" sub="One mapping for the whole Jira site. Enter the field ID as shown in Jira, e.g. customfield_10020.">
      <div style={{ border: '1px solid var(--border-subtle)', borderRadius: 12, overflowX: 'auto' }}>
        <div style={{ display: 'grid', gridTemplateColumns: GRID, gap: 12, padding: '8px 14px', background: 'var(--surface-hover)', borderRadius: '12px 12px 0 0', fontSize: 11, fontWeight: 600, color: 'var(--text-muted)', minWidth: 640 }}>
          <span>Tracker field</span><span>Expects</span><span>Jira field ID</span><span>Value on sample ticket</span>
        </div>
        {rows.map(([k, label, t]) => {
          const fv = map[k];
          const fd = snap.jiraFields.find((f) => f.value === fv.trim());
          let r = '', rFg = 'var(--text-muted)';
          if (res?.c) {
            const c = res.c;
            if (!fv) { r = 'Not mapped'; rFg = 'var(--hl-red-fg)'; }
            else if (!fd) { r = 'Field not found in Jira'; rFg = 'var(--hl-red-fg)'; }
            else if (fd.t !== t) { r = 'Type mismatch · expects ' + (t === 'date' ? 'a date' : 'hours'); rFg = 'var(--hl-red-fg)'; }
            else { r = '✓ ' + (t === 'date' ? (k === 'ps' ? c.ps : c.pe) : f1(c.est) + 'h'); rFg = 'var(--text-heading)'; }
          }
          return (
            <div key={k} style={{ display: 'grid', gridTemplateColumns: GRID, gap: 12, alignItems: 'center', padding: '8px 14px', borderTop: '1px solid var(--border-subtle)', minWidth: 640 }}>
              <b className="heading">{label}</b>
              <span className="eyebrow">{t === 'date' ? 'Date' : 'Hours'}</span>
              <TextField mono aria-label={`${label} field ID`} placeholder={k === 'eff' ? 'e.g. timeoriginalestimate' : 'e.g. customfield_10020'} value={fv}
                onChange={(v) => { setMap((m) => ({ ...m, [k]: v })); setRes(null); }} />
              <span style={{ fontSize: 12, fontWeight: 600, color: rFg }}>{r}</span>
            </div>
          );
        })}
      </div>
      <div style={{ display: 'flex', alignItems: 'flex-end', gap: 10, flexWrap: 'wrap' }}>
        <TextField label="Sample ticket" value={sample} onChange={(v) => { setSample(v); setRes(null); }} style={{ width: 200 }} onKeyDown={(e) => e.key === 'Enter' && check()} />
        <Button appearance="secondary" iconBefore="scan-search" disabled={checking} onClick={check}>Check</Button>
        {checking && <Busy>Reading ticket from Jira…</Busy>}
        {res?.c && <Busy>Read from {res.c.key} · {title(res.c)}</Busy>}
        {res?.err && <ErrText>{res.err}</ErrText>}
      </div>
    </Section>
  );
}

const TOKENS: [string, string][] = [['anken_name', 'Anken name'], ['os_task', 'OS task'], ['phase_name', 'Phase'], ['function_id', 'Function ID'], ['function_name', 'Function name'], ['step', 'Step']];
const DEF_RULE = '[{phase_name}][{function_id}] {function_name} – {step}';

/** Compiles a Summary pattern with {placeholders} into a regex with named groups. */
function tplRe(t: string) {
  const parts = t.split(/(\{[a-z_]+\})/);
  const seen = new Set<string>();
  let src = '';
  for (const p of parts) {
    const m = p.match(/^\{([a-z_]+)\}$/);
    if (m) {
      if (!TOKENS.some((x) => x[0] === m[1])) throw new Error('Unknown placeholder {' + m[1] + '}');
      if (seen.has(m[1])) throw new Error('{' + m[1] + '} is used twice');
      seen.add(m[1]);
      src += '(?<' + m[1] + '>.+?)';
    } else {
      src += p.split('').map((ch) => (/\s/.test(ch) ? '\\s*' : /[–-]/.test(ch) ? '[–-]' : ch.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))).join('');
    }
  }
  if (!seen.has('step')) throw new Error('Pattern must contain {step}');
  return { re: new RegExp('^\\s*' + src + '\\s*$'), tokens: [...seen] };
}

function ParseTab() {
  const { snap, setSnap } = useApp();
  const [rule, setRule] = useState(snap.settings.rule);
  const [alias, setAlias] = useState(snap.settings.alias);
  const [sample, setSample] = useState('[Implement_Coding][F03] Domestic card payment – Fix comment BAL');
  useAutosave({ rule, alias }, (v) => { api.patchSettings(v).then(setSnap).catch(() => {}); });

  let re: RegExp | null = null, reErr = '', tks: string[] = [];
  try { const x = tplRe(rule); re = x.re; tks = x.tokens; } catch (e) { reErr = (e as Error).message; }
  const stepOf = (s?: string) => (Object.keys(alias) as StepKey[]).find((k) => alias[k].split(',').map((x) => x.trim().toLowerCase()).filter(Boolean).includes(String(s || '').trim().toLowerCase()));
  const parse = (s: string) => {
    if (!re) return null;
    const m = s.match(re);
    return m?.groups ? { ...m.groups, stepKey: stepOf(m.groups.step) } as Record<string, string | undefined> : null;
  };
  const p = parse(sample);
  const titles = allCells(snap).map(title);
  const cov = titles.filter((t) => parse(t)?.stepKey).length;
  const parsed = TOKENS.filter((x) => tks.includes(x[0])).map(([k, label]) => {
    let v = p?.[k];
    let bad = !v;
    if (k === 'step' && p) {
      if (p.stepKey) v = 'Step ' + (snap.steps.findIndex((s) => s.k === p.stepKey) + 1) + ' · ' + v;
      else { v = v ? v + ' · no alias match' : ''; bad = true; }
    }
    return { label, v: v || '—', fg: bad ? 'var(--hl-red-fg)' : 'var(--text-heading)' };
  });

  return (
    <Section title="Ticket name rules" sub="Describe how sub-task Summaries are written. Placeholders are read as values; everything else must match literally.">
      <div style={{ display: 'flex', alignItems: 'flex-end', gap: 10 }}>
        <TextField mono label="Pattern" placeholder="[{anken_name}][{os_task}]_{phase_name}_{step}" value={rule} onChange={setRule} style={{ flex: 1 }} />
        <Button appearance="subtle" onClick={() => setRule(DEF_RULE)}>Reset</Button>
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
        <span className="eyebrow">Insert</span>
        {TOKENS.map(([k, l]) => (
          <button key={k} className="hv-bg" title={l} onClick={() => setRule((r) => r + '{' + k + '}')} style={{
            height: 26, padding: '0 10px', borderRadius: 8, border: '1px solid var(--border-subtle)', background: 'var(--surface-card)',
            opacity: tks.includes(k) ? 0.45 : 1, font: '500 12px/1 var(--font-mono)', color: 'var(--text-heading)', cursor: 'pointer',
          }}>{'{' + k + '}'}</button>
        ))}
      </div>
      {reErr && <ErrText>{reErr}</ErrText>}
      <TextField label="Test with a Summary" value={sample} onChange={setSample} />
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(160px,1fr))', gap: 8 }}>
        {parsed.map((x) => (
          <div key={x.label} style={{ padding: '10px 12px', borderRadius: 10, background: 'var(--surface-hover)' }}>
            <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>{x.label}</div>
            <div style={{ fontWeight: 700, color: x.fg }}>{x.v}</div>
          </div>
        ))}
      </div>
      {!reErr && !p && <ErrText>This Summary does not match the pattern.</ErrText>}
      <span style={{ fontSize: 12, fontWeight: 600, color: cov < titles.length ? 'var(--hl-amber-fg)' : 'var(--text-muted)' }}>{cov} / {titles.length} synced sub-tasks match this pattern with a known step</span>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8, paddingTop: 14, borderTop: '1px solid var(--border-subtle)' }}>
        <div>
          <div className="title">Step aliases</div>
          <div className="eyebrow">Comma-separated names that {'{step}'} may contain, in workflow order</div>
        </div>
        {snap.steps.map((s, i) => (
          <div key={s.k} style={{ display: 'grid', gridTemplateColumns: '150px minmax(0,1fr)', gap: 12, alignItems: 'center' }}>
            <b className="heading">Step {i + 1}</b>
            <TextField aria-label={`Step ${i + 1} aliases`} value={alias[s.k]} onChange={(v) => setAlias((a) => ({ ...a, [s.k]: v }))} />
          </div>
        ))}
      </div>
    </Section>
  );
}

function MembersTab() {
  const { snap, write } = useApp();
  const GRID = 'minmax(190px,1.3fr) minmax(190px,1.2fr) 180px 230px';
  return (
    <Section pad={false} title="Members & roles" sub="Role decides which step a member is assigned to. Admins can create, edit and delete data; Users can only view it.">
      <div style={{ overflowX: 'auto' }}>
        <div style={{ minWidth: 840 }}>
          <div style={{ display: 'grid', gridTemplateColumns: GRID, gap: 12, padding: '8px 20px', background: 'var(--surface-hover)', fontSize: 11, fontWeight: 600, color: 'var(--text-muted)' }}>
            <span>Member</span><span>Jira account</span><span>Role</span><span>Permission</span>
          </div>
          {Object.keys(snap.members).map((id) => {
            const m = snap.members[id];
            return (
              <div key={id} style={{ display: 'grid', gridTemplateColumns: GRID, gap: 12, alignItems: 'center', padding: '8px 20px', borderTop: '1px solid var(--border-subtle)' }}>
                <span style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                  <Avatar a={av(snap, id)} size={28} />
                  <span><b className="heading">{m.name}</b><div style={{ fontSize: 11, color: 'var(--text-muted)' }}>{m.full}</div></span>
                </span>
                <span style={{ fontSize: 12, color: 'var(--text-heading)' }}>{m.email}</span>
                <Select aria-label="Role" options={[['BA', 'BA'], ['BAL', 'BA Leader'], ['JM', 'Japan Member']].map(([value, label]) => ({ value, label }))} value={m.role}
                  onChange={(v) => write(api.patchMember(id, { role: v }))} />
                <Select aria-label="Permission" options={[{ value: 'admin', label: 'Admin · create, edit, delete' }, { value: 'user', label: 'User · view only' }]} value={m.perm ?? 'user'}
                  onChange={(v) => write(api.patchMember(id, { perm: v }))} />
              </div>
            );
          })}
        </div>
      </div>
    </Section>
  );
}

function HolidaysTab() {
  const { snap, write, showToast } = useApp();
  const [flt, setFlt] = useState<'all' | 'VN' | 'JP'>('all');
  const [nh, setNh] = useState({ d: '', name: '', c: 'VN' });
  const hols = Object.keys(snap.holidays).sort().filter((d) => flt === 'all' || snap.holidays[d][0].includes(flt));
  const addHol = async () => {
    const s = await write(api.addHoliday(nh.d, nh.name.trim(), nh.c));
    if (s) { showToast('Holiday added · ' + dm(nh.d)); setNh({ d: '', name: '', c: nh.c }); }
  };
  return (
    <section className="card card-clip">
      <div style={{ display: 'flex', alignItems: 'flex-end', gap: 12, flexWrap: 'wrap', padding: '16px 20px' }}>
        <div style={{ flex: 1, minWidth: 240 }}>
          <div className="title">Public holidays</div>
          <div className="eyebrow">Applied to members by country in Planning, capacity, timesheet and date suggestions.</div>
        </div>
        <Seg items={[['all', 'All'], ['VN', 'Vietnam'], ['JP', 'Japan']]} value={flt} onPick={setFlt} />
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: '160px minmax(0,1fr) 150px auto', gap: 10, alignItems: 'flex-end', padding: '12px 20px', background: 'var(--surface-hover)' }}>
        <TextField label="Date" type="date" value={nh.d} onChange={(v) => setNh({ ...nh, d: v })} />
        <TextField label="Name" placeholder="e.g. Hung Kings Festival (VN)" value={nh.name} onChange={(v) => setNh({ ...nh, name: v })} />
        <Select label="Country" options={[{ value: 'VN', label: 'Vietnam' }, { value: 'JP', label: 'Japan' }, { value: 'VN JP', label: 'Both' }]} value={nh.c} onChange={(v) => setNh({ ...nh, c: v })} />
        <Button iconBefore="plus" disabled={!(nh.d && nh.name.trim())} onClick={addHol}>Add</Button>
      </div>
      {hols.map((d) => (
        <div key={d} style={{ display: 'grid', gridTemplateColumns: '110px 50px minmax(0,1fr) 80px auto', gap: 12, alignItems: 'center', padding: '8px 20px', borderTop: '1px solid var(--border-subtle)', opacity: d < snap.today ? 0.55 : 1 }}>
          <b className="heading">{dm(d)}/{d.slice(0, 4)}</b>
          <span className="eyebrow">{WDN[dow(d)]}</span>
          <span className="heading">{snap.holidays[d][1]}</span>
          <span style={{ font: '600 11px/20px var(--font-text)', padding: '0 8px', borderRadius: 6, background: 'var(--surface-hover)', color: 'var(--text-heading)', width: 'max-content' }}>{snap.holidays[d][0].replace(' ', ' + ')}</span>
          <IconButton icon="trash-2" label="Delete" size="sm" onClick={() => write(api.deleteHoliday(d))} />
        </div>
      ))}
    </section>
  );
}

/** Number field bound to one config key; saves after typing settles. */
function ConfigNumber({ k, label, hint }: { k: Exclude<keyof Config, 'days'>; label: string; hint?: string }) {
  const { snap, write } = useApp();
  const [v, setV] = useState(String(snap.config[k]));
  const n = parseFloat(v);
  useAutosave(isNaN(n) ? snap.config[k] : n, (x) => { write(api.patchConfig({ [k]: x })); });
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <TextField label={label} type="number" min={0} value={v} onChange={setV} />
      {hint && <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>{hint}</span>}
    </div>
  );
}

function HoursTab() {
  const { snap, write } = useApp();
  const days = snap.config.days;
  return (
    <Section title="Standard working hours" sub="Used for capacity, timesheet colours and date suggestions.">
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(220px,1fr))', gap: 12 }}>
        <ConfigNumber k="hours" label="Hours per working day" />
        <ConfigNumber k="tsTol" label="Over-log tolerance (hours)" />
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--text-heading)' }}>Working days · {f1(snap.config.hours * days.length)}h / week</span>
        <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
          {[1, 2, 3, 4, 5, 6, 0].map((w) => {
            const on = days.includes(w);
            return (
              <button key={w} aria-pressed={on} onClick={() => write(api.patchConfig({ days: on ? days.filter((x) => x !== w) : [...days, w] }))} style={{
                width: 52, height: 36, border: '1px solid var(--border-subtle)', borderRadius: 10, cursor: 'pointer', font: '600 12px/1 var(--font-text)',
                background: on ? 'var(--button-primary-bg)' : 'transparent', color: on ? 'var(--button-primary-fg)' : 'var(--text-heading)',
              }}>{WDN[w]}</button>
            );
          })}
        </div>
      </div>
    </Section>
  );
}

function ExtraDaysTab() {
  const { snap, setSnap, showToast } = useApp();
  const [ot, setOt] = useState({ d: '', note: '' });
  const [err, setErr] = useState('');
  const addOt = async () => {
    try {
      setSnap(await api.addExtraDay(ot.d, ot.note));
      showToast(WDN[dow(ot.d)] + ' ' + dm(ot.d) + ' now shows on all calendars');
      setOt({ d: '', note: '' });
      setErr('');
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : String(e));
    }
  };
  const del = async (d: string) => setSnap(await api.deleteExtraDay(d));
  const extras = Object.keys(snap.extraDays).sort();
  return (
    <Section pad={false} title="Extra working days" sub="Weekends are hidden on every calendar. Add a weekend date here when the team works that day; it then appears in Planning, Effort and Team Calendar and counts toward capacity.">
      <div style={{ display: 'grid', gridTemplateColumns: '180px minmax(0,1fr) auto', gap: 10, alignItems: 'flex-end', padding: '12px 20px', background: 'var(--surface-hover)' }}>
        <TextField label="Date" type="date" value={ot.d} onChange={(v) => { setOt({ ...ot, d: v }); setErr(''); }} />
        <TextField label="Reason" placeholder="e.g. Release support" value={ot.note} onChange={(v) => setOt({ ...ot, note: v })} />
        <Button iconBefore="plus" disabled={!ot.d} onClick={addOt}>Add</Button>
      </div>
      {err && <div style={{ padding: '8px 20px 0', fontSize: 12, fontWeight: 600, color: 'var(--hl-red-fg)' }}>{err}</div>}
      {extras.map((d) => (
        <div key={d} style={{ display: 'grid', gridTemplateColumns: '110px 50px minmax(0,1fr) auto', gap: 12, alignItems: 'center', padding: '8px 20px', borderTop: '1px solid var(--border-subtle)', opacity: d < snap.today ? 0.55 : 1 }}>
          <b className="heading">{dm(d)}/{d.slice(0, 4)}</b>
          <span className="eyebrow">{WDN[dow(d)]}</span>
          <span className="heading">{snap.extraDays[d]}</span>
          <IconButton icon="trash-2" label="Delete" size="sm" onClick={() => del(d)} />
        </div>
      ))}
      {!extras.length && <div style={{ padding: '12px 20px', borderTop: '1px solid var(--border-subtle)', fontSize: 12, color: 'var(--text-muted)' }}>No extra working days.</div>}
    </Section>
  );
}

function AlertsTab() {
  return (
    <Section title="Alert thresholds" sub="Controls highlighting in Function Matrix, My Work, Anken Overview and Planning.">
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(260px,1fr))', gap: '16px 14px' }}>
        <ConfigNumber k="soon" label="Due soon (working days)" hint="Amber when Planned End is this close" />
        <ConfigNumber k="effWarn" label="Effort warning (% of estimate)" hint="Amber in Effort view of the matrix" />
        <ConfigNumber k="held" label="Held too long (days)" hint="Stuck in Resolved or waiting on JM" />
        <ConfigNumber k="rounds" label="Review rounds" hint="Flags ping-pong Fix comment tickets" />
        <ConfigNumber k="cap" label="Capacity overload (%)" hint="Red in the Planning heatmap above this" />
      </div>
    </Section>
  );
}
