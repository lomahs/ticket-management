import { getRouteApi, useMatch, useNavigate, useParams } from '@tanstack/react-router';
import { useEffect } from 'react';
import { useVisibleAnken } from '../shared/api/queries';
import { Select } from '../shared/ds';
import { ankenLabel, storeAnken, useAnkenScope } from '../shared/state/anken';

const root = getRouteApi('__root__');
const ALL = '';

/**
 * The global anken switcher: one anken, or "All my anken". Mounted once in the shell, it also keeps this browser's
 * stored anken in step with the URL: an explicit `?anken=` is stored, and opening an anken's screen (`/anken/$epic/matrix...`)
 * selects it. The Overview's detail (`/anken/$epic`) does not: it shows one anken among the others and leaves the switcher alone.
 */
export function AnkenSwitcher({ compact = false }: { compact?: boolean }) {
  const { anken, setAnken } = useAnkenScope();
  const visible = useVisibleAnken();
  useFollowUrl(anken, visible);
  if (compact) return null;
  return (
    <Select
      aria-label="Anken"
      value={anken ?? ALL}
      options={[{ value: ALL, label: 'All my anken' }, ...visible.map((a) => ({ value: a.key, label: ankenLabel(a) }))]}
      onChange={(k) => setAnken(k || null)}
    />
  );
}

function useFollowUrl(anken: string | null, visible: { key: string }[]) {
  const explicit = root.useSearch({ select: (s) => s.anken });
  const epic = useParams({ strict: false, select: (p) => (p as { epic?: string }).epic });
  const detail = useMatch({ from: '/app/anken/$epic', shouldThrow: false });
  const navigate = useNavigate();
  // Only an anken the viewer may see is followed: a hidden one answers 404 and must not become the stored one.
  const opened = epic && !detail && visible.some((a) => a.key === epic) ? epic : undefined;
  // These react to the URL only, not to the stored value, so resetting a hidden anken is not undone while the URL catches up.
  useEffect(() => { if (explicit) storeAnken(explicit); }, [explicit]);
  useEffect(() => {
    if (!opened || opened === anken) return;
    storeAnken(opened);
    if (explicit) void navigate({ to: '.', search: (prev) => ({ ...prev, anken: opened }), replace: true });
    // eslint-disable-next-line react-hooks/exhaustive-deps -- only when another anken is opened
  }, [opened]);
}
