import { z } from 'zod';

/**
 * `date`: a captured day (none: today, live); `phase`: the phase group picked in Process (none: all phases), which the header,
 * the burndown and the Function overview follow. The anken is the global switcher's.
 */
export const dailySearch = z.object({
  date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional().catch(undefined),
  phase: z.string().optional().catch(undefined),
});
