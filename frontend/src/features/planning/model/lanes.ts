import { add, dayDiff } from '../../../shared/format/dates';

/** Gantt geometry: which visible day columns an item covers and which lane it sits in. */

export interface Span { key: string; start: string; end: string }
export interface Block { key: string; col: number; span: number; lane: number }

/** First visible column on/after `d` (days.length when none). */
export const startCol = (days: string[], d: string) => {
  const i = days.findIndex((x) => x >= d);
  return i < 0 ? days.length : i;
};

/** Last visible column on/before `d` (−1 when none). */
export const endCol = (days: string[], d: string) => {
  let i = -1;
  days.forEach((x, j) => { if (x <= d) i = j; });
  return i;
};

export function layoutLanes(items: Span[], days: string[]): { blocks: Block[]; lanes: number } {
  const sorted = [...items].sort((a, b) => (a.start < b.start ? -1 : a.start > b.start ? 1 : a.end < b.end ? -1 : a.end > b.end ? 1 : 0));
  const lanes: number[] = [];
  const blocks: Block[] = [];
  for (const it of sorted) {
    const s = startCol(days, it.start);
    if (s >= days.length) continue;
    const e = Math.max(s, endCol(days, it.end));
    let lane = lanes.findIndex((last) => last < s);
    if (lane < 0) { lane = lanes.length; lanes.push(e); } else lanes[lane] = e;
    blocks.push({ key: it.key, col: s, span: e - s + 1, lane });
  }
  return { blocks, lanes: Math.max(1, lanes.length) };
}

/** Start date for a block dropped at `col` (already corrected for where it was grabbed). */
export function startForDrop(days: string[], col: number, itemStart: string, grab: number): string {
  const c = Math.max(0, Math.min(days.length - 1, col - grab));
  const offset = dayDiff(days[startCol(days, itemStart)] ?? itemStart, itemStart);
  return add(days[c], offset);
}
