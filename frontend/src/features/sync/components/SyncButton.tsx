import { errorMessage } from '../../../shared/api/errors';
import { useMeta } from '../../../shared/api/queries';
import { Icon } from '../../../shared/ds';
import { relTime } from '../../../shared/format/dates';
import { useToast } from '../../../shared/state/toast';
import { useSyncNow } from '../api';
import { useTick } from './useTick';

/** Sidebar row that runs an incremental Jira sync; icon-only when the sidebar is collapsed. */
export function SyncButton({ compact = false }: { compact?: boolean }) {
  const meta = useMeta().data;
  const sync = useSyncNow();
  const toast = useToast();
  useTick(30_000);
  const s = meta?.sync;
  const failed = s?.status === 'failed';
  const unset = !!meta && meta.mode !== 'demo' && !meta.projects.length;
  const sub = sync.isPending ? 'Syncing…'
    : unset ? 'Jira not set up yet'
    : failed ? `Failed · ${relTime(s?.finished_at ?? null)}`
    : s?.last_ok_at ? `Synced ${relTime(s.last_ok_at)}` : 'Not synced yet';
  const run = () => sync.mutate(false, {
    onSuccess: (r) => { if (r.status === 'failed') toast(r.error ?? 'Sync failed', true); else toast('Synced with Jira'); },
    onError: (e) => toast(errorMessage(e), true),
  });

  return (
    <button className="hv-bg" onClick={run} disabled={sync.isPending || unset} aria-label="Sync Jira"
      title={failed && s?.error ? `Sync Jira · last sync failed: ${s.error}` : `Sync Jira · ${sub}`} style={{
        display: 'flex', alignItems: 'center', justifyContent: compact ? 'center' : 'flex-start', gap: 10, width: '100%', minHeight: 34,
        padding: '4px 10px', border: 0, borderRadius: 10, background: 'transparent', textAlign: 'left',
        color: failed ? 'var(--hl-red-fg)' : 'var(--text-body)', cursor: sync.isPending || unset ? 'default' : 'pointer', opacity: unset ? 0.6 : 1,
      }}>
      <span style={{ display: 'flex', lineHeight: 0, animation: sync.isPending ? 'ttspin 0.9s linear infinite' : 'none' }}><Icon name="refresh-cw" size={16} /></span>
      {!compact && (
        <span style={{ flex: 1, minWidth: 0, whiteSpace: 'nowrap' }}>
          <span style={{ display: 'block', font: '500 13px/1.2 var(--font-text)' }}>Sync Jira</span>
          <span className="ellipsis" style={{ display: 'block', fontSize: 11, color: failed ? 'var(--hl-red-fg)' : 'var(--text-muted)' }}>{sub}</span>
        </span>
      )}
    </button>
  );
}
