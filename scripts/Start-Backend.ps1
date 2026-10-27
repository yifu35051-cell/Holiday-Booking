#Requires -Version 5.1
<#
.SYNOPSIS
    Start Docker MySQL and the Spring Boot backend using one local config file.
.DESCRIPTION
    Reads .env.mysql as data, never executes it. File values override inherited
    development settings for this process only; the original values are restored
    when the script finishes. Ctrl+C stops Java; MySQL data and container remain.
#>
[CmdletBinding()]
param(
    [string]$ConfigFile = '',
    [switch]$CheckOnly,
    [switch]$InitializeAdmin
)

$script:ProjectRoot = Split-Path -Parent $PSScriptRoot

function Read-LocalConfiguration {
    param([string]$Path, [switch]$EnableAdminInitialization)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw 'Local config is missing. Copy .env.mysql.example to .env.mysql and edit it first.'
    }

    $allowedKeys = @(
        'MYSQL_ROOT_PASSWORD', 'MYSQL_PASSWORD', 'MYSQL_PORT',
        'SERVER_PORT', 'BOOTSTRAP_ADMIN_EMAIL', 'BOOTSTRAP_ADMIN_PASSWORD'
    )
    $config = @{
        MYSQL_PORT = '3307'
        SERVER_PORT = '8080'
        BOOTSTRAP_ADMIN_EMAIL = ''
        BOOTSTRAP_ADMIN_PASSWORD = ''
    }
    $seen = @{}
    $lineNumber = 0

    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        $lineNumber++
        $text = $line.Trim()
        if (-not $text -or $text.StartsWith('#')) { continue }

        if ($text -notmatch '^([A-Z][A-Z0-9_]*)\s*=\s*(.*)$') {
            throw "Invalid configuration at line $lineNumber. Use KEY=value."
        }
        $key = $Matches[1]
        $value = $Matches[2].Trim()
        if ($key -cnotin $allowedKeys) {
            throw "Unsupported setting at line $lineNumber. See .env.mysql.example."
        }
        if ($seen.ContainsKey($key)) { throw "Duplicate setting: $key." }
        $seen[$key] = $true

        # Use the same literal subset of dotenv syntax as the supplied template.
        # Single quotes preserve $, #, spaces and backslashes without interpolation.
        if ($value.StartsWith("'")) {
            if ($value -notmatch "^'([^']*)'$") {
                throw "Invalid single-quoted value for $key. Embedded quotes/multiline values are not supported."
            }
            $value = $Matches[1]
        }
        elseif ($value -notmatch '^[A-Za-z0-9_./:@+-]*$') {
            throw "Use single quotes around $key. Double quotes, inline comments and interpolation are not supported."
        }
        $config[$key] = $value
    }

    foreach ($key in @('MYSQL_ROOT_PASSWORD', 'MYSQL_PASSWORD')) {
        if (-not $config.ContainsKey($key) -or [string]::IsNullOrWhiteSpace($config[$key])) {
            throw "$key must be configured in the local file."
        }
        if ($config[$key] -like 'REPLACE_WITH_*') {
            throw "Replace the example value for $key in the local file."
        }
    }
    foreach ($key in @('MYSQL_PORT', 'SERVER_PORT')) {
        $port = 0
        if (-not [int]::TryParse($config[$key], [ref]$port) -or $port -lt 1 -or $port -gt 65535) {
            throw "$key must be an integer from 1 to 65535."
        }
        $config[$key] = $port.ToString()
    }
    if ($config.MYSQL_PORT -eq $config.SERVER_PORT) {
        throw 'MYSQL_PORT and SERVER_PORT must be different.'
    }

    # Ordinary startup never provisions accounts, even with stale/partial config.
    if (-not $EnableAdminInitialization) {
        $config.BOOTSTRAP_ADMIN_EMAIL = ''
        $config.BOOTSTRAP_ADMIN_PASSWORD = ''
        return $config
    }
    $email = $config.BOOTSTRAP_ADMIN_EMAIL
    $password = $config.BOOTSTRAP_ADMIN_PASSWORD
    if ([string]::IsNullOrWhiteSpace($email) -or [string]::IsNullOrWhiteSpace($password)) {
        throw '-InitializeAdmin requires both BOOTSTRAP_ADMIN_EMAIL and BOOTSTRAP_ADMIN_PASSWORD. Omit this switch for ordinary startup.'
    }
    if ($email) {
        if ($email -notmatch '^[^@\s]+@[^@\s]+\.[^@\s]+$') {
            throw 'BOOTSTRAP_ADMIN_EMAIL is invalid.'
        }
        if ($password -like 'REPLACE_WITH_*' -or $password.Length -lt 12 -or
            $password.Length -gt 72 -or [Text.Encoding]::UTF8.GetByteCount($password) -gt 72) {
            throw 'Set a real administrator password: 12-72 characters and at most 72 UTF-8 bytes.'
        }
    }
    return $config
}

