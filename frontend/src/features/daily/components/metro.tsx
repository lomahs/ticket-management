import type { CSSProperties, ReactNode } from 'react';
import { Icon } from '../../../shared/ds';
import type { Member } from '../../../shared/api/types';

/** Where a row stands against its plan, as the Schedule column and the Task chips say it. */
export type Sched = 'done' | 'late' | 'ok' | 'nys';

/** Daily Overview colours that a dark theme swaps through the tokens. */
export const C = {
  done: 'var(--do-clo)', wip: 'var(--st-wip)', late: 'var(--do-red)', jm: 'var(--do-jm)', due: 'var(--do-due)',
  line: 'var(--do-line)', lineStrong: 'var(--do-line-strong)',
} as const;

/** A soft halo of the stop's own colour: the stop where a line is now. */
export const glow = (col: string): CSSProperties => ({
  boxShadow: `0 0 0 4px color-mix(in oklch, ${col} 18%, transparent), 0 0 14px 2px color-mix(in oklch, ${col} 42%, transparent)`,
});

/** Done · On track · Late Nd · Not started, each with its icon. */
export function SchedPill({ s, late = 0 }: { s: Sched; late?: number }) {
  const v = {
    done: { bg: 'var(--do-clo-bg)', fg: 'var(--do-clo)', icon: <Icon name="check" size={11} strokeWidth={3.2} />, label: 'Done' },
    late: { bg: 'var(--do-red-bg)', fg: 'var(--do-red)', icon: <Icon name="clock" size={11} strokeWidth={2.6} />, label: `Late ${late}d` },
    ok: { bg: 'var(--do-wip-bg)', fg: 'var(--do-wip)', icon: <Dot />, label: 'On track' },
    nys: { bg: 'var(--do-nys-bg)', fg: 'var(--do-nys)', icon: <Dot ring />, label: 'Not started' },
  }[s];
  return <span className="do-pill" style={{ background: v.bg, color: v.fg }}>{v.icon}{v.label}</span>;
}

/** A small filled (or ringed) dot in the text colour. */
export function Dot({ ring = false, color = 'currentColor', size = 7 }: { ring?: boolean; color?: string; size?: number }) {
  return (
    <span aria-hidden="true" style={{
      width: size, height: size, borderRadius: '50%', flex: 'none',
      ...(ring ? { boxShadow: `inset 0 0 0 1.5px ${color}` } : { background: color }),
    }} />
  );
}

/** A member's initial in their colour. */
export function Initial({ m, id, size = 28 }: { m: Member | undefined; id: string | null | undefined; size?: number }) {
  return (
    <span aria-hidden="true" style={{
      display: 'inline-grid', placeItems: 'center', width: size, height: size, borderRadius: '50%', flex: 'none',
      background: m?.color ?? 'var(--text-muted)', color: '#fff', font: `700 ${Math.round(size * 0.46)}px/1 var(--font-text)`,
    }}>{(m?.name ?? id ?? '?').slice(0, 1)}</span>
  );
}

/** A chip with an icon, for a Task's summary: late, in progress. */
export function CountChip({ tone, children }: { tone: 'late' | 'run'; children: ReactNode }) {
  const late = tone === 'late';
  return (
    <span className="do-pill" style={{
      height: 22, background: late ? 'var(--do-red-bg)' : 'var(--do-wip-bg)', color: late ? 'var(--do-red-strong)' : 'var(--do-wip-strong)', fontWeight: 800,
      boxShadow: `inset 0 0 0 1px color-mix(in oklch, ${late ? 'var(--do-red)' : 'var(--st-wip)'} 35%, transparent)`,
    }}>
      {late ? <Icon name="clock" size={11} strokeWidth={2.6} /> : <Dot />}{children}
    </span>
  );
}

export type StopKind = 'done' | 'now' | 'open' | 'due' | 'todo';

/**
 * The stops a line is drawn between: done, now (with the holder's initial), open but not now, due on the day shown but not
 * now (the small dot filled in yellow), not started. On a yellow now, the initial reads in dark.
 */
export function StopDot({ kind, col = C.wip, initial }: { kind: StopKind; col?: string; initial?: string }) {
  if (kind === 'done') {
    return <span className="do-dot" style={{ width: 20, height: 20, background: C.done }}><Icon name="check" size={11} strokeWidth={3.2} /></span>;
  }
  const fg = col === C.due ? { color: 'var(--do-due-fg)' } : undefined;
  if (kind === 'now') return <span className="do-dot do-now" style={{ width: 26, height: 26, background: col, ...fg, ...glow(col) }}>{initial}</span>;
  if (kind === 'due') return <span className="do-dot" style={{ width: 16, height: 16, background: col }} />;
  if (kind === 'open') return <span className="do-dot" style={{ width: 18, height: 18, background: 'var(--surface-card)', boxShadow: `inset 0 0 0 2.5px ${col}` }} />;
  return <span className="do-dot" style={{ width: 16, height: 16, background: 'var(--surface-card)', boxShadow: `inset 0 0 0 2px ${col}` }} />;
}
