import { useQuery } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { useState } from 'react';
import { errorMessage } from '../../../shared/api/errors';
import { Button } from '../../../shared/ds';
import { useToast } from '../../../shared/state/toast';
import { Loading, QueryError } from '../../../shared/ui/states';
import { onboardingMembersQuery, useFinishOnboarding } from '../api';
import { StepShell } from '../components/StepShell';
import { detailPlanQuery, useCheckResult, withCheck } from '../detail/api';
import { DetailPlan } from '../detail/DetailPlan';
import { PushControls } from '../detail/PushControls';

/** Step 6: who does each function, the dates, the push to Jira; once every row is in Jira the onboarding can be finished. */
export function DetailStep({ epic }: { epic: string }) {
  const toast = useToast();
  const navigate = useNavigate();
  const people = (useQuery(onboardingMembersQuery(epic)).data ?? []).filter((m) => m.in_anken)
    .map((m) => ({ id: m.member_id, name: m.name, role: m.role ?? null }));
  const name = (id: string) => people.find((p) => p.id === id)?.name ?? id;
  const q = useQuery(detailPlanQuery(epic));
  const check = useCheckResult(epic);
  const rows = q.data?.rows ?? [];
  const finish = useFinishOnboarding(epic);
  const done = rows.length > 0 && rows.every((r) => r.pushed_at);
  // what stands before the push opens on its own once the PIM saves or tries to finish, not on the first visit
  const [checked, setChecked] = useState(false);
  return (
    <StepShell epic={epic} step="detail" title="Detail plan"
      sub="Pick who does each function and in which order, then Auto allocate plans the dates from the phase start. Change what you need, Check it, Auto allocate again around it; then review and push the sub-tasks to Jira."
      onSave={async () => setChecked(true)}
      footer={(
        <>
          <Button appearance={done ? 'primary' : 'secondary'} disabled={finish.isPending} title={done ? undefined : 'Push every row to Jira first'} onClick={() => {
            if (!done) {
              setChecked(true);
              toast('Push every row to Jira first', true);
              return;
            }
            finish.mutate(undefined, {
              onSuccess: () => { toast(`${epic} is onboarded`); void navigate({ to: '/anken/$epic', params: { epic } }); },
              onError: (e) => toast(errorMessage(e), true),
            });
          }}>Finish onboarding</Button>
          {q.data && <PushControls epic={epic} v={withCheck(q.data, check)} name={name} onCheck={() => setChecked(true)} />}
        </>
      )}>
      {q.isPending ? <Loading /> : q.isError ? <QueryError error={q.error} /> : <DetailPlan epic={epic} v={q.data} people={people} checked={checked} />}
    </StepShell>
  );
}
