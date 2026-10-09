/**
 * Import boundaries (the frontend counterpart of backend import-linter):
 *  - shared/ never imports app/ or features/
 *  - a feature imports another feature only through its index.ts
 *  - inside a feature, only api.ts talks HTTP (imports shared/api/client)
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const SRC = resolve(__dirname);

function files(dir: string): string[] {
  return readdirSync(dir).flatMap((f) => {
    const p = join(dir, f);
    if (statSync(p).isDirectory()) return files(p);
    return /\.(ts|tsx)$/.test(f) && !/\.d\.ts$/.test(f) ? [p] : [];
  });
}

function imports(file: string): string[] {
  const src = readFileSync(file, 'utf8');
  return [...src.matchAll(/^\s*(?:import|export)\s[^'"]*?from\s+['"](\.[^'"]+)['"]/gm)].map((m) =>
    relative(SRC, resolve(dirname(file), m[1])).replaceAll('\\', '/'),
  );
}

export function violations(): string[] {
  const out: string[] = [];
  for (const f of files(SRC)) {
    const from = relative(SRC, f).replaceAll('\\', '/');
    if (from.endsWith('.test.ts') || from.endsWith('.test.tsx')) continue;
    for (const to of imports(f)) {
      if (from.startsWith('shared/') && (to.startsWith('app') || to.startsWith('features'))) out.push(`${from} → ${to}`);
      const ff = from.match(/^features\/([^/]+)\//)?.[1];
      const tf = to.match(/^features\/([^/]+)(\/.*)?$/);
      if (ff && tf && tf[1] !== ff && tf[2] && tf[2] !== '/index') out.push(`${from} → ${to} (use features/${tf[1]})`);
      if (ff && to === 'shared/api/client' && !from.endsWith('/api.ts')) out.push(`${from} → ${to} (only api.ts calls HTTP)`);
    }
  }
  return out;
}

describe('architecture', () => {
  it('respects import boundaries', () => {
    expect(violations()).toEqual([]);
  });
});
