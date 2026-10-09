import { z } from 'zod';

export const myWorkSearch = z.object({
  member: z.string().optional(),
  backlog: z.enum(['week', 'month']).optional().catch(undefined),
});
