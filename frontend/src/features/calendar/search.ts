import { z } from 'zod';

export const calendarSearch = z.object({ month: z.string().regex(/^\d{4}-\d{2}$/).optional().catch(undefined) });
export const newLeaveSearch = z.object({
  member: z.string().optional().catch(undefined),
  date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional().catch(undefined),
});
