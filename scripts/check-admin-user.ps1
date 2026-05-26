[CmdletBinding()]
param(
    [string]$ContainerName
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$RootDir = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$EnvFile = Join-Path $RootDir ".env.production"

function Import-EnvFile {
    param([Parameter(Mandatory = $true)][string]$Path)

    $values = @{}
    if (-not (Test-Path -LiteralPath $Path)) {
        return $values
    }

    foreach ($rawLine in (Get-Content -LiteralPath $Path)) {
        $line = $rawLine.Trim()
        if ($line.Length -eq 0 -or $line.StartsWith("#")) {
            continue
        }

        if ($line -match "^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$") {
            $key = $matches[1]
            $value = $matches[2].Trim()

            if (($value.StartsWith('"') -and $value.EndsWith('"')) -or
                ($value.StartsWith("'") -and $value.EndsWith("'"))) {
                $value = $value.Substring(1, $value.Length - 2)
            }

            $values[$key] = $value
        }
    }

    return $values
}

function Get-EnvValue {
    param(
        [Parameter(Mandatory = $true)][hashtable]$Values,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Default
    )

    if ($Values.ContainsKey($Name) -and -not [string]::IsNullOrWhiteSpace([string]$Values[$Name])) {
        return [string]$Values[$Name]
    }

    return $Default
}

$EnvValues = Import-EnvFile -Path $EnvFile
$PostgresUser = Get-EnvValue -Values $EnvValues -Name "POSTGRES_USER" -Default "access_user"
$PostgresDb = Get-EnvValue -Values $EnvValues -Name "POSTGRES_DB" -Default "access_control"

if ([string]::IsNullOrWhiteSpace($ContainerName)) {
    $ProjectName = Get-EnvValue -Values $EnvValues -Name "COMPOSE_PROJECT_NAME" -Default "consuma_catraca"
    $ContainerName = "$ProjectName-postgres-1"
}

$running = (& docker inspect --format '{{.State.Running}}' $ContainerName 2>$null)
if ($LASTEXITCODE -ne 0 -or ($running | Select-Object -First 1) -ne "true") {
    Write-Host "ERRO: container '$ContainerName' nao encontrado ou nao esta rodando."
    exit 1
}

Write-Host "Usuarios com role ADMIN no banco '$PostgresDb':"
Write-Host ""

$CountSql = "SELECT count(*) FROM users WHERE role = 'ADMIN';"
$AdminCount = (& docker exec -i $ContainerName psql -v ON_ERROR_STOP=1 -U $PostgresUser -d $PostgresDb -t -A -c $CountSql 2>&1)
if ($LASTEXITCODE -ne 0) {
    Write-Host $AdminCount
    exit $LASTEXITCODE
}

$AdminCountValue = [int](($AdminCount | Select-Object -First 1).Trim())
if ($AdminCountValue -eq 0) {
    Write-Host "ATENCAO: nenhum usuario ADMIN encontrado no banco."
    Write-Host "Para criar o primeiro admin, habilite temporariamente APP_SEED_ADMIN_ENABLED=true"
    Write-Host "com APP_SEED_ADMIN_EMAIL, APP_SEED_ADMIN_NAME e APP_SEED_ADMIN_PASSWORD no .env.production,"
    Write-Host "reinicie o backend, confirme o login, depois desabilite e reinicie novamente."
    exit 1
}

$AdminSql = "SELECT email, name, role, active, created_at, updated_at FROM users WHERE role = 'ADMIN' ORDER BY email;"
& docker exec -i $ContainerName psql -v ON_ERROR_STOP=1 -P pager=off -U $PostgresUser -d $PostgresDb -c $AdminSql
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "Total de admins encontrados: $AdminCountValue"
