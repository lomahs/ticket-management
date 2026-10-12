# Team Tracker API — Spring Boot

The Team Tracker backend (`../backend`, Python/FastAPI) ported to Java 21 + Spring Boot 3. It serves the same
`/api/v1` HTTP API (paths, query parameters, JSON field names, status codes, problem+json errors and their
field messages), so the frontend runs on it unchanged.

```
backend-java/
  src/main/java/tracker/
    domain/        pure rules: working-day calendar, flags, scheduling, naming rules, capacity, timesheet,
                   analysis, anken roll-ups, Q&A, leave import validation
    application/   ports (JiraReader, JiraWriter, repositories, Clock, SecretBox), SyncFromJira, settings,
                   usecases/ (one class per screen returning read models)
    adapters/      api/ (Spring MVC controllers, request validation, problem+json, static frontend),
                   persistence/ (JDBC + SQLite, Fernet), jira/ (Jira Cloud REST v3 reader), fake/ (demo site),
                   clock/, SyncScheduler
    bootstrap/     TrackerApplication (Spring Boot) and Bootstrap (picks adapters from the environment)
  src/main/resources/db/schema.sql   the tables of the original Alembic revision 0001
```

The layers follow the original (domain → application → adapters → bootstrap); `ArchitectureTest` enforces them
the way import-linter did, and domain/application use no framework or IO libraries.

## Run

Needs a JDK 21 (`java -version`). Maven comes with the wrapper (`./mvnw`).

```bash
backend-java/scripts/demo.sh          # demo data on http://localhost:8000 (builds the frontend and the jar)
backend-java/scripts/dev.sh           # development: API :8000 + Vite :5173, demo mode
backend-java/scripts/dev.sh jira      # development against real Jira (set it up in Settings → Jira)
backend-java/scripts/check.sh         # all backend tests
```

Or directly, from `backend-java/`: `./mvnw package` then `TRACKER_MODE=demo java -jar target/tracker.jar`.

| Variable | Meaning | Default |
|---|---|---|
| `TRACKER_MODE` | `demo` (simulated Jira, writes enabled) or `jira` (your site, read-only in phase 1) | `jira` |
| `TRACKER_DB_URL` | `sqlite:///path/to/tracker.db` (as before) or a JDBC URL | `sqlite:///backend-java/data/tracker.db` |
| `TRACKER_TODAY` | pin "today" | demo: `2026-10-07`; jira: real date (Asia/Ho_Chi_Minh) |
| `TRACKER_SECRET_KEY` | Fernet key for the stored Jira token | `backend-java/data/secret.key`, created on first run |
| `TRACKER_STATIC_DIR` | built frontend to serve | `frontend/dist` when it exists |
| `PORT` | HTTP port | `8000` |

Paths are relative to `backend-java/` (the working directory of the scripts). Demo mode recreates
`.tmp/demo-java/tracker.db` on every start. Jira mode runs the periodic sync on the interval set in Settings.

> **No login in phase 1.** As before: anyone who can reach the server can read the data and change settings, so run
> it only inside the team's network. The Jira token is stored encrypted and never returned by the API.

### Moving from the Python backend

The database and the key file are compatible both ways: same tables (the `alembic_version` row is kept), same JSON
settings documents and timestamp format, and Fernet tokens are read and written in the format of Python's
`cryptography`. To keep existing data, copy `backend/data/tracker.db` and `backend/data/secret.key` into
`backend-java/data/` (or point `TRACKER_DB_URL` / `TRACKER_SECRET_KEY` at them).

## Tests

`./mvnw test` runs:

- domain and use-case tests ported from the original suite, on the demo data synced through the real pipeline;
- `GoldenTest`: the characterization data of the original backend (`golden.json`);
- persistence (including a Fernet token produced by Python), the Jira reader against recorded REST v3 responses,
  the simulated Jira site;
- `ApiTest`: the HTTP API, validation messages and problem+json errors;
- `BootTest`: the real application on a random port: SPA serving, upload limits, and that every operation in the
  frontend's contract (`frontend/src/shared/api/openapi.json`) is served;
- `ArchitectureTest`: layer rules.

`JiraLiveTest` reads a real site and is excluded by default:
`TRACKER_JIRA_URL=… TRACKER_JIRA_EMAIL=… TRACKER_JIRA_TOKEN=… TRACKER_JIRA_EPIC=… ./mvnw test -Dgroups=jira_live -DexcludedGroups=`.

## Differences from the Python backend

- There is no `/openapi.json` or `/docs` (FastAPI generated them). The contract stays the committed
  `frontend/src/shared/api/openapi.json`; `BootTest` checks the Java API serves every operation in it.
- Unknown `GET /api/...` paths answer 404 `{"detail": "Not Found"}` instead of the frontend's `index.html`.
- The demo database lives in `.tmp/demo-java/` and the default data directory is `backend-java/data/`, so both
  backends can run side by side.
- Messages that wrap a library error differ in wording (an unreadable `.xlsx` reports Apache POI's message instead of
  openpyxl's; a sync that fails on an unexpected error names the Java exception). The position given for a JSON syntax
  error is Jackson's.
