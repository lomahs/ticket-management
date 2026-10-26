import type { CSSProperties } from 'react';
import type { Status } from '../../../shared/api/types';

/** A status chip's colours on the Daily Overview, which is read across a shared screen: stronger than the other screens'. */
export const chip = (s: Status): CSSProperties => {
  const v = s === 'C' ? 'clo' : s === 'R' ? 'res' : s === 'W' ? 'wip' : 'nys';
  return { background: `var(--do-${v}-bg)`, color: `var(--do-${v})` };
};

/** A late sub-task: red, outlined. */
export const lateChip: CSSProperties = { background: 'var(--hl-red)', color: 'var(--hl-red-fg)', border: '2px solid var(--hl-red-fg)' };

/** The step where a function's JM round is now: waiting for the JM review, or the team fixing JM's comments. */
export const ACTIVE_RING = '0 0 0 2px var(--surface-card), 0 0 0 4px var(--do-jm)';

const box = (bg: string, bd: string): CSSProperties => ({ width: 12, height: 12, borderRadius: 3, background: bg, border: bd, boxSizing: 'border-box' });

export function Legend() {
  const item: CSSProperties = { display: 'flex', alignItems: 'center', gap: 5 };
  const pill = (bg: string, fg: string): CSSProperties => ({ padding: '2px 6px', borderRadius: 99, background: bg, color: fg, font: '700 10px/1 var(--font-text)' });
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap', fontSize: 12, color: 'var(--text-muted)' }}>
      <span style={item}><span style={box('var(--do-nys-bg)', '1px solid var(--do-nys)')} />NYS</span>
      <span style={item}><span style={box('var(--do-wip-bg)', '1px solid var(--do-wip)')} />WIP</span>
      <span style={item}><span style={box('var(--do-res-bg)', '1px solid var(--do-res)')} />Resolved</span>
      <span style={item}><span style={box('var(--do-clo-bg)', '1px solid var(--do-clo)')} />Done</span>
      <span style={item}><span style={box('var(--hl-red)', '2px solid var(--hl-red-fg)')} />Late</span>
      <span style={item}><span style={pill('var(--do-jm)', 'var(--do-jm-fg)')}>R2 · JM</span>review round, with JM</span>
      <span style={item}><span style={{ ...box('var(--do-wip)', '0'), borderRadius: '50%', boxShadow: ACTIVE_RING, margin: '0 3px' }} />JM round is here now</span>
    </div>
  );
}
