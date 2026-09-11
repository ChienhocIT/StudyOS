$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repoRoot
python scripts/configure_runtime.py mvn -B -f apps/core-api/pom.xml verify
if ($LASTEXITCODE -ne 0) { throw 'Core verification failed.' }
uv run --project apps/ai-service --extra dev pytest apps/ai-service/tests
if ($LASTEXITCODE -ne 0) { throw 'AI verification failed.' }
uv run --project apps/worker --extra dev pytest apps/worker/tests
if ($LASTEXITCODE -ne 0) { throw 'Worker verification failed.' }
npm --prefix apps/web run typecheck
if ($LASTEXITCODE -ne 0) { throw 'Web typecheck failed.' }
npm --prefix apps/web test
if ($LASTEXITCODE -ne 0) { throw 'Web tests failed.' }
npm --prefix apps/web run build
if ($LASTEXITCODE -ne 0) { throw 'Web production build failed.' }
python scripts/validate_contracts.py --require-internal
if ($LASTEXITCODE -ne 0) { throw 'Contract validation failed.' }
