import { useQuery } from '@tanstack/react-query';
import { getRouteApi } from '@tanstack/react-router';
import { useState, type CSSProperties } from 'react';
import { errorMessage } from '../../../shared/api/errors';
import { useCan, useMemberMap, useMembers } from '../../../shared/api/queries';
import type { Schemas } from '../../../shared/api/types';
import { Button } from '../../../shared/ds';
import { dm, hrs } from '../../../shared/format/dates';
import { useToast } from '../../../shared/state/toast';
import { PageHead } from '../../../shared/ui/bits';
import { Loading, QueryError } from '../../../shared/ui/states';
import { PlanningTabs, WithAnken } from '../components/PlanningTabs';
import { masterScheduleQuery } from '../schedule/api';
import { replanQuery, useApplyReplan, useReplanPreview } from './api';

const route = getRouteApi('/app/planning/replan');

/** Re-plan: new dates (and assignees) for every sub-task of a phase that is not Closed, reviewed, then written to Jira. */
export function ReplanPage() {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16, width: '100%' }}>
      <PageHead eyebrow="Planning" title="Re-plan" />
      <PlanningTabs at="/planning/replan" />
      <WithAnken>{(epic) => <Replan key={epic} epic={epic} />}</WithAnken>
    </div>
  );
}

const SEL: CSSProperties = { height: 30, boxSizing: 'border-box', padding: '0 6px', border: '1px solid var(--border-strong)', borderRadius: 6, background: 'var(--surface-card)', color: 'var(--text-heading)', font: '500 12px/1 var(--font-text)' };
const FIELDS: Record<string, string> = { planned_start: 'Planned Start', planned_end: 'Planned End', assignee: 'Assignee' };
const span = (a?: string | null, b?: string | null) => (a ? `${dm(a)} – ${dm(b)}` : 'no plan');

function Replan({ epic }: { epic: string }) {
  const { phase } = route.useSearch();
  const navigate = route.useNavigate();
  const schedule = useQuery(masterScheduleQuery(epic));
  const phases = schedule.data?.current?.phases.map((p) => p.phase) ?? [];
  return (
    <>
      <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}>
        <select aria-label="Phase" value={phase ?? ''} style={SEL} onChange={(e) => void navigate({ search: (p) => ({ ...p, phase: e.target.value || undefined }) })}>
          <option value="">Choose a phase…</option>
          {phases.map((p) => <option key={p} value={p}>{p}</option>)}
        </select>
        {schedule.data && !schedule.data.current && <span className="muted">Save a master schedule first.</span>}
      </div>
      {phase && <Items key={phase} epic={epic} phase={phase} />}
    </>
  );
}

