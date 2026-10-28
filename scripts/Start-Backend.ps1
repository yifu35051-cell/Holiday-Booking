#Requires -Version 5.1
<#
.SYNOPSIS
    Start Docker MySQL and the Spring Boot backend using one local config file.
.DESCRIPTION
    Reads config/development.env as data, never executes it. File values override inherited
    development settings for this process only; the original values are restored
    when the script finishes. Ctrl+C stops Java; MySQL data and container remain.
#>
[CmdletBinding()]
param(
    [string]$ConfigFile = '',
    [switch]$CheckOnly,
    [switch]$InitializeAdmin,
    [switch]$SeedDemoData,
    [switch]$SeedOnly,
    [switch]$UseRunningDatabase
)

$script:ProjectRoot = Split-Path -Parent $PSScriptRoot

function Read-LocalConfiguration {
    param([string]$Path, [switch]$EnableAdminInitialization)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw 'Copy config/development.env.example to config/development.env and edit it first.'
    }

    $allowedKeys = @(
        'MYSQL_ROOT_PASSWORD', 'MYSQL_PASSWORD', 'MYSQL_PORT',
        'SERVER_PORT', 'BOOTSTRAP_ADMIN_EMAIL', 'BOOTSTRAP_ADMIN_PASSWORD',
        'PAYMENT_MODE', 'EMAIL_MODE', 'JOBS_ENABLED', 'MAIL_HEALTH_ENABLED',
        'ALIPAY_APP_ID', 'ALIPAY_PRIVATE_KEY', 'ALIPAY_PUBLIC_KEY', 'ALIPAY_SELLER_ID', 'ALIPAY_NOTIFY_URL',
        'MAIL_HOST', 'MAIL_PORT', 'MAIL_USERNAME', 'MAIL_PASSWORD', 'MAIL_FROM',
        'MAIL_SMTP_AUTH', 'MAIL_STARTTLS', 'MAIL_SSL_ENABLE', 'MAIL_RECIPIENT_ALLOWLIST',
        'EMAIL_DAILY_LIMIT', 'REMINDER_DAYS', 'API_DOCS_ENABLED', 'SESSION_COOKIE_SECURE',
        'MAILPIT_ENABLED', 'MAILPIT_PORT', 'MAILPIT_WEB_PORT'
    )
    $config = @{
        MYSQL_PORT = '3307'
        SERVER_PORT = '8080'
        BOOTSTRAP_ADMIN_EMAIL = ''
        BOOTSTRAP_ADMIN_PASSWORD = ''
        PAYMENT_MODE = 'disabled'
        EMAIL_MODE = 'disabled'
        JOBS_ENABLED = 'false'
        MAIL_HEALTH_ENABLED = 'false'
        ALIPAY_APP_ID = ''; ALIPAY_PRIVATE_KEY = ''; ALIPAY_PUBLIC_KEY = ''
        ALIPAY_SELLER_ID = ''; ALIPAY_NOTIFY_URL = ''
        MAIL_HOST = 'localhost'; MAIL_PORT = '1025'
        MAIL_USERNAME = ''; MAIL_PASSWORD = ''; MAIL_FROM = ''
        MAIL_SMTP_AUTH = 'false'; MAIL_STARTTLS = 'false'; MAIL_SSL_ENABLE = 'false'
        MAIL_RECIPIENT_ALLOWLIST = ''; EMAIL_DAILY_LIMIT = '50'
        REMINDER_DAYS = '1'; API_DOCS_ENABLED = 'true'; SESSION_COOKIE_SECURE = 'false'
        MAILPIT_ENABLED = 'false'; MAILPIT_PORT = '1025'; MAILPIT_WEB_PORT = '8025'
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
            throw "Unsupported setting at line $lineNumber. See config/development.env.example."
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
    foreach ($key in @('MYSQL_PORT', 'SERVER_PORT', 'MAIL_PORT', 'MAILPIT_PORT', 'MAILPIT_WEB_PORT')) {
        $port = 0
        if (-not [int]::TryParse($config[$key], [ref]$port) -or $port -lt 1 -or $port -gt 65535) {
            throw "$key must be an integer from 1 to 65535."
        }
        $config[$key] = $port.ToString()
    }
    if ($config.MYSQL_PORT -eq $config.SERVER_PORT) {
        throw 'MYSQL_PORT and SERVER_PORT must be different.'
    }

    foreach ($key in @('JOBS_ENABLED', 'MAIL_HEALTH_ENABLED', 'MAIL_SMTP_AUTH', 'MAIL_STARTTLS', 'MAIL_SSL_ENABLE', 'API_DOCS_ENABLED', 'SESSION_COOKIE_SECURE', 'MAILPIT_ENABLED')) {
        if ($config[$key] -cnotin @('true', 'false')) { throw "$key must be true or false." }
    }
    if ($config.PAYMENT_MODE -cnotin @('disabled', 'mock', 'alipay-sandbox')) { throw 'Unsupported PAYMENT_MODE.' }
    if ($config.EMAIL_MODE -cnotin @('disabled', 'log', 'smtp')) { throw 'Unsupported EMAIL_MODE.' }
    if ($config.REMINDER_DAYS -notmatch '^(1|3|3,1|1,3)$') { throw "REMINDER_DAYS must be '1', '3', '3,1' or '1,3'." }
    $dailyLimit = 0
    if (-not [int]::TryParse($config.EMAIL_DAILY_LIMIT, [ref]$dailyLimit) -or $dailyLimit -lt 1) { throw 'EMAIL_DAILY_LIMIT must be a positive integer.' }
    if ($config.MAIL_STARTTLS -eq 'true' -and $config.MAIL_SSL_ENABLE -eq 'true') { throw 'Choose STARTTLS or implicit SSL, not both.' }
    if ($config.MAILPIT_ENABLED -eq 'true') {
        if ($config.MAIL_HOST -notin @('localhost', '127.0.0.1') -or $config.MAIL_PORT -ne $config.MAILPIT_PORT) {
            throw 'Local Mailpit requires MAIL_HOST=127.0.0.1 (or localhost) and MAIL_PORT=MAILPIT_PORT.'
        }
        if ($config.MAIL_SMTP_AUTH -ne 'false' -or $config.MAIL_STARTTLS -ne 'false' -or $config.MAIL_SSL_ENABLE -ne 'false') {
            throw 'The supplied local Mailpit service uses SMTP without authentication or TLS.'
        }
        if (@(@($config.MYSQL_PORT, $config.SERVER_PORT, $config.MAILPIT_PORT, $config.MAILPIT_WEB_PORT) | Select-Object -Unique).Count -ne 4) {
            throw 'MySQL, HTTP, Mailpit SMTP and Mailpit web ports must be distinct.'
        }
    }
    $required = @()
    if ($config.PAYMENT_MODE -eq 'alipay-sandbox') {
        $required += @('ALIPAY_APP_ID', 'ALIPAY_PRIVATE_KEY', 'ALIPAY_PUBLIC_KEY', 'ALIPAY_SELLER_ID', 'ALIPAY_NOTIFY_URL')
        $callback = $null
        if (-not [Uri]::TryCreate($config.ALIPAY_NOTIFY_URL, [UriKind]::Absolute, [ref]$callback) -or
            $callback.Scheme -ne 'https' -or $callback.AbsolutePath -ne '/api/v1/payment-callbacks/alipay') {
            throw 'ALIPAY_NOTIFY_URL must be an absolute HTTPS URL ending in /api/v1/payment-callbacks/alipay.'
        }
    }
    if ($config.EMAIL_MODE -eq 'smtp') {
        $required += @('MAIL_HOST', 'MAIL_FROM')
        if ($config.MAIL_SMTP_AUTH -eq 'true') { $required += @('MAIL_USERNAME', 'MAIL_PASSWORD') }
    }
    foreach ($key in $required) {
        if ([string]::IsNullOrWhiteSpace($config[$key]) -or $config[$key] -like 'REPLACE_WITH_*') { throw "$key must be configured for the selected channel." }
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
    param([switch]$DatabaseAlreadyRunning)
    $requiredTools = @('java')
    if (-not $DatabaseAlreadyRunning) { $requiredTools += 'docker' }
    foreach ($tool in $requiredTools) {
        if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
            throw "$tool is not available. Install Docker Desktop and JDK 21, then open a new terminal."
        }
    }
    $java = Invoke-CapturedCommand 'java' @('-version')
    if ($java.Code -ne 0 -or $java.Text -notmatch 'version "21[."]') {
        throw 'The java command must use JDK 21. Check PATH and JAVA_HOME.'
    }
    # Keep the installed JDK and the explicit development JAVA_HOME consistent.
    if ($env:JAVA_HOME) {
        $homeJava = Join-Path $env:JAVA_HOME 'bin/java.exe'
        if (-not (Test-Path -LiteralPath $homeJava)) { throw 'JAVA_HOME does not point to a JDK.' }
        $homeVersion = Invoke-CapturedCommand $homeJava @('-version')
        if ($homeVersion.Code -ne 0 -or $homeVersion.Text -notmatch 'version "21[."]') {
            throw 'JAVA_HOME must point to JDK 21.'
        }
    }
    if ($DatabaseAlreadyRunning) { return }
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

    $composeFile = Join-Path $script:ProjectRoot 'config/compose.dev.yaml'
    & docker compose --env-file $ConfigPath -f $composeFile @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw 'Database command failed. Missing images require the README first-setup pull command; startup never builds or pulls. Inspect Docker/Compose output. Java was not started; existing data is retained.'
    }
}

