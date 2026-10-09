import { z } from 'zod';

const opt = z.string().optional().catch(undefined);
export const matrixSearch = z.object({ q: opt, members: opt, status: opt, prog: opt, sort: opt, r0: opt, r1: opt, r2: opt, r3: opt, r4: opt });
