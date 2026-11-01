import { useQuery } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { errorMessage } from '../../../shared/api/errors';
import { Button } from '../../../shared/ds';
import { useToast } from '../../../shared/state/toast';
import { onboardingMembersQuery, useFinishOnboarding } from '../api';
import { StepShell } from '../components/StepShell';
import { detailPlanQuery } from '../detail/api';
import { DetailPlan } from '../detail/DetailPlan';

/** Step 6: who does each row, the dates, the push to Jira; once every row is in Jira the onboarding can be finished. */
export function DetailStep({ epic }: { epic: string }) {
  const toast = useToast();
  const navigate = useNavigate();
  const people = (useQuery(onboardingMembersQuery(epic)).data ?? []).filter((m) => m.in_anken).map((m) => ({ id: m.member_id, name: m.name }));
  const rows = useQuery(detailPlanQuery(epic)).data?.rows ?? [];
  const finish = useFinishOnboarding(epic);
  const done = rows.length > 0 && rows.every((r) => r.pushed_at);
  return (
    <StepShell epic={epic} step="detail" title="Detail plan"
      sub="Who does each row and in which order; the dates follow from the phase start. Then push the sub-tasks to Jira."
      footer={(
        <Button disabled={!done || finish.isPending} title={done ? undefined : 'Push every row to Jira first'} onClick={() => finish.mutate(undefined, {
          onSuccess: () => { toast(`${epic} is onboarded`); void navigate({ to: '/anken/$epic', params: { epic } }); },
          onError: (e) => toast(errorMessage(e), true),
        })}>Finish onboarding</Button>
      )}>
      <DetailPlan epic={epic} people={people} />
    </StepShell>
  );
}
