import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { errorMessage } from '../../../shared/api/errors';
import { useAdminJiraWrite, useMemberMap } from '../../../shared/api/queries';
import type { Schemas, Status } from '../../../shared/api/types';
import { Button, Icon, IconButton, Select, TextField, type IconName } from '../../../shared/ds';
import { dm, f1, hrs } from '../../../shared/format/dates';
import { days, SCHEDULE_LABEL, STATUS, signedH } from '../../../shared/format/labels';
import { useToast } from '../../../shared/state/toast';
import { useOpenTicket, useTicketBack, useTicketKey, type TicketBack } from '../../../shared/state/ticket';
import { Avatar, av } from '../../../shared/ui/bits';
import { Loading, QueryError } from '../../../shared/ui/states';
import { ticketQuery, useApplyTicket, usePreviewTicket } from '../api';
import { ReviewDialog } from './ReviewDialog';

/** "Late start · 2 working days"; a missing plan reads "No planned dates". */
function dateLine(label: string, n: number) {
  if (label === 'no_plan') return 'No planned dates';
  return SCHEDULE_LABEL[label] + (n > 0 ? ` · ${days(n)}` : '');
}

type Warning = Schemas['TicketWarning'];
const WARN: Record<string, { icon: IconName; bg: string; fg: string; label: (w: Warning, name: (id: string | null) => string) => string }> = {
  late: { icon: 'alarm-clock', bg: 'var(--hl-red)', fg: 'var(--hl-red-fg)', label: (w) => `Late by ${w.value} days` },
  over: { icon: 'trending-up', bg: 'var(--hl-amber)', fg: 'var(--hl-amber-fg)', label: (w) => `Over estimate ${f1(w.value)}h` },
  rounds: { icon: 'refresh-cw', bg: 'var(--hl-amber)', fg: 'var(--hl-amber-fg)', label: (w) => `${w.value} round` },
  invalid_worklog: { icon: 'triangle-alert', bg: 'var(--hl-amber)', fg: 'var(--hl-amber-fg)',
    label: (w, name) => `${name(w.member_id)} logged ${f1(w.value)}h on ${dm(w.date)}: not a role that works on this step` },
};

/** Members to pick, with the current value kept even when it is not a member (a Jira account id). */
function people(members: Record<string, Schemas['Member']>, current: string) {
  const opts = Object.values(members).map((m) => ({ value: m.id, label: `${m.name}${m.role ? ` (${m.role})` : ''}` }));
  return current && !members[current] ? [{ value: current, label: current }, ...opts] : current ? opts : [{ value: '', label: '–' }, ...opts];
}

export function TicketPanel() {
  const key = useTicketKey();
  const open = useOpenTicket();
  const back = useTicketBack();
  if (!key) return null;
  return (
    <aside aria-label={`Ticket ${key}`} style={{
      position: 'fixed', top: 8, bottom: 8, right: 8, width: 440, maxWidth: 'calc(100vw - 16px)', zIndex: 30, borderRadius: 16, overflow: 'hidden',
      background: 'var(--surface-card)', border: '1px solid var(--border-subtle)', boxShadow: 'var(--shadow-overlay)', display: 'flex', flexDirection: 'column',
    }}>
      <TicketBody key={key} ticketKey={key} back={back} close={back ? back.close : () => open(null)} />
    </aside>
  );
}

/** The fields the panel edits, as the API names them; values are kept as the inputs hold them. */
type DraftField = 'status' | 'assignee_id' | 'reporter_id' | 'planned_start' | 'planned_end' | 'estimate_h';
type Draft = Partial<Record<DraftField, string>>;

