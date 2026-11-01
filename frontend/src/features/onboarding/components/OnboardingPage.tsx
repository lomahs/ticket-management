import { useQuery } from '@tanstack/react-query';
import { Link, useNavigate } from '@tanstack/react-router';
import { errorMessage } from '../../../shared/api/errors';
import { Button, IconButton } from '../../../shared/ds';
import { relTime } from '../../../shared/format/dates';
import { useToast } from '../../../shared/state/toast';
import { PageHead } from '../../../shared/ui/bits';
import { Loading, QueryError } from '../../../shared/ui/states';
import { onboardingListQuery, useDiscardOnboarding, type OnboardingView } from '../api';
import { STEPS } from './steps';

/** The ankens being onboarded, the step each one is at, and the way into a new one. */
export function OnboardingPage() {
  const navigate = useNavigate();
  const q = useQuery(onboardingListQuery);
  const open = (q.data ?? []).filter((o) => !o.finished_at);
  const done = (q.data ?? []).filter((o) => o.finished_at);
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16, width: '100%' }}>
      <PageHead eyebrow="Set up a new anken from its epic to its detail plan" title="Onboarding new anken">
        <Button iconBefore="plus" onClick={() => void navigate({ to: '/onboarding/new' })}>Start new anken</Button>
      </PageHead>
      <section aria-label="Steps" className="card" style={{ padding: '14px 18px', display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(150px,1fr))', gap: 12 }}>
        {STEPS.map((s) => (
          <div key={s.key} style={{ display: 'flex', gap: 10, alignItems: 'flex-start' }}>
            <span aria-hidden="true" style={{ width: 24, height: 24, flex: 'none', borderRadius: '50%', background: 'var(--surface-hover)', textAlign: 'center', font: '700 12px/24px var(--font-text)' }}>{s.n}</span>
            <span style={{ display: 'flex', flexDirection: 'column', gap: 2 }}><b className="heading">{s.label}</b><span className="eyebrow">{s.sub}</span></span>
          </div>
        ))}
      </section>
      {q.isPending ? <Loading /> : q.isError ? <QueryError error={q.error} /> : (
        <>
          <section aria-label="In progress" style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
            <h2 className="title" style={{ margin: 0 }}>{`In progress · ${open.length}`}</h2>
            {open.length === 0 && <div className="card" style={{ padding: 16, color: 'var(--text-muted)' }}>No anken is being onboarded.</div>}
            {open.map((o) => <Card key={o.epic_key} o={o} />)}
          </section>
          {done.length > 0 && (
            <section aria-label="Finished" className="card card-clip">
              <div style={{ padding: '12px 16px' }}><span className="title">Finished</span></div>
              {done.map((o) => (
                <div key={o.epic_key} style={{ display: 'flex', gap: 12, alignItems: 'center', padding: '8px 16px', borderTop: '1px solid var(--border-subtle)', fontSize: 13 }}>
                  <span style={{ font: '600 12px/1 var(--font-mono)' }}>{o.epic_key}</span>
                  <b className="heading">{o.title}</b>
                  <span className="spacer" />
                  <span className="muted">{`Finished ${relTime(o.finished_at!)}`}</span>
                  <Link to="/anken/$epic" params={{ epic: o.epic_key }} style={{ fontSize: 12 }}>Open anken</Link>
                </div>
              ))}
            </section>
          )}
        </>
      )}
    </div>
  );
}

function Card({ o }: { o: OnboardingView }) {
  const toast = useToast();
  const navigate = useNavigate();
  const discard = useDiscardOnboarding();
  const at = STEPS[Math.min(Math.max(o.step, 1), STEPS.length) - 1];
  return (
    <article className="card" style={{ padding: '14px 18px', display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 18 }}>
      <div style={{ flex: '1 1 240px', minWidth: 0, display: 'flex', flexDirection: 'column', gap: 4 }}>
        <span style={{ font: '600 12px/1 var(--font-mono)', color: 'var(--color-accent)' }}>{o.epic_key}</span>
        <b className="heading" style={{ fontSize: 16 }}>{o.title || o.epic_key}</b>
        <span className="eyebrow">{`Saved ${relTime(o.saved_at)} · ${o.saved_by}`}</span>
      </div>
      <div style={{ flex: '2 1 320px', display: 'flex', flexDirection: 'column', gap: 6 }}>
        <div aria-hidden="true" style={{ display: 'grid', gridTemplateColumns: 'repeat(6,minmax(0,1fr))', gap: 4 }}>
          {STEPS.map((s) => (
            <span key={s.key} style={{ height: 6, borderRadius: 3, background: s.n < at.n ? 'var(--button-primary-bg)' : s.n === at.n ? 'var(--color-accent)' : 'var(--border-subtle)' }} />
          ))}
        </div>
        <span style={{ fontSize: 12 }}>{`Step ${at.n} of 6 · `}<b>{at.label}</b></span>
      </div>
      <Button appearance="secondary" size="sm" iconAfter="arrow-right" aria-label={`Resume ${o.epic_key}`}
        onClick={() => void navigate({ to: '/onboarding/$epic/$step', params: { epic: o.epic_key, step: at.key } })}>Resume</Button>
      <IconButton icon="trash-2" label={`Discard ${o.epic_key}`} appearance="subtle" disabled={discard.isPending} onClick={() => {
        if (window.confirm(`Discard the onboarding of ${o.epic_key}? Its estimation rows not in Jira are deleted; the epic stays tracked and what is in Jira stays.`)) {
          discard.mutate(o.epic_key, { onSuccess: () => toast(`${o.epic_key} discarded`), onError: (e) => toast(errorMessage(e), true) });
        }
      }} />
    </article>
  );
}

