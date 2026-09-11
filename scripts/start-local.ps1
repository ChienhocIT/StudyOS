param([switch]$Build)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repoRoot
& (Join-Path $PSScriptRoot 'setup.ps1')
if ($Build) {
    docker compose --profile app up -d --build
} else {
    docker compose --profile app up -d
}
if ($LASTEXITCODE -ne 0) { throw 'Docker startup failed. Inspect docker compose logs.' }
Write-Host 'Web: http://localhost:3000 | Core health: http://localhost:8080/actuator/health'
