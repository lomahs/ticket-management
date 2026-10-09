import { useIsMutating } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { errorMessage } from '../../../shared/api/errors';
import { useMeta } from '../../../shared/api/queries';
import { Icon } from '../../../shared/ds';
import { relTime } from '../../../shared/format/dates';
import { useToast } from '../../../shared/state/toast';
import { Dot } from '../../../shared/ui/bits';
import { useSyncNow } from '../api';

function useTick(ms: number) {
  const [, set] = useState(0);
  useEffect(() => {
    const t = setInterval(() => set((n) => n + 1), ms);
    return () => clearInterval(t);
  }, [ms]);
}

/** "Synced with Jira · 2 minutes ago" with a re-sync button; shows writes in flight and the last failure. */
export function SyncPill() {
  const meta = useMeta();
  const writing = useIsMutating({ mutationKey: ['write'] });
  const sync = useSyncNow();
  const toast = useToast();
  useTick(30_000);
  const s = meta.data?.sync;
  const failed = s?.status === 'failed';
  const label = writing ? `Writing ${writing} change${writing > 1 ? 's' : ''} to Jira…`
    : sync.isPending ? 'Syncing…'
    : failed ? `Last sync failed · ${relTime(s?.finished_at ?? null)}`
    : s?.last_ok_at ? `Synced with Jira · ${relTime(s.last_ok_at)}` : 'Not synced yet';
  const refresh = () => sync.mutate(false, {
    onSuccess: (r) => { if (r.status === 'failed') toast(r.error ?? 'Sync failed', true); },
    onError: (e) => toast(errorMessage(e), true),
  });

  return (
    <div title={failed ? s?.error ?? undefined : undefined} style={{
      display: 'inline-flex', alignItems: 'center', gap: 6, height: 28, padding: '0 4px 0 10px', borderRadius: 999, border: '1px solid var(--border-subtle)',
      background: 'var(--surface-card)', fontSize: 12, color: failed ? 'var(--hl-red-fg)' : 'var(--text-muted)', whiteSpace: 'nowrap',
    }}>
      <Dot c={writing || sync.isPending ? 'var(--st-wip)' : failed ? 'var(--hl-red-fg)' : 'var(--st-clo)'} />
      <span>{label}</span>
      <button className="hv-bg" onClick={refresh} disabled={sync.isPending} title="Sync again from Jira" aria-label="Sync again from Jira" style={{
        display: 'flex', alignItems: 'center', justifyContent: 'center', width: 24, height: 24, padding: 0, border: 0, borderRadius: '50%',
        background: 'transparent', color: 'var(--text-heading)', cursor: 'pointer',
      }}>
        <span style={{ display: 'flex', lineHeight: 0, animation: sync.isPending ? 'ttspin 0.9s linear infinite' : 'none' }}><Icon name="refresh-cw" size={13} /></span>
      </button>
    </div>
  );
}
