$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $repoRoot '.env'
if (-not (Test-Path -LiteralPath $envPath)) {
    $configuration = Get-Content -Raw -LiteralPath (Join-Path $repoRoot '.env.example')
    foreach ($name in @('POSTGRES_PASSWORD', 'RABBITMQ_PASSWORD', 'MINIO_ROOT_PASSWORD', 'JWT_SIGNING_KEY', 'INTERNAL_JWT_SECRET')) {
        $bytes = New-Object byte[] 32
        $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        $generator.GetBytes($bytes)
        $generator.Dispose()
        $secret = [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
        $configuration = [regex]::Replace($configuration, "(?m)^$name=.*$", "$name=$secret")
    }
    [IO.File]::WriteAllText($envPath, $configuration, (New-Object Text.UTF8Encoding $false))
    Write-Host 'Created ignored .env with fresh local secrets.'
} else {
    Write-Host 'Existing .env preserved.'
}
Write-Host 'Run docker compose up -d --wait to start local infrastructure.'
