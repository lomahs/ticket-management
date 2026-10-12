#!/usr/bin/env bash
# Runs Team Tracker (Spring Boot backend) on demo data: builds the frontend and the jar, serves both on one port.
# The demo uses the same code as the real app with a simulated Jira site; every run starts from fresh data.
#   backend-java/scripts/demo.sh             # http://localhost:8000
#   PORT=8100 backend-java/scripts/demo.sh
set -euo pipefail
BACKEND="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(dirname "$BACKEND")"
PORT="${PORT:-8000}"

if [ ! -d "$ROOT/frontend/node_modules" ]; then (cd "$ROOT/frontend" && npm install); fi
(cd "$ROOT/frontend" && npm run build >/dev/null)
(cd "$BACKEND" && ./mvnw -q package -DskipTests)

echo "Team Tracker demo (Spring Boot) → http://localhost:$PORT"
cd "$BACKEND"
TRACKER_MODE=demo PORT="$PORT" exec java -jar target/tracker.jar
