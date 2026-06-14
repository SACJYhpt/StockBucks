param(
    [switch]$InstallMissing,
    [switch]$UseLocalAi,
    [switch]$PullOllamaModel,
    [switch]$StartDebugDashboard,
    [switch]$StartApp,
    [switch]$SkipCompile
)

$ErrorActionPreference = "Stop"

function Find-ProjectRoot {
    $cursor = Split-Path -Parent $PSCommandPath
    while ($cursor) {
        if (Test-Path (Join-Path $cursor "pom.xml")) {
            return $cursor
        }
        $parent = Split-Path -Parent $cursor
        if ($parent -eq $cursor) {
            break
        }
        $cursor = $parent
    }
    throw "Cannot find pom.xml. Please run this script inside StockBucks."
}

function Read-EnvMap($path) {
    $map = @{}
    if (-not (Test-Path $path)) {
        return $map
    }
    foreach ($line in Get-Content -Path $path) {
        $trimmed = $line.Trim()
        if ($trimmed -eq "" -or $trimmed.StartsWith("#")) {
            continue
        }
        $idx = $trimmed.IndexOf("=")
        if ($idx -le 0) {
            continue
        }
        $key = $trimmed.Substring(0, $idx).Trim()
        $value = $trimmed.Substring($idx + 1)
        if (-not $map.ContainsKey($key)) {
            $map[$key] = $value
        }
    }
    return $map
}

function Env-Line($map, $key, $default = "") {
    $value = $default
    if ($map.ContainsKey($key) -and -not [string]::IsNullOrWhiteSpace($map[$key])) {
        $value = $map[$key]
    }
    return "$key=$value"
}

function New-LocalAiKey {
    $bytes = New-Object byte[] 24
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($bytes)
    } finally {
        $rng.Dispose()
    }
    $token = [Convert]::ToBase64String($bytes).TrimEnd("=").Replace("+", "-").Replace("/", "_")
    return "local-$token"
}

function Test-Command($name) {
    return $null -ne (Get-Command $name -ErrorAction SilentlyContinue)
}

function Get-CommandSource($name) {
    $command = Get-Command $name -ErrorAction SilentlyContinue
    if ($null -eq $command) {
        return ""
    }
    return $command.Source
}

function Install-WithWinget($id, $name) {
    if (-not $InstallMissing) {
        Write-Host "Missing $name. Re-run with -InstallMissing to install it by winget."
        return $false
    }
    if (-not (Test-Command "winget")) {
        Write-Host "Missing winget. Cannot install $name automatically."
        return $false
    }
    Write-Host "Installing $name..."
    winget install --id $id --accept-package-agreements --accept-source-agreements
    return $true
}

function Find-OllamaCommand($root, $envMap) {
    if ($envMap.ContainsKey("OLLAMA_EXE_PATH") -and -not [string]::IsNullOrWhiteSpace($envMap["OLLAMA_EXE_PATH"])) {
        if (Test-Path $envMap["OLLAMA_EXE_PATH"]) {
            return (Resolve-Path $envMap["OLLAMA_EXE_PATH"]).Path
        }
    }

    $candidates = @(
        (Join-Path $root "tools\ollama\ollama.exe"),
        (Join-Path (Split-Path -Parent $root) "ai\tools\ollama\ollama.exe"),
        (Join-Path $env:LOCALAPPDATA "Programs\Ollama\ollama.exe"),
        (Join-Path $env:ProgramFiles "Ollama\ollama.exe")
    )

    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path $candidate)) {
            return (Resolve-Path $candidate).Path
        }
    }

    $source = Get-CommandSource "ollama"
    if (-not [string]::IsNullOrWhiteSpace($source)) {
        return $source
    }
    return ""
}