function Invoke-BackendJava {
    # Daily startup never invokes Maven or downloads dependencies.
    $jar = Join-Path $script:ProjectRoot 'backend/target/backend-0.0.1-SNAPSHOT.jar'
    & java -jar $jar
    if ($LASTEXITCODE -ne 0) {
        throw 'Spring Boot exited with an error. Check the application log. MySQL remains running.'
    }
}

function Start-LocalMailpit {
    param([string]$ConfigPath, [hashtable]$Config)
    # A pre-existing manual container belongs to the user: reuse it, never delete
    # or recreate it to make Compose take ownership.
    $existing = Invoke-CapturedCommand 'docker' @('inspect', 'holiday-mailpit', '--format', '{{json .Config.Labels}}')
    $managed = $false
    if ($existing.Code -eq 0) {
        $labels = $existing.Text | ConvertFrom-Json
        $managed = $null -ne $labels -and $labels.'com.docker.compose.project' -eq 'holiday-booking' -and $labels.'com.docker.compose.service' -eq 'mailpit'
    }
    if ($existing.Code -eq 0 -and -not $managed) {
        $started = Invoke-CapturedCommand 'docker' @('start', 'holiday-mailpit')
        if ($started.Code -ne 0) { throw 'Existing holiday-mailpit could not start; inspect its Docker logs.' }
        foreach ($binding in @(@('1025/tcp', $Config.MAILPIT_PORT), @('8025/tcp', $Config.MAILPIT_WEB_PORT))) {
            $mapped = Invoke-CapturedCommand 'docker' @('port', 'holiday-mailpit', $binding[0])
            if ($mapped.Code -ne 0 -or $mapped.Text -notmatch (':' + [regex]::Escape($binding[1]) + '(\r?\n|$)')) {
                throw 'Existing holiday-mailpit port mapping differs from the file; adjust the file or manage the container manually. It has not been deleted.'
            }
        }
        Write-Host 'Reusing existing holiday-mailpit; its storage and Compose ownership are unchanged.'
    } else {
        Invoke-DatabaseCompose $ConfigPath @('up', '-d', '--no-build', '--pull', 'never', '--wait', '--wait-timeout', '60', 'mailpit')
    }
    # Verify the SMTP greeting rather than treating a running container as ready.
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        $client = New-Object Net.Sockets.TcpClient
        try {
            $connection = $client.ConnectAsync('127.0.0.1', [int]$Config.MAILPIT_PORT)
            if (-not $connection.Wait(2000)) { throw 'SMTP connection timed out' }
            $stream = $client.GetStream()
            $stream.ReadTimeout = 2000
            $reader = New-Object IO.StreamReader($stream)
            if ($reader.ReadLine() -notmatch '^220[ -]') { throw 'Invalid SMTP greeting' }
            Write-Host "Mailpit ready; web UI: http://localhost:$($Config.MAILPIT_WEB_PORT)"
            return
        } catch {
            if ([DateTime]::UtcNow -ge $deadline) { throw 'Mailpit SMTP did not become ready; inspect docker logs holiday-mailpit.' }
        } finally { $client.Dispose() }
        Start-Sleep -Seconds 1
    } while ($true)
}

