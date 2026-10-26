import { useState } from 'react';
import { errorMessage } from '../../../shared/api/errors';
import { useCan, useMemberMap } from '../../../shared/api/queries';
import type { Schemas, Status } from '../../../shared/api/types';
import { dm } from '../../../shared/format/dates';
import { statusOf } from '../../../shared/format/labels';
import { useOpenTicket } from '../../../shared/state/ticket';
import { useToast } from '../../../shared/state/toast';
import { useSaveNote } from '../api';
import { chip } from './chips';

type Typed = Schemas['DailyTyped'];

const TYPES: { k: string; label: string; short: string; none: string }[] = [
  { k: 'qna', label: 'QnA', short: 'QnA', none: 'No QnA yet' },
  { k: 'bug', label: 'Bug', short: 'Bug', none: 'No bugs yet' },
  { k: 'work_request', label: 'WorkRequest', short: 'WR', none: 'No work requests yet' },
];
const COLS = '64px 92px minmax(200px,1.4fr) 120px 96px 120px minmax(260px,2fr)';
/** Working days with JM after which the wait is shown in red. */
const LONG_WAIT = 3;

/**
 * The sub-tasks of the QnA, Bug and WorkRequest Tasks: whether each is with JM (the client) or with the team, its status
 * and a note. Notes are edited on the live day only; a past day shows the notes it was captured with.
 */
export function TypedSection({ typed, anken, editable }: { typed: Typed[]; anken: string; editable: boolean }) {
  const open = typed.filter((t) => !t.closed_on);
  const withJm = open.filter((t) => t.with_jm).length;
  return (
    <section className="card" aria-label="QnA, Bug and WorkRequest" style={{ padding: '18px 20px', display: 'flex', flexDirection: 'column', gap: 14, minWidth: 0, borderRadius: 14 }}>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 10, flexWrap: 'wrap' }}>
        <span style={{ font: '700 18px/1.2 var(--font-display)', color: 'var(--text-heading)' }}>QnA · Bug · WorkRequest</span>
        <span className="spacer" />
        <span style={{ fontSize: 13, color: 'var(--text-muted)' }}>{withJm} with JM · {open.length - withJm} with the team</span>
      </div>
      <div style={{ overflowX: 'auto', margin: '0 -20px' }}>
        <div style={{ minWidth: 1040, padding: '0 20px', display: 'flex', flexDirection: 'column', gap: 14 }}>
          <div style={{ display: 'grid', gridTemplateColumns: COLS, gap: '0 12px', padding: '0 12px', font: '600 12px/1.2 var(--font-text)', color: 'var(--text-muted)' }}>
            <span>Type</span><span>Ticket</span><span>Content</span><span>Assignee</span><span>Status</span><span>Created</span><span>Note</span>
          </div>
          {TYPES.map((ty) => {
            const rows = typed.filter((t) => t.type === ty.k);
            const shut = rows.filter((t) => t.closed_on).length;
            return (
              <div key={ty.k} style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                {rows.length ? (
                  <>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '0 12px', font: '600 13px/1 var(--font-text)', color: 'var(--text-muted)' }}>
                      <span>{ty.label}</span><span style={{ color: 'var(--text-heading)' }}>{rows.length - shut}</span>
                      {shut > 0 && <span style={{ fontWeight: 400 }}>· {shut} closed in the last 7 days</span>}
                    </div>
                    {rows.map((t) => <Row key={t.key} t={t} short={ty.short} anken={anken} editable={editable} />)}
                  </>
                ) : (
                  <div style={{ padding: '10px 12px', border: '1px dashed var(--border-subtle)', borderRadius: 8, fontSize: 14, color: 'var(--text-muted)' }}>{ty.none}</div>
                )}
              </div>
            );
          })}
        </div>
      </div>
    </section>
  );
}

