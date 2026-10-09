#!/usr/bin/env bash
# Runs Team Tracker on demo data for quick UI confirmation.
# Every run starts from a fresh copy of the demo dataset; nothing touches real Jira.
#   scripts/demo.sh            # http://localhost:8000
#   PORT=8100 scripts/demo.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PORT="${PORT:-8000}"
DEMO_DIR="$ROOT/.tmp/demo"

if [ ! -x "$ROOT/backend/.venv/bin/uvicorn" ]; then
  python3 -m venv "$ROOT/backend/.venv"
  "$ROOT/backend/.venv/bin/pip" install -q -r "$ROOT/backend/requirements.txt"
fi
if [ ! -d "$ROOT/frontend/node_modules" ]; then
  (cd "$ROOT/frontend" && npm install)
fi
(cd "$ROOT/frontend" && npm run build >/dev/null)

rm -rf "$DEMO_DIR" && mkdir -p "$DEMO_DIR"
echo "Team Tracker demo → http://localhost:$PORT"
cd "$ROOT/backend"
TRACKER_DATA="$DEMO_DIR/store.json" exec .venv/bin/uvicorn app.main:app --port "$PORT"
