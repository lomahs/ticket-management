import { z } from 'zod';

/** `phase`: the phase being re-planned. */
export const replanSearch = z.object({ phase: z.string().min(1).optional().catch(undefined) });
