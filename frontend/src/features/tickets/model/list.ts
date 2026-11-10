import type { Schemas } from '../../../shared/api/types';
import { saved, type Edits, type Field, type NewRow } from './draft';

/** One line of the list: a ticket in Jira (`row`) or a draft (`draft`), a Task or a sub-task. */
export interface Item {
  /** The Jira key, or the draft's ref. */
  id: string;
  task: boolean;
  row?: Schemas['BoardRow'];
  draft?: NewRow;
  /** The parent's key (the epic for a Task), or `<new:ref>` for a draft Task. */
  parent: string;
  phase: string;
  /** A Task in Jira whose sub-tasks follow the 5-step review workflow. */
  workflow?: boolean;
}

/** The Tasks and sub-tasks of the board with the drafts, flat: each Task, then its sub-tasks; new Tasks last. */
export function itemsOf(b: Schemas['TicketBoard'], news: NewRow[]): Item[] {
  const draft = (n: NewRow, phase: string): Item => ({
    id: n.ref, task: !n.subtask, draft: n, parent: n.subtask ? n.parent_key ?? `<new:${n.parent_ref}>` : b.epic, phase,
  });
  return [
    ...b.tasks.flatMap((t) => [
      { id: t.task.key, task: true, row: t.task, parent: b.epic, phase: t.phase, workflow: t.workflow },
      ...t.subtasks.map((s) => ({ id: s.key, task: false, row: s, parent: t.task.key, phase: t.phase })),
      ...news.filter((n) => n.parent_key === t.task.key).map((n) => draft(n, t.phase)),
    ]),
    ...news.filter((n) => !n.subtask).flatMap((n) => [draft(n, ''), ...news.filter((s) => s.parent_ref === n.ref).map((s) => draft(s, ''))]),
  ];
}

/** The item's value of a field as the list shows it: the edit, else Jira's; a draft's own value (a new ticket is NYS). */
export function valueOf(it: Item, edits: Edits, f: Field): string {
  if (it.row) return edits[it.id]?.[f] ?? saved(it.row, f);
  if (f === 'status') return 'N';
  return it.draft![f];
}

export type SortKey = 'key' | 'assignee' | 'reporter' | 'start' | 'end';
export interface SortBy { k: SortKey; desc: boolean }
export const SORT_KEYS: SortKey[] = ['key', 'assignee', 'reporter', 'start', 'end'];

/** "assignee,-start" ⇄ [{assignee ↑}, {start ↓}]; unknown or repeated columns are dropped. */
export function parseSort(s: string | undefined): SortBy[] {
  const out: SortBy[] = [];
  for (const part of (s ?? '').split(',')) {
    const k = part.replace(/^-/, '') as SortKey;
    if (SORT_KEYS.includes(k) && !out.some((x) => x.k === k)) out.push({ k, desc: part.startsWith('-') });
  }
  return out;
}
export const sortString = (by: SortBy[]) => by.map((x) => (x.desc ? '-' : '') + x.k).join(',') || undefined;

/**
 * A click on a column's header: alone it sorts by that column only (a second click turns it around); with `add` (Shift) the
 * column joins the sort after the others, or turns around when it is in it already.
 */
export function clickSort(by: SortBy[], k: SortKey, add: boolean): SortBy[] {
  const at = by.find((x) => x.k === k);
  if (add) return at ? by.map((x) => (x.k === k ? { k, desc: !x.desc } : x)) : [...by, { k, desc: false }];
  return by.length === 1 && at ? [{ k, desc: !at.desc }] : [{ k, desc: false }];
}

const FIELD: Record<Exclude<SortKey, 'key'>, Field> = { assignee: 'assignee_id', reporter: 'reporter_id', start: 'planned_start', end: 'planned_end' };

/** "PAY-1104" → ["PAY", 1104], so PAY-999 comes before PAY-1000. */
const keyParts = (key: string) => {
  const m = /^(.*)-(\d+)$/.exec(key);
  return m ? [m[1], Number(m[2])] as const : [key, 0] as const;
};

/**
 * The items in the order of the sort, then in their board order. Tickets in Jira sort by Jira's values, not by edits, so a row
 * does not jump away while it is being edited; drafts by their own values. An empty value (and a draft's missing key) sorts
 * last both ways; names sort by the member's name.
 */
export function sortItems(items: Item[], by: SortBy[], name: (id: string) => string): Item[] {
  if (!by.length) return items;
  const base = (it: Item, k: SortKey): string => {
    if (k === 'key') return it.row ? it.id : '';
    const v = it.row ? saved(it.row, FIELD[k]) : it.draft![FIELD[k] as keyof NewRow] as string;
    return v && (k === 'assignee' || k === 'reporter') ? name(v) : v;
  };
  const cmp = (a: Item, b: Item, k: SortKey) => {
    const x = base(a, k);
    const y = base(b, k);
    if (!x || !y) return x ? -1 : y ? 1 : 0;
    if (k !== 'key') return x.localeCompare(y);
    const [px, nx] = keyParts(x);
    const [py, ny] = keyParts(y);
    return px.localeCompare(py) || nx - ny;
  };
  return items.map((it, i) => ({ it, i })).sort((a, b) => {
    for (const s of by) {
      const x = base(a.it, s.k);
      const y = base(b.it, s.k);
      const c = cmp(a.it, b.it, s.k);
      if (c) return x && y && s.desc ? -c : c;
    }
    return a.i - b.i;
  }).map((x) => x.it);
}