function Items({ epic, phase }: { epic: string; phase: string }) {
  const admin = useCan('planning', 'write');
  const toast = useToast();
  const members = useMembers().data ?? [];
  const names = useMemberMap();
  const name = (id?: string | null) => (id ? names[id]?.name ?? id : '–');
  const [assignees, setAssignees] = useState<Record<string, string>>({});
  const body = { phase, assignees };
  const q = useQuery(replanQuery(epic, body));
  const preview = useReplanPreview(epic);
  const apply = useApplyReplan(epic);
  if (q.isPending) return <Loading />;
  if (q.isError) return <QueryError error={q.error} />;
  const v: Schemas['ReplanView'] = q.data;
  const moved = v.items.filter((i) => i.from_member !== i.to_member || i.from_start !== i.to_start || i.from_end !== i.to_end);
  const blocked = v.problems.length > 0 || v.over_budget.length > 0 || v.over_day.length > 0 || moved.length === 0;
  return (
    <>
      {(v.problems.length > 0 || v.over_budget.length > 0 || v.over_day.length > 0) && (
        <section className="card" role="alert" aria-label="Before the push" style={{ padding: '12px 16px', display: 'flex', flexDirection: 'column', gap: 4, color: 'var(--hl-red-fg)', fontSize: 13 }}>
          {v.problems.map((p) => <span key={p}>{p}</span>)}
          {v.over_budget.map((o) => <span key={o.member_id}>{`${name(o.member_id)} is over budget in ${o.phase}: ${hrs(o.planned_h)} of ${hrs(o.budget_h)}`}</span>)}
          {v.over_day.map((o) => <span key={o.member_id + o.date}>{`${name(o.member_id)} has ${hrs(o.planned_h)} planned on ${dm(o.date)}, above the ${hrs(o.allocation_h)} allocation`}</span>)}
        </section>
      )}
      {v.check?.plan_end && (
        <div style={{ fontWeight: 600, color: v.check.days_late > 0 ? 'var(--hl-red-fg)' : 'var(--text-heading)' }}>
          {`${phase} ends ${dm(v.check.plan_end)} · master ${dm(v.check.master_end)}`}
        </div>
      )}
      <section className="card card-clip" aria-label="Re-plan items">
        <div style={{ padding: '12px 16px', borderBottom: '1px solid var(--border-subtle)', display: 'flex', gap: 10, alignItems: 'center' }}>
          <span className="title">{`${v.items.length} sub-tasks · ${moved.length} move`}</span>
          <span className="eyebrow">Closed sub-tasks stay as they are; a started one keeps its Planned Start and its remaining hours are placed.</span>
        </div>
        {v.items.map((i) => (
          <div key={i.key} style={{ display: 'grid', gridTemplateColumns: '90px minmax(200px,2fr) 130px 70px minmax(200px,1fr)', gap: 10, alignItems: 'center', padding: '6px 16px', borderTop: '1px solid var(--border-subtle)', fontSize: 13 }}>
            <span style={{ font: '600 11px/1 var(--font-mono)', color: 'var(--text-link)' }}>{i.key}</span>
            <span className="heading ellipsis">{i.summary}{i.started ? ' · started' : ''}</span>
            <select aria-label={`Member, ${i.key}`} disabled={!admin} value={i.to_member} style={SEL}
              onChange={(e) => setAssignees((a) => ({ ...a, [i.key]: e.target.value }))}>
              {members.map((m) => <option key={m.id} value={m.id}>{m.name}</option>)}
            </select>
            <span className="muted">{hrs(i.hours)}</span>
            <span>{`${span(i.from_start, i.from_end)} → ${span(i.to_start, i.to_end)}`}</span>
          </div>
        ))}
      </section>
      {admin && (
        <div><Button disabled={blocked || preview.isPending} onClick={() => preview.mutate(body, { onError: (e) => toast(errorMessage(e), true) })}>Review</Button></div>
      )}
      {preview.data && (
        <section className="card card-clip" aria-label="Re-plan review">
          <div style={{ padding: '12px 16px', borderBottom: '1px solid var(--border-subtle)', display: 'flex', gap: 10, alignItems: 'center' }}>
            <span className="title">{`${preview.data.updates.length} sub-tasks change in Jira`}</span>
            <span className="spacer" />
            <Button size="sm" appearance="subtle" onClick={() => preview.reset()}>Cancel</Button>
            <Button size="sm" iconBefore="upload" disabled={apply.isPending} onClick={() => apply.mutate({
              ...body, updates: preview.data.updates.map((u) => ({ key: u.key, changes: u.changes.map((c) => ({ field: c.field, before: c.before, after: c.after })) })),
            }, {
              onSuccess: (r) => { toast(`Re-planned ${r.updated.length} sub-task${r.updated.length === 1 ? '' : 's'} in Jira`); preview.reset(); setAssignees({}); },
              onError: (e) => toast(errorMessage(e), true),
            })}>Push to Jira</Button>
          </div>
          {preview.data.updates.map((u) => (
            <div key={u.key} style={{ display: 'flex', gap: 10, flexWrap: 'wrap', padding: '6px 16px', borderTop: '1px solid var(--border-subtle)', fontSize: 13 }}>
              <span style={{ font: '600 11px/1 var(--font-mono)', color: 'var(--text-link)' }}>{u.key}</span>
              <span className="heading">{u.summary}</span>
              {u.changes.map((c) => (
                <span key={c.field} className="muted">{c.field === 'assignee' ? `Assignee ${name(c.before)} → ${name(c.after)}`
                  : `${FIELDS[c.field] ?? c.field} ${dm(c.before)} → ${dm(c.after)}`}</span>
              ))}
            </div>
          ))}
        </section>
      )}
    </>
  );
}
