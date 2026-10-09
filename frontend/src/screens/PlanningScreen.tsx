import { Fragment, useRef, useState, type DragEvent } from 'react';
import { api } from '../api/client';
import type { Task } from '../api/types';
import { Avatar, PageHead } from '../components/bits';
import { Button, Icon, IconButton } from '../ds';
import { add, dayDiff, dm, dow, f1, mondayOf, WDN } from '../lib/dates';
import { actual, dayHead, HT, mix, planItems, SC, STAT, title } from '../lib/domain';
import { useApp } from '../state/AppContext';

interface Plan { ps: string; pe: string; who: string }
type Pending = Record<string, { from: Plan; to: Plan }>;

const stepK = (c: Task): keyof typeof SC => {
  if (c.sk) return c.sk;
  const t = (c.title ?? '').toLowerCase();
  return (['fixb', 'fixj', 'balr', 'jmr', 'create'] as const).find((k) => t.endsWith(SC[k][0].toLowerCase())) ?? 'other';
};

export function PlanningScreen() {
  const { snap, cal, write, openTicket } = useApp();
  const { today, members, projects, leaveTypes, config, extraDays } = snap;
  const [gs, setGs] = useState(() => mondayOf(today));
  const [view, setView] = useState<'w1' | 'w2'>('w2');
  const [pend, setPend] = useState<Pending>({});
  const [hide, setHide] = useState<string[]>([]);
  const [dropRow, setDropRow] = useState<string | null>(null);
  const drag = useRef<{ key: string; grab: number; off: number } | null>(null);

  const NW = view === 'w1' ? 1 : 2;
  const all7 = [...Array(NW * 7)].map((_, i) => add(gs, i));
  const last = all7[all7.length - 1];
  const V = all7.filter(cal.vis);
  const C = Math.max(1, V.length);
  const ix = (s: string) => dayDiff(gs, s);
  const pc = (n: number) => ((n / C) * 100).toFixed(4) + '%';
  const sCol = (s: string) => { const i = V.findIndex((v) => v >= s); return i < 0 ? C : i; };
  const eCol = (s: string) => { let i = -1; V.forEach((v, j) => { if (v <= s) i = j; }); return i; };

  const items = planItems(snap).filter((c) => c.ps && c.pe);
  const placed = items.map((c) => {
    const p = pend[c.key];
    return p ? { c, ps: p.to.ps, pe: p.to.pe, who: p.to.who, pend: true } : { c, ps: c.ps, pe: c.pe, who: c.worker, pend: false };
  });

  const moveItem = (key: string, ps: string, who: string) => {
    const c = items.find((x) => x.key === key);
    if (!c) return;
    setPend((cur) => {
      const p = cur[key];
      const from = p ? p.from : { ps: c.ps, pe: c.pe, who: c.worker };
      const now = p ? p.to : from;
      const to = { ps, pe: add(ps, dayDiff(now.ps, now.pe)), who };
      const n = { ...cur };
      if (to.ps === from.ps && to.who === from.who) delete n[key];
      else n[key] = { from, to };
      return n;
    });
    setDropRow(null);
  };

  const dayCell = (id: string, s: string) => {
    const h = cal.isHoliday(s, id);
    const lv = cal.lv(id, s);
    if (h) return { bg: HT('var(--border-strong)') + ',' + mix(leaveTypes.holiday.c, 22), tip: dm(s) + ' · ' + h[1] };
    if (lv && leaveTypes[lv.type].full) return { bg: HT(leaveTypes[lv.type].c) + ',' + mix(leaveTypes[lv.type].c, 12), tip: `${dm(s)} · ${members[id].name} · ${leaveTypes[lv.type].label}` };
    if (lv && leaveTypes[lv.type].half) return { bg: `linear-gradient(to bottom,${mix(leaveTypes[lv.type].c, 30)} 50%,transparent 50%)`, tip: dm(s) + ' · ' + leaveTypes[lv.type].label };
    return { bg: s === today ? 'var(--surface-tint)' : extraDays[s] ? mix(SC.create[1], 8) : 'transparent', tip: dm(s) + (extraDays[s] ? ' · Extra working day' : '') };
  };

  const rows = Object.keys(members).map((id) => {
    const list = placed
      .filter((x) => x.who === id && !hide.includes(x.c.prj) && x.pe >= gs && x.ps <= last)
      .sort((a, b) => (a.ps < b.ps ? -1 : a.ps > b.ps ? 1 : a.pe < b.pe ? -1 : 1));
    const lanes: number[] = [];
    const blocks = list.flatMap((x) => {
      const c = x.c;
      const s = sCol(x.ps);
      let e = eCol(x.pe);
      if (s >= C) return [];
      if (e < s) e = s;
      let l = lanes.findIndex((v) => v < s);
      if (l < 0) { l = lanes.length; lanes.push(e); } else lanes[l] = e;
      const top = 6 + l * 32;
      const a = actual(c, today);
      let act: { left: string; w: string; late: boolean } | null = null;
      if (a && a[1] >= gs && a[0] <= last) {
        const as = sCol(a[0]);
        let ae = eCol(a[1]);
        if (as < C) {
          if (ae < as) ae = as;
          act = { left: `calc(${pc(as)} + 2px)`, w: `calc(${pc(ae - as + 1)} - 4px)`, late: a[1] > c.pe };
        }
      }
      const sk = stepK(c);
      return [{
        x, s, top, act, color: SC[sk][1],
        left: `calc(${pc(s)} + 2px)`, w: `calc(${pc(e - s + 1)} - 4px)`,
        tip: `${c.key} · ${title(c)}\n${SC[sk][0]} · ${projects[c.prj]?.name ?? c.prj}\nPlan ${dm(x.ps)} – ${dm(x.pe)}\n`
          + `${a ? 'Actual ' + dm(a[0]) + ' – ' + dm(a[1]) : 'Actual: not started'}\n${f1(c.logged)} / ${f1(c.est)}h · ${STAT[c.status].label}`
          + `${x.pend ? '\nPending push to Jira' : ''}\nDrag to change dates or assignee`,
      }];
    });
    return { id, m: members[id], h: Math.max(1, lanes.length) * 32 + 10, blocks };
  });

  // Capacity: planned effort spread over each item's working days vs available hours, 5 weeks from the start date.
  const load: Record<string, number[]> = {};
  for (const x of placed) {
    const ds: string[] = [];
    for (let s = x.ps; s <= x.pe; s = add(s, 1)) if (!cal.off(s, x.who)) ds.push(s);
    if (!ds.length) ds.push(x.ps);
    const per = x.c.est / ds.length;
    for (const s of ds) {
      const w = Math.floor(ix(s) / 7);
      if (w >= 0 && w < 5) (load[x.who] ??= [0, 0, 0, 0, 0])[w] += per;
    }
  }
  const capOf = (id: string, w: number) => {
    let h = 0;
    for (let i = 0; i < 7; i++) {
      const s = add(gs, w * 7 + i);
      if (cal.off(s, id)) continue;
      const lv = cal.lv(id, s);
      h += config.hours * (lv && leaveTypes[lv.type].half ? 0.5 : 1);
    }
    return h;
  };
  const heatWeeks = [0, 1, 2, 3, 4].map((w) => dm(add(gs, w * 7)) + ' – ' + dm(add(gs, w * 7 + 6)));
  const weeks = [...Array(NW)].map((_, w) => ({ label: heatWeeks[w], n: V.filter((v) => Math.floor(ix(v) / 7) === w).length })).filter((x) => x.n);

  const pk = Object.keys(pend);
  const tray = pk.map((k) => {
    const p = pend[k];
    const c = items.find((x) => x.key === k)!;
    const df: { f: string; a: string; b: string }[] = [];
    if (p.from.ps !== p.to.ps) df.push({ f: 'Planned Start', a: dm(p.from.ps), b: dm(p.to.ps) });
    if (p.from.pe !== p.to.pe) df.push({ f: 'Planned End', a: dm(p.from.pe), b: dm(p.to.pe) });
    if (p.from.who !== p.to.who) df.push({ f: 'Assignee', a: members[p.from.who].name, b: members[p.to.who].name });
    const offs = [...new Set([p.to.ps, p.to.pe].map((d) => cal.off(d, p.to.who)).filter((x): x is string => !!x).map((x) => (x === 'we' ? 'weekend' : x)))];
    return { key: k, title: title(c), c: SC[stepK(c)][1], diffs: df, warn: offs.length ? 'Lands on a day off: ' + offs.join(', ') : '' };
  });
  const pushAll = async () => {
    const changes = pk.map((k) => ({ key: k, ...pend[k].to }));
    const s = await write(api.pushPlan(changes), `${pk.length} plan change${pk.length > 1 ? 's' : ''} from Planning`);
    if (s) setPend({});
  };

  const ti = V.indexOf(today);
  const onDrop = (e: DragEvent<HTMLDivElement>, id: string) => {
    e.preventDefault();
    const d = drag.current;
    drag.current = null;
    if (!d) { setDropRow(null); return; }
    const r = e.currentTarget.getBoundingClientRect();
    const col = Math.max(0, Math.min(C - 1, Math.floor(((e.clientX - r.left) / r.width) * C) - d.grab));
    moveItem(d.key, add(V[col], d.off), id);
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16, width: '100%' }}>
      <PageHead eyebrow="All projects · Planned Start / End from Jira" title="Planning">
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
          <div className="seg" style={{ marginRight: 6 }}>
            {([['w1', '1 week'], ['w2', '2 weeks']] as const).map(([k, l]) => (
              <button key={k} onClick={() => setView(k)} style={{ background: view === k ? 'var(--button-primary-bg)' : 'transparent', color: view === k ? 'var(--button-primary-fg)' : 'var(--text-heading)' }}>{l}</button>
            ))}
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginRight: 6 }}>
            <input type="date" value={gs} aria-label="Start date" title="Start date (snaps to Monday)"
              onChange={(e) => e.target.value && setGs(mondayOf(e.target.value))}
              style={{ height: 32, boxSizing: 'border-box', padding: '0 10px', border: '1px solid var(--border-strong)', borderRadius: 8, background: 'var(--surface-card)', color: 'var(--text-heading)', font: '600 13px/1 var(--font-text)' }} />
            <span style={{ fontSize: 12, color: 'var(--text-muted)', whiteSpace: 'nowrap' }}>to {dm(last)}/{last.slice(0, 4)}</span>
          </div>
          <IconButton icon="chevron-left" label="Previous" appearance="secondary" size="sm" onClick={() => setGs(add(gs, -NW * 7))} />
          <Button appearance="secondary" size="sm" onClick={() => setGs(mondayOf(today))}>Today</Button>
          <IconButton icon="chevron-right" label="Next" appearance="secondary" size="sm" onClick={() => setGs(add(gs, NW * 7))} />
        </div>
      </PageHead>

      <section className="card card-clip">
        <div style={{ display: 'flex', alignItems: 'center', gap: 14, flexWrap: 'wrap', padding: '12px 16px', borderBottom: '1px solid var(--border-subtle)' }}>
          <span className="title">Gantt by member</span>
          <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
            {Object.keys(projects).map((k) => {
              const hidden = hide.includes(k);
              return (
                <button key={k} className="hv-bg" aria-pressed={!hidden} onClick={() => setHide((h) => (h.includes(k) ? h.filter((x) => x !== k) : [...h, k]))} style={{
                  display: 'flex', alignItems: 'center', gap: 6, height: 26, padding: '0 10px', borderRadius: 99, border: '1px solid var(--border-subtle)',
                  background: 'transparent', opacity: hidden ? 0.4 : 1, font: '500 12px/1 var(--font-text)', color: 'var(--text-heading)', cursor: 'pointer',
                }}>
                  <Icon name={hidden ? 'eye-off' : 'eye'} size={13} />{projects[k].name}
                </button>
              );
            })}
          </div>
          <span className="spacer" />
          <div className="legend">
            {Object.values(SC).map(([label, c]) => <span key={label}><span style={{ width: 14, height: 9, borderRadius: 3, background: c }} />{label}</span>)}
            <span><span style={{ width: 18, height: 3, borderRadius: 2, background: 'var(--text-heading)' }} />Actual</span>
            <span><span style={{ width: 18, height: 3, borderRadius: 2, background: 'var(--hl-red-fg)' }} />Actual past plan</span>
            <span><span className="swatch" style={{ border: '1px solid var(--border-subtle)', background: HT('var(--border-strong)') + ',' + mix(leaveTypes.holiday.c, 22) }} />Holiday (VN / JP)</span>
            <span><span className="swatch" style={{ border: '1px solid var(--border-subtle)', background: HT(leaveTypes.full.c) + ',' + mix(leaveTypes.full.c, 12) }} />Leave</span>
          </div>
        </div>
        <div style={{ overflowX: 'auto' }}>
          <div style={{ display: 'flex', flexDirection: 'column', minWidth: 720 }}>
            <div style={{ display: 'flex', borderBottom: '1px solid var(--border-subtle)' }}>
              <div style={{ width: 120, flex: 'none', boxSizing: 'border-box', borderRight: '1px solid var(--border-subtle)', padding: '8px 12px', display: 'flex', alignItems: 'flex-end', fontSize: 11, color: 'var(--text-muted)' }}>Member</div>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ display: 'flex' }}>
                  {weeks.map((w) => (
                    <div key={w.label} style={{ width: pc(w.n), boxSizing: 'border-box', flex: 'none', padding: '6px 8px', borderLeft: '1px solid var(--border-subtle)', font: '600 11px/1 var(--font-text)', color: 'var(--text-heading)', whiteSpace: 'nowrap', overflow: 'hidden' }}>{w.label}</div>
                  ))}
                </div>
                <div style={{ display: 'flex' }}>
                  {V.map((s) => {
                    const h = dayHead(s, today, extraDays);
                    return (
                      <div key={s} className="day-head" title={h.tip} style={{ width: pc(1), flex: 'none', padding: '3px 0 5px', background: h.bg, color: h.fg }}>
                        <div>{WDN[dow(s)]}</div>
                        <div className="n" style={{ fontWeight: h.fw }}>{s.slice(8, 10)}</div>
                      </div>
                    );
                  })}
                </div>
              </div>
            </div>
            {rows.map((r) => (
              <div key={r.id} style={{ display: 'flex', borderBottom: '1px solid var(--border-subtle)' }}>
                <div style={{ width: 120, flex: 'none', boxSizing: 'border-box', borderRight: '1px solid var(--border-subtle)', padding: 10, display: 'flex', alignItems: 'flex-start', gap: 8 }}>
                  <Avatar a={{ ini: r.m.name[0], color: r.m.color }} size={22} />
                  <div style={{ minWidth: 0 }}>
                    <div className="ellipsis" style={{ fontWeight: 600, color: 'var(--text-heading)' }}>{r.m.name}</div>
                    <div className="ellipsis" style={{ fontSize: 11, color: 'var(--text-muted)' }}>{r.m.role}{r.m.ext ? ' · client' : ''}</div>
                  </div>
                </div>
                <div
                  onDragOver={(e) => { e.preventDefault(); if (dropRow !== r.id) setDropRow(r.id); }}
                  onDrop={(e) => onDrop(e, r.id)}
                  style={{ position: 'relative', flex: 1, minWidth: 0, height: r.h, boxShadow: dropRow === r.id ? 'inset 0 0 0 2px var(--border-focus)' : 'none' }}
                >
                  <div style={{ position: 'absolute', inset: 0, display: 'flex', pointerEvents: 'none' }}>
                    {V.map((s) => {
                      const d = dayCell(r.id, s);
                      return <div key={s} title={d.tip} style={{ width: pc(1), boxSizing: 'border-box', flex: 'none', borderLeft: '1px solid var(--border-subtle)', background: d.bg }} />;
                    })}
                  </div>
                  {ti >= 0 && <div style={{ position: 'absolute', top: 0, bottom: 0, left: `calc(${pc(ti + 0.5)} - 1px)`, width: 2, background: 'var(--hl-red-fg)', opacity: 0.5, pointerEvents: 'none' }} />}
                  {r.blocks.map((b) => (
                    <Fragment key={b.x.c.key}>
                      <div
                        draggable
                        className="hv-bright"
                        title={b.tip}
                        onClick={() => openTicket(b.x.c.key)}
                        onDragStart={(ev) => {
                          const g = ev.currentTarget.parentElement!.getBoundingClientRect();
                          const col = Math.floor(((ev.clientX - g.left) / g.width) * C);
                          drag.current = { key: b.x.c.key, grab: col - b.s, off: ix(b.x.ps) - ix(V[b.s]) };
                          ev.dataTransfer.setData('text/plain', b.x.c.key);
                          ev.dataTransfer.effectAllowed = 'move';
                        }}
                        onDragEnd={() => { drag.current = null; setDropRow(null); }}
                        style={{
                          position: 'absolute', zIndex: 1, boxShadow: '0 0 0 1px var(--surface-card)', left: b.left, top: b.top, width: b.w, height: 20, boxSizing: 'border-box',
                          padding: '0 6px', borderRadius: 6, background: b.color, outline: b.x.pend ? '2px dashed var(--text-heading)' : 'none', outlineOffset: 1,
                          color: '#fff', font: '600 11px/20px var(--font-mono)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', cursor: 'grab',
                        }}
                      >
                        {(b.x.c.status === 'C' ? '✓ ' : '') + b.x.c.key}
                      </div>
                      {b.act && <div style={{ position: 'absolute', left: b.act.left, top: b.top + 23, width: b.act.w, height: 3, borderRadius: 2, background: b.act.late ? 'var(--hl-red-fg)' : 'var(--text-heading)', pointerEvents: 'none' }} />}
                    </Fragment>
                  ))}
                </div>
              </div>
            ))}
          </div>
        </div>
      </section>

      <section className="card card-clip">
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap', padding: '12px 16px' }}>
          <Icon name="upload-cloud" size={16} />
          <span className="title">Changes pending push to Jira</span>
          {pk.length > 0 && <span className="count" style={{ padding: '0 8px', background: 'var(--surface-tint)', color: 'var(--text-heading)' }}>{pk.length}{pk.length === 1 ? ' change' : ' changes'}</span>}
          <span className="spacer" />
          {pk.length > 0 && (
            <>
              <Button appearance="subtle" size="sm" onClick={() => setPend({})}>Discard all</Button>
              <Button size="sm" iconBefore="upload" onClick={pushAll}>Push to Jira</Button>
            </>
          )}
        </div>
        {!pk.length && <div style={{ padding: '0 16px 14px', fontSize: 12, color: 'var(--text-muted)' }}>Drag a block sideways to change its dates, or onto another member to reassign it. Changes collect here until you push them.</div>}
        {tray.map((i) => (
          <div key={i.key} style={{ display: 'grid', gridTemplateColumns: 'minmax(0,1.3fr) minmax(0,2fr) auto', gap: 12, alignItems: 'center', padding: '10px 16px', borderTop: '1px solid var(--border-subtle)' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
              <span style={{ width: 10, height: 10, borderRadius: 3, background: i.c, flex: 'none' }} />
              <span style={{ font: '600 11px/1 var(--font-mono)', color: 'var(--text-link)', flex: 'none' }}>{i.key}</span>
              <span className="ellipsis heading" style={{ minWidth: 0 }}>{i.title}</span>
            </div>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, alignItems: 'center' }}>
              {i.diffs.map((df) => (
                <span key={df.f} style={{ display: 'inline-flex', alignItems: 'center', gap: 6, padding: '3px 8px', borderRadius: 8, background: 'var(--surface-hover)', fontSize: 12 }}>
                  <span className="muted">{df.f}</span>
                  <span className="muted" style={{ textDecoration: 'line-through' }}>{df.a}</span>
                  <span className="muted">→</span>
                  <b className="heading">{df.b}</b>
                </span>
              ))}
              {i.warn && <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--hl-amber-fg)' }}>{i.warn}</span>}
            </div>
            <IconButton icon="undo-2" label="Revert" size="sm" onClick={() => setPend((s) => { const n = { ...s }; delete n[i.key]; return n; })} />
          </div>
        ))}
      </section>

      <section className="card" style={{ padding: '16px 18px', display: 'flex', flexDirection: 'column', gap: 12 }}>
        <div style={{ display: 'flex', alignItems: 'flex-end', gap: 12, flexWrap: 'wrap' }}>
          <div style={{ flex: 1, minWidth: 220 }}>
            <div className="title">Capacity by week</div>
            <div className="eyebrow">Planned effort ÷ available hours after leave and holidays · includes pending changes</div>
          </div>
          <div style={{ display: 'flex', gap: 12, fontSize: 11, color: 'var(--text-muted)' }}>
            {([['Healthy', 'var(--hl-ok)'], ['Near full', 'var(--hl-amber)'], ['Over capacity', 'var(--hl-red)']] as const).map(([l, bg]) => (
              <span key={l} style={{ display: 'flex', alignItems: 'center', gap: 5 }}><span className="swatch" style={{ background: bg }} />{l}</span>
            ))}
          </div>
        </div>
        <div style={{ overflowX: 'auto' }}>
          <div style={{ minWidth: 600, display: 'flex', flexDirection: 'column', gap: 4 }}>
            <div style={{ display: 'grid', gridTemplateColumns: '150px repeat(5,minmax(84px,1fr))', gap: 4, fontSize: 11, color: 'var(--text-muted)' }}>
              <span />
              {heatWeeks.map((w) => <span key={w} style={{ padding: '0 4px', fontWeight: 600 }}>{w}</span>)}
            </div>
            {Object.keys(members).map((id) => (
              <div key={id} style={{ display: 'grid', gridTemplateColumns: '150px repeat(5,minmax(84px,1fr))', gap: 4, alignItems: 'stretch' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <Avatar a={{ ini: members[id].name[0], color: members[id].color }} size={22} />
                  <span style={{ fontWeight: 600, color: 'var(--text-heading)' }}>{members[id].name}</span>
                </div>
                {[0, 1, 2, 3, 4].map((w) => {
                  const h = load[id]?.[w] ?? 0, cp = capOf(id, w);
                  const p = cp ? Math.round((h / cp) * 100) : 0;
                  const over = p > config.cap;
                  const sub = f1(h) + ' / ' + cp + 'h';
                  return (
                    <div key={w} title={sub} style={{
                      padding: '7px 10px', borderRadius: 8, display: 'flex', alignItems: 'baseline', gap: 6,
                      background: over ? 'var(--hl-red)' : p >= config.cap * 0.85 ? 'var(--hl-amber)' : p >= 40 ? 'var(--hl-ok)' : 'var(--surface-hover)',
                    }}>
                      <span style={{ font: `${over ? 700 : 600} 14px/1 var(--font-display)`, color: over ? 'var(--hl-red-fg)' : 'var(--text-heading)' }}>{cp ? p + '%' : '–'}</span>
                      <span style={{ fontSize: 10, color: 'var(--text-muted)', whiteSpace: 'nowrap' }}>{sub}</span>
                    </div>
                  );
                })}
              </div>
            ))}
          </div>
        </div>
      </section>
    </div>
  );
}
