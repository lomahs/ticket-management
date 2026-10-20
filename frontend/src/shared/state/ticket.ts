import { getRouteApi, useNavigate } from '@tanstack/react-router';
import { useEffect, useRef, useSyncExternalStore } from 'react';

const root = getRouteApi('__root__');

/** The ticket open in the side panel (?ticket=KEY on any route). */
export function useTicketKey(): string | undefined {
  return root.useSearch({ select: (s) => s.ticket });
}

/** Opens (or with null closes) the ticket panel on top of the current screen. */
export function useOpenTicket(): (key: string | null) => void {
  // Not root.useNavigate(): that resolves '.' from '/', which redirects to /my-work and drops ?ticket.
  const navigate = useNavigate();
  return (key) => navigate({ to: '.', search: (prev) => ({ ...prev, ticket: key ?? undefined }) });
}

/** A way back from the ticket panel to the panel it was opened from (Planning's function panel). */
export interface TicketBack { label: string; back: () => void; close: () => void }

let current: TicketBack | null = null;
const listeners = new Set<() => void>();
const notify = () => listeners.forEach((l) => l());

/** The back action for the open ticket, when the screen behind it offers one. */
export function useTicketBack(): TicketBack | null {
  return useSyncExternalStore((l) => { listeners.add(l); return () => listeners.delete(l); }, () => current);
}

/** Offers `b` to the ticket panel while it is set; the latest callbacks are used without re-registering. */
export function useProvideTicketBack(b: TicketBack | null) {
  const ref = useRef(b);
  ref.current = b;
  const label = b?.label ?? null;
  useEffect(() => {
    if (label === null) return;
    const mine: TicketBack = { label, back: () => ref.current?.back(), close: () => ref.current?.close() };
    current = mine;
    notify();
    return () => { if (current === mine) { current = null; notify(); } };
  }, [label]);
}