function Ensure-LocalEnv($root, $ollamaPath = "") {
    $path = Join-Path $root "stockbucks.local.env"
    $map = Read-EnvMap $path
    if ($UseLocalAi) {
        if (-not $map.ContainsKey("OLLAMA_MODEL") -or [string]::IsNullOrWhiteSpace($map["OLLAMA_MODEL"])) {
            $map["OLLAMA_MODEL"] = "stockbucks-traditional-zh:latest"
        }
        if (-not $map.ContainsKey("LOCAL_AI_API_KEY") -or [string]::IsNullOrWhiteSpace($map["LOCAL_AI_API_KEY"])) {
            $map["LOCAL_AI_API_KEY"] = New-LocalAiKey
        }
        if (-not $map.ContainsKey("AI_COMPATIBLE_BASE_URL") -or [string]::IsNullOrWhiteSpace($map["AI_COMPATIBLE_BASE_URL"])) {
            $map["AI_COMPATIBLE_BASE_URL"] = "http://localhost:8000/v1"
        }
        if (-not $map.ContainsKey("OPENAI_COMPATIBLE_BASE_URL") -or [string]::IsNullOrWhiteSpace($map["OPENAI_COMPATIBLE_BASE_URL"])) {
            $map["OPENAI_COMPATIBLE_BASE_URL"] = "http://localhost:8000/v1"
        }
    }
    if (-not [string]::IsNullOrWhiteSpace($ollamaPath)) {
        $map["OLLAMA_EXE_PATH"] = $ollamaPath
    }

    $lines = @(
        "# StockBucks local environment. Do not commit this file.",
        "# Empty values mean not configured. Debug UI will show missing keys.",
        "# Local AI key is only for local/openai-compatible services. Ollama itself does not require a real key.",
        "",
        "# === AI default ===",
        (Env-Line $map "AI_PROVIDER" "gemini"),
        (Env-Line $map "AI_MODEL" "gemini-2.5-flash"),
        (Env-Line $map "AI_BASE_URL" "https://generativelanguage.googleapis.com/v1beta"),
        "",
        "# === Cloud AI API keys ===",
        (Env-Line $map "OPENAI_API_KEY"),
        (Env-Line $map "GEMINI_API_KEY"),
        (Env-Line $map "GOOGLE_API_KEY"),
        (Env-Line $map "ANTHROPIC_API_KEY"),
        (Env-Line $map "OPENROUTER_API_KEY"),
        "",
        "# === OpenAI-compatible endpoint ===",
        (Env-Line $map "AI_API_KEY"),
        (Env-Line $map "LOCAL_AI_API_KEY"),
        (Env-Line $map "AI_COMPATIBLE_BASE_URL"),
        (Env-Line $map "AI_COMPATIBLE_MODEL"),
        (Env-Line $map "OPENAI_COMPATIBLE_BASE_URL"),
        (Env-Line $map "OPENAI_COMPATIBLE_MODEL"),
        "",
        "# === Ollama local AI ===",
        (Env-Line $map "OLLAMA_BASE_URL" "http://localhost:11434"),
        (Env-Line $map "OLLAMA_MODEL" "stockbucks-traditional-zh:latest"),
        (Env-Line $map "OLLAMA_EXE_PATH"),
        (Env-Line $map "OLLAMA_MODELS"),
        "",
        "# === Stock provider order ===",
        (Env-Line $map "STOCK_PROVIDER_CHAIN" "broker,fugle,web,twse,tpex,finmind,local"),
        (Env-Line $map "STOCK_INTRADAY_PROVIDER_CHAIN" "broker,web,fugle,twse,tpex,finmind,local"),
        (Env-Line $map "STOCK_HISTORY_PROVIDER_CHAIN" "twse,tpex,web,finmind,local"),
        "",
        "# === Stock cache ===",
        (Env-Line $map "STOCK_CACHE_ENABLED" "true"),
        (Env-Line $map "STOCK_CACHE_DIR" "data/api_cache"),
        (Env-Line $map "STOCK_CACHE_QUOTE_TTL_SECONDS" "30"),
        (Env-Line $map "STOCK_CACHE_INTRADAY_TTL_SECONDS" "300"),
        (Env-Line $map "STOCK_CACHE_HISTORY_TTL_SECONDS" "86400"),
        "",
        "# === FinMind stock API ===",
        (Env-Line $map "FINMIND_TOKEN"),
        (Env-Line $map "FINMIND_SNAPSHOT_URL" "https://api.finmindtrade.com/api/v4/taiwan_stock_tick_snapshot"),
        (Env-Line $map "FINMIND_BASE_URL" "https://api.finmindtrade.com/api/v4/data"),
        "",
        "# === Fugle stock API ===",
        (Env-Line $map "FUGLE_API_KEY"),
        (Env-Line $map "FUGLE_BASE_URL" "https://api.fugle.tw/marketdata/v1.0/stock"),
        "",
        "# === Broker API ===",
        (Env-Line $map "BROKER_BASE_URL"),
        (Env-Line $map "BROKER_API_KEY"),
        (Env-Line $map "BROKER_ACCOUNT"),
        (Env-Line $map "BROKER_USERNAME"),
        (Env-Line $map "BROKER_PASSWORD"),
        (Env-Line $map "BROKER_AUTH_TOKEN"),
        (Env-Line $map "BROKER_LOGIN_ENDPOINT"),
        (Env-Line $map "BROKER_QUOTE_ENDPOINT"),
        (Env-Line $map "BROKER_INTRADAY_BARS_ENDPOINT"),
        (Env-Line $map "BROKER_ACCOUNT_ENDPOINT"),
        (Env-Line $map "BROKER_POSITIONS_ENDPOINT"),
        "",
        "# === TWSE / TPEx / Web sources ===",
        (Env-Line $map "TWSE_WEB_BASE_URL" "https://www.twse.com.tw"),
        (Env-Line $map "TWSE_OPENAPI_BASE_URL" "https://openapi.twse.com.tw/v1"),
        (Env-Line $map "TPEX_OPENAPI_BASE_URL" "https://www.tpex.org.tw/openapi/v1"),
        (Env-Line $map "WEB_STOCK_SOURCES" "google,yahoo,cnbc,msn,wantgoo"),
        (Env-Line $map "WEB_STOCK_GOOGLE_URL_TEMPLATE" "https://www.google.com/finance/quote/%s:TPE?hl=zh-TW"),
        (Env-Line $map "WEB_STOCK_YAHOO_URL_TEMPLATE" "https://tw.stock.yahoo.com/quote/%s.TW"),
        (Env-Line $map "WEB_STOCK_YAHOO_CHART_URL_TEMPLATE" "https://query1.finance.yahoo.com/v8/finance/chart/%s.TW?period1=%d&period2=%d&interval=1d"),
        (Env-Line $map "WEB_STOCK_YAHOO_INTRADAY_URL_TEMPLATE" "https://query1.finance.yahoo.com/v8/finance/chart/%s.TW?period1=%d&period2=%d&interval=%s"),
        (Env-Line $map "WEB_STOCK_CNBC_URL_TEMPLATE" "https://www.cnbc.com/quotes/%s.TW"),
        (Env-Line $map "WEB_STOCK_MSN_URL_TEMPLATE"),
        (Env-Line $map "WEB_STOCK_WANTGOO_URL_TEMPLATE" "https://www.wantgoo.com/stock/%s"),
        (Env-Line $map "WEB_STOCK_USER_AGENT" "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/125 Safari/537.36 StockBucks/1.0"),
        "",
        "# === Local fallback data ===",
        (Env-Line $map "LOCAL_STOCK_DATA_DIR" "data"),
        (Env-Line $map "LOCAL_STOCK_CSV_NAME" "TestDataTSMC")
    )
    Set-Content -Path $path -Value $lines -Encoding UTF8
    Write-Host "Checked local env: $path"
}

