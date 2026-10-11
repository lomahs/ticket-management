# Team Tracker

Jira companion for a BA team: My Work, Function Matrix, Anken Overview, Planning (Gantt + capacity), Effort
(timesheet + analysis), Team Calendar (leave, holidays), and Settings. It implements the claude.ai/design project
"Team Tracker" (`Team Tracker.dc.html`) on the Atlassian design-system tokens.

```
backend/   Python · FastAPI · SQLAlchemy/SQLite · clean architecture (domain → application → adapters → bootstrap)
frontend/  React · Vite · TypeScript · TanStack Router/Query · feature-sliced, typed client generated from OpenAPI
scripts/   setup.sh · demo.sh · dev.sh · check.sh
```

## Quick start

```bash
scripts/demo.sh          # demo data on http://localhost:8000 — same code as the real app, simulated Jira
scripts/dev.sh           # development: API :8000 (auto-reload) + Vite :5173, demo mode
scripts/dev.sh jira      # development against real Jira (set it up in Settings → Jira)
scripts/check.sh         # every automated check; add --e2e for the Playwright suite
```

`scripts/setup.sh` (run by the others) creates `backend/.venv` and installs `frontend/node_modules`; everything stays
inside the repository. The original mock-data build is tagged: `git checkout ui-mock-v0 && scripts/demo.sh`.

## Modes

| `TRACKER_MODE` | Jira | Writes | "Today" | Database |
|---|---|---|---|---|
| `demo` | simulated site with the design's data | enabled (to the simulated site) | 2026-10-07 | `.tmp/demo/tracker.db`, recreated on start |
| `jira` (default) | your Jira Cloud site, read-only in phase 1 | disabled; the UI says so | real date (Asia/Ho_Chi_Minh) | `backend/data/tracker.db` |

Other settings: `TRACKER_DB_URL` (SQLAlchemy URL), `TRACKER_TODAY` (pin the date), `TRACKER_SECRET_KEY` (Fernet key for
the stored Jira token; otherwise `backend/data/secret.key` is created), `TRACKER_STATIC_DIR` (built frontend to serve).

### Connecting a real Jira site (phase 1: read)

1. `scripts/dev.sh jira`, open Settings → Jira, enter the site URL, the service account e-mail and an
   [API token](https://id.atlassian.com/manage-profile/security/api-tokens), **Test connection**, **Save**.
2. Add the epics to track. Each epic is an anken; its children are phase parent tickets (`[Prepare] …`,
   `[Implement_Coding] …`), whose sub-tasks are the work items.
3. Field mapping: the custom field ids for Planned Start / Planned End and the effort field; **Check** against a ticket.
4. Name rules: how sub-task Summaries encode function id, name and step (default
   `[{phase_name}][{function_id}] {function_name} – {step}`); the preview shows how many synced sub-tasks match.
5. Status mapping (after the first sync): which Jira statuses count as NYS / WIP / Resolved / Closed.
6. Members appear as they are seen on issues; give them a role (BA / BAL / JM) and country.

The API syncs on the chosen interval and from the Sync button; screens read only the local database.

> **No login in phase 1.** Anyone who can reach the server can read the data and change settings, so run it only
> inside the team's network. The Jira token is stored encrypted and never returned by the API.

## Architecture

**Backend** (`backend/src/tracker`)

- `domain/` — pure rules: working-day calendar, flags (late, over, tone, held days, review rounds), scheduling,
  naming rules, capacity, timesheet, analysis, anken roll-ups, Q&A, leave import validation.
- `application/` — ports (`JiraReader`, `JiraWriter`, repositories, `Clock`, `SecretBox`), `SyncFromJira`, and one
  use-case module per screen returning read models.
- `adapters/` — FastAPI (`/api/v1`, problem+json errors), SQLAlchemy + Alembic, Jira Cloud REST v3 reader (httpx),
  the simulated Jira for demo mode, clocks, the sync scheduler.
- `bootstrap.py` — picks adapters from the environment.

Layer rules are enforced by `import-linter` (part of the test suite). The API serves raw values (ISO dates, hours,
enums); the browser formats them. The OpenAPI document is committed at `frontend/src/shared/api/openapi.json`; a
backend test fails when it drifts — run `npm run gen:api` in `frontend/`.

**Frontend** (`frontend/src`)

- `app/` — router (every screen, tab and dialog has a URL; `?ticket=KEY` opens the ticket panel anywhere), shell.
- `shared/` — design-system components, typed API client, formatting, small UI pieces.
- `features/<screen>/` — `api.ts` (the only place that calls HTTP), `model/` (pure display logic with tests),
  `components/`, `index.ts`.

Import boundaries are checked by `src/architecture.test.ts`.

| URL | Screen |
|---|---|
| `/my-work?member=&backlog=` | My Work |
| `/anken`, `/anken/$epic` | Anken Overview |
| `/anken/$epic/matrix?q=&members=&status=&prog=&sort=&r0…r4=`, `…/matrix/new` | Function Matrix, Add function |
| `/planning?start=&weeks=&hide=` | Planning |
| `/effort/timesheet?view=&from=&month=`, `/effort/analysis?anken=` | Effort |
| `/calendar?month=`, `/calendar/leave/new`, `/calendar/leave/$id`, `/calendar/import/$step` | Team Calendar |
| `/settings/$tab` | Settings (jira, field-mapping, status-mapping, name-rules, members, holidays, hours, extra-days, alerts) |

## Tests

`scripts/check.sh` runs: backend pytest (domain, use cases, persistence, Jira reader against REST v3 fixtures,
API, characterization against the mock's numbers, OpenAPI drift), import-linter, pyright, generated-type check, `tsc`, ESLint,
Vitest (display models, import boundaries, screens rendered with recorded API responses).

- `pytest -m jira_live` reads a real site (set `TRACKER_JIRA_URL`, `_EMAIL`, `_TOKEN`, `_EPIC`).
- `backend/scripts/record_jira_fixtures.py` records anonymised responses from a real site for new fixtures.
- `scripts/check.sh --e2e` runs Playwright against a demo server (needs a browser installed for Playwright).
