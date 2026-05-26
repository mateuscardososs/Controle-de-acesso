[CmdletBinding()]
param(
    [ValidateSet("hourly", "daily", "manual")]
    [string]$Scope = "hourly"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

if (-not $PSBoundParameters.ContainsKey("Scope") -and $env:BACKUP_SCOPE) {
    $Scope = $env:BACKUP_SCOPE
}

if (@("hourly", "daily", "manual") -notcontains $Scope) {
    Write-Error "Uso: .\scripts\backup.ps1 [-Scope hourly|daily|manual]"
    exit 1
}

$RootDir = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$EnvFile = Join-Path $RootDir ".env.production"
$ComposeFile = Join-Path $RootDir "docker-compose.prod.yml"
$Stamp = (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssZ")

Set-Location $RootDir

function Import-EnvFile {
    param([Parameter(Mandatory = $true)][string]$Path)

    $values = @{}

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
            Set-Item -Path "Env:$key" -Value $value
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

function Resolve-OperationalPath {
    param([Parameter(Mandatory = $true)][string]$Path)

    if ([System.IO.Path]::IsPathRooted($Path)) {
        return [System.IO.Path]::GetFullPath($Path)
    }

    return [System.IO.Path]::GetFullPath((Join-Path $RootDir $Path))
}

if (-not (Test-Path -LiteralPath $EnvFile)) {
    $fallbackLogDir = Join-Path $RootDir "backups"
    New-Item -ItemType Directory -Force -Path $fallbackLogDir | Out-Null
    $fallbackLog = Join-Path $fallbackLogDir "backup-task.log"
    $message = "$(Get-Date -Format "s") [ERROR] Arquivo .env.production nao encontrado."
    Add-Content -LiteralPath $fallbackLog -Value $message -Encoding UTF8
    Write-Error "Arquivo .env.production nao encontrado."
    exit 1
}

$EnvValues = Import-EnvFile -Path $EnvFile

$DbName = Get-EnvValue -Values $EnvValues -Name "POSTGRES_DB" -Default "access_control"
$DbUser = Get-EnvValue -Values $EnvValues -Name "POSTGRES_USER" -Default "access_user"
$ProjectName = Get-EnvValue -Values $EnvValues -Name "COMPOSE_PROJECT_NAME" -Default "access-control-prod"
$BackupRoot = Resolve-OperationalPath (Get-EnvValue -Values $EnvValues -Name "BACKUP_DIR" -Default "backups")
$DailyRetentionDays = [int](Get-EnvValue -Values $EnvValues -Name "BACKUP_DAILY_RETENTION_DAYS" -Default "90")
$HourlyRetentionDays = [int](Get-EnvValue -Values $EnvValues -Name "BACKUP_HOURLY_RETENTION_DAYS" -Default "14")
$ManualRetentionDays = [int](Get-EnvValue -Values $EnvValues -Name "BACKUP_MANUAL_RETENTION_DAYS" -Default "90")

$RunDir = Join-Path (Join-Path $BackupRoot "runs") $Stamp
$RunDbDir = Join-Path $RunDir "db"
$RunFilesDir = Join-Path $RunDir "files"
$RunLogsDir = Join-Path $RunDir "logs"
$DbDir = Join-Path (Join-Path $BackupRoot "db") $Scope
$FilesDir = Join-Path (Join-Path $BackupRoot "files") $Stamp
$LogsDir = Join-Path (Join-Path $BackupRoot "logs") $Stamp
$ManifestsDir = Join-Path $BackupRoot "manifests"
$TaskLog = Join-Path $BackupRoot "backup-task.log"

New-Item -ItemType Directory -Force -Path $RunDbDir, $RunFilesDir, $RunLogsDir, $DbDir, $FilesDir, $LogsDir, $ManifestsDir | Out-Null

function Write-Log {
    param(
        [Parameter(Mandatory = $true)][string]$Message,
        [ValidateSet("INFO", "WARN", "ERROR")]
        [string]$Level = "INFO"
    )

    $line = "$(Get-Date -Format "s") [$Level] $Message"
    Write-Host $line
    Add-Content -LiteralPath $TaskLog -Value $line -Encoding UTF8
}

function Assert-LastExitCode {
    param([Parameter(Mandatory = $true)][string]$Operation)

    if ($LASTEXITCODE -ne 0) {
        throw "$Operation falhou com codigo $LASTEXITCODE."
    }
}

function Invoke-Compose {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    & docker compose --env-file $EnvFile -f $ComposeFile @Arguments
    Assert-LastExitCode -Operation ("docker compose " + ($Arguments -join " "))
}

function Wait-ForPostgres {
    $attempts = 30

    while ($attempts -gt 0) {
        & docker compose --env-file $EnvFile -f $ComposeFile exec -T postgres pg_isready -U $DbUser -d $DbName *> $null
        if ($LASTEXITCODE -eq 0) {
            return
        }

        $attempts--
        Start-Sleep -Seconds 2
    }

    throw "PostgreSQL nao ficou pronto para backup."
}

function Format-FileSize {
    param([Parameter(Mandatory = $true)][string]$Path)

    $length = (Get-Item -LiteralPath $Path).Length
    if ($length -ge 1GB) {
        return "{0:N2} GB" -f ($length / 1GB)
    }
    if ($length -ge 1MB) {
        return "{0:N2} MB" -f ($length / 1MB)
    }
    if ($length -ge 1KB) {
        return "{0:N2} KB" -f ($length / 1KB)
    }

    return "$length B"
}

function Remove-OldFiles {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Filter,
        [Parameter(Mandatory = $true)][int]$Days
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }

    $cutoff = (Get-Date).AddDays(-$Days)
    Get-ChildItem -LiteralPath $Path -File -Filter $Filter -ErrorAction SilentlyContinue |
        Where-Object { $_.LastWriteTime -lt $cutoff } |
        Remove-Item -Force -ErrorAction SilentlyContinue
}

function Remove-OldDirectories {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][int]$Days
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }

    $cutoff = (Get-Date).AddDays(-$Days)
    Get-ChildItem -LiteralPath $Path -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.LastWriteTime -lt $cutoff } |
        Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
}

