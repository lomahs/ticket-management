import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { errorMessage } from '../../../shared/api/errors';
import type { Schemas } from '../../../shared/api/types';
import { Button, TextField } from '../../../shared/ds';
import { STEPS } from '../../../shared/format/labels';
import { useToast } from '../../../shared/state/toast';
import { Loading, QueryError } from '../../../shared/ui/states';
import { namePreviewQuery, nameRulesQuery, useSaveNameRules } from '../api';
import { AnkenPicker, useSettingsTarget } from './AnkenPicker';
import { ErrText, Section, useDebounced } from './common';

const TOKENS: [string, string][] = [['anken_name', 'Anken name'], ['os_task', 'OS task'], ['prefix', 'Task prefix'], ['phase_name', 'Phase'], ['function_id', 'Function ID'], ['function_name', 'Function name'], ['step', 'Step']];
const DEFAULT_RULE = '[{phase_name}][{function_id}] {function_name} – {step}';

export function NameRulesTab() {
  const t = useSettingsTarget();
  const anken = t.status === 'ready' ? t.anken : null;
  const [rev, setRev] = useState(0);
  const q = useQuery({ ...nameRulesQuery(anken), enabled: t.status === 'ready' });
  if (t.status === 'pending') return <Loading />;
  if (t.status === 'error') return <QueryError error={t.error} />;
  return (
    <>
      <AnkenPicker anken={anken} untracked={t.untracked} onCopied={() => setRev((r) => r + 1)} />
      {q.isPending ? <Loading /> : q.isError ? <QueryError error={q.error} /> : <NameRulesForm key={`${anken ?? ''}:${rev}`} anken={anken} v={q.data} />}
    </>
  );
}

function NameRulesForm({ v, anken }: { v: Schemas['NameRulesView']; anken: string | null }) {
  const toast = useToast();
  const save = useSaveNameRules(anken);
  const [rule, setRule] = useState(v.rule);
  const [aliases, setAliases] = useState<Record<string, string>>(v.aliases);
  const [sample, setSample] = useState('[Implement_Coding][F03] Domestic card payment – Fix comment BAL');
  const body = useDebounced({ rule, aliases, sample }, 250);
  const p = useQuery(namePreviewQuery(anken, body)).data;
  const parsedStepIndex = STEPS.findIndex((s) => s.k === p?.step);
  const used = p?.tokens ?? [];
  return (
    <Section title="Ticket name rules" sub="Describe how sub-task Summaries are written. Placeholders are read as values; everything else must match literally. {step} must be one of the aliases below.">
      <div style={{ display: 'flex', alignItems: 'flex-end', gap: 10 }}>
        <TextField mono label="Pattern" placeholder="[{anken_name}][{os_task}]_{phase_name}_{step}" value={rule} onChange={setRule} style={{ flex: 1 }} />
        <Button appearance="subtle" onClick={() => setRule(DEFAULT_RULE)}>Reset</Button>
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
        <span className="eyebrow">Insert</span>
        {TOKENS.map(([k, l]) => (
          <button key={k} className="hv-bg" title={l} onClick={() => setRule((r) => r + '{' + k + '}')} style={{
            height: 26, padding: '0 10px', borderRadius: 8, border: '1px solid var(--border-subtle)', background: 'var(--surface-card)',
            opacity: used.includes(k) ? 0.45 : 1, font: '500 12px/1 var(--font-mono)', color: 'var(--text-heading)', cursor: 'pointer',
          }}>{'{' + k + '}'}</button>
        ))}
      </div>
      {p?.error && <ErrText>{p.error}</ErrText>}
      <TextField label="Test with a Summary" value={sample} onChange={setSample} />
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(160px,1fr))', gap: 8 }}>
        {TOKENS.filter(([k]) => used.includes(k)).map(([k, label]) => {
          const val = k === 'step' && p?.step ? `Step ${parsedStepIndex + 1} · ${p.values.step}` : p?.values[k];
          return (
            <div key={k} style={{ padding: '10px 12px', borderRadius: 10, background: 'var(--surface-hover)' }}>
              <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>{label}</div>
              <div style={{ fontWeight: 700, color: val ? 'var(--text-heading)' : 'var(--hl-red-fg)' }}>{val || '—'}</div>
            </div>
          );
        })}
      </div>
      {p && !p.error && !p.sample_matches && <ErrText>This Summary does not match the pattern.</ErrText>}
      {p && (
        <span style={{ fontSize: 12, fontWeight: 600, color: p.matched < p.total ? 'var(--hl-amber-fg)' : 'var(--text-muted)' }}>
          {p.matched} / {p.total} synced sub-tasks match this pattern; the rest are shown as other tasks
        </span>
      )}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8, paddingTop: 14, borderTop: '1px solid var(--border-subtle)' }}>
        <div>
          <div className="title">Step aliases</div>
          <div className="eyebrow">Comma-separated names that {'{step}'} may contain, in workflow order</div>
        </div>
        {STEPS.map((s, i) => (
          <div key={s.k} style={{ display: 'grid', gridTemplateColumns: '150px minmax(0,1fr)', gap: 12, alignItems: 'center' }}>
            <b className="heading">Step {i + 1}</b>
            <TextField aria-label={`Step ${i + 1} aliases`} value={aliases[s.k] ?? ''} onChange={(val) => setAliases((a) => ({ ...a, [s.k]: val }))} />
          </div>
        ))}
      </div>
      <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
        <Button disabled={save.isPending || !!p?.error} onClick={() => save.mutate({ rule, aliases }, {
          onSuccess: () => toast('Name rules saved · synced'), onError: (e) => toast(errorMessage(e), true),
        })}>Save</Button>
      </div>
    </Section>
  );
}
