import { useEffect, useState } from 'react';

/** Re-renders every `ms` so relative times ("2 minutes ago") stay fresh. */
export function useTick(ms: number) {
  const [, set] = useState(0);
  useEffect(() => {
    const t = setInterval(() => set((n) => n + 1), ms);
    return () => clearInterval(t);
  }, [ms]);
}