try {
    Write-Log "Iniciando backup $Scope em $Stamp."

    Write-Log "Garantindo PostgreSQL ativo via Docker Compose."
    Invoke-Compose -Arguments @("up", "-d", "postgres")
    Wait-ForPostgres

    $DbDumpPath = Join-Path $RunDbDir "postgres.dump"
    $ContainerDump = "/tmp/consuma-postgres-$Stamp.dump"
    Write-Log "Gerando dump PostgreSQL em $DbDumpPath."
    try {
        Invoke-Compose -Arguments @(
            "exec", "-T", "postgres",
            "pg_dump",
            "-U", $DbUser,
            "-d", $DbName,
            "--format=custom",
            "--no-owner",
            "--no-acl",
            "-f", $ContainerDump
        )

        $PostgresContainerId = (& docker compose --env-file $EnvFile -f $ComposeFile ps -q postgres).Trim()
        Assert-LastExitCode -Operation "docker compose ps -q postgres"
        if ([string]::IsNullOrWhiteSpace($PostgresContainerId)) {
            throw "Container postgres nao encontrado pelo Docker Compose."
        }

        & docker cp "${PostgresContainerId}:$ContainerDump" $DbDumpPath
        Assert-LastExitCode -Operation "docker cp postgres dump"
    }
    finally {
        & docker compose --env-file $EnvFile -f $ComposeFile exec -T postgres rm -f $ContainerDump *> $null
    }
    Copy-Item -LiteralPath $DbDumpPath -Destination (Join-Path $DbDir "$Stamp.dump") -Force

    Write-Log "Arquivando uploads de faces."
    $UploadsVolume = "${ProjectName}_uploads_faces"
    & docker run --rm -v "${UploadsVolume}:/data:ro" -v "${RunFilesDir}:/backup" alpine:3.20 sh -c "cd /data && tar -czf /backup/uploads-faces.tar.gz ."
    Assert-LastExitCode -Operation "backup uploads_faces"
    $UploadsArchivePath = Join-Path $RunFilesDir "uploads-faces.tar.gz"
    Copy-Item -LiteralPath $UploadsArchivePath -Destination (Join-Path $FilesDir "uploads-faces.tar.gz") -Force

    Write-Log "Arquivando logs tecnicos."
    $BackendLogsVolume = "${ProjectName}_backend_logs"
    $NginxLogsVolume = "${ProjectName}_nginx_logs"
    & docker run --rm -v "${BackendLogsVolume}:/backend-logs:ro" -v "${NginxLogsVolume}:/nginx-logs:ro" -v "${RunLogsDir}:/backup" alpine:3.20 sh -c "tar -czf /backup/technical-logs.tar.gz -C / backend-logs nginx-logs"
    Assert-LastExitCode -Operation "backup logs tecnicos"
    $TechnicalLogsArchivePath = Join-Path $RunLogsDir "technical-logs.tar.gz"
    Copy-Item -LiteralPath $TechnicalLogsArchivePath -Destination (Join-Path $LogsDir "technical-logs.tar.gz") -Force

    $GitCommit = (& git -C $RootDir rev-parse --short=12 HEAD 2>$null)
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($GitCommit)) {
        $GitCommit = "unavailable"
    }

    $AppVersion = "unknown"
    $PomFile = Join-Path $RootDir "pom.xml"
    if (Test-Path -LiteralPath $PomFile) {
        $pomVersionLine = Get-Content -LiteralPath $PomFile | Where-Object { $_ -match "<version>([^<]+)</version>" } | Select-Object -First 1
        if ($pomVersionLine -match "<version>([^<]+)</version>") {
            $AppVersion = $matches[1]
        }
    }

    $ActiveContainers = (& docker compose --env-file $EnvFile -f $ComposeFile ps --format "table {{.Name}}\t{{.Service}}\t{{.State}}\t{{.Status}}" 2>$null)
    if ($LASTEXITCODE -ne 0 -or $null -eq $ActiveContainers) {
        $ActiveContainers = @("unavailable")
    }

    $SizeReport = @(
        "$(Format-FileSize -Path $DbDumpPath) $DbDumpPath",
        "$(Format-FileSize -Path $UploadsArchivePath) $UploadsArchivePath",
        "$(Format-FileSize -Path $TechnicalLogsArchivePath) $TechnicalLogsArchivePath"
    )

    $EnvironmentName = Get-EnvValue -Values $EnvValues -Name "APP_ENVIRONMENT" -Default "production-local"
    $ManifestPath = Join-Path $RunDir "manifest.txt"
    $Manifest = @(
        "environment=$EnvironmentName",
        "scope=$Scope",
        "created_at_utc=$Stamp",
        "git_commit=$GitCommit",
        "application_version=$AppVersion",
        "compose_project=$ProjectName",
        "database=$DbName",
        "",
        "files:",
        ($SizeReport -join [Environment]::NewLine),
        "",
        "active_containers:",
        ($ActiveContainers -join [Environment]::NewLine),
        "",
        "restore:",
        ".\scripts\restore.sh `"$RunDir`""
    ) -join [Environment]::NewLine

    Set-Content -LiteralPath $ManifestPath -Value $Manifest -Encoding UTF8
    Copy-Item -LiteralPath $ManifestPath -Destination (Join-Path $ManifestsDir "$Stamp.txt") -Force
    Copy-Item -LiteralPath $ManifestPath -Destination (Join-Path $ManifestsDir "latest-success.txt") -Force

    $LatestSuccess = @(
        "created_at_utc=$Stamp",
        "scope=$Scope",
        "run_dir=$RunDir",
        "manifest=$ManifestPath",
        "postgres_dump=$DbDumpPath"
    ) -join [Environment]::NewLine
    $LatestSuccessPath = Join-Path $BackupRoot "latest-success.txt"
    Set-Content -LiteralPath $LatestSuccessPath -Value $LatestSuccess -Encoding UTF8

    Write-Log "Copiando backup para volume Docker ${ProjectName}_backups_data."
    $BackupsVolume = "${ProjectName}_backups_data"
    & docker run --rm -v "${BackupsVolume}:/backup-volume" -v "${RunDir}:/backup-source:ro" alpine:3.20 sh -c "mkdir -p /backup-volume/runs && rm -rf /backup-volume/runs/$Stamp && cp -a /backup-source /backup-volume/runs/$Stamp"
    Assert-LastExitCode -Operation "copia para backups_data"

    Remove-OldFiles -Path (Join-Path (Join-Path $BackupRoot "db") "hourly") -Filter "*.dump" -Days $HourlyRetentionDays
    Remove-OldFiles -Path (Join-Path (Join-Path $BackupRoot "db") "daily") -Filter "*.dump" -Days $DailyRetentionDays
    Remove-OldFiles -Path (Join-Path (Join-Path $BackupRoot "db") "manual") -Filter "*.dump" -Days $ManualRetentionDays
    Remove-OldDirectories -Path (Join-Path $BackupRoot "runs") -Days $DailyRetentionDays
    Remove-OldDirectories -Path (Join-Path $BackupRoot "files") -Days $DailyRetentionDays
    Remove-OldDirectories -Path (Join-Path $BackupRoot "logs") -Days $DailyRetentionDays

    $manifestCutoff = (Get-Date).AddDays(-$DailyRetentionDays)
    Get-ChildItem -LiteralPath $ManifestsDir -File -Filter "*.txt" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -ne "latest-success.txt" -and $_.LastWriteTime -lt $manifestCutoff } |
        Remove-Item -Force -ErrorAction SilentlyContinue

    Write-Log "Backup criado em $RunDir"
    Write-Log "Ultimo sucesso: $LatestSuccessPath"
}
catch {
    Write-Log $_.Exception.Message "ERROR"
    throw
}
