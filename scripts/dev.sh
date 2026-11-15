#!/usr/bin/env bash
# Development: the API on :8000 (spring-boot:run) and Vite on :5173 (proxying /api).
#   scripts/dev.sh          # demo profile (simulated Jira, fixed "today")
#   scripts/dev.sh jira     # your Jira site (configure it in Settings → Jira); sign in as breakglass / admin
#                           # unless TRACKER_BREAKGLASS_PASSWORD_HASH is set
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODE="${1:-demo}"
DEV_ADMIN_HASH='$2y$10$masVnw.FKzwitUCIvSSiiOzn94hTXHL1uS48jy5pkDBwdTS79LBSK'
PROFILES=""
[ "$MODE" = "demo" ] && PROFILES="demo"
# Jira mode has no directory to sign in with locally: a default break-glass admin (password "admin"), dev only
[ "$MODE" = "jira" ] && export TRACKER_BREAKGLASS_PASSWORD_HASH="${TRACKER_BREAKGLASS_PASSWORD_HASH:-"$DEV_ADMIN_HASH"}"
"$ROOT/scripts/setup.sh"
trap 'kill 0' EXIT
(cd "$ROOT/backend" && SPRING_PROFILES_ACTIVE="$PROFILES" TRACKER_STATIC_DIR=/nonexistent ./mvnw -q spring-boot:run) &
(cd "$ROOT/frontend" && npm run dev) &
wait
