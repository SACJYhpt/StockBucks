param(
    [switch]$NoInstall,
    [switch]$NoLocalAi,
    [switch]$NoStart,
    [switch]$StartApp
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSCommandPath
$setupScript = Join-Path $root "src\main\java\com\stockbucks\api\config\setup-stockbucks-api.ps1"

if (-not (Test-Path $setupScript)) {
    throw "Cannot find StockBucks setup script: $setupScript"
}

$arguments = @()
if (-not $NoInstall) {
    $arguments += "-InstallMissing"
}
if (-not $NoLocalAi) {
    $arguments += "-UseLocalAi"
}
if (-not $NoStart) {
    if ($StartApp) {
        $arguments += "-StartApp"
    } else {
        $arguments += "-StartDebugDashboard"
    }
}

Write-Host "Preparing StockBucks on this machine..."
if ($NoStart) {
    Write-Host "This will create stockbucks.local.env, prepare Java/Maven, prepare Ollama local AI, and compile the project."
} else {
    Write-Host "This will create stockbucks.local.env, prepare Java/Maven, prepare Ollama local AI, compile the project, and start StockBucks."
}

& powershell -NoProfile -ExecutionPolicy Bypass -File $setupScript @arguments
