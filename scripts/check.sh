#!/usr/bin/env bash
# Every automated check: backend tests (incl. characterization), backend-java tests (incl. the OpenAPI contract the
# frontend's types come from), layer contracts, generated API types up to date, typecheck, lint, unit/component tests.
#   scripts/check.sh           # all of the above
#   scripts/check.sh --e2e     # also Playwright end-to-end tests (starts a demo server, needs a browser)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
"$ROOT/scripts/setup.sh"
# The API contract (frontend/src/shared/api/openapi.json) is generated from backend-java, so the Python drift test is skipped.
echo "▸ backend tests";        (cd "$ROOT/backend" && .venv/bin/python -m pytest -q --deselect tests/api/test_api.py::test_openapi_file_matches_the_app)
echo "▸ backend-java tests";   (cd "$ROOT/backend-java" && ./mvnw -q test)
echo "▸ layer contracts";      (cd "$ROOT/backend" && .venv/bin/lint-imports | tail -4)
echo "▸ backend types";        (cd "$ROOT/backend" && .venv/bin/pyright | tail -1)
echo "▸ API types up to date"; (cd "$ROOT/frontend" && mkdir -p "$ROOT/.tmp" && npx openapi-typescript src/shared/api/openapi.json -o "$ROOT/.tmp/schema.check.d.ts" >/dev/null \
                                 && diff -q src/shared/api/schema.d.ts "$ROOT/.tmp/schema.check.d.ts")
echo "▸ typecheck";            (cd "$ROOT/frontend" && npx tsc -p tsconfig.json --noEmit)
echo "▸ lint";                 (cd "$ROOT/frontend" && npx eslint src)
echo "▸ frontend tests";       (cd "$ROOT/frontend" && npx vitest run)
if [ "${1:-}" = "--e2e" ]; then
  echo "▸ end-to-end";         (cd "$ROOT/frontend" && npx playwright test)
fi
echo "✓ all checks passed"