function Start-LocalBackend {
    param([string]$Path, [switch]$ValidateOnly, [switch]$EnableAdminInitialization,
          [switch]$EnableDemoData, [switch]$ImportOnly, [switch]$DatabaseAlreadyRunning)

    if (-not $Path) { $Path = Join-Path $script:ProjectRoot 'config/development.env' }
    $config = Read-LocalConfiguration $Path -EnableAdminInitialization:$EnableAdminInitialization
    $configPath = (Resolve-Path -LiteralPath $Path).Path
    Assert-LocalTools -DatabaseAlreadyRunning:$DatabaseAlreadyRunning
    if (($EnableDemoData -or $ImportOnly) -and $config.PAYMENT_MODE -ne 'mock') {
        throw 'Demo data requires PAYMENT_MODE=mock in the local config.'
    }

    # One source of truth for both Compose and JDBC, even if the shell has old env values.
    $environment = @{
        MYSQL_ROOT_PASSWORD = $config.MYSQL_ROOT_PASSWORD
        MYSQL_PASSWORD = $config.MYSQL_PASSWORD
        MYSQL_PORT = $config.MYSQL_PORT
        SERVER_PORT = $config.SERVER_PORT
        SERVER_ADDRESS = '127.0.0.1'
        DEMO_DATA_ENABLED = ($EnableDemoData -or $ImportOnly).ToString().ToLowerInvariant()
        DEMO_CREDENTIALS_FILE = Join-Path $script:ProjectRoot '.local/demo-accounts.properties'
        DEMO_EXIT_AFTER_SEED = $ImportOnly.ToString().ToLowerInvariant()
        DEMO_CONTAINER = 'false'
        BOOTSTRAP_ADMIN_EMAIL = $config.BOOTSTRAP_ADMIN_EMAIL
        BOOTSTRAP_ADMIN_PASSWORD = $config.BOOTSTRAP_ADMIN_PASSWORD
        BOOKING_BOOTSTRAP_EMAIL = $config.BOOTSTRAP_ADMIN_EMAIL
        BOOKING_BOOTSTRAP_PASSWORD = $config.BOOTSTRAP_ADMIN_PASSWORD
        DB_URL = "jdbc:mysql://127.0.0.1:$($config.MYSQL_PORT)/holiday_booking?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
        DB_USERNAME = 'holiday_booking'
        DB_PASSWORD = $config.MYSQL_PASSWORD
        SPRING_PROFILES_ACTIVE = 'local'
    }
    # Apply every allowed channel setting from the file, including empty defaults,
    # so an old terminal credential cannot silently select a different account.
    foreach ($key in $config.Keys) {
        if (-not $environment.ContainsKey($key)) { $environment[$key] = $config[$key] }
    }
    if ($ImportOnly) {
        $environment.SERVER_PORT = '0'
        $environment.JOBS_ENABLED = 'false'
    }
    $original = @{}
    try {
        foreach ($key in $environment.Keys) {
            $original[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
            [Environment]::SetEnvironmentVariable($key, $environment[$key], 'Process')
        }
        Write-Host "[1/4] Validating local config (MySQL=$($config.MYSQL_PORT), HTTP=$($config.SERVER_PORT))..."
        if (-not $DatabaseAlreadyRunning) { Invoke-DatabaseCompose $configPath @('config', '--quiet') }
        if ($ValidateOnly) {
            Write-Host 'Configuration and tools validated. No container or Java process was started.'
            return
        }

        if (-not (Test-Path -LiteralPath (Join-Path $script:ProjectRoot 'backend/target/backend-0.0.1-SNAPSHOT.jar'))) {
            throw 'Backend JAR is missing. Prepare once: .\backend\mvnw.cmd -f .\backend\pom.xml -DskipTests package. See README development setup.'
        }

        if (-not $DatabaseAlreadyRunning) {
            Write-Host '[2/4] Checking Docker Linux engine...'
            Wait-DockerEngine
            Write-Host '[3/4] Starting MySQL and waiting for its health check...'
            Invoke-DatabaseCompose $configPath @('up', '-d', '--no-build', '--pull', 'never', '--wait', '--wait-timeout', '180', 'mysql')
            if ($config.MAILPIT_ENABLED -eq 'true') { Start-LocalMailpit $configPath $config }
        }

        # Do not confuse a failed second launch with the first backend still running.
        $listeners = [Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()
        if (-not $ImportOnly -and @($listeners | Where-Object { $_.Port -eq [int]$config.SERVER_PORT }).Count -gt 0) {
            throw "HTTP port $($config.SERVER_PORT) is already in use. Stop the previous backend or change SERVER_PORT in the file."
        }
        if (-not $config.BOOTSTRAP_ADMIN_EMAIL) {
            Write-Host 'Ordinary startup: administrator initialization is disabled (optional: -InitializeAdmin).'
        }
        if ($ImportOnly) {
            Write-Host '[4/4] Importing demo data using a temporary HTTP port; jobs are disabled and Java exits after import.'
        } else {
            Write-Host "[4/4] Starting Spring Boot; health URL: http://localhost:$($config.SERVER_PORT)/actuator/health"
            Write-Host 'Java runs in this terminal. Ctrl+C stops Java; MySQL and its data are retained.'
        }
        Push-Location $script:ProjectRoot
        try { Invoke-BackendJava } finally { Pop-Location }
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
        Start-LocalBackend -Path $ConfigFile -ValidateOnly:$CheckOnly -EnableAdminInitialization:$InitializeAdmin `
            -EnableDemoData:$SeedDemoData -ImportOnly:$SeedOnly -DatabaseAlreadyRunning:$UseRunningDatabase
    }
    catch {
        Write-Host "[ERROR] $($_.Exception.Message)" -ForegroundColor Red
        exit 1
    }
}
