# Windows version of dev.sh. Development: the API on :8000 (spring-boot:run) and Vite on :5173 (proxying /api).
# Ctrl+C stops both.
#   powershell -ExecutionPolicy Bypass -File scripts\dev.ps1          # demo profile (simulated Jira, fixed "today")
#   powershell -ExecutionPolicy Bypass -File scripts\dev.ps1 jira     # your Jira site (configure it in Settings -> Jira)
param([ValidateSet('demo', 'jira')][string]$Mode = 'demo')
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot

& (Join-Path $PSScriptRoot 'setup.ps1')
if ($LASTEXITCODE) { exit $LASTEXITCODE }

$env:SPRING_PROFILES_ACTIVE = if ($Mode -eq 'demo') { 'demo' } else { '' }
$env:TRACKER_STATIC_DIR = Join-Path $Root '.tmp\no-static'  # a folder that does not exist: Vite serves the frontend

$backend = Start-Process -FilePath (Join-Path $Root 'backend\mvnw.cmd') -ArgumentList '-q', 'spring-boot:run' `
    -WorkingDirectory (Join-Path $Root 'backend') -NoNewWindow -PassThru
$frontend = Start-Process -FilePath 'npm.cmd' -ArgumentList 'run', 'dev' `
    -WorkingDirectory (Join-Path $Root 'frontend') -NoNewWindow -PassThru
try {
    while (-not $backend.HasExited -and -not $frontend.HasExited) { Start-Sleep -Milliseconds 500 }
} finally {
    # mvnw and npm start child processes (java, node): stop the whole tree of each
    foreach ($p in @($backend, $frontend)) {
        if (-not $p.HasExited) { taskkill /T /F /PID $p.Id | Out-Null }
    }
}
