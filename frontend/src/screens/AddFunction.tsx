import { useState } from 'react';
import { api } from '../api/client';
import type { Role } from '../api/types';
import { Avatar } from '../components/bits';
import { Button, Icon, Select, TextField } from '../ds';
import { add, f1 } from '../lib/dates';
import { av, WEIGHTS } from '../lib/domain';
import { useApp } from '../state/AppContext';

const COLS = 'minmax(220px,1fr) 90px 56px 150px 150px';

export function AddFunction({ onDone }: { onDone: () => void }) {
  const { snap, cal, write } = useApp();
  const byRole = (r: Role) => Object.keys(snap.members).filter((id) => snap.members[id].role === r).map((id) => ({ value: id, label: snap.members[id].name }));
  const baOpts = byRole('BA'), balOpts = byRole('BAL'), jmOpts = byRole('JM');
  const [nf, setNf] = useState({
    parent: snap.parents[0]?.key ?? '', name: '', ba: baOpts[0]?.value ?? '', bal: balOpts[0]?.value ?? '', jm: jmOpts[0]?.value ?? '',
    create: '8', dates: [{}, {}, {}, {}, {}] as { ps?: string; pe?: string }[],
  });
  const set = <K extends keyof typeof nf>(k: K, v: (typeof nf)[K]) => setNf((s) => ({ ...s, [k]: v }));
  const setDate = (i: number, k: 'ps' | 'pe', v: string) => setNf((s) => ({ ...s, dates: s.dates.map((d, j) => (j === i ? { ...d, [k]: v } : d)) }));

  const fid = snap.nextFid;
  const parent = snap.parents.find((p) => p.key === nf.parent) ?? snap.parents[0];
  const phase = parent?.summary.match(/^\[([^\]]+)\]/)?.[1] ?? '';
  const base = parseFloat(nf.create) || 0;
  const who = [nf.ba, nf.bal, nf.ba, nf.jm, nf.ba];

  // Suggested plan: each step starts the working day after the previous one ends.
  let cur = add(snap.today, 1);
  const preview = snap.steps.map((st, i) => {
    const est = Math.round(base * WEIGHTS[i] * 2) / 2;
    const dur = Math.max(1, Math.ceil(est / 6));
    const sk: string[] = [];
    const [sps, spe] = cal.span(cur, dur, who[i], sk);
    const o = nf.dates[i];
    const ps = o.ps || sps;
    let pe = o.pe || (o.ps ? cal.span(o.ps, dur, who[i])[1] : spe);
    if (pe < ps) pe = ps;
    cur = add(pe, 1);
    const edited = !!(o.ps || o.pe);
    const offs = edited ? [ps, pe].map((d) => cal.off(d, who[i])).filter((x): x is string => !!x) : [];
    return {
      summary: `[${phase}][${fid}] ${nf.name || '(function name)'} – ${st.label}`, who: who[i], est, ps, pe,
      note: edited ? (offs.length ? 'Falls on a day off: ' + offs.map((x) => (x === 'we' ? 'weekend' : x)).join(', ') : 'Edited manually')
        : sk.length ? 'Suggested · skips ' + sk.join(', ') : 'Auto-suggested',
      noteFg: edited && offs.length ? 'var(--hl-red-fg)' : sk.length && !edited ? 'var(--hl-amber-fg)' : 'var(--text-muted)',
    };
  });

  const disabled = !nf.name.trim() || !(base > 0) || !nf.ba || !nf.bal || !nf.jm;
  const create = async () => {
    const s = await write(
      api.createFunction({ parent: nf.parent, phase, fid, name: nf.name.trim(), ba: nf.ba, rows: preview.map((r) => ({ ps: r.ps, pe: r.pe, est: r.est, who: r.who })) }),
      `created 5 sub-tasks for ${fid}`,
    );
    if (s) {
      setNf((x) => ({ ...x, name: '', dates: [{}, {}, {}, {}, {}] }));
      onDone();
    }
  };

  return (
    <div className="card" style={{ display: 'flex', flexDirection: 'column', gap: 16, fontSize: 13, padding: '20px 22px' }}>
      <div>
        <div style={{ font: '700 17px/1.3 var(--font-display)', color: 'var(--text-heading)' }}>Add new function</div>
        <div className="eyebrow">Creates 5 sequential sub-tasks from the template: Create → BAL Review → Fix comment BAL → JM Review → Fix comment JM</div>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(260px,1fr))', gap: 12 }}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
          <Select label="Parent ticket" options={snap.parents.map((p) => ({ value: p.key, label: p.key + ' · ' + p.summary }))} value={nf.parent} onChange={(v) => set('parent', v)} />
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap', fontSize: 12, color: 'var(--text-muted)' }}>
            <span>Phase from Summary:</span>
            <span style={{ font: '600 11px/20px var(--font-text)', padding: '0 8px', borderRadius: 999, background: 'var(--surface-tint)', color: 'var(--text-heading)' }}>{phase}</span>
            <span>· Auto-generated function ID: <b className="mono heading">{fid}</b></span>
          </div>
        </div>
        <TextField label="Function name" placeholder="e.g. Installment payment" value={nf.name} onChange={(v) => set('name', v)} />
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(150px,1fr))', gap: 12 }}>
        <Select label="BA" options={baOpts} value={nf.ba} onChange={(v) => set('ba', v)} />
        <Select label="Reviewer BAL" options={balOpts} value={nf.bal} onChange={(v) => set('bal', v)} />
        <Select label="Reviewer JM" options={jmOpts} value={nf.jm} onChange={(v) => set('jm', v)} />
        <TextField label="Effort Create (hours)" type="number" min={0} step={0.5} value={nf.create} onChange={(v) => set('create', v)} />
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap', padding: '10px 12px', borderRadius: 12, background: 'var(--surface-hover)', fontSize: 12 }}>
        <Icon name="scale" size={14} />
        <span className="muted">Effort for remaining steps = Create × project weight</span>
        <span className="spacer" />
        {snap.steps.slice(1).map((s, i) => (
          <span key={s.k} style={{ display: 'inline-flex', alignItems: 'center', gap: 6, height: 24, padding: '0 10px', borderRadius: 999, border: '1px solid var(--border-subtle)', background: 'var(--surface-card)' }}>
            <span className="muted">{s.label}</span>
            <span className="mono muted">×{WEIGHTS[i + 1]}</span>
            <b className="mono heading">{f1(Math.round(base * WEIGHTS[i + 1] * 2) / 2)}h</b>
          </span>
        ))}
      </div>
      <div>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 8, marginBottom: 6, flexWrap: 'wrap' }}>
          <span style={{ fontWeight: 600, color: 'var(--text-heading)' }}>Sub-tasks and plan per step</span>
          <span className="eyebrow">Suggested dates skip weekends, VN/JP holidays and leave; adjust any step if needed</span>
        </div>
        <div style={{ border: '1px solid var(--border-subtle)', borderRadius: 14, overflowX: 'auto' }}>
          <div style={{ display: 'grid', gridTemplateColumns: COLS, gap: 10, alignItems: 'center', padding: '8px 12px', minWidth: 720, borderBottom: '1px solid var(--border-subtle)', background: 'var(--surface-hover)', fontSize: 11, fontWeight: 600, color: 'var(--text-muted)' }}>
            <span>Summary</span><span>Assignee</span><span>Effort</span><span>Planned Start</span><span>Planned End</span>
          </div>
          {preview.map((pv, i) => {
            const a = av(snap, pv.who);
            return (
              <div key={i} style={{ display: 'grid', gridTemplateColumns: COLS, gap: 10, alignItems: 'center', padding: '6px 12px', minWidth: 720, borderBottom: '1px solid var(--border-subtle)', fontSize: 12 }}>
                <div style={{ minWidth: 0 }}>
                  <div className="ellipsis heading">{pv.summary}</div>
                  <div style={{ fontSize: 11, color: pv.noteFg }}>{pv.note}</div>
                </div>
                <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}><Avatar a={a} size={18} />{a.name}</span>
                <span className="mono heading">{f1(pv.est)}h</span>
                <TextField type="date" value={pv.ps} onChange={(v) => setDate(i, 'ps', v)} aria-label="Planned Start" />
                <TextField type="date" value={pv.pe} onChange={(v) => setDate(i, 'pe', v)} aria-label="Planned End" />
              </div>
            );
          })}
        </div>
      </div>
      <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
        <Button appearance="subtle" onClick={onDone}>Cancel</Button>
        <Button disabled={disabled} onClick={create}>Create 5 sub-tasks in Jira</Button>
      </div>
    </div>
  );
}
