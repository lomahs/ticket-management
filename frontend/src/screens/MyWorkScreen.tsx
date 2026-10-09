import { useState } from 'react';
import type { Snapshot, Task } from '../api/types';
import { Avatar, EffortTrio, Seg, StatusPill } from '../components/bits';
import { Icon, Select, type IconName } from '../ds';
import { add, dm, f1, longDate, mondayOf } from '../lib/dates';
import { allCells, av, extras, remain, STAT } from '../lib/domain';
import { useApp } from '../state/AppContext';

function itemView(snap: Snapshot, c: Task) {
  const st = STAT[c.status];
  return {
    key: c.key,
    title: c.fid ? `${c.fid} ${c.fname}` : c.title ?? '',
    sub: c.fid
      ? `${c.stepLabel}${c.rounds ? ' · round ' + c.rounds : ''}${c.held && c.status !== 'C' ? ' · held ' + c.held + ' days' : ''}`
      : c.phase,
    statusLabel: st.label, stBg: `var(--st-${st.v}-bg)`, dot: `var(--st-${st.v})`,
    due: c.late ? dm(c.pe) + ' · late ' + c.lateDays + 'd' : dm(c.pe),
    dateFg: c.late ? 'var(--hl-red-fg)' : 'var(--text-heading)',
    est: f1(c.est) + 'h', spent: f1(c.logged) + 'h', rem: f1(remain(c)) + 'h',
    hFg: c.over ? 'var(--hl-red-fg)' : 'var(--text-heading)',
    av: av(snap, c.assignee),
  };
}
type Item = ReturnType<typeof itemView>;

