param(
    [switch]$InstallDeps
)

$RootDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ServerScript = Join-Path $RootDir "server\start_backend.ps1"

if ($InstallDeps) {
    & powershell -ExecutionPolicy Bypass -File $ServerScript -InstallDeps
} else {
    & powershell -ExecutionPolicy Bypass -File $ServerScript
}
