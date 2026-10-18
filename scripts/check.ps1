# Windows version of check.sh. Every automated check: backend tests (domain, services, persistence, Jira reader, API,
# characterization, layer rules, the OpenAPI contract the frontend's types come from), generated API types up to date,
# typecheck, lint, frontend tests.
#   powershell -ExecutionPolicy Bypass -File scripts\check.ps1          # all of the above
#   powershell -ExecutionPolicy Bypass -File scripts\check.ps1 -E2E     # also Playwright end-to-end tests (starts a demo server, needs a browser)
param([switch]$E2E)
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot

function Invoke-In([string]$Dir, [scriptblock]$Command) {
    Push-Location (Join-Path $Root $Dir)
    try {
        & $Command
        if ($LASTEXITCODE -ne 0) { Write-Host 'x check failed' -ForegroundColor Red; exit $LASTEXITCODE }
    } finally { Pop-Location }
}

& (Join-Path $PSScriptRoot 'setup.ps1')
if ($LASTEXITCODE) { exit $LASTEXITCODE }

Write-Host '> backend tests'
Invoke-In 'backend' { .\mvnw.cmd -q test }

Write-Host '> API types up to date'
$generated = Join-Path $Root '.tmp\schema.check.d.ts'
New-Item -ItemType Directory -Force (Join-Path $Root '.tmp') | Out-Null
Invoke-In 'frontend' { npx openapi-typescript src/shared/api/openapi.json -o $generated | Out-Null }
# compare without line endings: a Git checkout on Windows may have turned LF into CRLF
$committed = (Get-Content -Raw (Join-Path $Root 'frontend\src\shared\api\schema.d.ts')) -replace "`r`n", "`n"
if ($committed -ne ((Get-Content -Raw $generated) -replace "`r`n", "`n")) {
    Write-Host 'x frontend/src/shared/api/schema.d.ts is out of date: run npm run gen:api' -ForegroundColor Red
    exit 1
}

Write-Host '> typecheck'
Invoke-In 'frontend' { npx tsc -p tsconfig.json --noEmit }
Write-Host '> lint'
Invoke-In 'frontend' { npx eslint src }
Write-Host '> frontend tests'
Invoke-In 'frontend' { npx vitest run }
if ($E2E) {
    Write-Host '> end-to-end'
    Invoke-In 'frontend' { npx playwright test }
}
Write-Host 'v all checks passed' -ForegroundColor Green
