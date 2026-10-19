import { z } from 'zod';

const opt = z.string().optional().catch(undefined);
/** @param tasks the shown Tasks, comma-separated; @param phase every Task of this phase, when no Tasks are given */
export const matrixSearch = z.object({ tasks: opt, phase: opt, q: opt, members: opt, status: opt, prog: opt, sort: opt, r0: opt, r1: opt, r2: opt, r3: opt, r4: opt });
