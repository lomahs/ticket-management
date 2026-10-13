#!/usr/bin/env bash
# Runs Team Tracker on demo data for quick UI confirmation (the "demo" Spring profile): builds the frontend and the
# backend jar and serves both on one port. The demo uses the same code as the real app with a simulated Jira site;
# every run starts from fresh data.
#   scripts/demo.sh             # http://localhost:8000
#   PORT=8100 scripts/demo.sh
# For the original mock build: git checkout ui-mock-v0 && scripts/demo.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PORT="${PORT:-8000}"

"$ROOT/scripts/setup.sh"
(cd "$ROOT/frontend" && npm run build >/dev/null)
(cd "$ROOT/backend" && ./mvnw -q package -DskipTests)

echo "Team Tracker demo → http://localhost:$PORT"
cd "$ROOT/backend"
SPRING_PROFILES_ACTIVE=demo PORT="$PORT" exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -jar target/tracker.jar
