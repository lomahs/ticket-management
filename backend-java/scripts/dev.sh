#!/usr/bin/env bash
# Development: Spring Boot API on :8000 and Vite on :5173 (proxying /api).
#   backend-java/scripts/dev.sh          # demo mode (simulated Jira, fixed "today")
#   backend-java/scripts/dev.sh jira     # real Jira mode (configure the site in Settings → Jira)
set -euo pipefail
BACKEND="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(dirname "$BACKEND")"
MODE="${1:-demo}"
PROFILES=""
[ "$MODE" = "demo" ] && PROFILES="demo"
if [ ! -d "$ROOT/frontend/node_modules" ]; then (cd "$ROOT/frontend" && npm install); fi
trap 'kill 0' EXIT
(cd "$BACKEND" && SPRING_PROFILES_ACTIVE="$PROFILES" TRACKER_STATIC_DIR=/nonexistent ./mvnw -q spring-boot:run) &
(cd "$ROOT/frontend" && npm run dev) &
wait
