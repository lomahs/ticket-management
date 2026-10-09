import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { api, ApiError } from '../api/client';
import type { Snapshot } from '../api/types';
import { makeCalendar, type Cal } from '../lib/domain';

export type Screen = 'mywork' | 'matrix' | 'overview' | 'planning' | 'effort' | 'calendar' | 'settings';
const SCREENS: Screen[] = ['mywork', 'matrix', 'overview', 'planning', 'effort', 'calendar', 'settings'];

const store = {
  get: (k: string) => { try { return localStorage.getItem('tt.' + k); } catch { return null; } },
  set: (k: string, v: string) => { try { localStorage.setItem('tt.' + k, v); } catch { /* storage unavailable */ } },
};

const screenFromHash = (): Screen => {
  const h = location.hash.replace(/^#\/?/, '') as Screen;
  return SCREENS.includes(h) ? h : 'matrix';
};

interface Toast { id: number; msg: string; error?: boolean }

interface AppValue {
  snap: Snapshot;
  cal: Cal;
  setSnap: (s: Snapshot) => void;
  screen: Screen;
  go: (s: Screen) => void;
  me: string;
  setMe: (id: string) => void;
  theme: 'light' | 'dark';
  toggleTheme: () => void;
  ticket: string | null;
  openTicket: (key: string | null) => void;
  toast: Toast | null;
  showToast: (msg: string, error?: boolean) => void;
  closeToast: () => void;
  pending: number;
  /** Runs a write; on success replaces the snapshot and, with `desc`, confirms "Written to Jira". */
  write: (op: Promise<Snapshot>, desc?: string) => Promise<Snapshot | null>;
  failed: (e: unknown) => void;
}

const Ctx = createContext<AppValue | null>(null);

export function useApp() {
  const v = useContext(Ctx);
  if (!v) throw new Error('useApp outside AppProvider');
  return v;
}

export function AppProvider({ children }: { children: ReactNode }) {
  const [snap, setSnap] = useState<Snapshot | null>(null);
  const [loadErr, setLoadErr] = useState<string | null>(null);
  const [screen, setScreen] = useState<Screen>(screenFromHash);
  const [me, setMeState] = useState(() => store.get('me') || 'binh');
  const [theme, setTheme] = useState<'light' | 'dark'>(() =>
    (store.get('theme') as 'light' | 'dark') || (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'));
  const [ticket, setTicket] = useState<string | null>(null);
  const [toast, setToast] = useState<Toast | null>(null);
  const [pending, setPending] = useState(0);
  const tt = useRef<number | undefined>(undefined);

  useEffect(() => {
    api.state().then(setSnap).catch((e) => setLoadErr(e instanceof Error ? e.message : String(e)));
  }, []);
  useEffect(() => {
    const on = () => setScreen(screenFromHash());
    addEventListener('hashchange', on);
    return () => removeEventListener('hashchange', on);
  }, []);
  useEffect(() => { document.documentElement.setAttribute('data-theme', theme); }, [theme]);

  const showToast = useCallback((msg: string, error = false) => {
    clearTimeout(tt.current);
    setToast({ id: Date.now(), msg, error });
    tt.current = window.setTimeout(() => setToast(null), error ? 5000 : 3200);
  }, []);
  const failed = useCallback((e: unknown) => {
    showToast(e instanceof ApiError || e instanceof Error ? e.message : 'Something went wrong', true);
  }, [showToast]);

  const write = useCallback(async (op: Promise<Snapshot>, desc?: string) => {
    setPending((n) => n + 1);
    try {
      const s = await op;
      setSnap(s);
      if (desc) showToast('Written to Jira · ' + desc);
      return s;
    } catch (e) {
      failed(e);
      return null;
    } finally {
      setPending((n) => n - 1);
    }
  }, [showToast, failed]);

  const cal = useMemo(() => (snap ? makeCalendar(snap) : null), [snap]);

  if (loadErr) {
    return (
      <div style={{ padding: 40, font: '14px/1.5 var(--font-text)', color: 'var(--text-body)' }}>
        <b>Could not reach the Team Tracker API.</b> Start the backend (see README) and reload.
        <div style={{ color: 'var(--text-muted)' }}>{loadErr}</div>
      </div>
    );
  }
  if (!snap || !cal) return null;

  const value: AppValue = {
    snap, cal, setSnap, screen,
    go: (s) => { location.hash = '/' + s; setScreen(s); },
    me,
    setMe: (id) => { store.set('me', id); setMeState(id); },
    theme,
    toggleTheme: () => setTheme((t) => { const n = t === 'dark' ? 'light' : 'dark'; store.set('theme', n); return n; }),
    ticket, openTicket: setTicket,
    toast, showToast, closeToast: () => setToast(null),
    pending, write, failed,
  };
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}
