#!/usr/bin/env bash
# Checks for a JDK 21 and installs the frontend packages when missing (everything stays inside the repository).
# Maven comes with the backend's wrapper (backend/mvnw).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
if ! "$JAVA" -version 2>&1 | grep -qE 'version "(2[1-9]|[3-9][0-9])'; then
  echo "A JDK 21 or newer is needed (java on PATH or JAVA_HOME)." >&2
  exit 1
fi
if [ ! -d "$ROOT/frontend/node_modules" ]; then
  (cd "$ROOT/frontend" && npm install)
fi
