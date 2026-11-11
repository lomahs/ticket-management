import { useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { errorMessage } from '../../../shared/api/errors';
import { useCan, useMemberMap } from '../../../shared/api/queries';
import type { Schemas } from '../../../shared/api/types';
import { Button, Icon } from '../../../shared/ds';
import { dm } from '../../../shared/format/dates';
import { statusOf } from '../../../shared/format/labels';
import { useOpenTicket } from '../../../shared/state/ticket';
import { useToast } from '../../../shared/state/toast';
import { useSaveNote } from '../api';
import { Dot, Initial } from './metro';

type Typed = Schemas['DailyTyped'];

const TYPES = [
  { k: 'qna', label: 'QnA', what: 'questions' },
  { k: 'work_request', label: 'WR', what: 'work requests' },
  { k: 'bug', label: 'Bugs', what: 'bugs' },
] as const;
/** Who holds an open ticket that is not with JM (the client). */
const TEAM = 'FPT';
const COLS = '96px minmax(220px,1.5fr) 190px 64px minmax(220px,1.6fr)';
const DOT: Record<string, string> = { N: 'var(--st-nys)', W: 'var(--st-wip)', R: 'var(--st-res)', C: 'var(--st-clo)' };

/**
 * The QnA, WorkRequest and Bug tickets of the anken, one tab each: totals (open with JM or with the team, closed in the last 7
 * days), then one row per ticket with its status and holder, and its note. Notes are edited on the live day only; a past day
 * shows the notes it was captured with.
 */
export function Issues({ typed, anken, editable }: { typed: Typed[]; anken: string; editable: boolean }) {
  const [tab, setTab] = useState<string>('qna');
  const ty = TYPES.find((t) => t.k === tab) ?? TYPES[0];
  const rows = typed.filter((t) => t.type === ty.k)
    .sort((a, b) => Number(!!a.closed_on) - Number(!!b.closed_on) || Number(b.with_jm) - Number(a.with_jm) || b.held_days - a.held_days);
  return (
    <section className="card" aria-label="Issues" style={{ overflow: 'hidden', minWidth: 0 }}>
      <div style={{ display: 'flex', alignItems: 'flex-end', gap: 16, padding: '6px 20px 0', borderBottom: '1px solid var(--border-subtle)', flexWrap: 'wrap' }}>
        <div role="tablist" aria-label="Issue type" style={{ display: 'flex', gap: 4 }}>
          {TYPES.map((t) => (
            <button key={t.k} type="button" role="tab" className="do-itab" aria-selected={t.k === ty.k} onClick={() => setTab(t.k)}>
              {t.label}<span className="do-icount">{typed.filter((x) => x.type === t.k).length}</span>
            </button>
          ))}
        </div>
        <span className="spacer" />
        <span style={{ fontSize: 12.5, color: 'var(--text-muted)', paddingBottom: 12 }}>QnA, WorkRequest and Bug tickets of the anken; closed ones stay 7 days</span>
      </div>
      <div role="tabpanel" aria-label={ty.label}>
        <Totals rows={rows} />
        {rows.length ? (
          <div style={{ overflowX: 'auto' }}>
            <div style={{ minWidth: 1000 }}>
              <div style={{ display: 'grid', gridTemplateColumns: COLS, gap: 14, padding: '10px 35px 8px', borderTop: '1px solid var(--border-subtle)', borderBottom: '1px solid var(--border-subtle)', font: '700 11.5px/1 var(--font-text)', color: 'var(--text-body)' }}>
                <span>Ticket</span><span>Content · assignee</span><span>Status · held by</span><span>Created</span><span>Note to share</span>
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8, padding: '12px 20px 18px' }}>
                {rows.map((t) => <Row key={t.key} t={t} anken={anken} editable={editable} />)}
              </div>
            </div>
          </div>
        ) : (
          <div style={{ display: 'flex', alignItems: 'center', gap: 16, margin: '4px 20px 18px', padding: '22px 24px', borderRadius: 12,
            background: 'color-mix(in oklch, var(--do-clo-bg) 55%, var(--surface-card))', border: '1px solid color-mix(in oklch, var(--do-clo) 22%, transparent)' }}>
            <span style={{ display: 'grid', placeItems: 'center', width: 52, height: 52, borderRadius: 14, background: 'var(--surface-card)', color: 'var(--do-clo)' }}>
              <Icon name="shield-check" size={30} />
            </span>
            <div>
              <div style={{ font: '700 15px/1.3 var(--font-text)', color: 'var(--text-heading)' }}>No open {ty.what} right now</div>
              <div style={{ fontSize: 12.5, color: 'var(--text-body)', marginTop: 2 }}>None open, and none closed in the last 7 days. New {ty.what} from Jira show here on the next sync.</div>
            </div>
          </div>
        )}
      </div>
    </section>
  );
}

