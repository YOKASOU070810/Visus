param(
    [string]$OutputPath = "dist\Visus-submission-source.zip"
)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$OutFile = Join-Path $Root $OutputPath
$OutDir = Split-Path -Parent $OutFile
$TempDir = Join-Path $Root "dist\_package_temp"

if (-not (Test-Path $OutDir)) {
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
}

if (Test-Path $TempDir) {
    Remove-Item -LiteralPath $TempDir -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $TempDir | Out-Null

$files = git -C $Root -c core.quotepath=false ls-files
foreach ($file in $files) {
    $src = Join-Path $Root $file
    $dst = Join-Path $TempDir $file
    $dstParent = Split-Path -Parent $dst
    if (-not (Test-Path $dstParent)) {
        New-Item -ItemType Directory -Force -Path $dstParent | Out-Null
    }
    Copy-Item -LiteralPath $src -Destination $dst -Force
}

if (Test-Path $OutFile) {
    Remove-Item -LiteralPath $OutFile -Force
}

Push-Location $TempDir
try {
    & tar.exe -a -cf $OutFile *
    if ($LASTEXITCODE -ne 0) {
        throw "tar.exe failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}
Remove-Item -LiteralPath $TempDir -Recurse -Force

Write-Host "[OK] Submission package created:"
Write-Host "  $OutFile"
Write-Host ""
Write-Host "This package is generated from git-tracked files only."
Write-Host "It excludes .env, .venv, .git, Gradle build outputs, local databases, and other ignored runtime files."
