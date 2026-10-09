import { useSyncExternalStore } from 'react';

/** Per-browser preferences in localStorage (theme, default "View as" member). Never required for correctness. */
const listeners = new Set<() => void>();
const read = (k: string) => {
  try { return localStorage.getItem('tt.' + k); } catch { return null; }
};
const write = (k: string, v: string) => {
  try { localStorage.setItem('tt.' + k, v); } catch { /* storage unavailable */ }
  listeners.forEach((l) => l());
};
const subscribe = (l: () => void) => {
  listeners.add(l);
  return () => listeners.delete(l);
};

export function usePref(key: string, fallback: string): [string, (v: string) => void] {
  const v = useSyncExternalStore(subscribe, () => read(key) ?? fallback);
  return [v, (nv: string) => write(key, nv)];
}

export const systemTheme = () => (typeof matchMedia !== 'undefined' && matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
export const readPref = read;