/** Total | Open (with JM · with the team) | Closed in the last 7 days. */
function Totals({ rows }: { rows: Typed[] }) {
  const open = rows.filter((t) => !t.closed_on);
  const jm = open.filter((t) => t.with_jm).length;
  const label = (t: string) => <span style={{ font: '600 11.5px/1 var(--font-text)', color: 'var(--text-muted)', letterSpacing: '.02em', textTransform: 'uppercase' }}>{t}</span>;
  const big = (n: number) => <b style={{ font: '800 20px/1 var(--font-display)', color: 'var(--text-heading)' }}>{n}</b>;
  const sep = <span aria-hidden="true" style={{ width: 1, height: 28, background: 'var(--border-subtle)' }} />;
  const cell = (inner: ReactNode) => <span style={{ display: 'flex', alignItems: 'center', gap: 10 }}>{inner}</span>;
  const sub = (n: number, t: string, bg: string, fg: string) => (
    <span className="do-pill" style={{ height: 22, background: bg, color: fg, fontSize: 12 }}>{t}<b style={{ fontWeight: 800 }}>{n}</b></span>
  );
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 18, flexWrap: 'wrap', padding: '14px 20px' }}>
      {cell(<>{label('Total')}{big(rows.length)}</>)}{sep}
      {cell(<>{label('Open')}{big(open.length)}{sub(jm, 'JM', 'color-mix(in oklch, var(--do-jm) 14%, var(--surface-card))', 'var(--do-jm)')}
        {sub(open.length - jm, TEAM, 'var(--do-team-bg)', 'var(--do-team)')}</>)}{sep}
      {cell(<>{label('Closed')}{big(rows.length - open.length)}
        <span className="do-pill" style={{ height: 22, background: 'var(--surface-hover)', color: 'var(--text-body)', fontWeight: 600, fontSize: 12 }}>last 7 days</span></>)}
    </div>
  );
}

/** The ticket's status and who holds it, in one pill: "● WIP · JM · 3d". */
function Held({ t }: { t: Typed }) {
  const st = statusOf(t.status);
  if (t.closed_on) {
    return <span className="do-pill" style={{ background: 'var(--surface-hover)', color: 'var(--text-muted)', fontWeight: 600, fontSize: 12 }}><Dot color={DOT.C} />{st.label} · {dm(t.closed_on)}</span>;
  }
  const held = t.held_days ? `${t.held_days}d` : 'today';
  return (
    <span className="do-pill" title={t.with_jm ? 'With JM (the client)' : 'With the team'} style={{
      fontWeight: 600, fontSize: 12,
      ...(t.with_jm ? { background: 'color-mix(in oklch, var(--do-jm) 14%, var(--surface-card))', color: 'var(--do-jm)' } : { background: 'var(--do-team-bg)', color: 'var(--do-team)' }),
    }}>
      <Dot color={DOT[t.status] ?? DOT.N} /><span style={{ color: 'var(--text-body)' }}>{st.label}</span> · <b>{t.with_jm ? 'JM' : TEAM}</b> · {held}
    </span>
  );
}

