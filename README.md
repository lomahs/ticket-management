# Team Tracker

Jira companion for a BA team: My Work, Function Matrix, Anken Overview, Planning (Gantt + capacity), Effort
(timesheet + analysis), Team Calendar (leave, holidays), and Settings. It implements the claude.ai/design project
"Team Tracker" (`Team Tracker.dc.html`) on the Atlassian design-system tokens.

```
backend/   Java 25 · Spring Boot 3 · JdbcClient/SQLite · Flyway · layered (domain → application → adapters → config)
frontend/  React · Vite · TypeScript · TanStack Router/Query · feature-sliced, typed client generated from OpenAPI
scripts/   setup.sh · demo.sh · dev.sh · check.sh (and a .ps1 of each for Windows)
```

## Quick start

```bash
scripts/demo.sh          # demo data on http://localhost:8000 — same code as the real app, simulated Jira
scripts/dev.sh           # development: API :8000 + Vite :5173, demo profile
scripts/dev.sh jira      # development against real Jira (set it up in Settings → Jira)
scripts/check.sh         # every automated check; add --e2e for the Playwright suite
```

`scripts/setup.sh` (run by the others) checks for a JDK 25 and installs `frontend/node_modules`; Maven comes with the
backend's wrapper (`backend/mvnw`). The original mock-data build is tagged: `git checkout ui-mock-v0 && scripts/demo.sh`.

On Windows, run the PowerShell version of each script (same options, PowerShell style):

```powershell
powershell -ExecutionPolicy Bypass -File scripts\demo.ps1            # -Port 8100 for another port
powershell -ExecutionPolicy Bypass -File scripts\dev.ps1             # or: scripts\dev.ps1 jira
powershell -ExecutionPolicy Bypass -File scripts\check.ps1           # -E2E for the Playwright suite
```

## Modes

| Spring profile | Jira | Writes | "Today" | Database |
|---|---|---|---|---|
| `demo` | simulated site with the design's data | enabled (to the simulated site) | 2026-10-07 | `.tmp/demo/tracker.db`, recreated on start |
| none (default) | your Jira Cloud site, read-only in phase 1 | disabled; the UI says so | real date (Asia/Ho_Chi_Minh) | `backend/data/tracker.db` |

Other settings (environment variables): `SPRING_DATASOURCE_URL` (JDBC URL), `TRACKER_TODAY` (pin the date),
`TRACKER_SECRET_KEY` (key that encrypts the stored Jira token; otherwise `backend/data/secret.key` is created),
`TRACKER_STATIC_DIR` (built frontend to serve), `PORT`. See `backend/README.md`.

### Connecting a real Jira site (phase 1: read)

