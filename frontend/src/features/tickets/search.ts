import { z } from 'zod';

const opt = z.string().optional().catch(undefined);
/**
 * The list's filters and sort. @param type sub (default) | task | all; @param parent a Task's key; @param changed only the
 * edited and new tickets; @param sort columns in order, "-" for descending ("assignee,-start").
 */
export const ticketsSearch = z.object({
  q: opt, type: z.enum(['sub', 'task', 'all']).optional().catch(undefined), parent: opt, status: opt, who: opt,
  changed: z.boolean().optional().catch(undefined), sort: opt,
});
