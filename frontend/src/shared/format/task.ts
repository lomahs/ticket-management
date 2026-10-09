import type { TaskCard } from '../api/types';
import { dm } from './dates';
import { stepLabel } from './labels';

export const taskTitle = (c: TaskCard) => (c.function_id ? `${c.function_id} ${c.function_name ?? ''}`.trim() : c.summary);

export function taskSub(c: TaskCard): string {
  if (!c.function_id) return c.phase;
  return [stepLabel(c.step), c.rounds ? `round ${c.rounds}` : '', c.held_days && c.status !== 'C' ? `held ${c.held_days} days` : '']
    .filter(Boolean).join(' · ');
}

/** "F03 · Fix comment BAL" for function steps, the summary otherwise. */
export const taskShort = (c: Pick<TaskCard, 'function_id' | 'step' | 'summary'>) =>
  c.function_id ? `${c.function_id} · ${stepLabel(c.step)}` : c.summary;

export const dueLabel = (c: TaskCard) => (!c.planned_end ? '–' : c.late ? `${dm(c.planned_end)} · late ${c.late_days}d` : dm(c.planned_end));
