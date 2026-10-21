/** The seven labels a ticket list can show (the Overview's counts and the effort deviations). */
export const SCHEDULE_FILTERS = ['overdue', 'late_start', 'early_start', 'late_finish', 'early_finish', 'over_burn', 'under_burn'] as const;
export type ScheduleFilter = (typeof SCHEDULE_FILTERS)[number];
