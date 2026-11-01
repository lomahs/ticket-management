import { useQuery } from '@tanstack/react-query';
import type { CSSProperties } from 'react';
import { errorMessage } from '../../../shared/api/errors';
import { useCan, useMemberMap, useMembers } from '../../../shared/api/queries';
import type { Schemas } from '../../../shared/api/types';
import { Button, IconButton } from '../../../shared/ds';
import { dm, f1, hrs } from '../../../shared/format/dates';
import { useToast } from '../../../shared/state/toast';
import { PageHead } from '../../../shared/ui/bits';
import { Loading, QueryError } from '../../../shared/ui/states';
import { PlanningTabs, WithAnken } from '../components/PlanningTabs';
import { detailPlanQuery, usePushPlan, usePushPreview, useSaveDraft, type DetailPlanView, type DraftIn } from './api';

/** Step 3 of planning: who does each row, where its sub-tasks go, the dates that follow, then the push to Jira. */
export function DetailPlanPage() {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16, width: '100%' }}>
      <PageHead eyebrow="Planning · step 3" title="Detail plan" />
      <PlanningTabs at="/planning/detail" />
      <WithAnken>{(epic) => <Detail key={epic} epic={epic} />}</WithAnken>
    </div>
  );
}

function Detail({ epic }: { epic: string }) {
  const q = useQuery(detailPlanQuery(epic));
  if (q.isPending) return <Loading />;
  if (q.isError) return <QueryError error={q.error} />;
  return <Plan epic={epic} v={q.data} />;
}

const SEL: CSSProperties = { height: 30, boxSizing: 'border-box', padding: '0 6px', border: '1px solid var(--border-strong)', borderRadius: 6, background: 'var(--surface-card)', color: 'var(--text-heading)', font: '500 12px/1 var(--font-text)', maxWidth: '100%' };
const COLS = '56px minmax(160px,2fr) minmax(220px,3fr) 120px 70px';
const days = (n: number) => `${n} day${Math.abs(n) === 1 ? '' : 's'}`;
type Row = Schemas['EstimationRow'];
type Target = Schemas['TaskTarget'];