export function MyWorkScreen() {
  const { snap, me, setMe, ticket, openTicket } = useApp();
  const [blMode, setBlMode] = useState<'week' | 'month'>('week');
  const today = snap.today, tmr = add(today, 1);
  const member = snap.members[me];

  const mine = [...allCells(snap), ...extras(snap)].filter((c) => c.assignee === me && c.status !== 'C');
  const isReview = (c: Task) => (c.isRev && c.isCur && (c.status === 'W' || c.status === 'N')) || (c.isFix && c.status === 'R');
  const taskL = mine.filter((c) => c.status !== 'R' || isReview(c));
  const byPe = (a: Task, b: Task) => (a.pe < b.pe ? -1 : 1);
  const iv = (c: Task) => itemView(snap, c);

  const focus: { label: string; icon: IconName; list: Task[]; headBg: string; cntFg: string; hint: string }[] = [
    { label: 'Delay task', icon: 'alarm-clock', list: taskL.filter((c) => c.pe < today), headBg: 'var(--hl-red)', cntFg: 'var(--hl-red-fg)', hint: 'Past deadline' },
    { label: 'Today', icon: 'sun', list: taskL.filter((c) => c.pe === today), headBg: 'var(--surface-tint)', cntFg: 'var(--text-heading)', hint: 'Deadline ' + dm(today) },
    { label: 'Tomorrow', icon: 'sunrise', list: taskL.filter((c) => c.pe === tmr), headBg: 'var(--surface-hover)', cntFg: 'var(--text-heading)', hint: 'Deadline ' + dm(tmr) },
  ];

  const groups: Record<string, { label: string; dot: string; l: Task[] }> = {};
  for (const c of mine) {
    let k: string, label: string, dot: string;
    if (c.pe < today && c.status !== 'R') { k = '0'; label = 'Overdue'; dot = 'var(--hl-red-fg)'; }
    else if (blMode === 'week') {
      const ws = mondayOf(c.pe), cur = ws === mondayOf(today);
      k = ws; label = (cur ? 'This week · ' : 'Week ') + dm(ws) + ' – ' + dm(add(ws, 6)); dot = cur ? 'var(--st-wip)' : 'var(--st-nys)';
    } else {
      k = c.pe.slice(0, 7); label = 'Month ' + c.pe.slice(5, 7) + '/' + c.pe.slice(0, 4); dot = k === today.slice(0, 7) ? 'var(--st-wip)' : 'var(--st-nys)';
    }
    (groups[k] ??= { label, dot, l: [] }).l.push(c);
  }
  const backlog = Object.keys(groups).sort().map((k) => groups[k]);

  const isBAL = member?.role === 'BAL';
  const relL = isBAL
    ? snap.functions.filter((f) => f.cells[1].assignee === me).flatMap((f) => [0, 2, 3, 4].map((i) => f.cells[i]))
    : snap.functions.filter((f) => f.ba === me).flatMap((f) => f.cells.slice(1));
  const related = relL.filter((c) => c.status !== 'C').sort(byPe).map(iv);

  const selBg = (k: string) => (k === ticket ? 'var(--surface-tint)' : 'transparent');
  const meOpts = Object.keys(snap.members).filter((id) => snap.members[id].role !== 'JM').map((id) => ({ value: id, label: `${snap.members[id].name} (${snap.members[id].role})` }));

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16, width: '100%' }}>
      <div style={{ display: 'flex', alignItems: 'flex-end', gap: 16, flexWrap: 'wrap' }}>
        <div style={{ flex: 1, minWidth: 260 }}>
          <div className="eyebrow">{longDate(today)}</div>
          <h1 className="h1" style={{ marginTop: 2 }}>My Work</h1>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span className="eyebrow">View as</span>
          <Select options={meOpts} value={me} onChange={setMe} style={{ width: 180 }} />
        </div>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(min(100%,300px),1fr))', gap: 16, alignItems: 'start' }}>
        {focus.map((g) => (
          <section key={g.label} className="card card-clip">
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '10px 14px', background: g.headBg }}>
              <Icon name={g.icon} size={14} />
              <span className="title">{g.label}</span>
              <span className="count" style={{ background: 'var(--surface-card)', color: g.cntFg }}>{g.list.length}</span>
              <span className="spacer" />
              <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>{g.hint}</span>
            </div>
            <div style={{ maxHeight: 420, overflowY: 'auto' }}>
              {[...g.list].sort(byPe).map(iv).map((it) => (
                <div key={it.key} className="hv-bg" role="button" tabIndex={0} onClick={() => openTicket(it.key)} onKeyDown={(e) => e.key === 'Enter' && openTicket(it.key)}
                  style={{ display: 'flex', flexDirection: 'column', gap: 6, padding: '10px 14px', borderTop: '1px solid var(--border-subtle)', cursor: 'pointer', background: selBg(it.key) }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <span className="key">{it.key}</span>
                    <StatusPill bg={it.stBg} dot={it.dot}>{it.statusLabel}</StatusPill>
                    <span className="spacer" />
                    <span style={{ font: '600 12px/1 var(--font-mono)', color: it.dateFg }}>{it.due}</span>
                  </div>
                  <div style={{ minWidth: 0 }}>
                    <div className="ellipsis" style={{ fontWeight: 600, color: 'var(--text-heading)' }}>{it.title}</div>
                    <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>{it.sub}</div>
                  </div>
                  <EffortTrio est={it.est} spent={it.spent} rem={it.rem} spentFg={it.hFg} />
                </div>
              ))}
            </div>
            {!g.list.length && <div className="empty-row">No tasks.</div>}
          </section>
        ))}
      </div>

      <section className="card card-clip">
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap', padding: '10px 14px' }}>
          <Icon name="list-todo" size={14} />
          <span className="title">Your backlog</span>
          <span className="eyebrow">{mine.length} open tasks · Remain {f1(mine.reduce((a, c) => a + remain(c), 0))}h</span>
          <span className="spacer" />
          <Seg outline items={[['week', 'By week'], ['month', 'By month']]} value={blMode} onPick={setBlMode} />
        </div>
        <div style={{ overflowX: 'auto' }}>
          {backlog.map((g) => (
            <div key={g.label}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '8px 14px', minWidth: 620, borderTop: '1px solid var(--border-subtle)', background: 'var(--surface-hover)' }}>
                <span className="dot" style={{ width: 8, height: 8, background: g.dot }} />
                <span className="title">{g.label}</span>
                <span className="count" style={{ background: 'var(--surface-card)', color: 'var(--text-heading)' }}>{g.l.length}</span>
                <span className="spacer" />
                <span className="eyebrow">Remain <b className="mono heading">{f1(g.l.reduce((a, c) => a + remain(c), 0))}h</b></span>
              </div>
              {[...g.l].sort(byPe).map(iv).map((bi) => (
                <ListRow key={bi.key} it={bi} cols="76px minmax(180px,1fr) 92px 76px 150px" minW={620} selBg={selBg(bi.key)} open={() => openTicket(bi.key)} />
              ))}
            </div>
          ))}
          {!backlog.length && <div className="empty-row">Backlog is empty.</div>}
        </div>
      </section>

      <section className="card card-clip">
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap', padding: '10px 14px' }}>
          <Icon name="link" size={14} />
          <span className="title">Related tasks</span>
          <span className="count" style={{ background: 'var(--surface-hover)', color: 'var(--text-heading)' }}>{related.length}</span>
          <span className="eyebrow">{isBAL ? 'BA Create, JM Review and Fix comment BAL/JM tickets of functions you review' : 'Steps after Create (review, fix comment) of functions you own'}</span>
        </div>
        <div style={{ overflowX: 'auto' }}>
          {related.map((ri) => (
            <ListRow key={ri.key} it={ri} withAssignee cols="76px minmax(180px,1fr) 96px 92px 76px 150px" minW={720} selBg={selBg(ri.key)} open={() => openTicket(ri.key)} />
          ))}
          {!related.length && <div className="empty-row">No open related tasks.</div>}
        </div>
      </section>
    </div>
  );
}

function ListRow({ it, cols, minW, selBg, open, withAssignee }: { it: Item; cols: string; minW: number; selBg: string; open: () => void; withAssignee?: boolean }) {
  return (
    <div className="hv-bg" role="button" tabIndex={0} onClick={open} onKeyDown={(e) => e.key === 'Enter' && open()}
      style={{ display: 'grid', gridTemplateColumns: cols, alignItems: 'center', gap: 10, padding: '8px 14px', minWidth: minW, borderTop: '1px solid var(--border-subtle)', cursor: 'pointer', background: selBg }}>
      <span className="key">{it.key}</span>
      <div style={{ minWidth: 0 }}>
        <div className="ellipsis" style={{ fontWeight: 600, color: 'var(--text-heading)' }}>{it.title}</div>
        <div className="ellipsis" style={{ fontSize: 11, color: 'var(--text-muted)' }}>{it.sub}</div>
      </div>
      {withAssignee && (
        <span style={{ display: 'flex', alignItems: 'center', gap: 6, minWidth: 0 }}>
          <Avatar a={it.av} size={20} /><span className="ellipsis">{it.av.name}</span>
        </span>
      )}
      <StatusPill bg={it.stBg} dot={it.dot}>{it.statusLabel}</StatusPill>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
        <span className="mini-label">Deadline</span>
        <span style={{ font: '600 12px/1.2 var(--font-mono)', color: it.dateFg }}>{it.due}</span>
      </div>
      <EffortTrio est={it.est} spent={it.spent} rem={it.rem} spentFg={it.hFg} />
    </div>
  );
}