function Row({ t, short, anken, editable }: { t: Typed; short: string; anken: string; editable: boolean }) {
  const members = useMemberMap();
  const openTicket = useOpenTicket();
  const st = statusOf(t.status);
  const who = members[t.assignee_id ?? '']?.name ?? t.assignee_id ?? 'Unassigned';
  const wait = t.closed_on ? `closed ${dm(t.closed_on)}` : `with ${t.with_jm ? 'JM' : 'the team'} · ${t.held_days ? t.held_days + 'd' : 'today'}`;
  const long = !t.closed_on && t.with_jm && t.held_days >= LONG_WAIT;
  return (
    <div style={{ display: 'grid', gridTemplateColumns: COLS, gap: '0 12px', alignItems: 'center', padding: '8px 12px', border: '1px solid var(--border-subtle)', borderRadius: 8, opacity: t.closed_on ? 0.7 : 1 }}>
      <span style={{ justifySelf: 'start', padding: '3px 7px', borderRadius: 3, background: 'var(--surface-hover)', color: 'var(--text-heading)', font: '700 11px/1.3 var(--font-text)' }}>{short}</span>
      <button type="button" className="key hv-underline" onClick={() => openTicket(t.key)} style={{ justifySelf: 'start', padding: 0, border: 0, background: 'none', cursor: 'pointer', font: '600 13px/1.2 var(--font-mono)' }}>
        {t.key}
      </button>
      <span style={{ font: '600 15px/1.35 var(--font-text)', color: 'var(--text-heading)' }}>{t.summary}</span>
      <span title={t.with_jm ? 'With JM (the client)' : 'With the team'} style={{
        justifySelf: 'start', padding: '4px 10px', borderRadius: 99, font: '700 13px/1.2 var(--font-text)', whiteSpace: 'nowrap',
        background: t.with_jm ? 'var(--do-jm)' : 'var(--do-team-bg)', color: t.with_jm ? 'var(--do-jm-fg)' : 'var(--do-team)',
      }}>{who}</span>
      <span style={{ justifySelf: 'start', padding: '2px 8px', borderRadius: 3, font: '600 12px/1.4 var(--font-text)', ...chip(t.status as Status) }}>{st.label}</span>
      <span style={{ display: 'flex', flexDirection: 'column', gap: 3, whiteSpace: 'nowrap' }}>
        <span style={{ font: '500 14px/1.2 var(--font-mono)', color: 'var(--text-heading)' }}>{dm(t.created)}</span>
        <span style={{ font: '600 12px/1.2 var(--font-text)', color: long ? 'var(--hl-red-fg)' : 'var(--text-muted)' }}>{wait}</span>
      </span>
      <Note key={t.key + '|' + t.note} t={t} anken={anken} editable={editable} />
    </div>
  );
}

/** Saved when the field loses focus with a changed text. */
function Note({ t, anken, editable }: { t: Typed; anken: string; editable: boolean }) {
  const canWrite = useCan('overview', 'write');
  const save = useSaveNote(anken);
  const toast = useToast();
  const [text, setText] = useState(t.note);
  if (!editable || !canWrite) {
    return <span style={{ font: '400 14px/1.45 var(--font-text)', color: t.note ? 'var(--text-heading)' : 'var(--text-muted)', whiteSpace: 'pre-wrap' }}>{t.note || '—'}</span>;
  }
  return (
    <textarea
      aria-label={`Note on ${t.key}`}
      value={text}
      rows={2}
      placeholder="None"
      disabled={save.isPending}
      onChange={(e) => setText(e.target.value)}
      onBlur={() => {
        if (text.trim() === t.note) return;
        save.mutate({ key: t.key, note: text }, { onError: (e) => { toast(errorMessage(e), true); setText(t.note); } });
      }}
      style={{
        width: '100%', boxSizing: 'border-box', minHeight: 44, padding: '6px 8px', border: '1px solid var(--border-subtle)', borderRadius: 4,
        background: 'var(--surface-card)', color: 'var(--text-heading)', font: '400 14px/1.45 var(--font-text)', resize: 'vertical',
      }}
    />
  );
}
