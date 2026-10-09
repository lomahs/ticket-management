#!/usr/bin/env bash
# Development: API with auto-reload on :8000 and Vite on :5173 (proxying /api).
#   scripts/dev.sh          # demo mode (simulated Jira, fixed "today")
#   scripts/dev.sh jira     # real Jira mode (configure the site in Settings → Jira)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODE="${1:-demo}"
"$ROOT/scripts/setup.sh"
trap 'kill 0' EXIT
(cd "$ROOT/backend" && TRACKER_MODE="$MODE" .venv/bin/uvicorn tracker.bootstrap:create_app_from_env --factory --reload --reload-dir src --port 8000) &
(cd "$ROOT/frontend" && npm run dev) &
wait
