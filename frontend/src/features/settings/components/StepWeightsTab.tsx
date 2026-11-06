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

/**
 * Settings → Step weights: the share of the Create estimate each step takes, and how a BA's day is shared between Create and
 * Fix comment, for one anken or the default.
 */
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

type Pic = 'BA' | 'BAL';
const COLS = 'minmax(140px,1.4fr) repeat(4,minmax(90px,1fr))';

/**
 * Per step: its weight (× Create), who Jira assigns it to at push (PIC) and the percent of its planned hours the function's
 * BA and BAL carry in every effort number (0 and 0: counted for nobody).
 */
function WeightsForm({ anken, v }: { anken: string | null; v: Schemas['StepWeightsView'] }) {
  const toast = useToast();
  const save = useSaveStepWeights(anken);
  const reset = useResetStepWeights(anken ?? '');
  const [w, setW] = useState<Record<string, string>>(Object.fromEntries(STEPS.map((s) => [s.k, String(v.weights[s.k] ?? 0)])));
  const [pic, setPic] = useState<Record<string, Pic>>(Object.fromEntries(STEPS.map((s) => [s.k, v.pic[s.k] === 'BAL' ? 'BAL' : 'BA'])));
  const [ba, setBa] = useState<Record<string, string>>(Object.fromEntries(STEPS.map((s) => [s.k, String(v.shares[s.k]?.ba ?? 0)])));
  const [bal, setBal] = useState<Record<string, string>>(Object.fromEntries(STEPS.map((s) => [s.k, String(v.shares[s.k]?.bal ?? 0)])));
  const [day, setDay] = useState({ create: String(v.day_split?.create ?? 2), fix: String(v.day_split?.fix ?? 1) });
  const num = (r: Record<string, string>, k: string) => Number(r[k]);
  const dayCreate = Number(day.create);
  const dayFix = Number(day.fix);
  const badDay = day.create.trim() === '' || day.fix.trim() === '' || !(dayCreate >= 0 && dayFix >= 0) || dayCreate + dayFix <= 0;
  const fixPct = badDay ? 0 : Math.round((dayFix / (dayCreate + dayFix)) * 100);
  const badWeight = STEPS.some((s) => w[s.k].trim() === '' || !(num(w, s.k) >= 0));
  const badShare = STEPS.some((s) => [ba, bal].some((r) => r[s.k].trim() === '' || !(num(r, s.k) >= 0 && num(r, s.k) <= 100))
    || num(ba, s.k) + num(bal, s.k) > 100);
  const body = (): Schemas['StepWeightsIn'] => ({
    weights: Object.fromEntries(STEPS.map((s) => [s.k, num(w, s.k)])),
    pic: { ...pic },
    shares: Object.fromEntries(STEPS.map((s) => [s.k, { ba: num(ba, s.k), bal: num(bal, s.k) }])),
    day_split: { create: dayCreate, fix: dayFix },
  });
  const field = (label: string, r: Record<string, string>, set: (f: (p: Record<string, string>) => Record<string, string>) => void, k: string) => (
    <TextField aria-label={label} type="number" size="sm" value={r[k]} onChange={(x) => set((p) => ({ ...p, [k]: x }))} />
  );
  return (
    <Section title="Step weights" sub={`Effort of a step = Create effort × its weight. PIC: who Jira assigns the sub-task to when the plan is pushed (the function's BA or BAL). Effort: how the step's planned hours are shared between the function's BA and BAL in every screen; 0 % and 0 % count for nobody (JM Review, which only sets the dates of its own step). Logged hours count for whoever logs them. ${anken
      ? (v.own ? `${anken} has its own weights.` : `${anken} uses the default weights.`) : 'Anken without their own weights use these.'}`}>
      <div role="table" aria-label="Step weights" style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        <div role="row" className="eyebrow" style={{ display: 'grid', gridTemplateColumns: COLS, gap: 10 }}>
          <span role="columnheader">Step</span><span role="columnheader">Weight (× Create)</span><span role="columnheader">PIC</span>
          <span role="columnheader">Effort: BA %</span><span role="columnheader">Effort: BAL %</span>
        </div>
        {STEPS.map((s) => (
          <div key={s.k} role="row" style={{ display: 'grid', gridTemplateColumns: COLS, gap: 10, alignItems: 'center' }}>
            <span role="cell" className="heading">{s.label}</span>
            <span role="cell">{field(`${s.label} weight`, w, setW, s.k)}</span>
            <span role="cell"><Select aria-label={`${s.label} PIC`} value={pic[s.k]} onChange={(x) => setPic((p) => ({ ...p, [s.k]: x as Pic }))}
              options={[{ value: 'BA', label: 'BA' }, { value: 'BAL', label: 'BAL' }]} /></span>
            <span role="cell">{field(`${s.label} BA share`, ba, setBa, s.k)}</span>
            <span role="cell">{field(`${s.label} BAL share`, bal, setBal, s.k)}</span>
          </div>
        ))}
      </div>
      <div role="group" aria-label="A BA's day in the detail plan" style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        <span className="heading" style={{ fontWeight: 600 }}>A BA&apos;s day in the detail plan</span>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
          <span>Create and tickets</span>
          <span style={{ width: 80 }}><TextField aria-label="Create part of a BA's day" type="number" size="sm" value={day.create}
            onChange={(x) => setDay((d) => ({ ...d, create: x }))} /></span>
          <span>:</span>
          <span style={{ width: 80 }}><TextField aria-label="Fix comment part of a BA's day" type="number" size="sm" value={day.fix}
            onChange={(x) => setDay((d) => ({ ...d, fix: x }))} /></span>
          <span>Fix comment</span>
          {!badDay && <span className="muted" style={{ fontSize: 12 }}>{`= ${100 - fixPct} % and ${fixPct} %`}</span>}
        </div>
        <span className="eyebrow">How the detail plan and Auto allocate share each day of a BA: Create (and tickets), and the BA&apos;s part of the Fix comments that can start, the function whose Create ended first first. What one side does not use goes to the other: with no Fix comment to do, Create takes the whole day. The BAL&apos;s reviews take the BAL&apos;s whole day.</span>
      </div>
      <div style={{ display: 'flex', gap: 8 }}>
        <Button disabled={badWeight || badShare || badDay || save.isPending} onClick={() => save.mutate(body(), {
          onSuccess: () => toast('Step weights saved'), onError: (e) => toast(errorMessage(e), true),
        })}>Save</Button>
        {anken && v.own && <Button appearance="subtle" disabled={reset.isPending} onClick={() => reset.mutate(undefined, {
          onSuccess: () => toast(`${anken} uses the default weights`), onError: (e) => toast(errorMessage(e), true),
        })}>Use default</Button>}
        {badWeight && <ErrText>Every weight is a number of 0 or more.</ErrText>}
        {badShare && <ErrText>Each share is 0 to 100 %, and the BA and BAL shares of a step are 100 % at most together.</ErrText>}
        {badDay && <ErrText>The Create and Fix comment parts of a BA&apos;s day are 0 or more, and not both 0.</ErrText>}
      </div>
    </Section>
  );
}