function Invoke-CapturedCommand {
    param([string]$Executable, [string[]]$Arguments)

    # Native stderr in Windows PowerShell 5.1 must not bypass exit-code checks.
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $Executable @Arguments 2>&1
        $code = $LASTEXITCODE
    }
    finally { $ErrorActionPreference = $previousPreference }
    return @{ Code = $code; Text = ($output | Out-String) }
}

function Assert-LocalTools {
    foreach ($tool in @('docker', 'java')) {
        if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
            throw "$tool is not available. Install Docker Desktop and JDK 21, then open a new terminal."
        }
    }
    $java = Invoke-CapturedCommand 'java' @('-version')
    if ($java.Code -ne 0 -or $java.Text -notmatch 'version "21[."]') {
        throw 'The java command must use JDK 21. Check PATH and JAVA_HOME.'
    }
    # Maven Wrapper prefers JAVA_HOME, so also validate it when explicitly set.
    if ($env:JAVA_HOME) {
        $homeJava = Join-Path $env:JAVA_HOME 'bin/java.exe'
        if (-not (Test-Path -LiteralPath $homeJava)) { throw 'JAVA_HOME does not point to a JDK.' }
        $homeVersion = Invoke-CapturedCommand $homeJava @('-version')
        if ($homeVersion.Code -ne 0 -or $homeVersion.Text -notmatch 'version "21[."]') {
            throw 'JAVA_HOME must point to JDK 21.'
        }
    }
    $compose = Invoke-CapturedCommand 'docker' @('compose', 'version')
    if ($compose.Code -ne 0) { throw 'Docker Compose v2 is required (docker compose version).' }
}

function Wait-DockerEngine {
    $info = Invoke-CapturedCommand 'docker' @('info', '--format', '{{.OSType}}')
    if ($info.Code -ne 0) {
        if ($info.Text -match 'Access is denied|permission denied') {
            throw 'Docker engine access denied. Open Docker Desktop under your Windows account and check its access permissions.'
        }
        $candidates = @(
            (Join-Path $env:ProgramFiles 'Docker/Docker/Docker Desktop.exe'),
            (Join-Path $env:LOCALAPPDATA 'Programs/DockerDesktop/Docker Desktop.exe')
        )
        $desktop = $candidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
        if (-not $desktop) {
            throw 'Docker engine is unavailable. Start Docker Desktop with Linux containers, then retry.'
        }
        Write-Host '[2/4] Starting Docker Desktop; waiting up to 120 seconds...'
        Start-Process -FilePath $desktop -WindowStyle Hidden | Out-Null
        $deadline = [DateTime]::UtcNow.AddSeconds(120)
        do {
            Start-Sleep -Seconds 3
            $info = Invoke-CapturedCommand 'docker' @('info', '--format', '{{.OSType}}')
            if ($info.Code -eq 0) { break }
            if ([DateTime]::UtcNow -ge $deadline) {
                throw 'Docker engine did not become ready. Open Docker Desktop and inspect the WSL/Linux engine status.'
            }
            Write-Host 'Waiting for the Docker engine...'
        } while ($true)
    }
    if ($info.Text.Trim() -ne 'linux') {
        throw 'Switch Docker Desktop to Linux containers before running this project.'
    }
}

function Invoke-DatabaseCompose {
    param([string]$ConfigPath, [string[]]$Arguments)

    $composeFile = Join-Path $script:ProjectRoot 'compose.mysql.yaml'
    & docker compose --env-file $ConfigPath -f $composeFile @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw 'Database command failed. Inspect Docker/Compose output; Java was not started. Existing data is retained.'
    }
}