function Plan({ epic, v }: { epic: string; v: DetailPlanView }) {
  const admin = useCan('planning', 'write');
  const toast = useToast();
  const members = useMembers().data ?? [];
  const names = useMemberMap();
  const name = (id: string) => names[id]?.name ?? id;
  const save = useSaveDraft(epic);
  const preview = usePushPreview(epic);
  const push = usePushPlan(epic);
  const open = v.rows.filter((r) => !r.pushed_at);
  const draft = (p: Partial<DraftIn>) => save.mutate({
    order: open.map((r) => r.id), targets: v.targets,
    assigns: open.map((r) => ({ row_id: r.id, ba_id: r.ba_id ?? null, bal_id: r.bal_id ?? null, jm_id: r.jm_id ?? null })), ...p,
  }, { onError: (e) => toast(errorMessage(e), true) });
  const assign = (r: Row, p: Partial<Row>) => draft({
    assigns: open.map((x) => { const y = x.id === r.id ? { ...x, ...p } : x; return { row_id: y.id, ba_id: y.ba_id ?? null, bal_id: y.bal_id ?? null, jm_id: y.jm_id ?? null }; }),
  });
  const move = (i: number, d: -1 | 1) => {
    const ids = open.map((r) => r.id);
    [ids[i], ids[i + d]] = [ids[i + d], ids[i]];
    draft({ order: ids });
  };
  const subphases = [...new Map(open.map((r) => [`${r.phase}/${r.subphase}`, { phase: r.phase, subphase: r.subphase }])).values()];
  const targetOf = (s: { phase: string; subphase: string }) => v.targets.find((t) => t.phase === s.phase && t.subphase === s.subphase);
  const setTarget = (s: { phase: string; subphase: string }, t: Partial<Target>) => {
    const cur = targetOf(s) ?? { phase: s.phase, subphase: s.subphase, existing_key: null, new_name: null, new_assignee_id: null };
    draft({ targets: [...v.targets.filter((x) => x !== targetOf(s)), { ...cur, ...t }] });
  };
  const blocked = v.problems.length > 0 || v.over_budget.length > 0 || v.over_day.length > 0 || v.plan.length === 0;
  const pick = (label: string, value: string | null | undefined, on: (id: string | null) => void) => (
    <select aria-label={label} disabled={!admin} value={value ?? ''} onChange={(e) => on(e.target.value || null)} style={SEL}>
      <option value="">–</option>
      {members.map((m) => <option key={m.id} value={m.id}>{m.name}</option>)}
    </select>
  );
  return (
    <>
      {(v.problems.length > 0 || v.over_budget.length > 0 || v.over_day.length > 0) && (
        <section className="card" aria-label="Before the push" role="alert" style={{ padding: '12px 16px', display: 'flex', flexDirection: 'column', gap: 4 }}>
          {v.problems.map((p) => <span key={p} style={{ color: 'var(--hl-red-fg)', fontSize: 13 }}>{p}</span>)}
          {v.over_budget.map((o) => <span key={o.member_id + o.phase} style={{ color: 'var(--hl-red-fg)', fontSize: 13 }}>{`${name(o.member_id)} is over budget in ${o.phase}: ${hrs(o.planned_h)} of ${hrs(o.budget_h)}`}</span>)}
          {v.over_day.map((o) => <span key={o.member_id + o.date} style={{ color: 'var(--hl-red-fg)', fontSize: 13 }}>{`${name(o.member_id)} has ${hrs(o.planned_h)} planned on ${dm(o.date)}, above the ${hrs(o.allocation_h)} allocation`}</span>)}
        </section>
      )}
      {v.checks.length > 0 && (
        <section className="card" aria-label="Against the master schedule" style={{ padding: '12px 16px', display: 'flex', gap: 16, flexWrap: 'wrap', fontSize: 13 }}>
          {v.checks.map((c) => (
            <span key={c.phase} style={{ fontWeight: 600, color: c.days_late > 0 ? 'var(--hl-red-fg)' : 'var(--text-heading)' }}>
              {c.plan_end ? `${c.phase} ends ${dm(c.plan_end)} · master ${dm(c.master_end)} · ${c.days_late > 0 ? days(c.days_late) + ' late' : c.days_late < 0 ? days(-c.days_late) + ' early' : 'on time'}`
                : `${c.phase}: nothing planned · master ends ${dm(c.master_end)}`}
            </span>
          ))}
        </section>
      )}

      <section className="card card-clip" aria-label="Detail plan rows">
        <div style={{ padding: '12px 16px', borderBottom: '1px solid var(--border-subtle)', display: 'flex', alignItems: 'center', gap: 10 }}>
          <span className="title">Rows</span>
          <span className="eyebrow">Scheduled in this order from the phase's Start; each member's day is filled up to their allocation.</span>
        </div>
        {open.length === 0 && <div style={{ padding: 16, color: 'var(--text-muted)' }}>Every estimation row is in Jira.</div>}
        <div style={{ overflowX: 'auto' }}>
          <div style={{ minWidth: 760 }}>
            {open.map((r, i) => {
              const steps = v.plan.find((p) => p.row_id === r.id)?.steps ?? [];
              const span = steps.length ? `${dm(steps[0].start)} – ${dm(steps[steps.length - 1].end)}` : '–';
              return (
                <div key={r.id} style={{ display: 'grid', gridTemplateColumns: COLS, gap: 10, alignItems: 'center', padding: '8px 16px', borderTop: i ? '1px solid var(--border-subtle)' : undefined }}>
                  <div style={{ display: 'flex' }}>
                    <IconButton icon="chevron-up" label={`Move ${r.function_name} up`} size="sm" appearance="subtle" disabled={!admin || i === 0} onClick={() => move(i, -1)} />
                    <IconButton icon="chevron-down" label={`Move ${r.function_name} down`} size="sm" appearance="subtle" disabled={!admin || i === open.length - 1} onClick={() => move(i, 1)} />
                  </div>
                  <div style={{ minWidth: 0 }}>
                    <div className="heading ellipsis" style={{ fontWeight: 600 }}>{r.function_name}</div>
                    <div className="eyebrow ellipsis">{`${r.phase} / ${r.subphase} · ${r.steps ? `${r.steps} steps · Create ${hrs(r.effort_h)}` : hrs(r.effort_h)}`}</div>
                  </div>
                  <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                    {r.steps == null ? pick(`Member, ${r.function_name}`, r.ba_id, (id) => assign(r, { ba_id: id })) : (
                      <>
                        {pick(`BA, ${r.function_name}`, r.ba_id, (id) => assign(r, { ba_id: id }))}
                        {pick(`BAL, ${r.function_name}`, r.bal_id, (id) => assign(r, { bal_id: id }))}
                        {r.steps === 5 && pick(`JM, ${r.function_name}`, r.jm_id, (id) => assign(r, { jm_id: id }))}
                      </>
                    )}
                  </div>
                  <span title={steps.map((s) => `${s.step ?? 'Ticket'} · ${name(s.member_id)} · ${f1(s.hours)}h · ${dm(s.start)} – ${dm(s.end)}`).join('\n')}>{span}</span>
                  <span className="muted">{steps.length ? hrs(steps.reduce((a, s) => a + s.hours, 0)) : ''}</span>
                </div>
              );
            })}
          </div>
        </div>
      </section>

      {subphases.length > 0 && (
        <section className="card card-clip" aria-label="Tasks in Jira">
          <div style={{ padding: '12px 16px', borderBottom: '1px solid var(--border-subtle)' }}><span className="title">Task of each subphase</span></div>
          {subphases.map((s) => {
            const t = targetOf(s);
            const label = `${s.phase} / ${s.subphase}`;
            return (
              <div key={label} style={{ display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap', padding: '8px 16px', borderTop: '1px solid var(--border-subtle)' }}>
                <b className="heading" style={{ minWidth: 200 }}>{label}</b>
                <select aria-label={`Task, ${label}`} disabled={!admin} value={t?.existing_key ?? (t?.new_name != null ? 'new' : '')} style={SEL}
                  onChange={(e) => setTarget(s, e.target.value === 'new' ? { existing_key: null, new_name: t?.new_name ?? s.subphase, new_assignee_id: t?.new_assignee_id ?? null }
                    : { existing_key: e.target.value || null, new_name: null, new_assignee_id: null })}>
                  <option value="">Choose…</option>
                  <option value="new">New Task</option>
                  {v.tasks.map((x) => <option key={x.key} value={x.key}>{`${x.key} ${x.summary}`}</option>)}
                </select>
                {t?.new_name != null && !t.existing_key && (
                  <>
                    <input aria-label={`New Task name, ${label}`} disabled={!admin} defaultValue={t.new_name} style={{ ...SEL, minWidth: 220 }}
                      onBlur={(e) => e.target.value !== t.new_name && setTarget(s, { new_name: e.target.value })} />
                    {pick(`New Task assignee, ${label}`, t.new_assignee_id, (id) => setTarget(s, { new_assignee_id: id }))}
                  </>
                )}
              </div>
            );
          })}
        </section>
      )}

      {admin && (
        <div style={{ display: 'flex', gap: 8 }}>
          <Button disabled={blocked || preview.isPending} onClick={() => preview.mutate(undefined, { onError: (e) => toast(errorMessage(e), true) })}>Review push</Button>
        </div>
      )}
      {preview.data && (
        <section className="card card-clip" aria-label="Push review">
          <div style={{ padding: '12px 16px', borderBottom: '1px solid var(--border-subtle)', display: 'flex', alignItems: 'center', gap: 10 }}>
            <span className="title">{`${preview.data.creates.length} tickets to create`}</span>
            <span className="spacer" />
            <Button appearance="subtle" size="sm" onClick={() => preview.reset()}>Cancel</Button>
            <Button size="sm" iconBefore="upload" disabled={push.isPending} onClick={() => push.mutate(undefined, {
              onSuccess: (r) => { toast(`Created ${Object.keys(r.created).length} tickets in Jira`); preview.reset(); },
              onError: (e) => toast(errorMessage(e), true),
            })}>Push to Jira</Button>
          </div>
          {preview.data.creates.map((c) => (
            <div key={c.ref} style={{ display: 'flex', gap: 10, padding: '6px 16px', borderTop: '1px solid var(--border-subtle)', fontSize: 13, paddingLeft: c.subtask ? 32 : 16 }}>
              <span className="eyebrow" style={{ minWidth: 70 }}>{c.subtask ? 'Sub-task' : 'Task'}</span>
              <span className="heading">{c.summary}</span>
            </div>
          ))}
        </section>
      )}
    </>
  );
}
