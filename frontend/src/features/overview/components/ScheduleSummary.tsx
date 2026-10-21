import { Link } from '@tanstack/react-router';
import type { Schemas } from '../../../shared/api/types';
import { SCHEDULE_LABEL, STATE_LABEL, signedH } from '../../../shared/format/labels';
import { f1 } from '../../../shared/format/dates';
import type { ScheduleFilter } from '../search';

type Rollup = Schemas['ScheduleRollup'];

const STATE_TONE: Record<string, { bg: string; fg: string }> = {
  LATE: { bg: 'var(--hl-red)', fg: 'var(--hl-red-fg)' },
};
const neutral = { bg: 'var(--surface-hover)', fg: 'var(--text-heading)' };

/** An anken card's schedule: the state badge, the effort against plan and the overdue count (a link to the ticket list). */
export function ScheduleSummary({ epic, s }: { epic: string; s: Rollup }) {
  const tone = STATE_TONE[s.state] ?? neutral;
  return (
    <div style={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: '4px 10px', fontSize: 12 }}>
      <span style={{ padding: '2px 8px', borderRadius: 99, fontWeight: 600, background: tone.bg, color: tone.fg }}>{STATE_LABEL[s.state]}</span>
      <span style={{ color: 'var(--text-muted)' }}>Effort: {signedH(s.diff_h)} ({SCHEDULE_LABEL[s.effort]})</span>
      <CountLink epic={epic} label="overdue" n={s.overdue} />
    </div>
  );
}

/** "{label} {n}": a link to the ticket list when n > 0, muted text otherwise. */
export function CountLink({ epic, label, n, phase }: { epic: string; label: ScheduleFilter; n: number; phase?: string }) {
  const text = `${SCHEDULE_LABEL[label]} ${n}`;
  if (!n) return <span style={{ color: 'var(--text-muted)', opacity: 0.6, whiteSpace: 'nowrap' }}>{text}</span>;
  return (
    <Link to="/anken/$epic/tickets" params={{ epic }} search={{ label, phase }} onClick={(e) => e.stopPropagation()}
      style={{ color: 'var(--hl-red-fg)', fontWeight: 600, whiteSpace: 'nowrap' }}>{text}</Link>
  );
}

/** A phase's plan against actual to date, and the counts of its sub-tasks per label. */
export function PhaseSchedule({ epic, phase, s }: { epic: string; phase: string; s: Rollup }) {
  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: '4px 14px', padding: '0 14px 8px', fontSize: 12 }}>
      <span style={{ color: 'var(--text-muted)' }}>plan to date {f1(s.plan_to_date_h)}h · actual {f1(s.actual_to_date_h)}h · {signedH(s.diff_h)}</span>
      <span style={{ display: 'inline-flex', flexWrap: 'wrap', gap: '4px 8px' }}>
        <CountLink epic={epic} phase={phase} label="overdue" n={s.overdue} />
        <CountLink epic={epic} phase={phase} label="late_start" n={s.late_start} />
        <CountLink epic={epic} phase={phase} label="early_start" n={s.early_start} />
        <CountLink epic={epic} phase={phase} label="late_finish" n={s.late_finish} />
        <CountLink epic={epic} phase={phase} label="early_finish" n={s.early_finish} />
      </span>
    </div>
  );
}
