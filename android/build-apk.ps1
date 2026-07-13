param(
    [Parameter(Mandatory = $true)]
    [string]$SocketUrl
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
& .\gradlew.bat assembleDebug "-PSOCKET_URL=$SocketUrl"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$apk = Join-Path $PSScriptRoot "app\build\outputs\apk\debug\app-debug.apk"
Write-Host "APK generado: $apk"
