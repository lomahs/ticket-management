import type { Schemas } from '../../../shared/api/types';
import { usePref } from '../../../shared/state/prefs';

/**
 * How this browser lays out an anken's Function Matrix (Setting phase): kept in localStorage, so each person has their own.
 *
 * @param phases phase groups in the chosen order
 * @param tasks  Task keys in the chosen order within their phase
 * @param shown  the Tasks shown on the matrix
 */
export interface MatrixLayout { phases: string[]; tasks: string[]; shown: string[] }

type Parent = Schemas['ParentOption'];

const parse = (raw: string): MatrixLayout | null => {
  try {
    const v = JSON.parse(raw) as Partial<MatrixLayout>;
    return Array.isArray(v.phases) && Array.isArray(v.tasks) && Array.isArray(v.shown) ? { phases: v.phases, tasks: v.tasks, shown: v.shown } : null;
  } catch {
    return null;
  }
};

/** This browser's layout of the anken; null when none is saved. Saving null goes back to the default. */
export function useMatrixLayout(epic: string): [MatrixLayout | null, (l: MatrixLayout | null) => void] {
  const [raw, setRaw] = usePref('matrixLayout.' + epic, '');
  return [raw ? parse(raw) : null, (l) => setRaw(l ? JSON.stringify(l) : '')];
}

/** Position in the chosen order; items not in it come after, keeping their default order. */
const rank = (chosen: string[], item: string) => {
  const i = chosen.indexOf(item);
  return i >= 0 ? i : chosen.length;
};

/** The Tasks in the layout's order: phase groups first, then Tasks within each; anything the layout does not list keeps its place after. */
export function applyLayout(parents: Parent[], layout: MatrixLayout | null): Parent[] {
  if (!layout) return parents;
  const groups = [...new Set(parents.map((p) => p.group))];
  return parents
    .map((p, i) => ({ p, i }))
    .sort((a, b) => rank(layout.phases, a.p.group) - rank(layout.phases, b.p.group)
      || groups.indexOf(a.p.group) - groups.indexOf(b.p.group)
      || rank(layout.tasks, a.p.key) - rank(layout.tasks, b.p.key) || a.i - b.i)
    .map((x) => x.p);
}