function Ensure-Tooling {
    if (-not (Test-Command "java")) {
        [void](Install-WithWinget "EclipseAdoptium.Temurin.21.JDK" "Java 21")
    } else {
        Write-Host "Java is available."
    }

    if (-not (Test-Command "mvn")) {
        [void](Install-WithWinget "Apache.Maven" "Maven")
    } else {
        Write-Host "Maven is available."
    }
}

function Compile-Project($root) {
    if ($SkipCompile) {
        Write-Host "Compile skipped by -SkipCompile."
        return
    }
    if (Test-Command "mvn") {
        Push-Location $root
        try {
            Write-Host "Compiling project and downloading Maven dependencies..."
            mvn -q -DskipTests compile
            if ($LASTEXITCODE -ne 0) {
                throw "Maven compile failed. Please check network access or Maven settings."
            }
            Write-Host "Compile finished."
        } finally {
            Pop-Location
        }
    } else {
        throw "Maven is not available. Install Maven or re-run with -InstallMissing."
    }
}

function Test-OllamaServer($baseUrl) {
    try {
        Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/api/tags" -TimeoutSec 3 | Out-Null
        return $true
    } catch {
        return $false
    }
}

function Start-OllamaServer($ollamaCommand, $baseUrl) {
    if (Test-OllamaServer $baseUrl) {
        return
    }
    Write-Host "Starting Ollama server..."
    Start-Process -WindowStyle Hidden -FilePath $ollamaCommand -ArgumentList "serve"
    $deadline = (Get-Date).AddSeconds(20)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 500
        if (Test-OllamaServer $baseUrl) {
            Write-Host "Ollama server is running."
            return
        }
    }
    throw "Ollama server did not start. Open Ollama manually and re-run this script."
}