function Invoke-BackendMaven {
    $wrapper = Join-Path $script:ProjectRoot 'backend/mvnw.cmd'
    $pom = Join-Path $script:ProjectRoot 'backend/pom.xml'
    & $wrapper -f $pom spring-boot:run
    if ($LASTEXITCODE -ne 0) {
        throw 'Spring Boot exited with an error. Check the preceding Maven/application log. MySQL remains running.'
    }
}

function Start-LocalBackend {
    param([string]$Path, [switch]$ValidateOnly, [switch]$EnableAdminInitialization)

    if (-not $Path) { $Path = Join-Path $script:ProjectRoot '.env.mysql' }
    $config = Read-LocalConfiguration $Path -EnableAdminInitialization:$EnableAdminInitialization
    $configPath = (Resolve-Path -LiteralPath $Path).Path
    Assert-LocalTools

    # One source of truth for both Compose and JDBC, even if the shell has old env values.
    $environment = @{
        MYSQL_ROOT_PASSWORD = $config.MYSQL_ROOT_PASSWORD
        MYSQL_PASSWORD = $config.MYSQL_PASSWORD
        MYSQL_PORT = $config.MYSQL_PORT
        SERVER_PORT = $config.SERVER_PORT
        BOOTSTRAP_ADMIN_EMAIL = $config.BOOTSTRAP_ADMIN_EMAIL
        BOOTSTRAP_ADMIN_PASSWORD = $config.BOOTSTRAP_ADMIN_PASSWORD
        BOOKING_BOOTSTRAP_EMAIL = $config.BOOTSTRAP_ADMIN_EMAIL
        BOOKING_BOOTSTRAP_PASSWORD = $config.BOOTSTRAP_ADMIN_PASSWORD
        DB_URL = "jdbc:mysql://127.0.0.1:$($config.MYSQL_PORT)/holiday_booking?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
        DB_USERNAME = 'holiday_booking'
        DB_PASSWORD = $config.MYSQL_PASSWORD
        SPRING_PROFILES_ACTIVE = 'local'
        PAYMENT_MODE = 'disabled'
        EMAIL_MODE = 'disabled'
        JOBS_ENABLED = 'false'
        MAIL_HEALTH_ENABLED = 'false'
    }
    $original = @{}
    try {
        foreach ($key in $environment.Keys) {
            $original[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
            [Environment]::SetEnvironmentVariable($key, $environment[$key], 'Process')
        }
        Write-Host "[1/4] Validating local config (MySQL=$($config.MYSQL_PORT), HTTP=$($config.SERVER_PORT))..."
        Invoke-DatabaseCompose $configPath @('config', '--quiet')
        if ($ValidateOnly) {
            Write-Host 'Configuration and tools validated. No container or Java process was started.'
            return
        }

        Write-Host '[2/4] Checking Docker Linux engine...'
        Wait-DockerEngine
        Write-Host '[3/4] Starting MySQL and waiting for its health check...'
        Invoke-DatabaseCompose $configPath @('up', '-d', '--wait', '--wait-timeout', '180', 'mysql')

        # Do not confuse a failed second launch with the first backend still running.
        $listeners = [Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()
        if (@($listeners | Where-Object { $_.Port -eq [int]$config.SERVER_PORT }).Count -gt 0) {
            throw "HTTP port $($config.SERVER_PORT) is already in use. Stop the previous backend or change SERVER_PORT in the file."
        }
        if (-not $config.BOOTSTRAP_ADMIN_EMAIL) {
            Write-Host 'Ordinary startup: administrator initialization is disabled (optional: -InitializeAdmin).'
        }
        Write-Host "[4/4] Starting Spring Boot; health URL: http://localhost:$($config.SERVER_PORT)/actuator/health"
        Write-Host 'Java runs in this terminal. Ctrl+C stops Java; MySQL and its data are retained.'
        Push-Location $script:ProjectRoot
        try { Invoke-BackendMaven } finally { Pop-Location }
    }
    finally {
        foreach ($key in $original.Keys) {
            [Environment]::SetEnvironmentVariable($key, $original[$key], 'Process')
        }
    }
}

# Dot-sourcing exposes functions for local validation without starting services.
if ($MyInvocation.InvocationName -ne '.') {
    $ErrorActionPreference = 'Stop'
    try {
        Start-LocalBackend -Path $ConfigFile -ValidateOnly:$CheckOnly -EnableAdminInitialization:$InitializeAdmin
    }
    catch {
        Write-Host "[ERROR] $($_.Exception.Message)" -ForegroundColor Red
        exit 1
    }
}
