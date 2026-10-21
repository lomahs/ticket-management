import { z } from 'zod';

export const timesheetSearch = z.object({
  view: z.enum(['week', 'month']).optional().catch(undefined),
  from: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional().catch(undefined),
  month: z.string().regex(/^\d{4}-\d{2}$/).optional().catch(undefined),
});

/** The anken is the global switcher's (the root search's `anken`). */
export const burndownSearch = z.object({
  phase: z.string().optional().catch(undefined),
  task: z.string().optional().catch(undefined),
  member: z.string().optional().catch(undefined),
});
