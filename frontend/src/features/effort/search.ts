import { z } from 'zod';

export const timesheetSearch = z.object({
  view: z.enum(['week', 'month']).optional().catch(undefined),
  from: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional().catch(undefined),
  month: z.string().regex(/^\d{4}-\d{2}$/).optional().catch(undefined),
  /** Only the members with an over-allocated, under-allocated or unallocated day. */
  show: z.enum(['over', 'under', 'no_allocation']).optional().catch(undefined),
});

/** The anken is the global switcher's (the root search's `anken`). */
export const burndownSearch = z.object({
  phase: z.string().optional().catch(undefined),
  task: z.string().optional().catch(undefined),
  member: z.string().optional().catch(undefined),
});
