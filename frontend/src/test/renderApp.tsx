/** Renders the real router at a URL with MSW serving recorded API responses. */
import { QueryClientProvider } from '@tanstack/react-query';
import { createMemoryHistory, RouterProvider } from '@tanstack/react-router';
import { render } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { makeRouter } from '../app/router';
import { makeQueryClient } from '../app/session';
import { ToastProvider } from '../shared/state/toast';
import anken from './fixtures/anken.json';
import matrix from './fixtures/matrix-PAY-1100.json';
import members from './fixtures/members.json';
import meAdmin from './fixtures/me-admin.json';
import meta from './fixtures/meta.json';
import myWork from './fixtures/my-work-binh.json';
import jira from './fixtures/settings-jira.json';
import ticket from './fixtures/ticket-PAY-1213.json';

export const fixtures = { anken, matrix, members, meta, myWork, jira, ticket };

export const server = setupServer(
  http.get('*/api/v1/me', () => HttpResponse.json(meAdmin)),
  http.get('*/api/v1/meta', () => HttpResponse.json(meta)),
  http.get('*/api/v1/members', () => HttpResponse.json(members)),
  http.get('*/api/v1/my-work', () => HttpResponse.json(myWork)),
  http.get('*/api/v1/anken', () => HttpResponse.json(anken)),
  http.get('*/api/v1/anken/PAY-1100/matrix', () => HttpResponse.json(matrix)),
  http.get('*/api/v1/tickets/PAY-1213', () => HttpResponse.json(ticket)),
  http.get('*/api/v1/settings/jira', () => HttpResponse.json(jira)),
  http.get('*/api/v1/admin/access-requests', () => HttpResponse.json([])),
);

export function renderApp(url: string) {
  const queryClient = makeQueryClient(() => router, { defaultOptions: { queries: { retry: false } } });
  const router = makeRouter(queryClient, createMemoryHistory({ initialEntries: [url] }));
  const view = render(
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <RouterProvider router={router} />
      </ToastProvider>
    </QueryClientProvider>,
  );
  return { ...view, router };
}
