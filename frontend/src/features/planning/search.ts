import { z } from 'zod';

export const planningSearch = z.object({
  start: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional().catch(undefined),
  weeks: z.coerce.number().pipe(z.union([z.literal(1), z.literal(2)])).optional().catch(undefined),
  hide: z.string().optional().catch(undefined),
});
