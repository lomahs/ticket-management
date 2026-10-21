import type { LeaveTypeKey, Status, StepKind, Tone } from '../api/types';

/** Display vocabulary: labels and colour tokens for the enums the API returns. */

export const STATUS: Record<Status, { label: string; v: 'nys' | 'wip' | 'res' | 'clo' }> = {
  N: { label: 'NYS', v: 'nys' }, W: { label: 'WIP', v: 'wip' }, R: { label: 'Resolved', v: 'res' }, C: { label: 'Closed', v: 'clo' },
};
export const statusOf = (s: string) => STATUS[(s as Status)] ?? STATUS.W;

export const TONE: Record<Tone, string> = {
  ontime: 'On schedule', early: 'Early start', latestart: 'Late start', delay: 'Delay', nys: 'NYS', res: 'Resolved', clo: 'Closed',
};

export const STEPS: { k: StepKind; label: string; short: string; color: string }[] = [
  { k: 'create', label: 'Create', short: 'Create', color: 'oklch(0.55 0.14 255)' },
  { k: 'balr', label: 'BAL Review', short: 'BAL Review', color: 'oklch(0.56 0.15 300)' },
  { k: 'fixb', label: 'Fix comment BAL', short: 'Fix BAL', color: 'oklch(0.62 0.15 50)' },
  { k: 'jmr', label: 'JM Review', short: 'JM Review', color: 'oklch(0.54 0.12 165)' },
  { k: 'fixj', label: 'Fix comment JM', short: 'Fix JM', color: 'oklch(0.58 0.17 20)' },
];
export const OTHER_STEP = { label: 'Other task', color: 'oklch(0.62 0.02 260)' };
export const stepLabel = (k?: string | null) => STEPS.find((s) => s.k === k)?.label ?? '';
export const stepColor = (k?: string | null) => STEPS.find((s) => s.k === k)?.color ?? OTHER_STEP.color;

export const ROLE_LABEL: Record<string, string> = { BA: 'BA', BAL: 'BA Leader', PM: 'Project Manager', PIM: 'Project Internal Manager', JM: 'Japan Member · client' };


export const LEAVE: Record<LeaveTypeKey | 'holiday', { label: string; ab: string; c: string; full?: boolean; half?: boolean; tent?: boolean }> = {
  full: { label: 'All day', ab: '1', c: 'oklch(0.6 0.13 250)', full: true },
  half: { label: 'Half day', ab: '½', c: 'oklch(0.6 0.13 250)', half: true },
  fullTent: { label: 'All day (tentative)', ab: '1?', c: 'oklch(0.62 0.13 310)', full: true, tent: true },
  halfTent: { label: 'Half day (tentative)', ab: '½?', c: 'oklch(0.62 0.13 310)', half: true, tent: true },
  holiday: { label: 'Holiday', ab: 'H', c: 'oklch(0.6 0.02 260)' },
};
export const LEAVE_KEYS: LeaveTypeKey[] = ['full', 'half', 'fullTent', 'halfTent'];

export const mix = (c: string, p: number) => `color-mix(in oklch, ${c} ${p}%, var(--surface-card))`;
export const hatch = (c: string) => `repeating-linear-gradient(135deg,${c} 0 1.5px,transparent 1.5px 6px)`;
export const HOLIDAY_BG = hatch('var(--border-strong)') + ',' + mix(LEAVE.holiday.c, 22);
export const EXTRA_DAY_BG = mix(STEPS[0].color, 8);

export function leaveBg(k: LeaveTypeKey | 'holiday') {
  const t = LEAVE[k];
  if (k === 'holiday') return HOLIDAY_BG;
  const b = t.tent ? hatch(mix(t.c, 70)) + ',' : '';
  return t.half ? b + `linear-gradient(to bottom,${mix(t.c, 45)} 50%,${mix(t.c, 10)} 50%)` : b + mix(t.c, t.tent ? 18 : 38);
}

/** Header colours for a day column: today, extra working day, or plain. */
export const dayHeadStyle = (d: { is_today: boolean; extra_note?: string | null }) => ({
  fg: d.is_today ? 'var(--text-heading)' : d.extra_note ? STEPS[0].color : 'var(--text-muted)',
  bg: d.is_today ? 'var(--surface-tint)' : d.extra_note ? EXTRA_DAY_BG : 'transparent',
  fw: d.is_today || d.extra_note ? 700 : 500,
  tip: d.extra_note ? 'Extra working day · ' + d.extra_note : '',
});

/** The start, finish and effort labels of a sub-task (`schedule`) and of a roll-up. */
export const SCHEDULE_LABEL: Record<string, string> = {
  early_start: 'Early start', late_start: 'Late start', on_time_start: 'On time', not_due: 'Not due',
  early_finish: 'Early finish', late_finish: 'Late finish', on_time_finish: 'On time', overdue: 'Overdue', open: 'Open',
  under_burn: 'Under-burn', over_burn: 'Over-burn', on_plan: 'On plan',
  no_plan: 'No plan', no_estimate: 'No estimate',
};

/** The main state of an anken or a phase. */
export const STATE_LABEL: Record<string, string> = {
  LATE: 'Late', EARLY: 'Early', ON_TRACK: 'On track', NOT_STARTED: 'Not started', DONE: 'Done', UNKNOWN: '—',
};

/** A signed number of hours: "+1h", "−14h" (the sign is always shown). */
export const signedH = (n: number) => {
  const r = Math.round(n * 10) / 10;
  return (r < 0 ? '−' : '+') + Math.abs(r) + 'h';
};
