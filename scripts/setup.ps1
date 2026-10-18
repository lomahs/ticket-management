# Windows version of setup.sh: checks for a JDK 25 and installs the frontend packages when missing (everything stays
# inside the repository). Maven comes with the backend's wrapper (backend\mvnw.cmd).
#   powershell -ExecutionPolicy Bypass -File scripts\setup.ps1
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$Java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }

# java -version prints to stderr; Windows PowerShell turns redirected stderr into errors, so read it leniently.
$prev = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$version = try { (& $Java -version 2>&1 | Out-String) } catch { '' }
$ErrorActionPreference = $prev
if ($version -notmatch 'version "(2[5-9]|[3-9][0-9])') {
    Write-Host 'A JDK 25 or newer is needed (java on PATH or JAVA_HOME).' -ForegroundColor Red
    exit 1
}

if (-not (Test-Path (Join-Path $Root 'frontend\node_modules'))) {
    Push-Location (Join-Path $Root 'frontend')
    try {
        npm install
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    } finally { Pop-Location }
}
