import type { UseQueryResult } from '@tanstack/react-query';
import { useMemberMap } from '../../../shared/api/queries';
import type { Schemas } from '../../../shared/api/types';
import { IconButton } from '../../../shared/ds';
import { dm, f1 } from '../../../shared/format/dates';
import { STEPS, statusOf } from '../../../shared/format/labels';
import { Avatar, av } from '../../../shared/ui/bits';
import { Loading, QueryError } from '../../../shared/ui/states';

/** Planned dates of a ticket as Planning shows them: where a pending move puts it, else Jira's. */
export type PlanOf = (c: Schemas['TaskCard']) => { start: string | null; end: string | null; pend: boolean };

/** To do today: not resolved or closed, and its plan covers today. */
export const dueToday = (c: Schemas['TaskCard'], plan: ReturnType<PlanOf>, today?: string) =>
  !!today && c.status !== 'R' && c.status !== 'C' && !!plan.start && !!plan.end && plan.start <= today && today <= plan.end;

/**
 * Side panel of one function: its five steps, with the ones to do today highlighted.
 *
 * @param q       the function of {@code subtask} (Planning loads it to dim the other blocks too)
 * @param subtask the clicked sub-task
 */
export function FunctionPanel({ q, subtask, today, planOf, close, openTicket }: {
  q: UseQueryResult<Schemas['FunctionSteps']>; subtask: string; today?: string; planOf: PlanOf; close: () => void; openTicket: (key: string) => void;
}) {
  return (
    <>
      <div onClick={close} style={{ position: 'fixed', inset: 0, zIndex: 40, background: 'rgba(16,18,20,0.2)' }} />
      <aside aria-label="Function detail" style={{
        position: 'fixed', top: 8, bottom: 8, right: 8, width: 440, maxWidth: 'calc(100vw - 16px)', zIndex: 41, borderRadius: 16, overflow: 'hidden',
        background: 'var(--surface-card)', border: '1px solid var(--border-subtle)', boxShadow: 'var(--shadow-overlay)', display: 'flex', flexDirection: 'column',
      }}>
        {q.isPending ? <><Head sub={subtask} close={close} /><Loading /></>
          : q.isError ? <><Head sub={subtask} close={close} /><div style={{ padding: 16 }}><QueryError error={q.error} /></div></>
            : <Body f={q.data} today={today} planOf={planOf} close={close} openTicket={openTicket} />}
      </aside>
    </>
  );
}

function Head({ sub, name, span, close }: { sub: string; name?: string; span?: string; close: () => void }) {
  return (
    <div style={{ display: 'flex', alignItems: 'flex-start', gap: 10, padding: '18px 20px 14px', borderBottom: '1px solid var(--border-subtle)' }}>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 12, color: 'var(--text-muted)' }}>{sub}</div>
        {name && <div style={{ font: '700 18px/1.3 var(--font-display)', color: 'var(--text-heading)', textWrap: 'pretty' }}>{name}</div>}
        {span && <div style={{ fontSize: 12, color: 'var(--text-muted)', marginTop: 2 }}>{span}</div>}
      </div>
      <IconButton icon="x" label="Close" appearance="subtle" size="sm" onClick={close} />
    </div>
  );
}

