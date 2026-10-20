import createClient from 'openapi-fetch';
import { ApiError, type FieldError } from './errors';
import type { paths } from './schema';

/** Typed HTTP client for the Team Tracker API. Only feature api.ts modules use it. */
export const http = createClient<paths>({
  baseUrl: typeof location !== 'undefined' ? location.origin : '',
  fetch: (request) => globalThis.fetch(request), // looked up per call so test interceptors (MSW) apply
});

/** Double-submit CSRF: echo the XSRF-TOKEN cookie in X-XSRF-TOKEN on every request that changes data. */
http.use({
  onRequest({ request }) {
    if (request.method === 'GET' || request.method === 'HEAD') return undefined;
    const token = typeof document !== 'undefined' ? /(?:^|;\s*)XSRF-TOKEN=([^;]*)/.exec(document.cookie)?.[1] : undefined;
    if (token) request.headers.set('X-XSRF-TOKEN', decodeURIComponent(token));
    return request;
  },
});

interface Result<T> { data?: T; error?: unknown; response: Response }

/** Returns the response body or throws an ApiError built from the problem+json body. */
export async function unwrap<T>(call: Promise<Result<T>>): Promise<T> {
  let res: Result<T>;
  try {
    res = await call;
  } catch {
    throw new ApiError(0, 'network', 'Cannot reach the Team Tracker API. Is the backend running?', []);
  }
  if (res.error !== undefined || !res.response.ok) {
    const p = (res.error ?? {}) as { code?: string; detail?: string; errors?: FieldError[] };
    throw new ApiError(res.response.status, p.code ?? 'error', p.detail ?? res.response.statusText, p.errors ?? []);
  }
  return res.data as T;
}
