import { Navigate, getRouteApi } from '@tanstack/react-router';
import { DetailStep } from '../steps/DetailStep';
import { EpicStep } from '../steps/EpicStep';
import { EstimationStep } from '../steps/EstimationStep';
import { MembersStep } from '../steps/MembersStep';
import { ScheduleStep } from '../steps/ScheduleStep';
import { TasksStep } from '../steps/TasksStep';
import { isStep } from './steps';

const route = getRouteApi('/app/onboarding/$epic/$step');

/** One step of an anken's onboarding, by its URL segment. */
export function OnboardingStepPage() {
  const { epic, step } = route.useParams();
  if (!isStep(step)) return <Navigate to="/onboarding" />;
  const Step = { epic: EpicStep, tasks: TasksStep, estimation: EstimationStep, members: MembersStep, schedule: ScheduleStep, detail: DetailStep }[step];
  return <Step key={epic} epic={epic} />;
}
