import { useSyncExternalStore } from 'react';

/** Planning moves waiting to be pushed to Jira; kept per browser tab (sessionStorage) so a reload keeps them. */
export interface PendingMove { key: string; start: string; member_id: string }

const KEY = 'tt.planning.pending';
const listeners = new Set<() => void>();
let cache: string | null = null;
let parsed: PendingMove[] = [];

function read(): PendingMove[] {
  let raw: string | null = null;
  try { raw = sessionStorage.getItem(KEY); } catch { /* unavailable */ }
  if (raw !== cache) {
    cache = raw;
    try { parsed = raw ? JSON.parse(raw) : []; } catch { parsed = []; }
  }
  return parsed;
}

function write(moves: PendingMove[]) {
  try { sessionStorage.setItem(KEY, JSON.stringify(moves)); } catch { /* unavailable */ }
  listeners.forEach((l) => l());
}

export function usePendingMoves() {
  const moves = useSyncExternalStore((l) => { listeners.add(l); return () => listeners.delete(l); }, read);
  return {
    moves,
    /** Adds or replaces the move for an item; moving it back where Jira has it removes the move. */
    set: (m: PendingMove, original: { start: string; member_id: string | null }) => {
      const rest = read().filter((x) => x.key !== m.key);
      write(m.start === original.start && m.member_id === original.member_id ? rest : [...rest, m]);
    },
    revert: (key: string) => write(read().filter((x) => x.key !== key)),
    clear: () => write([]),
  };
}
