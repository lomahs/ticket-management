import { useQuery } from '@tanstack/react-query';
import { errorMessage } from '../../../shared/api/errors';
import { JIRA_WRITE_OFF, useCapability, useMemberMap } from '../../../shared/api/queries';
import type { Status } from '../../../shared/api/types';
import { Icon, IconButton, Select, TextField, type IconName } from '../../../shared/ds';
import { dm, f1, hrs } from '../../../shared/format/dates';
import { STATUS, statusOf } from '../../../shared/format/labels';
import { useToast } from '../../../shared/state/toast';
import { useOpenTicket, useTicketKey } from '../../../shared/state/ticket';
import { Avatar, av } from '../../../shared/ui/bits';
import { Loading, QueryError } from '../../../shared/ui/states';
import { ticketQuery, usePatchTicket } from '../api';

const WARN: Record<string, { icon: IconName; bg: string; fg: string; label: (v: number) => string }> = {
  late: { icon: 'alarm-clock', bg: 'var(--hl-red)', fg: 'var(--hl-red-fg)', label: (v) => `Late by ${v} days` },
  over: { icon: 'trending-up', bg: 'var(--hl-amber)', fg: 'var(--hl-amber-fg)', label: (v) => `Over estimate ${f1(v)}h` },
  rounds: { icon: 'refresh-cw', bg: 'var(--hl-amber)', fg: 'var(--hl-amber-fg)', label: (v) => `${v} round` },
};

export function TicketPanel() {
  const key = useTicketKey();
  const open = useOpenTicket();
  if (!key) return null;
  return (
    <aside aria-label={`Ticket ${key}`} style={{
      position: 'fixed', top: 8, bottom: 8, right: 8, width: 440, maxWidth: 'calc(100vw - 16px)', zIndex: 30, borderRadius: 16, overflow: 'hidden',
      background: 'var(--surface-card)', border: '1px solid var(--border-subtle)', boxShadow: 'var(--shadow-overlay)', display: 'flex', flexDirection: 'column',
    }}>
      <TicketBody ticketKey={key} close={() => open(null)} />
    </aside>
  );
}

function TicketBody({ ticketKey, close }: { ticketKey: string; close: () => void }) {
  const q = useQuery(ticketQuery(ticketKey));
  const members = useMemberMap();
  const canWrite = useCapability('jiraWrite');
  const patch = usePatchTicket(ticketKey);
  const toast = useToast();

  const header = (phase: string) => (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '10px 12px 10px 18px', borderBottom: '1px solid var(--border-subtle)' }}>
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
  const write = (field: 'status' | 'assignee_id' | 'planned_start' | 'planned_end', label: string, value: string, fmt: (x: string) => string) => {
    const old = { status: c.status, assignee_id: c.assignee_id, planned_start: c.planned_start, planned_end: c.planned_end }[field] ?? '';
    if (!value || value === old) return;
    patch.mutate({ [field]: value }, {
      onSuccess: () => toast(`Written to Jira · ${c.key} ${label}: ${fmt(old)} → ${fmt(value)}`),
      onError: (e) => toast(errorMessage(e), true),
    });
  };
  const tot = Math.max(c.estimate_h, c.logged_h) || 1;
  const inn = Math.min(c.logged_h, c.estimate_h);
  const ov = Math.max(0, c.logged_h - c.estimate_h);
  const offTitle = canWrite ? undefined : JIRA_WRITE_OFF;
  const disabled = !canWrite || patch.isPending;

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
              {t.warnings.map((w) => {
                const s = WARN[w.kind];
                return s ? (
                  <span key={w.kind} style={{ display: 'inline-flex', alignItems: 'center', gap: 5, font: '600 11px/22px var(--font-text)', padding: '0 8px', borderRadius: 6, background: s.bg, color: s.fg }}>
                    <Icon name={s.icon} size={12} />{s.label(w.value)}
                  </span>
                ) : null;
              })}
            </div>
          )}
        </div>
        <div title={offTitle} style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
          <Select label="Status" disabled={disabled} options={(Object.keys(STATUS) as Status[]).map((k) => ({ value: k, label: STATUS[k].label }))}
            value={c.status} onChange={(v) => write('status', 'status', v, (x) => statusOf(x).label)} />
          <Select label="Assignee" disabled={disabled}
            options={Object.values(members).map((m) => ({ value: m.id, label: `${m.name}${m.role ? ` (${m.role})` : ''}` }))}
            value={c.assignee_id ?? ''} onChange={(v) => write('assignee_id', 'assignee', v, name)} />
          <TextField label="Planned Start" type="date" disabled={disabled} value={c.planned_start ?? ''} onChange={(v) => write('planned_start', 'Planned Start', v, dm)} />
          <TextField label="Planned End" type="date" disabled={disabled} value={c.planned_end ?? ''} onChange={(v) => write('planned_end', 'Planned End', v, dm)} />
        </div>
        {!canWrite && <div className="eyebrow">{JIRA_WRITE_OFF}. Edit this ticket in Jira; it syncs back here.</div>}
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
              const back = h.to_id === t.owner_id && h.from_id !== t.owner_id;
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
