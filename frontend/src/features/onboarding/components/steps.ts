/** The six steps of onboarding a new anken, in order; {@code key} is the step's URL segment. */
export const STEPS = [
  { n: 1, key: 'epic', label: 'Add epic', sub: 'Find the epic in Jira and track it' },
  { n: 2, key: 'tasks', label: 'Template Tasks', sub: 'Create the missing Tasks with the anken prefix' },
  { n: 3, key: 'estimation', label: 'Estimation', sub: 'Functions, effort, and the Task each goes under' },
  { n: 4, key: 'members', label: 'Add member', sub: 'Who works on the anken, and their hours a day' },
  { n: 5, key: 'schedule', label: 'Master schedule', sub: 'Start date → phase dates and Gantt' },
  { n: 6, key: 'detail', label: 'Detail plan', sub: 'Assign, schedule, push to Jira' },
] as const;

export type StepKey = (typeof STEPS)[number]['key'];

export const isStep = (s: string): s is StepKey => STEPS.some((x) => x.key === s);
