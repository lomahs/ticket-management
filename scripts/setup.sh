#!/usr/bin/env bash
# Creates backend/.venv and installs frontend packages when missing (all inside the repository).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [ ! -x "$ROOT/backend/.venv/bin/python" ]; then
  python3 -m venv "$ROOT/backend/.venv"
fi
if [ ! -x "$ROOT/backend/.venv/bin/uvicorn" ] || [ ! -x "$ROOT/backend/.venv/bin/pyright" ]; then
  "$ROOT/backend/.venv/bin/pip" install -q -e "$ROOT/backend[dev]"
fi
if [ ! -d "$ROOT/frontend/node_modules" ]; then
  (cd "$ROOT/frontend" && npm install)
fi
