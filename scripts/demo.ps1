# Windows version of demo.sh. Runs Team Tracker on demo data for quick UI confirmation (the "demo" Spring profile):
# builds the frontend and the backend jar and serves both on one port. The demo uses the same code as the real app with
# a simulated Jira site; every run starts from fresh data.
#   powershell -ExecutionPolicy Bypass -File scripts\demo.ps1             # http://localhost:8000
#   powershell -ExecutionPolicy Bypass -File scripts\demo.ps1 -Port 8100
param([int]$Port = $(if ($env:PORT) { [int]$env:PORT } else { 8000 }))
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot

function Invoke-In([string]$Dir, [scriptblock]$Command) {
    Push-Location (Join-Path $Root $Dir)
    try {
        & $Command
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    } finally { Pop-Location }
}

& (Join-Path $PSScriptRoot 'setup.ps1')
if ($LASTEXITCODE) { exit $LASTEXITCODE }
Invoke-In 'frontend' { npm run build | Out-Null }
Invoke-In 'backend' { .\mvnw.cmd -q package -DskipTests }

Write-Host "Team Tracker demo -> http://localhost:$Port"
$env:SPRING_PROFILES_ACTIVE = 'demo'
$env:PORT = "$Port"
$Java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
Invoke-In 'backend' { & $Java -jar target\tracker.jar }
