import { MutationCache, QueryCache, QueryClient, type QueryClientConfig, type QueryKey } from '@tanstack/react-query';
import { ApiError } from '../shared/api/errors';
import type { makeRouter } from './router';

type AppRouter = ReturnType<typeof makeRouter>;

/**
 * A request other than /me that answers 401 `unauthenticated` means the session ended: forget the user and go to sign-in.
 * The /me query is skipped because the route gate owns that redirect.
 */
export function handleUnauthenticated(error: unknown, queryKey: QueryKey | undefined, queryClient: QueryClient, router: AppRouter) {
  if (!(error instanceof ApiError) || error.code !== 'unauthenticated') return;
  if (queryKey?.[0] === 'me') return;
  if (router.state.location.pathname === '/login') return;
  queryClient.removeQueries({ queryKey: ['me'] });
  void router.navigate({ to: '/login', search: { next: router.state.location.href } });
}

/** The app's query client, with the session-loss handler wired in. `getRouter` is lazy because the router needs the client first. */
export function makeQueryClient(getRouter: () => AppRouter, config: QueryClientConfig = {}): QueryClient {
  const client: QueryClient = new QueryClient({
    ...config,
    queryCache: new QueryCache({ onError: (e, q) => handleUnauthenticated(e, q.queryKey, client, getRouter()) }),
    mutationCache: new MutationCache({ onError: (e, _v, _c, m) => handleUnauthenticated(e, m.options.mutationKey, client, getRouter()) }),
  });
  return client;
}
