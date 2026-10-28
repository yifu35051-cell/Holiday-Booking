#Requires -Version 5.1
<# Export installed runtime images only. Never build, pull, or start services. #>
[CmdletBinding()]
param(
    [ValidatePattern('^([a-zA-Z0-9][a-zA-Z0-9_.-]{0,63})?$')][string]$Version = '',
    [string]$OutputDirectory = '',
    [switch]$SkipBuild # Compatibility: export now always skips building.
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$previousVersion = $env:APP_VERSION
try {
    if ($Version) { $env:APP_VERSION = $Version }
    $configFile = Join-Path $projectRoot 'config/deployment.env'
    if (-not (Test-Path -LiteralPath $configFile)) { $configFile = Join-Path $projectRoot 'config/deployment.env.example' }
    $composeArgs = @('compose', '--env-file', $configFile, '-f', (Join-Path $projectRoot 'config/compose.yaml'))
    & docker @composeArgs config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Compose validation failed.' }
    $images = @(& docker @composeArgs config --images)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot determine runtime images.' }
    $metadata = @(& docker image inspect --format '{{.RepoTags}} {{.Id}} {{.Os}}/{{.Architecture}}' @images)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect all runtime images. Check Docker access and prepare the missing images using README first-build commands. Export never builds or pulls.' }
    $backendImage = $images | Where-Object { $_ -match '^holiday-booking-backend:' } | Select-Object -First 1
    if (-not $backendImage) { throw 'Backend image tag was not found in the runtime configuration.' }
    $Version = $backendImage.Substring('holiday-booking-backend:'.Length)
    if (-not $OutputDirectory) { $OutputDirectory = Join-Path $projectRoot ("release/holiday-booking-$Version-" + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
    if (Test-Path -LiteralPath $OutputDirectory) { throw 'Choose a new output directory; existing delivery files will not be overwritten.' }
    New-Item -ItemType Directory -Path $OutputDirectory | Out-Null
    $destination = (Resolve-Path -LiteralPath $OutputDirectory).Path
    $archive = Join-Path $destination 'images.tar'
    & docker image save --output $archive @images
    if ($LASTEXITCODE -ne 0) { throw 'Image export failed; this output directory is incomplete.' }
    $deliveryConfig = Join-Path $destination 'config'
    New-Item -ItemType Directory -Path $deliveryConfig | Out-Null
    Copy-Item -LiteralPath (Join-Path $projectRoot 'config/compose.yaml') -Destination $deliveryConfig
    $example = Get-Content -LiteralPath (Join-Path $projectRoot 'config/deployment.env.example') -Raw -Encoding UTF8
    $example = $example -replace '(?m)^APP_VERSION=.*$', "APP_VERSION=$Version"
    [IO.File]::WriteAllText((Join-Path $deliveryConfig 'deployment.env.example'), $example, (New-Object Text.UTF8Encoding($false)))
    New-Item -ItemType Directory -Path (Join-Path $destination 'scripts') | Out-Null
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Start-System.ps1') -Destination (Join-Path $destination 'scripts')
    Copy-Item -LiteralPath (Join-Path $projectRoot 'docs/交付与演示数据.md') -Destination (Join-Path $destination 'README.md')
    $metadata | Set-Content -LiteralPath (Join-Path $destination 'images.txt') -Encoding UTF8
    $hash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  images.tar" | Set-Content -LiteralPath (Join-Path $destination 'SHA256SUMS.txt') -Encoding ASCII
    Write-Host "Delivery package ready: $destination"
    Write-Host 'Includes images, Compose, configuration example and instructions. No real passwords or database volumes included.'
} catch {
    Write-Host "[ERROR] $($_.Exception.Message)" -ForegroundColor Red
    exit 1
} finally { $env:APP_VERSION = $previousVersion }