function Test-OllamaModel($ollamaCommand, $model) {
    $output = & $ollamaCommand list 2>$null
    if ($LASTEXITCODE -ne 0) {
        return $false
    }
    return ($output -join "`n") -match [Regex]::Escape($model)
}

function Ensure-OllamaModel($root, $ollamaCommand, $model) {
    if (Test-OllamaModel $ollamaCommand $model) {
        Write-Host "Ollama model is available: $model"
        return
    }

    $modelfile = Join-Path $root "src\main\java\com\stockbucks\api\config\ollama-traditional-zh.Modelfile"
    if ($model -eq "stockbucks-traditional-zh:latest" -and (Test-Path $modelfile)) {
        Write-Host "Creating StockBucks local AI model: $model"
        & $ollamaCommand create $model -f $modelfile
    } else {
        Write-Host "Pulling Ollama model: $model"
        & $ollamaCommand pull $model
    }
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to prepare Ollama model: $model"
    }
}

function Ensure-Ollama($root) {
    $envPath = Join-Path $root "stockbucks.local.env"
    $envMap = Read-EnvMap $envPath
    $ollamaCommand = Find-OllamaCommand $root $envMap
    if ([string]::IsNullOrWhiteSpace($ollamaCommand)) {
        if (-not ($UseLocalAi -or $PullOllamaModel)) {
            Write-Host "Ollama is not installed. Re-run with -UseLocalAi to prepare local AI fallback."
            return ""
        }
        [void](Install-WithWinget "Ollama.Ollama" "Ollama")
        $envMap = Read-EnvMap $envPath
        $ollamaCommand = Find-OllamaCommand $root $envMap
        if ([string]::IsNullOrWhiteSpace($ollamaCommand)) {
            throw "Ollama is still not available. Restart the terminal, set OLLAMA_EXE_PATH, or install Ollama manually."
        }
    }

    Write-Host "Ollama is available: $ollamaCommand"
    Ensure-LocalEnv $root $ollamaCommand
    if ($UseLocalAi -or $PullOllamaModel) {
        $envMap = Read-EnvMap $envPath
        $baseUrl = "http://localhost:11434"
        if ($envMap.ContainsKey("OLLAMA_BASE_URL") -and -not [string]::IsNullOrWhiteSpace($envMap["OLLAMA_BASE_URL"])) {
            $baseUrl = $envMap["OLLAMA_BASE_URL"].TrimEnd("/")
        }
        $model = "stockbucks-traditional-zh:latest"
        if ($envMap.ContainsKey("OLLAMA_MODEL") -and -not [string]::IsNullOrWhiteSpace($envMap["OLLAMA_MODEL"])) {
            $model = $envMap["OLLAMA_MODEL"]
        }
        Start-OllamaServer $ollamaCommand $baseUrl
        Ensure-OllamaModel $root $ollamaCommand $model
    }
    return $ollamaCommand
}

function Start-StockBucks($root, $mainClass) {
    Push-Location $root
    try {
        Write-Host "Starting $mainClass ..."
        mvn -q javafx:run "-Djavafx.mainClass=$mainClass"
    } finally {
        Pop-Location
    }
}

$root = Find-ProjectRoot
Write-Host "StockBucks root: $root"
Ensure-Tooling
Ensure-LocalEnv $root
[void](Ensure-Ollama $root)
Compile-Project $root

if ($StartDebugDashboard) {
    Start-StockBucks $root "com.stockbucks.api.debug.ApiDebugDashboard"
} elseif ($StartApp) {
    Start-StockBucks $root "com.stockbucks.gui.MainApp"
}

Write-Host "StockBucks API setup finished."
