import type { Schemas } from './types';

/** Prints the requests a reviewed write sends to Jira in the browser console; the review itself shows only the fields. */
export function logJiraRequests(what: string, requests: Schemas['JiraRequest'][]) {
  console.groupCollapsed(`[Jira] ${what} · ${requests.length} request${requests.length === 1 ? '' : 's'}`);
  for (const r of requests) console.info(`${r.method} ${r.path}`, json(r.body));
  console.groupEnd();
}

function json(body: string | null | undefined): unknown {
  if (!body) return '';
  try {
    return JSON.parse(body);
  } catch {
    return body;
  }
}

/** Prints how a write ended in the browser console. */
export function logJiraResult(what: string, outcome: unknown) {
  if (outcome instanceof Error) console.error(`[Jira] ${what} · failed`, outcome);
  else console.info(`[Jira] ${what} · done`, outcome);
}