function TicketBody({ ticketKey, back, close }: { ticketKey: string; back: TicketBack | null; close: () => void }) {
  const q = useQuery(ticketQuery(ticketKey));
  const members = useMemberMap();
  const { ok: canWrite, off } = useAdminJiraWrite();
  const preview = usePreviewTicket(ticketKey);
  const apply = useApplyTicket(ticketKey);
  const toast = useToast();
  // Edits stay here until they are reviewed and applied; nothing reaches Jira before.
  const [draft, setDraft] = useState<Draft>({});
  const [review, setReview] = useState<Schemas['TicketChangePreview'] | null>(null);

  const header = (phase: string) => (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: back ? '10px 12px 10px 10px' : '10px 12px 10px 18px', borderBottom: '1px solid var(--border-subtle)' }}>
      {back && <IconButton icon="arrow-left" label={back.label} size="sm" onClick={back.back} />}
      <span className="key">{ticketKey}</span>
      <span style={{ fontSize: 12, color: 'var(--text-muted)' }}>{phase}</span>
      <span className="spacer" />
      <IconButton icon="x" label="Close" size="sm" onClick={close} />
    </div>
  );
  if (q.isPending) return <>{header('')}<Loading /></>;
  if (q.isError) return <>{header('')}<div style={{ padding: 16 }}><QueryError error={q.error} /></div></>;

  const t = q.data;
  const c = t.card;
  const name = (id?: string | null) => (id ? members[id]?.name ?? id : '–');
  const saved: Record<DraftField, string> = {
    status: c.status, assignee_id: c.assignee_id ?? '', reporter_id: t.reporter_id ?? '', planned_start: c.planned_start ?? '',
    planned_end: c.planned_end ?? '', estimate_h: String(c.estimate_h),
  };
  const value = (f: DraftField) => draft[f] ?? saved[f];
  const edit = (f: DraftField) => (v: string) => setDraft((d) => {
    const next = { ...d };
    if (v === saved[f] || v === '') delete next[f];
    else next[f] = v;
    return next;
  });
  const edited = Object.keys(draft) as DraftField[];
  const FIELD_LABEL: Record<DraftField, string> = {
    status: 'Status', assignee_id: 'Assignee', reporter_id: 'Reporter', planned_start: 'Planned Start', planned_end: 'Planned End',
    estimate_h: 'Original estimate',
  };
  const openReview = () => preview.mutate({
    status: draft.status as Status | undefined, assignee_id: draft.assignee_id, reporter_id: draft.reporter_id,
    planned_start: draft.planned_start, planned_end: draft.planned_end, estimate_h: draft.estimate_h != null ? Number(draft.estimate_h) : undefined,
  }, {
    onSuccess: (r) => {
      if (r.changes.length) setReview(r);
      else {
        setDraft({});
        toast('Jira already has these values; nothing to apply');
      }
    },
    onError: (e) => toast(errorMessage(e), true),
  });
  const applyReview = () => review && apply.mutate(review.changes, {
    onSuccess: () => {
      toast(`Written to Jira · ${c.key} · ${review.changes.length} field${review.changes.length === 1 ? '' : 's'}`);
      setDraft({});
      setReview(null);
    },
    onError: (e) => {
      // A 409 means Jira changed after the review and nothing was written; the draft stays for a new review.
      toast(errorMessage(e), true);
      setReview(null);
    },
  });
  const tot = Math.max(c.estimate_h, c.logged_h) || 1;
  const inn = Math.min(c.logged_h, c.estimate_h);
  const ov = Math.max(0, c.logged_h - c.estimate_h);
  const sc = c.schedule;
  const effortLine = sc.effort === 'no_plan' ? 'No planned dates' : sc.effort === 'no_estimate' ? 'No estimate'
    : `${SCHEDULE_LABEL[sc.effort]} · plan to date ${f1(sc.plan_to_date_h)}h · actual ${f1(sc.actual_to_date_h)}h · ${signedH(sc.diff_h)}`;
  const offTitle = off;
  const disabled = !canWrite || preview.isPending || apply.isPending;

  return (
    <>
      {header(c.phase)}
      <div style={{ flex: 1, overflow: 'auto', padding: '16px 18px', display: 'flex', flexDirection: 'column', gap: 18 }}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          <h2 style={{ margin: 0, font: '700 16px/1.35 var(--font-display)', color: 'var(--text-heading)' }}>
            {t.jira_url ? (
              <a href={t.jira_url} target="_blank" rel="noopener noreferrer" title="Open in Jira" className="hv-underline" style={{ color: 'inherit', textDecoration: 'none' }}>
                {c.summary}
                <Icon name="external-link" size={13} style={{ marginLeft: 6, verticalAlign: '-1px', color: 'var(--text-link)' }} />
              </a>
            ) : c.summary}
          </h2>
          {t.warnings.length > 0 && (
            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
              {t.warnings.map((w, i) => {
                const s = WARN[w.kind];
                return s ? (
                  <span key={i} style={{ display: 'inline-flex', alignItems: 'center', gap: 5, font: '600 11px/22px var(--font-text)', padding: '0 8px', borderRadius: 6, background: s.bg, color: s.fg }}>
                    <Icon name={s.icon} size={12} />{s.label(w, (id) => members[id ?? '']?.name ?? id ?? '–')}
                  </span>
                ) : null;
              })}
            </div>
          )}
        </div>
        <div title={offTitle} style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
          <Select label="Status" disabled={disabled} options={(Object.keys(STATUS) as Status[]).map((k) => ({ value: k, label: STATUS[k].label }))}
            value={value('status')} onChange={edit('status')} />
          <Select label="Assignee" disabled={disabled} options={people(members, saved.assignee_id)}
            value={value('assignee_id')} onChange={edit('assignee_id')} />
          <TextField label="Planned Start" type="date" disabled={disabled} value={value('planned_start')} onChange={edit('planned_start')} />
          <TextField label="Planned End" type="date" disabled={disabled} value={value('planned_end')} onChange={edit('planned_end')} />
          <Select label="Reporter" disabled={disabled} options={people(members, saved.reporter_id)}
            value={value('reporter_id')} onChange={edit('reporter_id')} />
          <TextField label="Original estimate (h)" type="number" disabled={disabled} value={value('estimate_h')} onChange={edit('estimate_h')} />
        </div>
        {!canWrite && (
          <div style={{ display: 'flex', gap: 8, alignItems: 'flex-start', padding: '10px 12px', borderRadius: 8, background: 'var(--surface-hover)', fontSize: 12 }}>
            <Icon name="lock" size={14} style={{ flex: 'none', marginTop: 1 }} />
            <span>{off}. Change it in Jira; it syncs back here.</span>
          </div>
        )}
        {edited.length > 0 && (
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap', padding: '10px 12px', borderRadius: 8, background: 'var(--surface-tint)' }}>
            <span style={{ flex: 1, minWidth: 160, fontSize: 12, fontWeight: 600, color: 'var(--text-heading)' }}>
              {edited.length} change{edited.length === 1 ? '' : 's'} not sent · {edited.map((f) => FIELD_LABEL[f]).join(', ')}
            </span>
            <Button appearance="subtle" size="sm" disabled={preview.isPending} onClick={() => setDraft({})}>Discard</Button>
            <Button size="sm" iconBefore="eye" disabled={preview.isPending} onClick={openReview}>{preview.isPending ? 'Reading Jira…' : 'Review & apply'}</Button>
          </div>
        )}
        {review && <ReviewDialog review={review} name={name} applying={apply.isPending} apply={applyReview} close={() => setReview(null)} />}
        <div>
          <div className="title" style={{ marginBottom: 8 }}>Schedule</div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12, color: 'var(--text-body)' }}>
            <div>{`Start: ${dateLine(sc.start, sc.start_days)}`}</div>
            <div>{`Finish: ${dateLine(sc.finish, sc.finish_days)}`}</div>
            <div>{`Effort: ${effortLine}`}</div>
          </div>
        </div>
        <div>
          <div style={{ display: 'flex', alignItems: 'baseline', gap: 8 }}>
            <span className="title">Effort</span>
            <span className="spacer" />
            <span style={{ font: '600 13px/1 var(--font-mono)', color: c.over ? 'var(--hl-red-fg)' : 'var(--text-heading)' }}>{f1(c.logged_h)} / {hrs(c.estimate_h)}</span>
          </div>
          <div style={{ display: 'flex', gap: 2, height: 8, borderRadius: 99, background: 'var(--border-subtle)', marginTop: 8, overflow: 'hidden' }}>
            <div style={{ height: 8, width: (inn / tot) * 100 + '%', background: 'var(--st-wip)' }} />
            <div style={{ height: 8, width: (ov / tot) * 100 + '%', background: 'var(--hl-red-fg)' }} />
          </div>
          <div style={{ display: 'flex', gap: 14, marginTop: 6, fontSize: 11, color: 'var(--text-muted)' }}>
            <span style={{ display: 'flex', alignItems: 'center', gap: 5 }}>
              <span style={{ width: 8, height: 8, borderRadius: 2, background: 'var(--st-wip)' }} />Within original estimate <b className="mono heading">{hrs(inn)}</b>
            </span>
            {ov > 0 && (
              <span style={{ display: 'flex', alignItems: 'center', gap: 5 }}>
                <span style={{ width: 8, height: 8, borderRadius: 2, background: 'var(--hl-red-fg)' }} />Additional <b className="mono" style={{ color: 'var(--hl-red-fg)' }}>+{hrs(ov)}</b>
              </span>
            )}
          </div>
        </div>
        {t.history.length > 0 && (
          <div>
            <div className="title" style={{ marginBottom: 8 }}>Assignee history{c.rounds ? ` · ${c.rounds} round` : ''}</div>
            {t.history.map((h, i) => {
              const back = h.to_id === t.ba_id && h.from_id !== t.ba_id;
              return (
                <div key={i} style={{ display: 'flex', gap: 10, alignItems: 'center', padding: '6px 0', borderBottom: '1px solid var(--border-subtle)', fontSize: 12 }}>
                  <span style={{ font: '600 11px/1 var(--font-mono)', color: 'var(--text-muted)', width: 40 }}>{dm(h.date)}</span>
                  <Avatar a={av(members[h.from_id ?? ''], h.from_id ?? '–')} size={18} />
                  <Icon name="arrow-right" size={12} />
                  <Avatar a={av(members[h.to_id ?? ''], h.to_id ?? '–')} size={18} />
                  <span style={{ flex: 1, color: 'var(--text-body)' }}>
                    {c.step === 'fixb' || c.step === 'fixj' ? (back ? 'Sent comments' : 'Fixed, sent back for review') : `Assigned to ${name(h.to_id)}`}
                  </span>
                </div>
              );
            })}
          </div>
        )}
        <div>
          <div className="title" style={{ marginBottom: 8 }}>Worklog</div>
          {t.worklogs.map((wl, i) => (
            <div key={i} style={{ display: 'flex', gap: 10, alignItems: 'center', padding: '6px 0', borderBottom: '1px solid var(--border-subtle)', fontSize: 12 }}>
              <span style={{ font: '600 11px/1 var(--font-mono)', color: 'var(--text-muted)', width: 40 }}>{dm(wl.date)}</span>
              <Avatar a={av(members[wl.member_id], wl.member_id)} size={18} />
              <span style={{ flex: 1, color: 'var(--text-body)' }}>{wl.note || '—'}</span>
              <span style={{ font: '600 12px/1 var(--font-mono)', color: 'var(--text-heading)' }}>{hrs(wl.hours)}</span>
            </div>
          ))}
          {!t.worklogs.length && <div style={{ fontSize: 12, color: 'var(--text-muted)' }}>No worklogs yet.</div>}
        </div>
      </div>
    </>
  );
}
