import { MutationCache, QueryCache, QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider } from '@tanstack/react-router';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { makeRouter } from './app/router';
import './app/styles/tokens.css';
import './app/styles/app.css';
import { ApiError } from './shared/api/errors';
import { ToastProvider } from './shared/state/toast';

/** A request that answers 401 `unauthenticated` means the session ended: forget the user and go to the sign-in page. */
function onSessionLost(error: unknown) {
  if (!(error instanceof ApiError) || error.code !== 'unauthenticated') return;
  queryClient.removeQueries({ queryKey: ['me'] });
  if (router.state.location.pathname === '/login') return;
  void router.navigate({ to: '/login', search: { next: location.href.slice(location.origin.length) } });
}

const queryClient: QueryClient = new QueryClient({
  defaultOptions: { queries: { staleTime: 15_000, retry: 1, refetchOnWindowFocus: true } },
  queryCache: new QueryCache({ onError: onSessionLost }),
  mutationCache: new MutationCache({ onError: onSessionLost }),
});
const router = makeRouter(queryClient);

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <RouterProvider router={router} />
      </ToastProvider>
    </QueryClientProvider>
  </StrictMode>,
);
