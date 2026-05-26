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
if ([string]::IsNullOrWhiteSpace($ContainerName)) {
    $ProjectName = Get-EnvValue -Values $EnvValues -Name "COMPOSE_PROJECT_NAME" -Default "consuma_catraca"
    $ContainerName = "$ProjectName-nginx-1"
}

$running = (& docker inspect --format '{{.State.Running}}' $ContainerName 2>$null)
if ($LASTEXITCODE -ne 0 -or ($running | Select-Object -First 1) -ne "true") {
    Write-Host "ERRO: container '$ContainerName' nao encontrado ou nao esta rodando."
    Write-Host "Verifique com: docker compose --env-file .env.production -f docker-compose.prod.yml ps"
    exit 1
}

Write-Host "Recarregando configuracao do Nginx em '$ContainerName'..."
& docker exec $ContainerName nginx -s reload
if ($LASTEXITCODE -ne 0) {
    Write-Host "ERRO: falha ao recarregar o Nginx. Verifique com:"
    Write-Host "  docker logs $ContainerName --tail=50"
    exit 1
}

Write-Host "Nginx recarregado com sucesso."
Write-Host "Se havia 502 por cache de DNS apos restart do backend, aguarde 5 segundos e teste novamente."
