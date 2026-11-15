import { useQuery } from '@tanstack/react-query';
import { Link, useNavigate } from '@tanstack/react-router';
import { useState } from 'react';
import { errorMessage } from '../../../shared/api/errors';
import type { Schemas } from '../../../shared/api/types';
import { Button, TextField } from '../../../shared/ds';
import { relTime } from '../../../shared/format/dates';
import { useToast } from '../../../shared/state/toast';
import { onboardingQuery, useLinkEpic, useLookupEpic, useStartDraft, useStartOnboarding } from '../api';
import { NoEpic, StepShell, Stepper } from '../components/StepShell';

type EpicLookup = Schemas['EpicLookup'];

/** Step 1 of a new anken: either its epic (looked up in Jira, then tracked) or, when there is none yet, its name. */
export function NewOnboardingPage() {
  const toast = useToast();
  const navigate = useNavigate();
  const [mode, setMode] = useState<'epic' | 'draft'>('epic');
  const [key, setKey] = useState('');
  const [name, setName] = useState('');
  const lookup = useLookupEpic();
  const start = useStartOnboarding();
  const draft = useStartDraft();
  const e = lookup.data;
  const opened = (o: { epic_key: string; step: number }) =>
    void navigate({ to: '/onboarding/$epic/$step', params: { epic: o.epic_key, step: o.step === 2 ? 'tasks' : 'epic' } });
  const go = () => {
    if (mode === 'draft') {
      draft.mutate(name.trim(), { onSuccess: opened, onError: (err) => toast(errorMessage(err), true) });
    } else if (e) {
      start.mutate(e.key, { onSuccess: opened, onError: (err) => toast(errorMessage(err), true) });
    }
  };
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16, width: '100%' }}>
      <Stepper current={1} reached={1} />
      <header style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
        <h1 style={{ margin: 0, font: '700 22px/1.2 var(--font-text)', color: 'var(--text-heading)' }}>Add epic ticket</h1>
        <span className="eyebrow">The epic is the anken. Look it up in Jira and track it, or plan the anken first and link its epic once it exists.</span>
      </header>
      <section className="card" style={{ padding: '16px 18px', display: 'flex', flexDirection: 'column', gap: 14, maxWidth: 760 }}>
        <fieldset style={{ margin: 0, padding: 0, border: 0, display: 'flex', flexWrap: 'wrap', gap: 10 }}>
          <legend className="eyebrow" style={{ marginBottom: 8 }}>Does the anken have its epic in Jira?</legend>
          <Choice checked={mode === 'epic'} onChange={() => setMode('epic')} title="Yes, the epic exists" sub="Track it and onboard under it, as usual." />
          <Choice checked={mode === 'draft'} onChange={() => setMode('draft')} title="No, not yet"
            sub="Plan it now in the app; create its Tasks and push its sub-tasks once you link the epic." />
        </fieldset>
        {mode === 'epic' ? (
          <>
            <form style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'flex-end', gap: 10 }}
              onSubmit={(ev) => { ev.preventDefault(); if (key.trim()) lookup.mutate(key.trim()); }}>
              <TextField mono label="Epic key" placeholder="e.g. PAY-1420" value={key} onChange={(v) => setKey(v.toUpperCase())} style={{ width: 220 }} />
              <Button type="submit" appearance="secondary" iconBefore="search" disabled={!key.trim() || lookup.isPending}>Look up in Jira</Button>
            </form>
            {lookup.isError && <Alert>{errorMessage(lookup.error)}</Alert>}
            {e && <EpicCard e={e} note={e.tracked ? 'The epic is tracked already: its onboarding starts (or resumes) here.'
              : 'Continuing adds it to Settings › Jira › Tracked epics and runs a full sync.'} />}
          </>
        ) : (
          <>
            <TextField label="Anken name" placeholder="e.g. Refund automation" value={name} onChange={setName} style={{ maxWidth: 420 }} />
            <span className="muted" style={{ fontSize: 12 }}>
              The anken is kept in the app only and shows in Onboarding, not on the other screens. Its members' hours are weighed
              against their other anken, and never count on them.
            </span>
          </>
        )}
      </section>
      <div className="card action-bar">
        <Link to="/onboarding" style={{ fontSize: 13, fontWeight: 600 }}>Cancel</Link>
        <span className="spacer" />
        {mode === 'draft'
          ? <Button disabled={!name.trim() || draft.isPending} onClick={go}>Start without epic</Button>
          : <Button disabled={!e || start.isPending} onClick={go}>{e?.tracked ? 'Continue' : 'Track & continue'}</Button>}
      </div>
    </div>
  );
}

