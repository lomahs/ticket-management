import type { ReactNode } from 'react';
export { useDebounced } from '../../../shared/state/debounce';

export { Section } from '../../../shared/ui/bits';

export const ErrText = ({ children }: { children: ReactNode }) => (
  <span role="alert" style={{ fontSize: 12, fontWeight: 600, color: 'var(--hl-red-fg)', alignSelf: 'center' }}>{children}</span>
);

export const Busy = ({ children }: { children: ReactNode }) => (
  <span role="status" style={{ fontSize: 12, color: 'var(--text-muted)', alignSelf: 'center' }}>{children}</span>
);
