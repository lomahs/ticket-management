import { useQuery } from '@tanstack/react-query';
import { getRouteApi } from '@tanstack/react-router';
import type { Schemas } from '../../../shared/api/types';
import { useMe, useMemberMap, usePickableMembers } from '../../../shared/api/queries';
import { useAnkenScope } from '../../../shared/state/anken';
import type { TaskCard } from '../../../shared/api/types';
import { Icon, Select, type IconName } from '../../../shared/ds';
import { add, dm, hrs, longDate } from '../../../shared/format/dates';
import { statusOf } from '../../../shared/format/labels';
import { dueLabel, taskSub, taskTitle } from '../../../shared/format/task';
import { useOpenTicket, useTicketKey } from '../../../shared/state/ticket';
import { Avatar, av, EffortTrio, Seg, StatusPill } from '../../../shared/ui/bits';
import { Loading, QueryError } from '../../../shared/ui/states';
import { myWorkQuery, type Backlog } from '../api';

const route = getRouteApi('/app/my-work');

export function MyWorkPage() {
  const search = route.useSearch();
  const navigate = route.useNavigate();
  const me = useMe().data;
  const member = search.member ?? me?.member_id ?? '';
  const backlog = search.backlog ?? 'week';
  const { anken } = useAnkenScope();
  const q = useQuery({ ...myWorkQuery(member, backlog, anken), enabled: !!member });
  const pickable = usePickableMembers();
  const pick = (id: string) => { navigate({ search: (p) => ({ ...p, member: id }) }); };
  const opts = pickable.filter((m) => m.role !== 'JM').map((m) => ({ value: m.id, label: `${m.name}${m.role ? ` (${m.role})` : ''}` }));
  if (!member) opts.unshift({ value: '', label: 'Select a member' });

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16, width: '100%' }}>
      <div style={{ display: 'flex', alignItems: 'flex-end', gap: 16, flexWrap: 'wrap' }}>
        <div style={{ flex: 1, minWidth: 260 }}>
          <div className="eyebrow">{q.data ? longDate(q.data.today) : ' '}</div>
          <h1 className="h1" style={{ marginTop: 2 }}>My Work</h1>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span className="eyebrow">View as</span>
          <Select aria-label="View as" options={opts} value={member} onChange={pick} style={{ width: 180 }} />
        </div>
      </div>
      {!member ? <div className="empty-row">Pick a member to view their work.</div> : q.isPending ? <Loading /> : q.isError ? <QueryError error={q.error} /> : (
        <MyWorkBody w={q.data} backlog={backlog} setBacklog={(b) => navigate({ search: (p) => ({ ...p, backlog: b }) })} />
      )}
    </div>
  );
}

function MyWorkBody({ w, backlog, setBacklog }: { w: Schemas['MyWork']; backlog: Backlog; setBacklog: (b: Backlog) => void }) {
  const focus: { label: string; icon: IconName; list: TaskCard[]; headBg: string; cntFg: string; hint: string }[] = [
    { label: 'Delay task', icon: 'alarm-clock', list: w.delay, headBg: 'var(--hl-red)', cntFg: 'var(--hl-red-fg)', hint: 'Past deadline' },
    { label: 'Today', icon: 'sun', list: w.due_today, headBg: 'var(--surface-tint)', cntFg: 'var(--text-heading)', hint: 'Deadline ' + dm(w.today) },
    { label: w.next_day === add(w.today, 1) ? 'Tomorrow' : 'Next working day', icon: 'sunrise', list: w.due_next, headBg: 'var(--surface-hover)',
      cntFg: 'var(--text-heading)', hint: 'Deadline ' + dm(w.next_day) },
  ];
  const groupLabel = (g: Schemas['BacklogGroup']) => {
    if (g.kind === 'overdue') return { label: 'Overdue', dot: 'var(--hl-red-fg)' };
    if (g.kind === 'unplanned') return { label: 'No planned end', dot: 'var(--st-nys)' };
    const dot = g.current ? 'var(--st-wip)' : 'var(--st-nys)';
    if (g.kind === 'month') return { label: `Month ${g.start!.slice(5)}/${g.start!.slice(0, 4)}`, dot };
    return { label: `${g.current ? 'This week · ' : 'Week '}${dm(g.start)} – ${dm(add(g.start!, 6))}`, dot };
  };

  return (
    <>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(min(100%,300px),1fr))', gap: 16, alignItems: 'start' }}>
        {focus.map((g) => (
          <section key={g.label} className="card card-clip" aria-label={g.label}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '10px 14px', background: g.headBg }}>
              <Icon name={g.icon} size={14} />
              <span className="title">{g.label}</span>
              <span className="count" style={{ background: 'var(--surface-card)', color: g.cntFg }}>{g.list.length}</span>
              <span className="spacer" />
              <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>{g.hint}</span>
            </div>
            <div style={{ maxHeight: 420, overflowY: 'auto' }}>
              {g.list.map((c) => <FocusItem key={c.key} c={c} />)}
            </div>
            {!g.list.length && <div className="empty-row">No tasks.</div>}
          </section>
        ))}
      </div>

      <section className="card card-clip" aria-label="Your backlog">
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap', padding: '10px 14px' }}>
          <Icon name="list-todo" size={14} />
          <span className="title">Your backlog</span>
          <span className="eyebrow">{w.open_count} open tasks · Remain {hrs(w.remaining_h)}</span>
          <span className="spacer" />
          <Seg outline items={[['week', 'By week'], ['month', 'By month']]} value={backlog} onPick={setBacklog} />
        </div>
        <div style={{ overflowX: 'auto' }}>
          {w.backlog.map((g) => {
            const { label, dot } = groupLabel(g);
            return (
              <div key={g.kind + (g.start ?? '')}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '8px 14px', minWidth: 620, borderTop: '1px solid var(--border-subtle)', background: 'var(--surface-hover)' }}>
                  <span className="dot" style={{ width: 8, height: 8, background: dot }} />
                  <span className="title">{label}</span>
                  <span className="count" style={{ background: 'var(--surface-card)', color: 'var(--text-heading)' }}>{g.items.length}</span>
                  <span className="spacer" />
                  <span className="eyebrow">Remain <b className="mono heading">{hrs(g.remaining_h)}</b></span>
                </div>
                {g.items.map((c) => <ListRow key={c.key} c={c} cols="76px minmax(180px,1fr) 92px 76px 150px" minW={620} />)}
              </div>
            );
          })}
          {!w.backlog.length && <div className="empty-row">Backlog is empty.</div>}
        </div>
      </section>

      {w.related_scope !== 'none' && (
        <section className="card card-clip" aria-label="Related tasks">
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap', padding: '10px 14px' }}>
            <Icon name="link" size={14} />
            <span className="title">Related tasks</span>
            <span className="count" style={{ background: 'var(--surface-hover)', color: 'var(--text-heading)' }}>{w.related.length}</span>
            <span className="eyebrow">{w.related_scope === 'bal'
              ? 'Create, JM Review and Fix comments not with you, of functions you are BAL of'
              : 'BAL Review, JM Review and Fix comments with the BAL, of functions you are BA of'}</span>
          </div>
          <div style={{ overflowX: 'auto' }}>
            {w.related.map((c) => <ListRow key={c.key} c={c} withAssignee cols="76px minmax(180px,1fr) 96px 92px 76px 150px" minW={720} />)}
            {!w.related.length && <div className="empty-row">No open related tasks.</div>}
          </div>
        </section>
      )}
    </>
  );
}

