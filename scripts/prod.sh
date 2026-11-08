#!/usr/bin/env bash
# Runs Team Tracker as in production against your Jira site (no Spring profile): builds the frontend and the backend jar
# and serves both on one port. It uses the same database as `scripts/dev.sh jira` (backend/data/tracker.db), so the Jira
# connection and settings made there carry over. Sign-in reads the LDAP and admin environment variables
# (SPRING_LDAP_*, TRACKER_ADMINS, TRACKER_BREAKGLASS_PASSWORD_HASH; see backend/src/main/resources/application.properties).
#   scripts/prod.sh             # http://localhost:8000
#   PORT=8100 scripts/prod.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PORT="${PORT:-8000}"

"$ROOT/scripts/setup.sh"
(cd "$ROOT/frontend" && npm run build >/dev/null)
(cd "$ROOT/backend" && ./mvnw -q package -DskipTests)

echo "Team Tracker → http://localhost:$PORT"
cd "$ROOT/backend"
SPRING_PROFILES_ACTIVE="" PORT="$PORT" exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -jar target/tracker.jar
