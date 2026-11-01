import { useQuery } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { useState } from 'react';
import { errorMessage } from '../../../shared/api/errors';
import type { Schemas } from '../../../shared/api/types';
import { Button, Select, TextField } from '../../../shared/ds';
import { STEPS } from '../../../shared/format/labels';
import { ankenLabel } from '../../../shared/state/anken';
import { useToast } from '../../../shared/state/toast';
import { Loading, QueryError } from '../../../shared/ui/states';
import { jiraQuery, stepWeightsQuery, useResetStepWeights, useSaveStepWeights } from '../api';
import { useSettingsTarget } from './AnkenPicker';
import { ErrText, Section } from './common';

/** Settings → Step weights: the share of the Create estimate each step takes, for one anken or the default. */
export function StepWeightsTab() {
  const t = useSettingsTarget();
  const anken = t.status === 'ready' ? t.anken : null;
  const q = useQuery({ ...stepWeightsQuery(anken), enabled: t.status === 'ready' });
  const navigate = useNavigate();
  const epics = useQuery(jiraQuery).data?.epics ?? [];
  if (t.status === 'pending') return <Loading />;
  if (t.status === 'error') return <QueryError error={t.error} />;
  return (
    <>
      <Select label="Settings for" value={anken ?? ''} style={{ minWidth: 280, alignSelf: 'flex-start' }}
        onChange={(k) => void navigate({ to: '.', search: (prev: Record<string, unknown>) => ({ ...prev, for: k || 'default' }) })}
        options={[{ value: '', label: 'Default' }, ...epics.map((e) => ({ value: e.key, label: ankenLabel(e) }))]} />
      {q.isPending ? <Loading /> : q.isError ? <QueryError error={q.error} /> : <WeightsForm key={`${anken ?? ''}:${JSON.stringify(q.data)}`} anken={anken} v={q.data} />}
    </>
  );
}

function WeightsForm({ anken, v }: { anken: string | null; v: Schemas['StepWeightsView'] }) {
  const toast = useToast();
  const save = useSaveStepWeights(anken);
  const reset = useResetStepWeights(anken ?? '');
  const [w, setW] = useState<Record<string, string>>(Object.fromEntries(STEPS.map((s) => [s.k, String(v.weights[s.k] ?? 0)])));
  const nums = Object.fromEntries(STEPS.map((s) => [s.k, Number(w[s.k])]));
  const bad = STEPS.some((s) => w[s.k].trim() === '' || !(nums[s.k] >= 0));
  return (
    <Section title="Step weights" sub={`Effort of a step = Create effort × its weight. JM Review only sets the dates of its own step. ${anken
      ? (v.own ? `${anken} has its own weights.` : `${anken} uses the default weights.`) : 'Anken without their own weights use these.'}`}>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(150px,1fr))', gap: 10 }}>
        {STEPS.map((s) => (
          <TextField key={s.k} label={s.label} type="number" value={w[s.k]} onChange={(x) => setW((p) => ({ ...p, [s.k]: x }))} />
        ))}
      </div>
      <div style={{ display: 'flex', gap: 8 }}>
        <Button disabled={bad || save.isPending} onClick={() => save.mutate(nums, {
          onSuccess: () => toast('Step weights saved'), onError: (e) => toast(errorMessage(e), true),
        })}>Save</Button>
        {anken && v.own && <Button appearance="subtle" disabled={reset.isPending} onClick={() => reset.mutate(undefined, {
          onSuccess: () => toast(`${anken} uses the default weights`), onError: (e) => toast(errorMessage(e), true),
        })}>Use default</Button>}
        {bad && <ErrText>Every weight is a number of 0 or more.</ErrText>}
      </div>
    </Section>
  );
}
