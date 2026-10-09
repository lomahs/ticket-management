# Team Tracker

Jira companion for a BA team: function matrix, personal work queue, anken overview, planning Gantt,
effort timesheet, team calendar and admin settings. Implements the claude.ai/design project
"Team Tracker" (`Team Tracker.dc.html`) on the Atlassian design-system tokens.

```
backend/   Python · FastAPI API, JSON-file store seeded with the design's demo data
frontend/  React + Vite + TypeScript UI
```

## Run (development)

```bash
# API on :8000
cd backend
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
.venv/bin/uvicorn app.main:app --reload --port 8000

# UI on :5173 (proxies /api to :8000)
cd frontend
npm install
npm run dev
```

Open http://localhost:5173.

## Run (single server)

```bash
cd frontend && npm run build          # writes frontend/dist
cd ../backend && .venv/bin/uvicorn app.main:app --port 8000
```

The API serves the built UI at http://localhost:8000.

## Tests and checks

```bash
cd backend && .venv/bin/python -m pytest -q
cd frontend && npm run typecheck
```

## Configuration

| Env var         | Default                    | Purpose                                                         |
|-----------------|----------------------------|-----------------------------------------------------------------|
| `TRACKER_DATA`  | `backend/data/store.json`  | Where state is persisted. Deleted → reseeded on next start.     |
| `TRACKER_TODAY` | `2026-10-07`               | "Today" for lateness, timesheets and plans (the demo data is anchored to this date). |

`POST /api/reset` restores the demo data.

## How it fits together

- `GET /api/state` returns one snapshot: members, config, holidays, leave, functions (5 sub-tasks each),
  other tasks, timesheet, settings. The server adds derived flags to every task (`late`, `lateDays`, `over`,
  `tone`, `isCur`). Every mutation returns a fresh snapshot, and the UI just replaces its copy.
- Writes that the UI confirms with "Written to Jira" (ticket edits, plan pushes, new functions) go through
  the API store. **Jira is simulated.** There is no live Jira client yet: the connection test, epic lookup and
  field-mapping check run against the seeded catalog. `backend/app/main.py` (`_jira_ok`, `add_epic`, and the
  ticket/plan routes) is where a real Jira REST client would plug in.
- Leave import accepts `.csv` and `.xlsx` (via openpyxl). Rows are validated server-side, and only valid rows are imported.
- The design-system components (`Button`, `IconButton`, `Select`, `TextField`, `Tabs`, `Toast`, `Icon`) are ported
  to typed React in `frontend/src/ds/`. The tokens live in `frontend/src/styles/tokens.css`, including dark theme.
  Icons come from `lucide-react` (the design loaded lucide-static from a CDN).
