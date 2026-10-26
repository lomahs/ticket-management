import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { dm } from '../../../shared/format/dates';
import { Loading, QueryError } from '../../../shared/ui/states';
import { jiraLogQuery } from '../api';
import { Section } from './common';

const SOURCE: Record<string, string> = { ticket_panel: 'Ticket panel', add_function: 'Add function', planning: 'Planning' };
const GRID = '104px 140px 110px 96px minmax(0,1fr) 72px';

/** "2026-10-07T02:14:09.120Z" → "07/10 09:14" in the browser's time zone. */
function at(iso: string) {
  const d = new Date(iso);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${dm(`${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`)} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

/** Every request the app sent to change Jira: who applied it, from where, the call and body, and Jira's answer. */
export function JiraLogTab() {
  const [failed, setFailed] = useState(false);
  const q = useQuery(jiraLogQuery(failed));
  return (
    <Section pad={false} title="Jira log" sub="Each request the app sends to update Jira, newest first. The API token is never logged.">
      <div style={{ display: 'flex', padding: '0 20px 12px' }}>
        <div className="seg" role="group" aria-label="Show">
          {([[false, 'All'], [true, 'Not written']] as const).map(([v, label]) => (
            <button key={label} type="button" aria-pressed={failed === v} onClick={() => setFailed(v)}
              style={{ background: failed === v ? 'var(--button-primary-bg)' : 'transparent', color: failed === v ? 'var(--button-primary-fg)' : 'var(--text-heading)' }}>
              {label}
            </button>
          ))}
        </div>
      </div>
      {q.isPending ? <Loading /> : q.isError ? <div style={{ padding: 16 }}><QueryError error={q.error} /></div> : (
        <div style={{ overflowX: 'auto' }}>
          <div style={{ minWidth: 900 }}>
            <div style={{ display: 'grid', gridTemplateColumns: GRID, gap: 12, padding: '8px 20px', background: 'var(--surface-hover)', fontSize: 11, fontWeight: 600, color: 'var(--text-muted)' }}>
              <span>Time</span><span>Applied by</span><span>From</span><span>Ticket</span><span>Request</span><span>Result</span>
            </div>
            {!q.data.length && <div className="empty-row">{failed ? 'Every request was written.' : 'Nothing has been written to Jira yet.'}</div>}
            {q.data.map((e) => {
              const bad = e.status === 0 || e.status >= 400;
              return (
                <div key={e.id} style={{ display: 'grid', gridTemplateColumns: GRID, gap: 12, alignItems: 'start', padding: '10px 20px', borderTop: '1px solid var(--border-subtle)', fontSize: 12 }}>
                  <span className="mono muted">{at(e.at)}</span>
                  <span className="ellipsis heading" title={e.actor_email}>{e.actor_name || e.actor_email}</span>
                  <span>{SOURCE[e.source] ?? e.source}</span>
                  <span className="key">{e.key}</span>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 3, minWidth: 0, font: '12px/1.5 var(--font-mono)', wordBreak: 'break-all' }}>
                    <span className="heading" style={{ fontWeight: 600 }}>{e.method} {e.path}</span>
                    {e.body && <span className="muted">{e.body}</span>}
                    {e.error && <span style={{ font: '12px/1.45 var(--font-text)', color: 'var(--hl-red-fg)' }}>{e.error}</span>}
                  </div>
                  <span style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: 2 }}>
                    <span className="mono" style={{ padding: '1px 7px', borderRadius: 4, fontWeight: 600, fontSize: 11, background: bad ? 'var(--hl-red)' : 'var(--hl-ok)', color: bad ? 'var(--hl-red-fg)' : 'var(--hl-ok-fg)' }}>
                      {e.status || '—'}
                    </span>
                    {e.duration_ms > 0 && <span style={{ fontSize: 10 }} className="muted">{e.duration_ms} ms</span>}
                  </span>
                </div>
              );
            })}
          </div>
        </div>
      )}
    </Section>
  );
}
