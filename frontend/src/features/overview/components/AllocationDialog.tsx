import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { errorMessage } from '../../../shared/api/errors';
import { useCan, useMemberMap, useMeta } from '../../../shared/api/queries';
import type { Schemas } from '../../../shared/api/types';
import { Button, IconButton, TextField } from '../../../shared/ds';
import { useToast } from '../../../shared/state/toast';
import { Loading, QueryError } from '../../../shared/ui/states';
import { allocationQuery, useSaveAllocation } from '../api';

const cell = { padding: '4px 8px', textAlign: 'right' as const };

/** A member's hours per day on each of their anken, from an effective date; every change adds a step to the history. */
export function AllocationDialog({ memberId, close }: { memberId: string; close: () => void }) {
  const names = useMemberMap();
  const q = useQuery(allocationQuery(memberId));
  return (
    <div className="scrim" onClick={close}>
      <div className="dialog" role="dialog" aria-modal="true" aria-label={`Allocation · ${names[memberId]?.name ?? memberId}`}
        onClick={(e) => e.stopPropagation()} style={{ width: 'min(560px,100%)' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span className="dialog-title" style={{ flex: 1 }}>Allocation · {names[memberId]?.name ?? memberId}</span>
          <IconButton icon="x" label="Close" size="sm" onClick={close} />
        </div>
        {q.isPending ? <Loading /> : q.isError ? <QueryError error={q.error} /> : <Body timeline={q.data} close={close} />}
      </div>
    </div>
  );
}

function Body({ timeline, close }: { timeline: Schemas['AllocationTimeline']; close: () => void }) {
  const write = useCan('members', 'write');
  const toast = useToast();
  const today = useMeta().data?.today ?? '';
  const save = useSaveAllocation(timeline.member_id);
  const { epics, steps, hours_per_day: day } = timeline;
  const current = steps[steps.length - 1];
  const [from, setFrom] = useState(today);
  const [vals, setVals] = useState<Record<string, string>>(() => Object.fromEntries(epics.map((e) => [e, String(current?.hours[e] ?? 0)])));
  const num = (e: string) => Number(vals[e]) || 0;
  const total = epics.reduce((t, e) => t + num(e), 0);
  const over = total > day;
  const negative = epics.some((e) => num(e) < 0);
  const submit = () => save.mutate({ from, hours: Object.fromEntries(epics.map((e) => [e, num(e)])) }, {
    onSuccess: () => toast('Allocation saved.'),
    onError: (e) => toast(errorMessage(e), true),
  });
  return (
    <>
      {steps.length ? (
        <table style={{ borderCollapse: 'collapse', fontSize: 12 }}>
          <thead>
            <tr>
              <th style={{ ...cell, textAlign: 'left' }}>From</th>
              {epics.map((e) => <th key={e} style={cell} className="mono">{e}</th>)}
              <th style={cell}>Total</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {steps.map((s, i) => (
              <tr key={s.from} style={{ borderTop: '1px solid var(--border-subtle)' }}>
                <td style={{ ...cell, textAlign: 'left' }}>{s.from}</td>
                {epics.map((e) => <td key={e} style={cell}>{s.hours[e] ?? 0}</td>)}
                <td style={cell}>{epics.reduce((t, e) => t + (s.hours[e] ?? 0), 0)}</td>
                <td style={{ ...cell, textAlign: 'left', color: 'var(--text-muted)' }}>{i === steps.length - 1 ? 'current' : ''}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : <div style={{ fontSize: 12, color: 'var(--text-muted)' }}>No allocation yet.</div>}
      {write && (
        <>
          <TextField label="From" type="date" value={from} onChange={setFrom} />
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
            {epics.map((e) => (
              <TextField key={e} label={e} type="number" min={0} step={0.5} value={vals[e] ?? ''} style={{ width: 110 }}
                onChange={(v) => setVals((x) => ({ ...x, [e]: v }))} />
            ))}
          </div>
          <div style={{ fontWeight: 600, color: over ? 'var(--hl-red-fg)' : 'var(--text-body)' }}>Total {total}h of {day}h</div>
        </>
      )}
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <span className="spacer" />
        <Button appearance="subtle" onClick={close}>Close</Button>
        {write && <Button disabled={over || negative || !from || save.isPending} onClick={submit}>Save</Button>}
      </div>
    </>
  );
}
