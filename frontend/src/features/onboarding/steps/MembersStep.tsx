import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { hrs } from '../../../shared/format/dates';
import { Loading, QueryError } from '../../../shared/ui/states';
import { onboardingMembersQuery, useSaveMembers, type MemberHours } from '../api';
import { StepShell } from '../components/StepShell';

/** Step 4: who works on the anken, and how many hours a day each one gives it (their allocation, from today). */
export function MembersStep({ epic }: { epic: string }) {
  const q = useQuery(onboardingMembersQuery(epic));
  if (q.isPending) return <Loading />;
  if (q.isError) return <QueryError error={q.error} />;
  return <Members key={JSON.stringify(q.data)} epic={epic} saved={q.data} />;
}

interface Draft { in: boolean; h: string }

function Members({ epic, saved }: { epic: string; saved: MemberHours[] }) {
  const save = useSaveMembers(epic);
  const [find, setFind] = useState('');
  const [d, setD] = useState<Record<string, Draft>>(() =>
    Object.fromEntries(saved.map((m) => [m.member_id, { in: m.in_anken, h: m.hours_per_day == null ? '' : String(m.hours_per_day) }])));
  const set = (id: string, p: Partial<Draft>) => setD((x) => ({ ...x, [id]: { ...x[id], ...p } }));
  const on = saved.filter((m) => d[m.member_id].in);
  const sum = (jm: boolean) => on.filter((m) => (m.role === 'JM') === jm).reduce((a, m) => a + (Number(d[m.member_id].h) || 0), 0);
  const changed = saved.filter((m) => {
    const x = d[m.member_id];
    return x.in !== m.in_anken || (x.in && x.h !== '' && Number(x.h) !== m.hours_per_day);
  });
  const shown = saved.filter((m) => !find.trim() || m.name.toLowerCase().includes(find.trim().toLowerCase()));
  return (
    <StepShell epic={epic} step="members" title="Add member"
      sub="Who works on this anken, and how many hours a day each one gives it. The master schedule plans with these hours."
      onSave={async () => {
        if (changed.length) {
          await save.mutateAsync(changed.map((m) => ({ member_id: m.member_id, in_anken: d[m.member_id].in, hours_per_day: d[m.member_id].h === '' ? null : Number(d[m.member_id].h) })));
        }
      }}>
      <section aria-label="Team totals" className="card" style={{ padding: '12px 18px', display: 'flex', flexWrap: 'wrap', gap: 28 }}>
        {([['Members on the anken', String(on.length)], ['Team hours / day (not JM)', hrs(sum(false))], ['JM hours / day', hrs(sum(true))]] as const).map(([l, v]) => (
          <div key={l} style={{ display: 'flex', flexDirection: 'column', gap: 2 }}><span className="eyebrow">{l}</span><b className="heading" style={{ fontSize: 18 }}>{v}</b></div>
        ))}
      </section>
      <section aria-label="Team" className="card card-clip">
        <div style={{ padding: '12px 16px', display: 'flex', alignItems: 'center', gap: 10, borderBottom: '1px solid var(--border-subtle)' }}>
          <span className="title">Team</span>
          <span className="spacer" />
          <input type="search" aria-label="Find a member" placeholder="Find a member…" value={find} onChange={(e) => setFind(e.target.value)}
            style={{ height: 32, padding: '0 10px', border: '1px solid var(--border-strong)', borderRadius: 8, background: 'var(--surface-card)', color: 'var(--text-heading)' }} />
        </div>
        <div style={{ overflowX: 'auto' }}>
          <table style={{ width: '100%', minWidth: 640, borderCollapse: 'collapse', fontSize: 13 }}>
            <thead>
              <tr style={{ textAlign: 'left', fontSize: 11, color: 'var(--text-muted)' }}>
                <th scope="col" style={{ padding: '8px 16px', fontWeight: 500 }}>In</th><th scope="col" style={{ padding: 8, fontWeight: 500 }}>Member</th>
                <th scope="col" style={{ padding: 8, fontWeight: 500 }}>Role</th><th scope="col" style={{ padding: 8, fontWeight: 500 }}>Country</th>
                <th scope="col" style={{ padding: '8px 16px', fontWeight: 500 }}>Hours / day on this anken</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((m) => {
                const x = d[m.member_id];
                return (
                  <tr key={m.member_id} style={{ borderTop: '1px solid var(--border-subtle)', opacity: x.in ? 1 : 0.6 }}>
                    <td style={{ padding: '6px 16px' }}><input type="checkbox" aria-label={`Include ${m.name}`} checked={x.in} onChange={(e) => set(m.member_id, { in: e.target.checked })} /></td>
                    <td style={{ padding: 8, fontWeight: 600 }}>{m.name}</td>
                    <td style={{ padding: 8 }}>{m.role ?? '–'}</td>
                    <td style={{ padding: 8 }}>{m.country}</td>
                    <td style={{ padding: '6px 16px' }}>
                      <input type="number" min={0} max={24} step={0.5} aria-label={`Hours per day, ${m.name}`} disabled={!x.in} value={x.h}
                        onChange={(e) => set(m.member_id, { h: e.target.value })}
                        style={{ width: 80, height: 30, padding: '0 8px', border: '1px solid var(--border-strong)', borderRadius: 6, background: 'var(--surface-card)', color: 'var(--text-heading)' }} /> h
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </section>
    </StepShell>
  );
}
