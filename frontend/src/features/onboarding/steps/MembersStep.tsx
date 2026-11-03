import { useQuery } from '@tanstack/react-query';
import { useState, type FocusEvent, type MouseEvent } from 'react';
import { hrs } from '../../../shared/format/dates';
import { Button } from '../../../shared/ds';
import { Loading, QueryError } from '../../../shared/ui/states';
import { onboardingMembersQuery, useSaveMembers, type MemberHours } from '../api';
import { NewMemberDialog } from '../components/NewMemberDialog';
import { StepShell } from '../components/StepShell';

/** Step 4: who works on the anken, and how many hours a day each one gives it (their allocation, from today). */
export function MembersStep({ epic }: { epic: string }) {
  const q = useQuery(onboardingMembersQuery(epic));
  if (q.isPending) return <Loading />;
  if (q.isError) return <QueryError error={q.error} />;
  return <Members epic={epic} saved={q.data} />;
}

interface Draft { in: boolean; h: string }
interface Pop { id: string; top: number; left: number }

/** Hours a day the member already gives the other anken today. */
const elsewhere = (m: MemberHours) => m.elsewhere.reduce((a, e) => a + e.hours, 0);

function Members({ epic, saved }: { epic: string; saved: MemberHours[] }) {
  const save = useSaveMembers(epic);
  const [find, setFind] = useState('');
  // only what the user changed: a new person added meanwhile (a refetch) keeps the other rows' edits
  const [edits, setEdits] = useState<Record<string, Partial<Draft>>>({});
  const d: Record<string, Draft> = Object.fromEntries(saved.map((m) =>
    [m.member_id, { in: m.in_anken, h: m.hours_per_day == null ? '' : String(m.hours_per_day), ...edits[m.member_id] }]));
  const [adding, setAdding] = useState(false);
  const [pop, setPop] = useState<Pop | null>(null);
  // set once a save found members over the working day: their rows show the warning from then on
  const [warned, setWarned] = useState(false);
  const [ask, setAsk] = useState<{ over: MemberHours[]; answer: (ok: boolean) => void } | null>(null);
  const set = (id: string, p: Partial<Draft>) => setEdits((x) => ({ ...x, [id]: { ...x[id], ...p } }));
  const on = saved.filter((m) => d[m.member_id].in);
  const sum = (jm: boolean) => on.filter((m) => (m.role === 'JM') === jm).reduce((a, m) => a + (Number(d[m.member_id].h) || 0), 0);
  const changed = saved.filter((m) => {
    const x = d[m.member_id];
    return x.in !== m.in_anken || (x.in && x.h !== '' && Number(x.h) !== m.hours_per_day);
  });
  const total = (m: MemberHours) => elsewhere(m) + (Number(d[m.member_id].h) || 0);
  const isOver = (m: MemberHours) => d[m.member_id].in && d[m.member_id].h !== '' && total(m) > m.working_day_h;
  const shown = saved.filter((m) => !find.trim() || m.name.toLowerCase().includes(find.trim().toLowerCase()));
  const showPop = (e: MouseEvent<HTMLElement> | FocusEvent<HTMLElement>, id: string) => {
    const r = e.currentTarget.getBoundingClientRect();
    setPop({ id, top: Math.max(8, Math.min(r.bottom + 6, innerHeight - 260)), left: Math.max(8, Math.min(r.left + r.width / 2 - 150, innerWidth - 316)) });
  };
  const popped = pop && saved.find((m) => m.member_id === pop.id);
  return (
    <StepShell epic={epic} step="members" title="Add member"
      sub="Who works on this anken, and how many hours a day each one gives it. The master schedule plans with these hours."
      onSave={async () => {
        if (!changed.length) return;
        const over = changed.filter(isOver);
        if (over.length) {
          setWarned(true);
          const ok = await new Promise<boolean>((answer) => setAsk({ over, answer }));
          setAsk(null);
          if (!ok) return false;
        }
        await save.mutateAsync({
          members: changed.map((m) => ({ member_id: m.member_id, in_anken: d[m.member_id].in, hours_per_day: d[m.member_id].h === '' ? null : Number(d[m.member_id].h) })),
          allow_over_the_day: over.length > 0,
        });
        setEdits({});
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
          <Button appearance="secondary" size="sm" onClick={() => setAdding(true)}>New member</Button>
        </div>
        <div style={{ overflowX: 'auto' }}>
          <table style={{ width: '100%', minWidth: 760, borderCollapse: 'collapse', fontSize: 13 }}>
            <thead>
              <tr style={{ textAlign: 'left', fontSize: 11, color: 'var(--text-muted)' }}>
                <th scope="col" style={{ padding: '8px 16px', fontWeight: 500 }}>In</th><th scope="col" style={{ padding: 8, fontWeight: 500 }}>Member</th>
                <th scope="col" style={{ padding: 8, fontWeight: 500 }}>Role</th><th scope="col" style={{ padding: 8, fontWeight: 500 }}>Country</th>
                <th scope="col" style={{ padding: 8, fontWeight: 500 }}>Already allocated / day</th>
                <th scope="col" style={{ padding: '8px 16px', fontWeight: 500 }}>Hours / day on this anken</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((m) => {
                const x = d[m.member_id];
                const warn = warned && isOver(m);
                return (
                  <tr key={m.member_id} style={{ borderTop: '1px solid var(--border-subtle)', opacity: x.in ? 1 : 0.6, background: warn ? 'var(--hl-red)' : undefined }}>
                    <td style={{ padding: '6px 16px' }}><input type="checkbox" aria-label={`Include ${m.name}`} checked={x.in} onChange={(e) => set(m.member_id, { in: e.target.checked })} /></td>
                    <td style={{ padding: 8, fontWeight: 600 }}>{m.name}</td>
                    <td style={{ padding: 8 }}>{m.role ?? '–'}</td>
                    <td style={{ padding: 8 }}>{m.country}</td>
                    <td style={{ padding: 8 }}>
                      <span tabIndex={0} aria-label={`Already allocated, ${m.name}`} onMouseEnter={(e) => showPop(e, m.member_id)} onMouseLeave={() => setPop(null)}
                        onFocus={(e) => showPop(e, m.member_id)} onBlur={() => setPop(null)}
                        style={{ cursor: 'default', borderBottom: '1px dotted var(--border-strong)', fontVariantNumeric: 'tabular-nums' }}>
                        {m.elsewhere.length ? hrs(elsewhere(m)) : '–'}
                      </span>
                    </td>
                    <td style={{ padding: '6px 16px' }}>
                      <input type="number" min={0} max={24} step={0.5} aria-label={`Hours per day, ${m.name}`} disabled={!x.in} value={x.h}
                        onChange={(e) => set(m.member_id, { h: e.target.value })}
                        style={{ width: 80, height: 30, padding: '0 8px', border: `1px solid ${warn ? 'var(--hl-red-fg)' : 'var(--border-strong)'}`, borderRadius: 6, background: 'var(--surface-card)', color: 'var(--text-heading)' }} /> h
                      {warn && (
                        <div role="alert" style={{ fontSize: 12, fontWeight: 600, color: 'var(--hl-red-fg)', marginTop: 4 }}>
                          {`${hrs(total(m))} a day with other anken, more than the ${hrs(m.working_day_h)} working day`}
                        </div>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </section>
      {pop && popped && <AllocatedPopover pop={pop} m={popped} draft={x2h(d[popped.member_id])} />}
      {adding && <NewMemberDialog epic={epic} close={() => setAdding(false)} />}
      {ask && <ConfirmOver over={ask.over} total={total} answer={ask.answer} />}
    </StepShell>
  );
}

/** The hours on this anken as the draft has them: null while out of the anken or blank. */
const x2h = (x: Draft) => (x.in && x.h !== '' ? Number(x.h) || 0 : null);

/** On hover of Already allocated: the member's hours a day on each other anken today, this anken's draft and the total. */
function AllocatedPopover({ pop, m, draft }: { pop: Pop; m: MemberHours; draft: number | null }) {
  const total = elsewhere(m) + (draft ?? 0);
  const row = { display: 'grid', gridTemplateColumns: 'minmax(0,1fr) auto', gap: 10, padding: '6px 14px', fontSize: 12 } as const;
  return (
    <div role="dialog" aria-label={`Allocation · ${m.name}`} style={{
      position: 'fixed', top: pop.top, left: pop.left, zIndex: 56, pointerEvents: 'none', width: 300,
      borderRadius: 14, background: 'var(--surface-card)', border: '1px solid var(--border-subtle)', boxShadow: '0 12px 32px rgba(9,30,66,0.22)',
    }}>
      <div className="title" style={{ padding: '10px 14px', borderBottom: '1px solid var(--border-subtle)' }}>{m.name} · hours a day today</div>
      {m.elsewhere.length ? m.elsewhere.map((e) => (
        <div key={e.epic_key} style={row}>
          <span className="ellipsis"><span className="mono" style={{ color: 'var(--color-accent)' }}>{e.epic_key}</span> {e.title}</span>
          <b>{hrs(e.hours)}</b>
        </div>
      )) : <div style={{ ...row, color: 'var(--text-muted)' }}>No hours on other anken</div>}
      <div style={{ ...row, color: 'var(--text-muted)' }}><span>This anken</span><span>{hrs(draft)}</span></div>
      <div style={{ ...row, borderTop: '1px solid var(--border-subtle)', fontWeight: 700, color: total > m.working_day_h ? 'var(--hl-red-fg)' : 'var(--text-heading)' }}>
        <span>{`Total · working day ${hrs(m.working_day_h)}`}</span><span>{hrs(total)}</span>
      </div>
    </div>
  );
}

/** Asked on save when members would go over the working day: saving anyway keeps their hours. */
function ConfirmOver({ over, total, answer }: { over: MemberHours[]; total: (m: MemberHours) => number; answer: (ok: boolean) => void }) {
  return (
    <div className="scrim" onClick={() => answer(false)}>
      <div className="dialog" role="dialog" aria-modal="true" aria-label="Hours over the working day" onClick={(e) => e.stopPropagation()} style={{ width: 'min(480px,100%)' }}>
        <span className="dialog-title">Hours over the working day</span>
        <span style={{ fontSize: 13 }}>
          {over.length === 1 ? 'This member gives' : 'These members give'} more hours a day over every anken than the working day. Save anyway?
        </span>
        <ul style={{ margin: 0, paddingLeft: 18, fontSize: 13, display: 'flex', flexDirection: 'column', gap: 4 }}>
          {over.map((m) => (
            <li key={m.member_id}>
              <b>{m.name}</b>{` · ${hrs(total(m))} a day, ${hrs(total(m) - m.working_day_h)} over the ${hrs(m.working_day_h)} working day`}
            </li>
          ))}
        </ul>
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
          <Button appearance="secondary" onClick={() => answer(false)}>Cancel</Button>
          <Button onClick={() => answer(true)}>Save anyway</Button>
        </div>
      </div>
    </div>
  );
}
