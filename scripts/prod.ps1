# Windows version of prod.sh. Runs Team Tracker as in production against your Jira site (no Spring profile): builds the
# frontend and the backend jar and serves both on one port. It uses the same database as `scripts\dev.ps1 jira`
# (backend\data\tracker.db), so the Jira connection and settings made there carry over. Sign-in reads the LDAP and admin
# environment variables (SPRING_LDAP_*, TRACKER_ADMINS, TRACKER_BREAKGLASS_PASSWORD_HASH; see
# backend\src\main\resources\application.properties).
#   powershell -ExecutionPolicy Bypass -File scripts\prod.ps1             # http://localhost:8000
#   powershell -ExecutionPolicy Bypass -File scripts\prod.ps1 -Port 8100
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

Write-Host "Team Tracker -> http://localhost:$Port"
$env:SPRING_PROFILES_ACTIVE = ''
$env:PORT = "$Port"
$Java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
Invoke-In 'backend' { & $Java -jar target\tracker.jar }