function Choice({ checked, onChange, title, sub }: { checked: boolean; onChange: () => void; title: string; sub: string }) {
  return (
    <label style={{
      flex: '1 1 260px', display: 'flex', gap: 10, alignItems: 'flex-start', padding: '10px 12px', borderRadius: 10, cursor: 'pointer',
      border: `1.5px solid ${checked ? 'var(--color-accent)' : 'var(--border-subtle)'}`, background: checked ? 'var(--surface-hover)' : undefined,
    }}>
      <input type="radio" name="has-epic" checked={checked} onChange={onChange} style={{ marginTop: 3 }} />
      <span style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
        <b className="heading">{title}</b>
        <span className="muted" style={{ fontSize: 12 }}>{sub}</span>
      </span>
    </label>
  );
}

function Alert({ children }: { children: string }) {
  return <div role="alert" style={{ padding: '10px 14px', borderRadius: 10, background: 'var(--hl-red)', color: 'var(--hl-red-fg)', fontWeight: 600 }}>{children}</div>;
}

/** The epic as Jira has it, with what continuing does. */
function EpicCard({ e, note }: { e: EpicLookup; note: string }) {
  return (
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
      <div style={{ padding: '12px 14px', borderTop: '1px solid var(--border-subtle)', fontSize: 12 }} className="muted">{note}</div>
    </div>
  );
}

/** Step 1 once the anken is saved: its epic, or, for an anken without one yet, the way to link it. */
export function EpicStep({ epic }: { epic: string }) {
  const o = useQuery(onboardingQuery(epic)).data;
  return (
    <StepShell epic={epic} step="epic" title="Add epic ticket"
      sub={o?.draft ? 'The anken has no epic yet: link it once it exists in Jira.' : 'The epic is the anken; it is tracked by the app.'}>
      <section className="card" style={{ padding: '16px 18px', display: 'flex', flexDirection: 'column', gap: 6, maxWidth: 760 }}>
        {o?.draft ? <NoEpic />
          : <span style={{ font: '600 12px/1 var(--font-mono)', color: 'var(--color-accent)' }}>{epic}</span>}
        <b className="heading" style={{ fontSize: 15 }}>{o?.title}</b>
        {o && <span className="eyebrow">{`Onboarding started ${relTime(o.started_at)} by ${o.started_by}`}</span>}
      </section>
      {o?.draft && <LinkEpic draft={epic} />}
    </StepShell>
  );
}

/** Links a draft anken to its epic: looked up in Jira, then tracked; the plan moves under it and Setting Phase opens. */
function LinkEpic({ draft }: { draft: string }) {
  const toast = useToast();
  const navigate = useNavigate();
  const [key, setKey] = useState('');
  const lookup = useLookupEpic();
  const link = useLinkEpic(draft);
  const e = lookup.data;
  return (
    <section aria-label="Link epic" className="card" style={{ padding: '16px 18px', display: 'flex', flexDirection: 'column', gap: 14, maxWidth: 760 }}>
      <span className="title">Link the epic</span>
      <form style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'flex-end', gap: 10 }}
        onSubmit={(ev) => { ev.preventDefault(); if (key.trim()) lookup.mutate(key.trim()); }}>
        <TextField mono label="Epic key" placeholder="e.g. PAY-1420" value={key} onChange={(v) => setKey(v.toUpperCase())} style={{ width: 220 }} />
        <Button type="submit" appearance="secondary" iconBefore="search" disabled={!key.trim() || lookup.isPending}>Look up in Jira</Button>
      </form>
      {lookup.isError && <Alert>{errorMessage(lookup.error)}</Alert>}
      {e && (
        <>
          <EpicCard e={e} note={`${e.tracked ? '' : 'Linking adds it to Settings › Jira › Tracked epics and runs a full sync. '}`
            + 'The estimation, members, master schedule and detail plan move under it; then create its Tasks in Setting Phase and push the plan.'} />
          <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
            <Button iconBefore="link" disabled={link.isPending || e.onboarding} title={e.onboarding ? 'The epic is being onboarded already' : undefined}
              onClick={() => link.mutate(e.key, {
                onSuccess: (o) => {
                  toast(`Linked to ${o.epic_key}`);
                  void navigate({ to: '/onboarding/$epic/$step', params: { epic: o.epic_key, step: 'tasks' } });
                },
                onError: (err) => toast(errorMessage(err), true),
              })}>{`Link ${e.key}`}</Button>
          </div>
        </>
      )}
    </section>
  );
}
