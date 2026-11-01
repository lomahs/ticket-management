import { useQuery } from '@tanstack/react-query';
import { Link, useNavigate } from '@tanstack/react-router';
import { useState, type ReactNode } from 'react';
import { errorMessage } from '../../../shared/api/errors';
import { Button, Icon } from '../../../shared/ds';
import { relTime } from '../../../shared/format/dates';
import { useToast } from '../../../shared/state/toast';
import { onboardingQuery, useSaveProgress } from '../api';
import { STEPS, type StepKey } from './steps';

/** The progress band of one anken: its epic, when it was saved, and the six steps (those reached can be opened). */
export function Stepper({ epic, title, savedAt, current, reached, onExit }: {
  epic?: string; title?: string; savedAt?: string; current: number; reached: number; onExit?: () => void;
}) {
  return (
    <section aria-label="Onboarding progress" className="card" style={{ padding: '14px 18px', display: 'flex', flexDirection: 'column', gap: 12 }}>
      <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 10 }}>
        <span className="eyebrow">Onboarding new anken ·</span>
        {epic && <span style={{ font: '600 12px/1 var(--font-mono)', color: 'var(--color-accent)' }}>{epic}</span>}
        {title && <b className="heading">{title}</b>}
        <span className="spacer" />
        {savedAt && (
          <span className="eyebrow" style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
            <Icon name="check" size={13} color="var(--hl-green-fg)" />{`Draft saved · ${relTime(savedAt)}`}
          </span>
        )}
        {onExit ? <Button appearance="secondary" size="sm" onClick={onExit}>Save &amp; exit</Button>
          : <Link to="/onboarding" style={{ fontSize: 12 }}>Exit</Link>}
      </div>
      <ol style={{ margin: 0, padding: 0, listStyle: 'none', display: 'grid', gridTemplateColumns: 'repeat(6,minmax(0,1fr))', gap: 6 }}>
        {STEPS.map((s) => {
          const done = s.n < current || (s.n <= reached && s.n !== current);
          const cur = s.n === current;
          const open = !!epic && s.n <= Math.max(reached, current);
          const body = (
            <span style={{ display: 'flex', alignItems: 'center', gap: 8, color: done || cur ? 'var(--text-heading)' : 'var(--text-muted)' }}>
              <span aria-hidden="true" style={{
                width: 22, height: 22, flex: 'none', borderRadius: '50%', textAlign: 'center', font: '700 11px/22px var(--font-text)',
                background: done ? 'var(--button-primary-bg)' : 'var(--surface-card)', color: done ? 'var(--button-primary-fg)' : 'var(--text-heading)',
                boxShadow: `inset 0 0 0 1.5px ${done || cur ? 'var(--button-primary-bg)' : 'var(--border-strong)'}`,
              }}>{done ? '✓' : s.n}</span>
              <span style={{ display: 'flex', flexDirection: 'column', minWidth: 0 }}>
                <span style={{ fontSize: 10 }} className="muted">{`Step ${s.n}`}</span>
                <span className="ellipsis" style={{ fontWeight: cur ? 700 : 500 }}>{s.label}</span>
              </span>
            </span>
          );
          return (
            <li key={s.key} style={{ display: 'flex', flexDirection: 'column', gap: 6, minWidth: 0 }}>
              <span aria-hidden="true" style={{ height: 4, borderRadius: 2, background: done ? 'var(--button-primary-bg)' : cur ? 'var(--color-accent)' : 'var(--border-subtle)' }} />
              {open ? <Link to="/onboarding/$epic/$step" params={{ epic: epic!, step: s.key }} aria-current={cur ? 'step' : undefined} style={{ textDecoration: 'none' }}>{body}</Link>
                : <span aria-current={cur ? 'step' : undefined}>{body}</span>}
            </li>
          );
        })}
      </ol>
    </section>
  );
}

/**
 * One step of an anken's onboarding: the progress band, the step's title, its content and the footer. {@code onSave} saves
 * what the step holds (Save draft, Save & exit and Continue call it first); Continue then records the next step.
 */
export function StepShell({ epic, step, title, sub, onSave, canContinue = true, continueLabel, children, footer }: {
  epic: string; step: StepKey; title: string; sub: string; onSave?: () => Promise<unknown>; canContinue?: boolean; continueLabel?: string;
  children: ReactNode; footer?: ReactNode;
}) {
  const toast = useToast();
  const navigate = useNavigate();
  const o = useQuery(onboardingQuery(epic)).data;
  const progress = useSaveProgress(epic);
  const [busy, setBusy] = useState(false);
  const n: number = STEPS.find((s) => s.key === step)!.n;
  const next = STEPS.find((s) => s.n === n + 1);
  const prev = STEPS.find((s) => s.n === n - 1);
  const run = async (to: number, then?: () => void) => {
    setBusy(true);
    try {
      await onSave?.();
      await progress.mutateAsync({ step: to });
      then?.();
    } catch (e) {
      toast(errorMessage(e), true);
    } finally {
      setBusy(false);
    }
  };
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16, width: '100%' }}>
      <Stepper epic={epic} title={o?.title} savedAt={o?.saved_at} current={n} reached={o?.step ?? n}
        onExit={() => void run(n, () => void navigate({ to: '/onboarding' }))} />
      <header style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
        <h1 style={{ margin: 0, font: '700 22px/1.2 var(--font-text)', color: 'var(--text-heading)' }}>{title}</h1>
        <span className="eyebrow">{sub}</span>
      </header>
      {children}
      <div className="card" style={{ position: 'sticky', bottom: 12, display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 10, padding: '12px 16px', boxShadow: 'var(--shadow-raised, 0 8px 24px rgba(16,18,20,.08))' }}>
        {prev ? <Link to="/onboarding/$epic/$step" params={{ epic, step: prev.key }} style={{ fontSize: 13, fontWeight: 600 }}>← Back</Link>
          : <Link to="/onboarding" style={{ fontSize: 13, fontWeight: 600 }}>Cancel</Link>}
        <span className="spacer" />
        {footer}
        <Button appearance="secondary" disabled={busy} onClick={() => void run(n, () => toast('Draft saved'))}>Save draft</Button>
        {next && (
          <Button disabled={busy || !canContinue} onClick={() => void run(next.n, () => void navigate({ to: '/onboarding/$epic/$step', params: { epic, step: next.key } }))}>
            {continueLabel ?? `Continue to ${next.label}`}
          </Button>
        )}
      </div>
    </div>
  );
}
