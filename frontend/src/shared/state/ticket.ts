import { getRouteApi, useNavigate } from '@tanstack/react-router';

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