1. `scripts/dev.sh jira`, open Settings → Jira, enter the site URL, the service account e-mail and an
   [API token](https://id.atlassian.com/manage-profile/security/api-tokens), **Test connection**, **Save**.
2. Add the epics to track. Each epic is an anken; its children are Tasks (a fixed set under every epic), whose
   sub-tasks are the work items.
3. Field mapping: the custom field ids for Planned Start / Planned End and the effort field; **Check** against a ticket.
4. Phases & tasks: the phases in project order, and one row per Task of the fixed set — the Task name (matched against
   the Task Summary, ignoring case; `*` matches any text), its phase, the prefix its sub-tasks use (`{prefix}`), and
   whether its sub-tasks follow the 5-step review workflow (turn it off for Q&A, Work Request, Change Request…). The
   "Tasks in Jira" table shows how the synced Tasks are classified before you save. A Task no row matches takes its
   phase from a leading `[Phase]` in its Summary. Functions are grouped per Task, so the Function Matrix shows one Task
   at a time.
5. Name rules: how sub-task Summaries encode the function and the step, for example
   `[{prefix}][{function_name}]_{step}` with the step aliases `Create`, `BALReview`, `FixCommentBAL`, `JMReview`,
   `FixCommentJM` (default `[{phase_name}][{function_id}] {function_name} – {step}`). Without `{function_id}` the
   function name identifies the function; the first alias of each step is used when the tracker creates sub-tasks.
   The preview shows how many synced sub-tasks match.

   Steps 4 and 5 are set per anken: pick the anken in the **Anken** picker at the top of each page. The picker only
   affects Settings; it does not change the sidebar anken switcher. **Default for new anken** is the template: it is
   copied into every epic you add, and at start-up into every tracked epic that has no settings yet (so an upgrade
   starts each anken from the current settings). **Copy from…** copies the phases & tasks and the name rules of the
   Default or of another anken into the selected one. Saving or copying runs a full sync; an invalid save changes
   nothing.
6. Status mapping (after the first sync): which Jira statuses count as NYS / WIP / Resolved / Closed.
7. Members appear as they are seen on issues; give them a role (BA / BAL / JM) and country.

The API syncs on the chosen interval and from the Sync button; screens read only the local database.

The Jira token is stored encrypted and never returned by the API.

## Sign-in and access

Everyone signs in with their company (LDAP / Active Directory) account. The identity is the account's `mail`
attribute, lowercased, matched to the member's e-mail. Passwords are never stored or logged. A person with no access
yet lands on a page where they can send an access request; an admin approves it (and picks a role) or rejects it in
**Users**. Sessions live in the server's memory (HttpOnly cookie, idle timeout 8 hours), so **a restart signs everyone out**.

| Variable | Meaning |
|---|---|
| `SPRING_LDAP_URLS` | directory URL, e.g. `ldaps://ad.company.local:636` |
| `SPRING_LDAP_BASE` | search base, e.g. `dc=company,dc=local` |
| `SPRING_LDAP_USERNAME` / `SPRING_LDAP_PASSWORD` | bind account that searches for the user |
| `TRACKER_LDAP_USER_FILTER` | filter for the typed username, `{0}` is the username (default `(\|(uid={0})(sAMAccountName={0})(mail={0}))`) |
| `TRACKER_LDAP_MAIL_ATTRIBUTE` / `TRACKER_LDAP_NAME_ATTRIBUTE` | attributes with the e-mail and the display name (default `mail`, `displayName`) |
| `TRACKER_ADMINS` | comma-separated e-mails of the bootstrap admins: always admin and active |
| `TRACKER_BREAKGLASS_PASSWORD_HASH` | bcrypt hash of an emergency password for the user `breakglass`; **off unless set** |

Active Directory example:

```bash
SPRING_LDAP_URLS=ldaps://ad.company.local:636
SPRING_LDAP_BASE=dc=company,dc=local
SPRING_LDAP_USERNAME='cn=svc-tracker,ou=service,dc=company,dc=local'
SPRING_LDAP_PASSWORD=...
TRACKER_LDAP_USER_FILTER='(sAMAccountName={0})'
TRACKER_ADMINS=lead@company.com
```

Make the break-glass hash with `htpasswd -bnBC 12 "" 'the-password' | tr -d ':\n'` and pass the output as
`TRACKER_BREAKGLASS_PASSWORD_HASH` (quote it: it contains `$`). The server does not start when the value is set but is
not a bcrypt hash.

**Anken access:** an anken (a Jira epic) is visible to the people who are its members. Memberships are seeded once,
at the first start of this version, from who had already worked on each epic's sub-tasks in whatever was synced at
that time; the seed never runs again. After that the PM, PIM or an admin manages them in the **Members** panel of the
anken detail page, which also lists people working there who are not members, with *Add to anken*. An epic added in
Settings → Jira after the first start begins with **no members**: its BAs and BALs see nothing of it until a PM or PIM
adds them. Each user has an **Anken scope** (admins set it in **Users**; an admin always sees every anken): *default*
(PM and PIM see every tracked anken, others see the ones they belong to), *all*, or *members* (only the ones they
belong to). Every screen and API call is limited to the visible anken, and an anken that is hidden answers 404 like one
that is not tracked. The sidebar switcher ("All my anken" or one anken) narrows every screen and is remembered per
browser until sign-out; `?anken=` in the URL wins. Everyone always sees their own My Work and their own leave. The Team
Calendar has a row for each member of the visible anken, so a PM or PIM who is a member of no anken has a row only on
their own calendar, and nobody else sees their leave: add them to their anken. **Users → Merge…** joins a duplicate
member into another one (two members with different Jira accounts are two people and cannot be merged).

**HTTPS or a reverse proxy:** set `server.servlet.session.cookie.secure=true` (`SERVER_SERVLET_SESSION_COOKIE_SECURE=true`)
and `server.forward-headers-strategy=native` (or `framework`; `SERVER_FORWARD_HEADERS_STRATEGY`). The login throttle (5
failures for one username within 15 minutes lock it for 15 minutes) counts per username and client address; behind a
proxy without forwarded headers every client shares the proxy's address.

**Demo profile:** an embedded LDAP server with `minh` (BAL, admin), `an`, `binh`, `chi` and `dung` (BA, active:
read-only on every feature except Members; `an` belongs to PAY-1100 and PAY-1300 only, to show the separation), `lan`
(no access yet: use her to try the request flow) and `khach` (a visitor without access). The password is `demo` for all
of them.

**Upgrading an existing install:** set `TRACKER_ADMINS` before you deploy this version, so someone can approve the
others. Everyone else starts without access and must request it (or be activated in **Users** by an admin). Members
that had the old Admin flag become real admins once they are activated. When nobody can approve requests (no
`TRACKER_ADMINS`, no break-glass and no active admin), the server logs a warning at start-up.

**Who sees and changes what** (the role defaults; an admin can override each feature per person, and admins can do everything):

| Role | Every feature except Members | Members |
|---|---|---|
| PM, PIM | read and write | read and write |
| BAL, BA, or no role | read only | none |

In this phase every active user sees every anken.

## Architecture

**Backend** (`backend/src/main/java/tracker`, details in `backend/README.md`)

- `domain/` — plain Java rules: working-day calendar, flags (late, over, tone, held days, review rounds), scheduling,
  naming rules, capacity, timesheet, analysis, anken roll-ups, Q&A, leave import validation.
- `application/` — ports (`JiraReader`, `JiraWriter`, repositories, `SecretBox`), `SyncService`, settings, and one
  `@Service` per screen returning read models.
- `adapters/` — Spring MVC (`/api/v1`, Bean Validation, RFC 9457 problem details, springdoc), `@Repository` classes on
  `JdbcClient` + Flyway, the Jira Cloud REST v3 reader, the simulated Jira for the demo profile, token encryption, the
  scheduled sync.
- `config/` — `TrackerProperties`, the clock and encryption, the demo and Jira-site configurations.

Layer rules are enforced by ArchUnit (part of the test suite). The API serves raw values (ISO dates, hours, enums); the
browser formats them. The OpenAPI document is committed at `frontend/src/shared/api/openapi.json`; a backend test fails
when it drifts — run `npm run gen:api` in `frontend/`.

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
| `/anken/$epic/matrix?task=&q=&members=&status=&prog=&sort=&r0…r4=`, `…/matrix/new` | Function Matrix (one Task at a time), Add function |
| `/planning?start=&weeks=&hide=` | Planning |
| `/effort/timesheet?view=&from=&month=`, `/effort/analysis?anken=` | Effort |
| `/calendar?month=`, `/calendar/leave/new`, `/calendar/leave/$id`, `/calendar/import/$step` | Team Calendar |
| `/settings/$tab` | Settings (jira, field-mapping, status-mapping, structure, name-rules, members, holidays, hours, extra-days, alerts) |

## Tests

`scripts/check.sh` runs: the backend tests (domain, services, persistence, Jira reader against REST v3 fixtures, API,
characterization against the mock's numbers, layer rules, OpenAPI drift), the generated-type check, `tsc`, ESLint,
Vitest (display models, import boundaries, screens rendered with recorded API responses).

- `./mvnw test -Dgroups=jira_live -DexcludedGroups=` in `backend/` reads a real site (set `TRACKER_JIRA_URL`, `_EMAIL`,
  `_TOKEN`, `_EPIC`).
- `scripts/check.sh --e2e` runs Playwright against a demo server (needs a browser installed for Playwright).
