#!/usr/bin/env bash
# Development: the API on :8000 (spring-boot:run) and Vite on :5173 (proxying /api).
#   scripts/dev.sh          # demo profile (simulated Jira, fixed "today")
#   scripts/dev.sh jira     # your Jira site (configure it in Settings → Jira)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODE="${1:-demo}"
PROFILES=""
[ "$MODE" = "demo" ] && PROFILES="demo"
"$ROOT/scripts/setup.sh"
trap 'kill 0' EXIT
(cd "$ROOT/backend" && SPRING_PROFILES_ACTIVE="$PROFILES" TRACKER_STATIC_DIR=/nonexistent ./mvnw -q spring-boot:run) &
(cd "$ROOT/frontend" && npm run dev) &
wait