function Row({ t, anken, editable }: { t: Typed; anken: string; editable: boolean }) {
  const members = useMemberMap();
  const openTicket = useOpenTicket();
  const m = members[t.assignee_id ?? ''];
  return (
    <div className={'do-irow' + (t.closed_on ? ' closed' : '')} style={{ gridTemplateColumns: COLS }}>
      <button type="button" className="key hv-underline" onClick={() => openTicket(t.key)}
        style={{ justifySelf: 'start', padding: 0, border: 0, background: 'none', cursor: 'pointer', font: '600 12px/1 var(--font-mono)', color: 'var(--text-heading)' }}>
        {t.key}
      </button>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, minWidth: 0 }}>
        <Initial m={m} id={t.assignee_id} size={24} />
        <div style={{ minWidth: 0 }}>
          <div style={{ font: '700 13.5px/1.35 var(--font-text)', color: 'var(--text-heading)' }}>{t.summary}</div>
          <div style={{ font: '500 12px/1.3 var(--font-text)', color: 'var(--text-muted)' }}>{m?.name ?? t.assignee_id ?? 'Unassigned'}</div>
        </div>
      </div>
      <span><Held t={t} /></span>
      <span style={{ font: '500 12px var(--font-mono)', color: 'var(--text-body)' }}>{dm(t.created)}</span>
      <Note t={t} anken={anken} editable={editable} />
    </div>
  );
}

/** The note, two lines at most (hover shows it all); on the live day the pencil opens an editor. */
function Note({ t, anken, editable }: { t: Typed; anken: string; editable: boolean }) {
  const canWrite = useCan('overview', 'write');
  const [editing, setEditing] = useState(false);
  const can = editable && canWrite;
  return (
    <div className="do-note-wrap">
      {t.note ? (
        <>
          <span className="do-note">{t.note}</span>
          <span className="do-note-pop" role="tooltip">{t.note}</span>
        </>
      ) : <span className="do-note" style={{ color: 'var(--text-disabled)' }}>{can ? 'Add a note…' : '—'}</span>}
      {can && (
        <button type="button" className="do-edit" aria-label={`Edit note on ${t.key}`} onClick={() => setEditing(true)}><Icon name="pencil" size={13} /></button>
      )}
      {/* outside the row: a row lifts on hover, which would carry a fixed dialog with it */}
      {editing && createPortal(<NoteEditor t={t} anken={anken} close={() => setEditing(false)} />, document.body)}
    </div>
  );
}

function NoteEditor({ t, anken, close }: { t: Typed; anken: string; close: () => void }) {
  const save = useSaveNote(anken);
  const toast = useToast();
  const [text, setText] = useState(t.note);
  const submit = () => {
    if (text.trim() === t.note) { close(); return; }
    save.mutate({ key: t.key, note: text }, { onSuccess: close, onError: (e) => toast(errorMessage(e), true) });
  };
  return (
    <div className="scrim" onClick={close} onKeyDown={(e) => { if (e.key === 'Escape') close(); }}>
      <div className="dialog" role="dialog" aria-modal="true" aria-label={`Note on ${t.key}`} onClick={(e) => e.stopPropagation()} style={{ width: 'min(560px, calc(100vw - 32px))' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <span style={{ font: '600 12px var(--font-mono)', color: 'var(--text-muted)' }}>{t.key}</span>
          <span className="dialog-title">{t.summary}</span>
        </div>
        <label style={{ display: 'flex', flexDirection: 'column', gap: 6, font: '600 12px/1 var(--font-text)', color: 'var(--text-muted)' }}>
          Note to share with the client
          <textarea
            aria-label={`Note on ${t.key}`}
            autoFocus
            rows={6}
            value={text}
            placeholder="What the client should know about this ticket…"
            disabled={save.isPending}
            onChange={(e) => setText(e.target.value)}
            style={{ boxSizing: 'border-box', width: '100%', padding: '10px 12px', borderRadius: 10, border: '1px solid var(--border-strong)', background: 'var(--surface-card)',
              color: 'var(--text-heading)', font: '400 14px/1.5 var(--font-text)', resize: 'vertical' }}
          />
        </label>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span style={{ fontSize: 12, color: 'var(--text-muted)' }}>Shown on the Daily Overview and kept in its captures.</span>
          <span className="spacer" />
          <Button appearance="subtle" onClick={close}>Cancel</Button>
          <Button onClick={submit} disabled={save.isPending}>Save note</Button>
        </div>
      </div>
    </div>
  );
}
