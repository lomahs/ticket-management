# Team Tracker

A Jira companion for a BA team: My Work, Function Matrix, Anken Overview, Planning, Effort, Team Calendar and Settings.
It syncs Jira into a local SQLite database, and every screen reads from that database. The setup and architecture are
in `README.md` and `backend/README.md`.

## ⚠️ Jira ticket types: what they mean here

This rule drives every roll-up, so do not mix the levels up:

| Jira type | Meaning in this app | Notes |
|---|---|---|
| **Epic** | **Anken**: a project | Only epics listed in Settings → Jira are tracked. |
| **Task** (child of the Epic) | **A piece of a phase** | One phase can span **many** Tasks, e.g. phase ITA = `ITA Prepare`, `ITA QA management`, `ITA Create test case`, `ITA Execute`, `ITA Bug fix`. A Task is **never** a unit of work: it is not counted in progress, done/total or effort. |
| **Sub-task** (child of a Task) | **The unit of work** | A member handles it and it is tracked daily. All progress, effort, dates, late flags and "needs attention" come from sub-tasks. |

**Naming** (real data: `docs/Phase.csv`):

- Task: `[{anken_prefix}][LocalTask] {phase_name}`, e.g. `[BookUI][LocalTask] ITA Prepare`.
- Sub-task: `[{anken_prefix}][LocalTask][{phase_name_prefix}]_{function_name}_{step}`.
- `BookUI` is the anken prefix. `LocalTask` only marks our own tickets apart from the client's. **Neither is a phase.**

**Phase groups** (`backend/src/main/java/tracker/domain/PhaseGroups.java`) are computed per epic at read time:

1. A Task template from **the anken's** Settings → Structure that matches the Task decides its phase. So does a leading `[Phase]`
   that names a configured phase; the demo data relies on this.
2. Otherwise, drop the leading `[...]` groups. The first word of what remains (`ITA`) is a phase if **two or more**
   Tasks of the epic share it. Every other Task goes to `Other` (e.g. `WorkRequest`, `Change Request`).
3. A sub-task belongs to its parent Task's phase, through the Jira parent link and never through its title.

Phase progress = `Σ logged hours / (Σ logged + Σ remaining estimate)` over the phase's **sub-tasks** (Jira's progress
bar). Done = status Closed. `Overview.scheduleState` (early/late) is a stub that the user will write; don't fill it in
unasked. `Issue.phase` (stored at sync, used by function creation and Effort Analysis) is a different thing from
phase groups.

## Commands

```bash
scripts/demo.sh                        # demo data on :8000 (simulated Jira, "today" = 2026-10-07)
scripts/dev.sh                         # API :8000 + Vite :5173 (demo profile); `scripts/dev.sh jira` for real Jira
scripts/check.sh                       # every check: backend tests, API types, typecheck, lint, frontend tests
cd backend && ./mvnw -q test -Dtest=PhaseGroupsTest     # one backend test class
cd frontend && npx vitest run src/test/screens.test.tsx  # one frontend test file
cd frontend && npm run gen:api         # after any API change; a backend test fails when openapi.json drifts
```

## Conventions

- Backend layers: `domain/` (pure rules, unit tests with `Fixtures`), then `application/` (ports, `SyncService`,
  one service per screen), then `adapters/` (web, JDBC + Flyway, Jira), then `config/`.
- Every `/api` handler declares `@Open(...)` or `@Requires(feature, level)`; `AccessGateTest` fails otherwise.
- Schema changes go in a new `backend/src/main/resources/db/migration/V<n>__*.sql`. Never edit an applied migration.
  Data that a new column needs from Jira shows up only after a **full sync**.
- The API returns raw values (ISO dates, hours, enums), and the frontend formats them. Frontend code is
  feature-sliced under `frontend/src/features/<screen>`, and URL state lives in each route's zod `search` schema.
- Specs and plans are in `docs/superpowers/specs` and `docs/superpowers/plans`.
