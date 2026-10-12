#!/usr/bin/env bash
# Backend checks: unit, use-case, persistence, Jira reader, characterization, API, boot (incl. the frontend's OpenAPI
# contract) and architecture tests.
#   backend-java/scripts/check.sh
set -euo pipefail
BACKEND="$(cd "$(dirname "$0")/.." && pwd)"
cd "$BACKEND" && ./mvnw -q test
echo "✓ backend-java checks passed"
