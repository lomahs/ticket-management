import { useQuery } from '@tanstack/react-query';
import { Link, useNavigate } from '@tanstack/react-router';
import { useState } from 'react';
import { errorMessage } from '../../../shared/api/errors';
import { Button } from '../../../shared/ds';
import { useToast } from '../../../shared/state/toast';
import { Loading, QueryError } from '../../../shared/ui/states';
import { onboardingMembersQuery, onboardingQuery, useFinishOnboarding } from '../api';
import { StepShell } from '../components/StepShell';
import { detailPlanQuery } from '../detail/api';
import { DetailPlan, usePersonName } from '../detail/DetailPlan';
import { PushControls } from '../detail/PushControls';

/**
 * Step 6: who does each function, the dates, the push to Jira; once every row is in Jira the onboarding can be finished. An anken
 * without its epic is planned the same way; pushing and finishing wait until the epic is linked.
 */
export function DetailStep({ epic }: { epic: string }) {
  const toast = useToast();
  const navigate = useNavigate();
  const people = (useQuery(onboardingMembersQuery(epic)).data ?? []).filter((m) => m.in_anken)
    .map((m) => ({ id: m.member_id, name: m.name, role: m.role ?? null }));
  const name = usePersonName(people);
  const q = useQuery(detailPlanQuery(epic));
  const rows = q.data?.rows ?? [];
  const finish = useFinishOnboarding(epic);
  const draft = !!useQuery(onboardingQuery(epic)).data?.draft;
  const done = rows.length > 0 && rows.every((r) => r.pushed_at);
  // what stands before the push opens on its own once the PIM saves or tries to finish, not on the first visit
  const [checked, setChecked] = useState(false);
  return (
    <StepShell epic={epic} step="detail" title="Detail plan"
      sub="Pick who does each function, type the hours of each step in its days or let Auto allocate fill them, and watch each member's effort per day above. Sub-tasks already in Jira are planned here too. Then review and push to Jira."
      onSave={async () => setChecked(true)}
      footer={draft ? (
        <>
          <span className="eyebrow">The plan is pushed to Jira once the epic is linked.</span>
          <Link to="/onboarding/$epic/$step" params={{ epic, step: 'epic' }} style={{ fontSize: 13, fontWeight: 600 }}>Link epic</Link>
        </>
      ) : (
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
          {q.data && <PushControls epic={epic} v={q.data} name={name} onCheck={() => setChecked(true)} />}
        </>
      )}>
      {q.isPending ? <Loading /> : q.isError ? <QueryError error={q.error} /> : <DetailPlan epic={epic} v={q.data} people={people} checked={checked} />}
    </StepShell>
  );
}
