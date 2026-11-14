import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useMembers } from '../../../shared/api/queries';
import { PageHead } from '../../../shared/ui/bits';
import { Loading, QueryError } from '../../../shared/ui/states';
import { detailPlanQuery, DetailPlan, PushControls } from '../../onboarding';
import { PlanningTabs, WithAnken } from '../components/PlanningTabs';

/** The detail plan of a running anken: who does each step, the hours of each day, the push to Jira (the same screen as Onboarding's last step). */
export function DetailPlanPage() {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16, width: '100%' }}>
      <PageHead eyebrow="Planning" title="Detail plan" />
      <PlanningTabs at="/planning/detail" />
      <WithAnken>{(epic) => <Detail key={epic} epic={epic} />}</WithAnken>
    </div>
  );
}

function Detail({ epic }: { epic: string }) {
  const people = (useMembers().data ?? []).map((m) => ({ id: m.id, name: m.name, role: m.role ?? null }));
  const name = (id: string) => people.find((p) => p.id === id)?.name ?? id;
  const q = useQuery(detailPlanQuery(epic));
  // what stands before the push opens on its own once the push is tried
  const [checked, setChecked] = useState(false);
  if (q.isPending) return <Loading />;
  if (q.isError) return <QueryError error={q.error} />;
  return (
    <>
      <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', flexWrap: 'wrap' }}>
        <PushControls epic={epic} v={q.data} name={name} onCheck={() => setChecked(true)} />
      </div>
      <DetailPlan epic={epic} v={q.data} people={people} checked={checked} />
    </>
  );
}
