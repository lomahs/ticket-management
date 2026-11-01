import { useQuery } from '@tanstack/react-query';
import { Link, useNavigate } from '@tanstack/react-router';
import { useState } from 'react';
import { errorMessage } from '../../../shared/api/errors';
import { Button, TextField } from '../../../shared/ds';
import { relTime } from '../../../shared/format/dates';
import { useToast } from '../../../shared/state/toast';
import { onboardingQuery, useLookupEpic, useStartOnboarding } from '../api';
import { StepShell, Stepper } from '../components/StepShell';

/** Step 1 of a new anken: look the epic up in Jira, then track it and start its onboarding. */
export function NewOnboardingPage() {
  const toast = useToast();
  const navigate = useNavigate();
  const [key, setKey] = useState('');
  const lookup = useLookupEpic();
  const start = useStartOnboarding();
  const e = lookup.data;
  const go = () => start.mutate(e!.key, {
    onSuccess: (o) => void navigate({ to: '/onboarding/$epic/$step', params: { epic: o.epic_key, step: o.step === 2 ? 'tasks' : 'epic' } }),
    onError: (err) => toast(errorMessage(err), true),
  });
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16, width: '100%' }}>
      <Stepper current={1} reached={1} />
      <header style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
        <h1 style={{ margin: 0, font: '700 22px/1.2 var(--font-text)', color: 'var(--text-heading)' }}>Add epic ticket</h1>
        <span className="eyebrow">The epic is the anken. Look it up in Jira, then track it so the next steps can read and write under it.</span>
      </header>
      <section className="card" style={{ padding: '16px 18px', display: 'flex', flexDirection: 'column', gap: 14, maxWidth: 760 }}>
        <form style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'flex-end', gap: 10 }}
          onSubmit={(ev) => { ev.preventDefault(); if (key.trim()) lookup.mutate(key.trim()); }}>
          <TextField mono label="Epic key" placeholder="e.g. PAY-1420" value={key} onChange={(v) => setKey(v.toUpperCase())} style={{ width: 220 }} />
          <Button type="submit" appearance="secondary" iconBefore="search" disabled={!key.trim() || lookup.isPending}>Look up in Jira</Button>
        </form>
        {lookup.isError && <div role="alert" style={{ padding: '10px 14px', borderRadius: 10, background: 'var(--hl-red-bg)', color: 'var(--hl-red-fg)', fontWeight: 600 }}>{errorMessage(lookup.error)}</div>}
        {e && (
          <div className="card-clip" style={{ border: '1px solid var(--border-subtle)', borderRadius: 12 }}>
            <div style={{ padding: '12px 14px', display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 10, background: 'var(--surface-hover)' }}>
              <span style={{ font: '600 12px/1 var(--font-mono)', color: 'var(--color-accent)' }}>{e.key}</span>
              <b className="heading" style={{ fontSize: 15 }}>{e.title}</b>
            </div>
            <dl style={{ margin: 0, padding: '12px 14px', display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(150px,1fr))', gap: 10 }}>
              <div><dt className="eyebrow">Project</dt><dd style={{ margin: 0, fontWeight: 600 }}>{e.project}</dd></div>
              <div><dt className="eyebrow">Tracked by the app</dt><dd style={{ margin: 0, fontWeight: 600, color: e.tracked ? 'var(--text-heading)' : 'var(--hl-red-fg)' }}>{e.tracked ? 'Yes' : 'Not yet'}</dd></div>
              <div><dt className="eyebrow">Onboarding</dt><dd style={{ margin: 0, fontWeight: 600 }}>{e.onboarding ? 'Started already' : 'Not started'}</dd></div>
            </dl>
            <div style={{ padding: '12px 14px', borderTop: '1px solid var(--border-subtle)', fontSize: 12 }} className="muted">
              {e.tracked ? 'The epic is tracked already: its onboarding starts (or resumes) here.'
                : 'Continuing adds it to Settings › Jira › Tracked epics and runs a full sync.'}
            </div>
          </div>
        )}
      </section>
      <div className="card" style={{ position: 'sticky', bottom: 12, display: 'flex', alignItems: 'center', gap: 10, padding: '12px 16px' }}>
        <Link to="/onboarding" style={{ fontSize: 13, fontWeight: 600 }}>Cancel</Link>
        <span className="spacer" />
        <Button disabled={!e || start.isPending} onClick={go}>{e?.tracked ? 'Continue' : 'Track & continue'}</Button>
      </div>
    </div>
  );
}

/** Step 1 once the epic is tracked: what it is and when its onboarding started. */
export function EpicStep({ epic }: { epic: string }) {
  const o = useQuery(onboardingQuery(epic)).data;
  return (
    <StepShell epic={epic} step="epic" title="Add epic ticket" sub="The epic is the anken; it is tracked by the app.">
      <section className="card" style={{ padding: '16px 18px', display: 'flex', flexDirection: 'column', gap: 6, maxWidth: 760 }}>
        <span style={{ font: '600 12px/1 var(--font-mono)', color: 'var(--color-accent)' }}>{epic}</span>
        <b className="heading" style={{ fontSize: 15 }}>{o?.title}</b>
        {o && <span className="eyebrow">{`Onboarding started ${relTime(o.started_at)} by ${o.started_by}`}</span>}
      </section>
    </StepShell>
  );
}