function Body({ f, today, planOf, close, openTicket }: {
  f: Schemas['FunctionSteps']; today?: string; planOf: PlanOf; close: () => void; openTicket: (key: string) => void;
}) {
  const members = useMemberMap();
  const steps = STEPS.map((s, i) => {
    const c = f.steps[i] ?? null;
    return { s, c, plan: c ? planOf(c) : null };
  });
  const cards = steps.flatMap((x) => (x.c ? [x.c] : []));
  const starts = steps.flatMap((x) => (x.plan?.start ? [x.plan.start] : [])).sort();
  const ends = steps.flatMap((x) => (x.plan?.end ? [x.plan.end] : [])).sort();
  const nToday = steps.filter((x) => x.c && x.plan && dueToday(x.c, x.plan, today)).length;
  const stats = [
    ['Steps closed', `${cards.filter((c) => c.status === 'C').length} / ${STEPS.length}`],
    ['Effort', `${f1(cards.reduce((a, c) => a + c.logged_h, 0))} / ${f1(cards.reduce((a, c) => a + c.estimate_h, 0))}h`],
    ['To do today', String(nToday)],
  ];
  return (
    <>
      <Head sub={`${f.function_id} · ${f.phase} · ${f.epic_key}`} name={f.name} close={close}
        span={starts.length ? `Plan ${dm(starts[0])} – ${dm(ends[ends.length - 1])}` : 'No plan dates'} />
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3,minmax(0,1fr))', borderBottom: '1px solid var(--border-subtle)' }}>
        {stats.map(([label, value]) => (
          <div key={label} style={{ padding: '10px 20px' }}>
            <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>{label}</div>
            <div style={{ font: '700 15px/1.3 var(--font-text)', color: 'var(--text-heading)' }}>{value}</div>
          </div>
        ))}
      </div>
      <div style={{ padding: '14px 20px 6px', font: '600 12px/1 var(--font-text)', color: 'var(--text-muted)' }}>Steps</div>
      <ol aria-label="Steps" style={{ flex: 1, overflow: 'auto', margin: 0, padding: '4px 12px 20px', listStyle: 'none' }}>
        {steps.map(({ s, c, plan }, i) => {
          const rail = (
            <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', paddingTop: 12 }}>
              <span style={{ width: 22, height: 22, borderRadius: '50%', background: c ? s.color : 'var(--border-strong)', color: '#fff', font: '700 11px/22px var(--font-text)', textAlign: 'center', flex: 'none' }}>{i + 1}</span>
              {i < steps.length - 1 && <span style={{ flex: 1, width: 2, background: 'var(--border-subtle)', marginTop: 4 }} />}
            </div>
          );
          if (!c || !plan) {
            return (
              <li key={s.k} style={{ display: 'grid', gridTemplateColumns: '24px minmax(0,1fr)', gap: 10 }}>
                {rail}
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, margin: '4px 0', padding: '10px 12px', borderRadius: 8, border: '1px dashed var(--border-strong)' }}>
                  <span style={{ fontWeight: 700, color: 'var(--text-muted)' }}>{s.label}</span>
                  <span className="spacer" />
                  <span style={{ fontSize: 12, color: 'var(--text-muted)' }}>Not in Jira</span>
                </div>
              </li>
            );
          }
          const now = dueToday(c, plan, today);
          const st = statusOf(c.status);
          const w = av(members[c.worker_id ?? ''], c.worker_id ?? '–');
          const actEnd = c.actual_end ?? (c.actual_start && today && today > c.actual_start ? today : c.actual_start);
          const actLate = !!(actEnd && plan.end && actEnd > plan.end);
          return (
            <li key={s.k} style={{ display: 'grid', gridTemplateColumns: '24px minmax(0,1fr)', gap: 10 }}>
              {rail}
              <button className="hv-bd" onClick={() => openTicket(c.key)} aria-label={`${c.key} ${s.label}${now ? ' · to do today' : ''}`} style={{
                display: 'flex', flexDirection: 'column', gap: 6, margin: '4px 0', padding: now ? '9px 11px' : '10px 12px', borderRadius: 8,
                border: now ? '2px solid var(--border-focus)' : '1px solid var(--border-subtle)', background: now ? 'var(--surface-tint)' : 'var(--surface-card)',
                textAlign: 'left', font: 'inherit', color: 'inherit', cursor: 'pointer',
              }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                  <span style={{ fontWeight: 700, color: 'var(--text-heading)' }}>{s.label}</span>
                  {now && <span style={{ padding: '1px 6px', borderRadius: 3, background: 'var(--border-focus)', color: 'var(--surface-card)', font: '700 11px/1.4 var(--font-text)' }}>Today</span>}
                  <span className="spacer" />
                  <span style={{ padding: '2px 7px', borderRadius: 3, background: `var(--st-${st.v}-bg)`, color: 'var(--text-heading)', font: '600 11px/1.4 var(--font-text)' }}>{st.label}</span>
                </div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 12, color: 'var(--text-muted)' }}>
                  <span className="key">{c.key}</span>
                  <span style={{ display: 'flex', alignItems: 'center', gap: 5 }}><Avatar a={w} size={16} />{w.name}</span>
                  <span className="spacer" />
                  <span style={{ fontFamily: 'var(--font-mono)' }}>{f1(c.logged_h)} / {f1(c.estimate_h)}h</span>
                </div>
                <div style={{ display: 'grid', gridTemplateColumns: 'auto minmax(0,1fr)', gap: '2px 10px', fontSize: 12 }}>
                  <span className="muted">Plan</span><span className="heading">{plan.start && plan.end ? `${dm(plan.start)} – ${dm(plan.end)}` : 'No plan dates'}</span>
                  <span className="muted">Actual</span>
                  <span style={{ color: actLate ? 'var(--hl-red-fg)' : 'var(--text-muted)' }}>
                    {c.actual_start ? `${dm(c.actual_start)} – ${c.actual_end ? dm(c.actual_end) : 'in progress'}` : 'Not started'}
                  </span>
                </div>
                {plan.pend && <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--text-heading)' }}>Pending push to Jira</span>}
              </button>
            </li>
          );
        })}
      </ol>
    </>
  );
}
