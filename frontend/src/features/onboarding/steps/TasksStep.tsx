import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { errorMessage } from '../../../shared/api/errors';
import { useMe } from '../../../shared/api/queries';
import { Button, Select, TextField } from '../../../shared/ds';
import { useToast } from '../../../shared/state/toast';
import { Loading, QueryError } from '../../../shared/ui/states';
import { onboardingMembersQuery, onboardingQuery, templateTasksQuery, useCreateTasks, usePreviewTasks, useSaveProgress } from '../api';
import { StepShell } from '../components/StepShell';

function useDebounced<T>(value: T, ms: number): T {
  const [v, setV] = useState(value);
  useEffect(() => { const t = setTimeout(() => setV(value), ms); return () => clearTimeout(t); }, [value, ms]);
  return v;
}

/** Step 2: the anken's template Tasks; the missing ones are named by the Task name rule and created in one go. */
export function TasksStep({ epic }: { epic: string }) {
  const o = useQuery(onboardingQuery(epic));
  if (o.isPending) return <Loading />;
  if (o.isError) return <QueryError error={o.error} />;
  return <Tasks epic={epic} savedPrefix={o.data.anken_prefix} />;
}

function Tasks({ epic, savedPrefix }: { epic: string; savedPrefix: string }) {
  const toast = useToast();
  const me = useMe().data;
  const [prefix, setPrefix] = useState(savedPrefix);
  const [names, setNames] = useState<Record<string, string>>({});
  const q = useQuery(templateTasksQuery(epic, useDebounced(prefix.trim(), 250)));
  const people = (useQuery(onboardingMembersQuery(epic)).data ?? []).filter((m) => m.in_anken);
  const [assignee, setAssignee] = useState('');
  const who = assignee || (people.some((m) => m.member_id === me?.member_id) ? me!.member_id! : people[0]?.member_id ?? '');
  const progress = useSaveProgress(epic);
  const preview = usePreviewTasks(epic);
  const create = useCreateTasks(epic);
  const rows = q.data?.tasks ?? [];
  const missing = rows.filter((t) => !t.existing_key);
  const body = () => ({ anken_prefix: prefix.trim(), assignee_id: who, tasks: missing.map((t) => ({ match: t.match, name: names[t.match] ?? t.name })) });
  return (
    <StepShell epic={epic} step="tasks" title="Template Tasks"
      sub="Every anken carries the Tasks of its template (Settings › Phases & tasks). Create the missing ones in one go."
      onSave={() => progress.mutateAsync({ step: 2, anken_prefix: prefix.trim() })}>
      <section className="card card-clip" aria-label="Template Tasks">
        <div style={{ padding: '12px 16px', display: 'flex', flexWrap: 'wrap', alignItems: 'flex-end', gap: 16, borderBottom: '1px solid var(--border-subtle)' }}>
          <TextField mono label="Anken prefix" placeholder="e.g. BookUI" value={prefix} onChange={setPrefix} style={{ width: 180 }} />
          <span className="eyebrow" style={{ paddingBottom: 10 }}>{q.data ? `Task name rule: ${q.data.task_rule}` : ''}</span>
          <span className="spacer" />
          <span className="eyebrow" style={{ paddingBottom: 10 }}>{`${rows.length - missing.length} found · ${missing.length} missing`}</span>
        </div>
        {q.isError ? <div style={{ padding: 16 }}><QueryError error={q.error} /></div> : !q.data ? <Loading /> : rows.length === 0 ? (
          <div style={{ padding: 16, color: 'var(--text-muted)' }}>The anken's template has no Tasks (Settings › Phases &amp; tasks).</div>
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', minWidth: 760, borderCollapse: 'collapse', fontSize: 13 }}>
              <thead>
                <tr style={{ textAlign: 'left', fontSize: 11, color: 'var(--text-muted)' }}>
                  <th scope="col" style={{ padding: '8px 16px', fontWeight: 500 }}>Template Task</th>
                  <th scope="col" style={{ padding: 8, fontWeight: 500 }}>Phase</th>
                  <th scope="col" style={{ padding: 8, fontWeight: 500 }}>In the epic</th>
                  <th scope="col" style={{ padding: '8px 16px', fontWeight: 500 }}>Task to create</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((t) => (
                  <tr key={t.match} style={{ borderTop: '1px solid var(--border-subtle)' }}>
                    <td style={{ padding: '8px 16px', fontWeight: 600 }}>{t.match}</td>
                    <td style={{ padding: 8 }}>{t.phase}</td>
                    <td style={{ padding: 8 }}>{t.existing_key ? <span style={{ font: '600 12px/1 var(--font-mono)' }}>{t.existing_key}</span>
                      : <span className="status-pill" style={{ background: 'var(--hl-red-bg)', color: 'var(--hl-red-fg)' }}>Missing</span>}</td>
                    <td style={{ padding: '6px 16px' }}>
                      {t.existing_key ? <span className="muted">{t.name}</span> : (
                        <input aria-label={`Name, ${t.match}`} value={names[t.match] ?? t.name} onChange={(e) => setNames((n) => ({ ...n, [t.match]: e.target.value }))}
                          style={{ height: 30, width: '100%', boxSizing: 'border-box', padding: '0 8px', border: '1px solid var(--border-strong)', borderRadius: 6, font: '500 13px/1 var(--font-text)', background: 'var(--surface-card)', color: 'var(--text-heading)' }} />
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {missing.length > 0 && (
          <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'flex-end', gap: 10, padding: '12px 16px', borderTop: '1px solid var(--border-subtle)' }}>
            <Select label="Assignee of the new Tasks" value={who} onChange={setAssignee} style={{ minWidth: 200 }}
              options={people.length ? people.map((m) => ({ value: m.member_id, label: m.name })) : [{ value: '', label: 'No member yet' }]} />
            <span className="eyebrow" style={{ paddingBottom: 10 }}>Nothing reaches Jira until you review and create.</span>
            <span className="spacer" />
            <Button appearance="secondary" iconBefore="eye" disabled={!prefix.trim() || !who || preview.isPending}
              onClick={() => preview.mutate(body(), { onError: (e) => toast(errorMessage(e), true) })}>{`Review ${missing.length} Tasks`}</Button>
          </div>
        )}
        {preview.data && (
          <div role="region" aria-label="Tasks to create" style={{ borderTop: '1px solid var(--border-subtle)', background: 'var(--surface-hover)', padding: '12px 16px', display: 'flex', flexDirection: 'column', gap: 6 }}>
            <b className="heading">{`${preview.data.creates.length} Tasks to create under ${epic}`}</b>
            {preview.data.creates.map((c) => <span key={c.ref} style={{ fontSize: 13 }}>{c.summary}</span>)}
            <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
              <Button appearance="subtle" size="sm" onClick={() => preview.reset()}>Cancel</Button>
              <Button size="sm" iconBefore="upload" disabled={create.isPending} onClick={() => create.mutate(body(), {
                onSuccess: (r) => { toast(`Created ${Object.keys(r.created).length} Tasks in Jira`); preview.reset(); setNames({}); },
                onError: (e) => toast(errorMessage(e), true),
              })}>Create in Jira</Button>
            </div>
          </div>
        )}
      </section>
    </StepShell>
  );
}
