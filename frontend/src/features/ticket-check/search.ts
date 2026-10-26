import { z } from 'zod';

/** `check`: show only the sub-tasks with this problem. The anken is the global switcher's (none: every anken). */
export const ticketCheckSearch = z.object({ check: z.string().optional().catch(undefined) });
