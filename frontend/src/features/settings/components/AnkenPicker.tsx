import { useQuery } from '@tanstack/react-query';
import { useNavigate, useSearch } from '@tanstack/react-router';
import { useState } from 'react';
import { errorMessage } from '../../../shared/api/errors';
import { Button, Select } from '../../../shared/ds';
import { ankenLabel, useAnkenScope } from '../../../shared/state/anken';
import { useToast } from '../../../shared/state/toast';
import { jiraQuery, useCopyAnkenSettings } from '../api';

const DEFAULT = '';

type Target = { status: 'pending' } | { status: 'error'; error: Error } | { status: 'ready'; anken: string | null };

/**
 * The anken whose rules Settings edits: `?for=` (`default`: the template for new anken), else the switcher's anken, else the
 * default. It stays pending until the tracked epics are known, so a tab never loads or saves the wrong target; an anken that
 * is not tracked falls back to the default.
 */
export function useSettingsTarget(): Target {
  const { anken: scoped } = useAnkenScope();
  const forParam = useSearch({ strict: false, select: (s) => (s as { for?: string }).for });
  const jira = useQuery(jiraQuery);
  if (jira.isPending) return { status: 'pending' };
  if (jira.isError) return { status: 'error', error: jira.error };
  const want = forParam ? (forParam === 'default' ? null : forParam) : scoped;
  return { status: 'ready', anken: want && jira.data.epics.some((e) => e.key === want) ? want : null };
}

/** Picks the anken (or the default) that Phases & tasks and Name rules show; for an anken it can copy another's rules in. */
export function AnkenPicker({ anken, onCopied }: { anken: string | null; onCopied: () => void }) {
  const navigate = useNavigate();
  const epics = useQuery(jiraQuery).data?.epics ?? [];
  const toast = useToast();
  const copy = useCopyAnkenSettings(anken ?? '');
  const [copying, setCopying] = useState(false);
  const [from, setFrom] = useState('');
  const pick = (k: string) => { setCopying(false); setFrom(''); void navigate({ to: '.', search: (prev: Record<string, unknown>) => ({ ...prev, for: k || 'default' }) }); };
  const fromOpts = [
    { value: '', label: 'Choose…' }, { value: 'default', label: 'Default for new anken' },
    ...epics.filter((e) => e.key !== anken).map((e) => ({ value: e.key, label: ankenLabel(e) })),
  ];
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
      <div style={{ display: 'flex', alignItems: 'flex-end', gap: 10, flexWrap: 'wrap' }}>
        <Select label="Anken" value={anken ?? DEFAULT} style={{ minWidth: 280 }} onChange={pick}
          options={[{ value: DEFAULT, label: 'Default for new anken' }, ...epics.map((e) => ({ value: e.key, label: ankenLabel(e) }))]} />
        {anken && !copying && <Button appearance="secondary" onClick={() => setCopying(true)}>Copy from…</Button>}
        {anken && copying && (
          <>
            <Select aria-label="Copy from" value={from} options={fromOpts} onChange={setFrom} style={{ minWidth: 240 }} />
            <Button disabled={!from || copy.isPending} onClick={() => copy.mutate(from, {
              onSuccess: () => { toast(`Copied settings from ${from}. Sync started.`); setCopying(false); setFrom(''); onCopied(); },
              onError: (e) => toast(errorMessage(e), true),
            })}>Copy</Button>
            <Button appearance="subtle" onClick={() => { setCopying(false); setFrom(''); }}>Cancel</Button>
          </>
        )}
      </div>
      <span className="eyebrow">
        {anken ? `These rules apply to ${anken} only.` : 'New anken start with these rules. Existing anken keep their own.'}
      </span>
    </div>
  );
}
