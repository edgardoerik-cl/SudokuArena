param(
    [Parameter(Mandatory = $true)]
    [string]$SocketUrl
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
& .\gradlew.bat assembleDebug "-PSOCKET_URL=$SocketUrl"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$apk = Join-Path $PSScriptRoot "app\build\outputs\apk\debug\app-debug.apk"
$versionName = ([regex]::Match((Get-Content (Join-Path $PSScriptRoot "app\build.gradle.kts") -Raw), 'versionName\s*=\s*"([^"]+)"')).Groups[1].Value
if ([string]::IsNullOrWhiteSpace($versionName)) { throw "No fue posible leer versionName." }

$outputDirectory = Join-Path $PSScriptRoot "releases"
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
$versionedApk = Join-Path $outputDirectory "SudokuArena-v$versionName-debug.apk"
Copy-Item -Force $apk $versionedApk
Write-Host "APK generado: $versionedApk"
