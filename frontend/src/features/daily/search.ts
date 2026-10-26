import { z } from 'zod';

/** `date`: a captured day (none: today, live); `phase`: the burndown's phase group. The anken is the global switcher's. */
export const dailySearch = z.object({
  date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional().catch(undefined),
  phase: z.string().optional().catch(undefined),
});
