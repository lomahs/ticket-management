#!/usr/bin/env bash
# Runs Team Tracker on demo data for quick UI confirmation (TRACKER_MODE=demo).
# The demo uses the same code as the real app with a simulated Jira site; every run starts from fresh data.
#   scripts/demo.sh             # http://localhost:8000
#   PORT=8100 scripts/demo.sh
# For the original mock build: git checkout ui-mock-v0 && scripts/demo.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PORT="${PORT:-8000}"

"$ROOT/scripts/setup.sh"
(cd "$ROOT/frontend" && npm run build >/dev/null)

echo "Team Tracker demo → http://localhost:$PORT"
cd "$ROOT/backend"
TRACKER_MODE=demo exec .venv/bin/uvicorn tracker.bootstrap:create_app_from_env --factory --port "$PORT"
