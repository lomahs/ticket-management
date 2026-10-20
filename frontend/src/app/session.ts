import { MutationCache, QueryCache, QueryClient, type QueryClientConfig, type QueryKey } from '@tanstack/react-query';
import { ApiError } from '../shared/api/errors';
import type { makeRouter } from './router';

type AppRouter = ReturnType<typeof makeRouter>;

/**
 * A request other than /me that answers 401 `unauthenticated` means the session ended: forget the user and go to sign-in.
 * One that answers 403 `no_access` means the user lost access (e.g. was disabled): reload /me, then go to /no-access.
 * The /me query is skipped because the route gate owns those redirects.
 */
export function handleSessionErrors(error: unknown, queryKey: QueryKey | undefined, queryClient: QueryClient, router: AppRouter) {
  if (!(error instanceof ApiError)) return;
  if (queryKey?.[0] === 'me') return;
  const path = router.state.location.pathname;
  if (error.code === 'unauthenticated') {
    if (path === '/login') return;
    queryClient.removeQueries({ queryKey: ['me'] });
    void router.navigate({ to: '/login', search: { next: router.state.location.href } });
  } else if (error.code === 'no_access') {
    if (path === '/no-access') return;
    // Refetched first, so /no-access does not see the old, active user and send them back.
    void queryClient.invalidateQueries({ queryKey: ['me'], refetchType: 'all' })
      .finally(() => { if (router.state.location.pathname !== '/no-access') void router.navigate({ to: '/no-access' }); });
  }
}

/** The app's query client, with the session-loss handler wired in. `getRouter` is lazy because the router needs the client first. */
export function makeQueryClient(getRouter: () => AppRouter, config: QueryClientConfig = {}): QueryClient {
  const client: QueryClient = new QueryClient({
    ...config,
    queryCache: new QueryCache({ onError: (e, q) => handleSessionErrors(e, q.queryKey, client, getRouter()) }),
    mutationCache: new MutationCache({ onError: (e, _v, _c, m) => handleSessionErrors(e, m.options.mutationKey, client, getRouter()) }),
  });
  return client;
}
