import { useEffect, useState } from 'react';

/** The value as it was {@code ms} after it last changed: a request follows typing, not every keystroke. */
export function useDebounced<T>(value: T, ms: number): T {
  const [v, setV] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setV(value), ms);
    return () => clearTimeout(t);
  }, [value, ms]);
  return v;
}