function useRowProps(key: string) {
  const open = useOpenTicket();
  const current = useTicketKey();
  return {
    className: 'hv-bg', role: 'button', tabIndex: 0, onClick: () => open(key),
    onKeyDown: (e: React.KeyboardEvent) => e.key === 'Enter' && open(key),
    bg: current === key ? 'var(--surface-tint)' : 'transparent',
  };
}

function Pill({ c }: { c: TaskCard }) {
  const st = statusOf(c.status);
  return <StatusPill bg={`var(--st-${st.v}-bg)`} dot={`var(--st-${st.v})`}>{st.label}</StatusPill>;
}

function Effort({ c }: { c: TaskCard }) {
  return <EffortTrio est={hrs(c.estimate_h)} spent={hrs(c.logged_h)} rem={hrs(c.remaining_h)} spentFg={c.over ? 'var(--hl-red-fg)' : 'var(--text-heading)'} />;
}

function FocusItem({ c }: { c: TaskCard }) {
  const { bg, ...p } = useRowProps(c.key);
  return (
    <div {...p} style={{ display: 'flex', flexDirection: 'column', gap: 6, padding: '10px 14px', borderTop: '1px solid var(--border-subtle)', cursor: 'pointer', background: bg }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <span className="key">{c.key}</span>
        <Pill c={c} />
        <span className="spacer" />
        <span style={{ font: '600 12px/1 var(--font-mono)', color: c.late ? 'var(--hl-red-fg)' : 'var(--text-heading)' }}>{dueLabel(c)}</span>
      </div>
      <div style={{ minWidth: 0 }}>
        <div className="ellipsis" style={{ fontWeight: 600, color: 'var(--text-heading)' }}>{taskTitle(c)}</div>
        <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>{taskSub(c)}</div>
      </div>
      <Effort c={c} />
    </div>
  );
}

function ListRow({ c, cols, minW, withAssignee }: { c: TaskCard; cols: string; minW: number; withAssignee?: boolean }) {
  const { bg, ...p } = useRowProps(c.key);
  const members = useMemberMap();
  const a = av(members[c.assignee_id ?? ''], c.assignee_id ?? '');
  return (
    <div {...p} style={{ display: 'grid', gridTemplateColumns: cols, alignItems: 'center', gap: 10, padding: '8px 14px', minWidth: minW, borderTop: '1px solid var(--border-subtle)', cursor: 'pointer', background: bg }}>
      <span className="key">{c.key}</span>
      <div style={{ minWidth: 0 }}>
        <div className="ellipsis" style={{ fontWeight: 600, color: 'var(--text-heading)' }}>{taskTitle(c)}</div>
        <div className="ellipsis" style={{ fontSize: 11, color: 'var(--text-muted)' }}>{taskSub(c)}</div>
      </div>
      {withAssignee && (
        <span style={{ display: 'flex', alignItems: 'center', gap: 6, minWidth: 0 }}>
          <Avatar a={a} size={20} /><span className="ellipsis">{a.name}</span>
        </span>
      )}
      <Pill c={c} />
      <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
        <span className="mini-label">Deadline</span>
        <span style={{ font: '600 12px/1.2 var(--font-mono)', color: c.late ? 'var(--hl-red-fg)' : 'var(--text-heading)' }}>{dueLabel(c)}</span>
      </div>
      <Effort c={c} />
    </div>
  );
}
