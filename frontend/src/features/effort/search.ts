import { z } from 'zod';

export const timesheetSearch = z.object({
  view: z.enum(['week', 'month']).optional().catch(undefined),
  from: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional().catch(undefined),
  month: z.string().regex(/^\d{4}-\d{2}$/).optional().catch(undefined),
});

export const analysisSearch = z.object({ anken: z.string().optional().catch(undefined) });
