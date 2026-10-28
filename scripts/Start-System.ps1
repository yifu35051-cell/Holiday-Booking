#Requires -Version 5.1
<#
.SYNOPSIS
    Start installed Docker images (default) or the prepared development environment.
.DESCRIPTION
    Docker mode publishes only Nginx and the loopback Mailpit UI. Development mode
    supervises its own Java/Vite processes; Ctrl+C stops those children only.
#>
[CmdletBinding()]
param(
    [ValidateSet('Docker', 'Development', 'Source')][string]$Mode = 'Docker',
    [string]$ConfigFile = '',
    [switch]$PrepareImages, # Explicit Docker preparation only; never starts containers.
    [switch]$ImagesOnly, # Compatibility: Docker startup now always uses installed images.
    [switch]$SkipDemoData,
    [switch]$InitializeAdmin,
    [ValidateRange(1024, 65535)][int]$FrontendPort = 5173
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

function Invoke-DockerInspection([string[]]$Arguments) {
    # Capture native stderr on Windows PowerShell 5.1 without hiding the exit code.
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & docker @Arguments 2>&1
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    return @{ Code = $code; Text = ($output | Out-String).Trim() }
}

function Get-MissingImages([string[]]$ComposeArguments) {
    $engine = Invoke-DockerInspection @('info', '--format', '{{.OSType}}')
    if ($engine.Code -ne 0) { throw "Cannot access Docker engine. No containers were changed. $($engine.Text)" }
    if ($engine.Text -ne 'linux') { throw 'Docker must use Linux containers.' }

    # Resolve service/image names without printing environment values or passwords.
    $resolved = Invoke-DockerInspection ($ComposeArguments + @('config', '--format', 'json'))
    if ($resolved.Code -ne 0) { throw 'Cannot resolve Docker configuration.' }
    $services = ($resolved.Text | ConvertFrom-Json).services
    $missing = @()
    foreach ($name in @('mysql', 'mailpit', 'backend', 'frontend')) {
        $imageName = $services.$name.image
        if (-not $imageName) { throw "No image configured for $name." }
        $inspection = Invoke-DockerInspection @('image', 'inspect', '--format', '{{.Id}}', $imageName)
        if ($inspection.Code -eq 0) {
            Write-Host "Reuse installed image: $imageName"
        } elseif ($inspection.Text -match 'No such image|No such object') {
            $missing += [pscustomobject]@{ Service = $name; Image = $imageName }
        } else {
            throw "Cannot inspect image $imageName. $($inspection.Text)"
        }
    }
    return $missing
}

function Assert-FreePort([int]$Port) {
    $listeners = [Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()
    if (@($listeners | Where-Object { $_.Port -eq $Port }).Count -gt 0) {
        throw "Port $Port is occupied. Stop the previous service before starting this system."
    }
}

function Wait-HttpService([string]$Url, [Diagnostics.Process]$Process, [string]$Name) {
    $deadline = [DateTime]::UtcNow.AddMinutes(10)
    $nextMessage = [DateTime]::UtcNow
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($Process.HasExited) { throw "$Name exited; inspect the log directory printed above." }
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 3
            if ($response.StatusCode -eq 200) { return }
        } catch { }
        if ([DateTime]::UtcNow -ge $nextMessage) {
            Write-Host "Waiting for $Name..."
            $nextMessage = [DateTime]::UtcNow.AddSeconds(15)
        }
        Start-Sleep -Seconds 1
    }
    throw "$Name startup timed out; inspect its log."
}

function Stop-OwnedProcess([Diagnostics.Process]$Process) {
    if ($null -ne $Process -and -not $Process.HasExited) {
        # Only terminate this launcher's process tree, never all Java/Node processes.
        & taskkill.exe /PID $Process.Id /T /F | Out-Null
    }
}

