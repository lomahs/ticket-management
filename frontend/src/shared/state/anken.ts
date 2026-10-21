import { getRouteApi, useNavigate, useParams } from '@tanstack/react-router';
import { readPref, usePref, writePref } from './prefs';

const root = getRouteApi('__root__');
const KEY = 'anken';

/** The anken this browser last picked in the switcher; null: "All my anken". */
export const storedAnken = (): string | null => readPref(KEY) || null;

/** Picks "All my anken" in this browser (the stored anken is no longer visible). */
export const forgetAnken = () => writePref(KEY, '');

/** Remembers `key` as this browser's anken. */
export const storeAnken = (key: string) => writePref(KEY, key);

/** How the switcher and the anken pickers name an anken. */
export const ankenLabel = (a: { key: string; title: string }) => (a.title ? `${a.key} · ${a.title}` : a.key);

/** The `anken` query parameter of a scoped request: sent only for one anken. */
export const ankenParam = (anken: string | null): { anken?: string } => (anken ? { anken } : {});

/**
 * The anken every screen shows (the global switcher); null means "All my anken".
 * An explicit `?anken=` in the URL wins, else the one stored in this browser.
 * `setAnken` stores the choice and puts it in the URL. On a route that carries an epic (`/anken/$epic/...`) it opens
 * the same screen of the picked anken; "All" there goes to the Overview of every anken.
 */
export function useAnkenScope(): { anken: string | null; setAnken: (k: string | null) => void } {
  const inUrl = root.useSearch({ select: (s) => s.anken });
  const [stored] = usePref(KEY, '');
  const epic = useParams({ strict: false, select: (p) => (p as { epic?: string }).epic });
  // Not root.useNavigate(): that resolves '.' from '/' (see useOpenTicket).
  const navigate = useNavigate();
  const setAnken = (k: string | null) => {
    writePref(KEY, k ?? '');
    if (epic === undefined) void navigate({ to: '.', search: (prev) => ({ ...prev, anken: k ?? undefined }) });
    else if (k) void navigate({ to: '.', params: (p: Record<string, string>) => ({ ...p, epic: k }), search: { anken: k } });
    else void navigate({ to: '/anken', search: {} });
  };
  return { anken: inUrl || stored || null, setAnken };
}
