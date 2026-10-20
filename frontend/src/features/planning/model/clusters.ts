/** Gantt blocks of one member row: tickets of the same function whose plans share a day are drawn as one block. */

export interface Clusterable { key: string; start: string; end: string; group: string | null; order: number }
export interface Cluster<T> { key: string; start: string; end: string; items: T[] }

export function clusterByFunction<T extends Clusterable>(items: T[]): Cluster<T>[] {
  const out: Cluster<T>[] = [];
  const byGroup = new Map<string, T[]>();
  for (const it of items) {
    if (it.group === null) out.push({ key: it.key, start: it.start, end: it.end, items: [it] });
    else byGroup.set(it.group, [...(byGroup.get(it.group) ?? []), it]);
  }
  for (const list of byGroup.values()) {
    let cur: Cluster<T> | null = null;
    for (const it of [...list].sort((a, b) => (a.start < b.start ? -1 : a.start > b.start ? 1 : 0))) {
      if (cur && it.start <= cur.end) {
        cur.items.push(it);
        if (it.end > cur.end) cur.end = it.end;
      } else {
        cur = { key: it.key, start: it.start, end: it.end, items: [it] };
        out.push(cur);
      }
    }
  }
  for (const c of out) {
    c.items.sort((a, b) => a.order - b.order || (a.start < b.start ? -1 : a.start > b.start ? 1 : 0));
    c.key = c.items[0].key;
  }
  return out;
}

/** Equal hard-edged stripes, one per colour, left to right. */
export function stripes(colors: string[]): string {
  if (colors.length === 1) return colors[0];
  const w = 100 / colors.length;
  return `linear-gradient(to right,${colors.map((c, i) => `${c} ${(i * w).toFixed(2)}% ${((i + 1) * w).toFixed(2)}%`).join(',')})`;
}
