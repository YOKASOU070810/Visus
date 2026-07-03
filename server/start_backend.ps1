param(
    [switch]$InstallDeps
)

$ErrorActionPreference = "Stop"

$ServerDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$SrcDir = Join-Path $ServerDir "src"
$EnvFile = Join-Path $ServerDir ".env"
$ReqFile = Join-Path $ServerDir "config\requirements.txt"
$VenvPython = Join-Path $ServerDir ".venv\Scripts\python.exe"

if (-not (Test-Path $EnvFile)) {
    Write-Host "[ERROR] Missing server\.env. Copy server\.env.example to server\.env and fill keys."
    exit 1
}

$envValues = @{}
Get-Content $EnvFile | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
        $idx = $line.IndexOf("=")
        $name = $line.Substring(0, $idx).Trim()
        $value = $line.Substring($idx + 1).Trim()
        $envValues[$name] = $value
    }
}

$required = @(
    "ARK_API_KEY",
    "ARK_MODEL",
    "DASHSCOPE_API_KEY",
    "VOLCENGINE_TTS_APP_ID",
    "VOLCENGINE_TTS_ACCESS_TOKEN"
)

$missingKeys = @()
foreach ($key in $required) {
    $value = if ($envValues.ContainsKey($key)) { $envValues[$key] } else { "" }
    if (-not $value -or $value -match "^(your-|sk-your-|your_)") {
        $missingKeys += $key
    }
}

if ($missingKeys.Count -gt 0) {
    Write-Host "[ERROR] server\.env has missing placeholder values:"
    $missingKeys | ForEach-Object { Write-Host "  - $_" }
    exit 1
}

if ($InstallDeps) {
    if (-not (Test-Path $VenvPython)) {
        Write-Host "[SETUP] Creating virtual environment: server\.venv"
        python -m venv (Join-Path $ServerDir ".venv")
    }
    Write-Host "[SETUP] Upgrading pip"
    & $VenvPython -m pip install --upgrade pip
    Write-Host "[SETUP] Installing backend requirements"
    & $VenvPython -m pip install -r $ReqFile
}

$PythonExe = if (Test-Path $VenvPython) { $VenvPython } else { "python" }

$checkScript = @'
mods = ["fastapi", "uvicorn", "dashscope", "openai", "httpx", "requests", "cv2", "numpy", "torch", "mediapipe", "ultralytics"]
missing = []
for mod in mods:
    try:
        __import__(mod)
    except Exception as exc:
        missing.append(f"{mod}: {exc!r}")
if missing:
    print("[ERROR] Missing Python dependencies:")
    print("\n".join("  - " + item for item in missing))
    print("\nRun: powershell -ExecutionPolicy Bypass -File server\\start_backend.ps1 -InstallDeps")
    raise SystemExit(1)
print("[OK] Python dependencies look installed.")
'@

$checkScript | & $PythonExe -
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

$ModelsDir = Join-Path $ServerDir "assets\models"
if (-not $env:BLIND_PATH_MODEL) {
    $env:BLIND_PATH_MODEL = Join-Path $ModelsDir "yolo-seg.pt"
}
if (-not $env:OBSTACLE_MODEL) {
    $env:OBSTACLE_MODEL = Join-Path $ModelsDir "yoloe-11l-seg.pt"
}

Write-Host "[INFO] Backend URL: http://localhost:8081"
Write-Host "[INFO] Workdir: $SrcDir"
Write-Host "[INFO] Starting Visus backend..."

Push-Location $SrcDir
try {
    & $PythonExe "core\app_main.py"
}
finally {
    Pop-Location
}