try {
    if ($Mode -eq 'Docker') {
        Write-Host 'Mode: Docker deployment (8088). For Java/Vue development (5173), use -Mode Development.'
        if ($InitializeAdmin) { throw 'Docker: configure bootstrap credentials in config/deployment.env on first startup; this switch is for Development mode.' }
        if (-not $ConfigFile) { $ConfigFile = Join-Path $projectRoot 'config/deployment.env' }
        if (-not (Test-Path -LiteralPath $ConfigFile)) {
            throw 'Copy config/deployment.env.example to config/deployment.env and replace the three password placeholders first.'
        }
        $configPath = (Resolve-Path -LiteralPath $ConfigFile).Path
        if ((Get-Content -LiteralPath $configPath -Raw) -match 'REPLACE_WITH_') {
            throw 'Replace the example password values in the Docker configuration.'
        }
        # Runtime configuration has no build directives. Never attach the build overlay here.
        $composeArgs = @('compose', '--env-file', $configPath, '-f', (Join-Path $projectRoot 'config/compose.yaml'))
        $previousDemo = $env:DEMO_DATA_ENABLED
        try {
            if ($SkipDemoData) { $env:DEMO_DATA_ENABLED = 'false' }
            & docker @composeArgs config --quiet
            if ($LASTEXITCODE -ne 0) { throw 'Docker configuration validation failed.' }
            # Preflight before `up`: missing images must not leave a half-created stack.
            $missing = @(Get-MissingImages $composeArgs)
            if ($PrepareImages) {
                $applicationServices = @($missing | Where-Object { $_.Service -in @('backend', 'frontend') } | ForEach-Object { $_.Service })
                $buildOverlay = Join-Path $projectRoot 'config/compose.build.yaml'
                if ($applicationServices.Count -gt 0 -and -not (Test-Path -LiteralPath $buildOverlay)) {
                    throw 'This delivery package has no source build configuration. Run docker load --input images.tar first.'
                }
                foreach ($entry in @($missing | Where-Object { $_.Service -in @('mysql', 'mailpit') })) {
                    Write-Host "Preparing missing service image: $($entry.Image)"
                    & docker pull $entry.Image
                    if ($LASTEXITCODE -ne 0) { throw "Image preparation failed: $($entry.Image)" }
                }
                if ($applicationServices.Count -gt 0) {
                    Write-Host "Building missing application images only: $($applicationServices -join ', ')"
                    & docker @composeArgs -f $buildOverlay build @applicationServices
                    if ($LASTEXITCODE -ne 0) { throw 'Application image preparation failed.' }
                }
                Write-Host 'Image preparation complete. Existing images were reused. No containers were started.'
                return
            }
            if ($missing.Count -gt 0) {
                foreach ($entry in $missing) { Write-Host "Missing image: $($entry.Image) (service: $($entry.Service))" }
                throw 'Images are missing; no containers were changed. For development use -Mode Development (no application images needed). For deployment run this script with -Mode Docker -PrepareImages once, or load the delivery images.tar. Existing images will be reused.'
            }
            $startArgs = @('up', '-d', '--no-build', '--pull', 'never', '--wait', '--wait-timeout', '300')
            & docker @composeArgs @startArgs
            if ($LASTEXITCODE -ne 0) { throw 'System startup failed. Missing images must be built or loaded once using README setup commands; startup never builds/pulls. Otherwise inspect Compose logs. Existing data is retained.' }
            Write-Host 'System ready. Default URL: http://localhost:8088 (WEB_PORT in config/deployment.env).'
            Write-Host 'Mailpit UI: http://localhost:8026 (MAILPIT_WEB_PORT). See README for credentials and stop commands.'
        } finally { $env:DEMO_DATA_ENABLED = $previousDemo }
        return
    }

    if ($PrepareImages) { throw '-PrepareImages applies to Docker mode only. Development runs Java/Vue on the host.' }
    if ($ImagesOnly) { throw '-ImagesOnly applies to Docker mode only.' }
    Write-Host 'Mode: Development (5173). Reuse Docker MySQL/Mailpit; run Java/Vue on the host. No application images required.'
    . (Join-Path $PSScriptRoot 'Start-Backend.ps1') -ConfigFile $ConfigFile -InitializeAdmin:$InitializeAdmin
    if (-not $ConfigFile) { $ConfigFile = Join-Path $projectRoot 'config/development.env' }
    $configPath = (Resolve-Path -LiteralPath $ConfigFile).Path
    $config = Read-LocalConfiguration $configPath -EnableAdminInitialization:$InitializeAdmin
    if (-not $SkipDemoData -and $config.PAYMENT_MODE -ne 'mock') { throw 'Demo import requires PAYMENT_MODE=mock; alternatively use -SkipDemoData.' }
    Assert-FreePort ([int]$config.SERVER_PORT)
    Assert-FreePort $FrontendPort
    if ([int]$config.SERVER_PORT -eq $FrontendPort) { throw 'Backend and frontend ports must be different.' }
    Assert-LocalTools
    foreach ($tool in @('node.exe')) {
        if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { throw "$tool is required for Development mode." }
    }
    $frontendRoot = Join-Path $projectRoot 'frontend'
    $logRoot = Join-Path $projectRoot ('.local/run-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
    New-Item -ItemType Directory -Path $logRoot -Force | Out-Null
    Write-Host "Service logs: $logRoot"

    # Preparation is explicit. Development serves source via Vite HMR; no dist rebuild.
    if (-not (Test-Path -LiteralPath (Join-Path $frontendRoot 'node_modules/vite/bin/vite.js'))) {
        throw 'Frontend dependencies are missing. Run npm --prefix frontend ci once, then restart.'
    }
    if (-not (Test-Path -LiteralPath (Join-Path $projectRoot 'backend/target/backend-0.0.1-SNAPSHOT.jar'))) {
        throw 'Backend JAR is missing. Run .\backend\mvnw.cmd -f .\backend\pom.xml -DskipTests package once, then restart.'
    }

    $backendProcess = $null
    $frontendProcess = $null
    $previousTarget = $env:BACKEND_URL
    try {
        $backendArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + (Join-Path $PSScriptRoot 'Start-Backend.ps1') + '"'), '-ConfigFile', ('"' + $configPath + '"'))
        if (-not $SkipDemoData) { $backendArgs += '-SeedDemoData' }
        if ($InitializeAdmin) { $backendArgs += '-InitializeAdmin' }
        $backendProcess = Start-Process -FilePath 'powershell.exe' -ArgumentList $backendArgs -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $logRoot 'backend.log') -RedirectStandardError (Join-Path $logRoot 'backend-error.log')
        Wait-HttpService "http://127.0.0.1:$($config.SERVER_PORT)/actuator/health/readiness" $backendProcess 'backend and demo import'
        $env:BACKEND_URL = "http://127.0.0.1:$($config.SERVER_PORT)"
        $frontendProcess = Start-Process -FilePath (Get-Command node.exe).Source `
            -ArgumentList @('node_modules/vite/bin/vite.js', '--host', 'localhost', '--port', "$FrontendPort") `
            -WorkingDirectory $frontendRoot -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $logRoot 'frontend.log') -RedirectStandardError (Join-Path $logRoot 'frontend-error.log')
        Wait-HttpService "http://localhost:$FrontendPort" $frontendProcess 'frontend'
        Write-Host "System ready: http://localhost:$FrontendPort"
        Write-Host 'Demo passwords: .local/demo-accounts.properties. Keep this terminal open; Ctrl+C stops Java/Vue and retains Docker data.'
        while (-not $backendProcess.HasExited -and -not $frontendProcess.HasExited) { Start-Sleep -Seconds 2 }
        throw 'A service exited; inspect its logs. The companion process will be stopped.'
    } finally {
        Stop-OwnedProcess $frontendProcess
        Stop-OwnedProcess $backendProcess
        $env:BACKEND_URL = $previousTarget
    }
} catch {
    Write-Host "[ERROR] $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
