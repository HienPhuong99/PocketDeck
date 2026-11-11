param(
    [string]$DestDir = "$env:LOCALAPPDATA\PocketDeck\tools\scrcpy"
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

if (Test-Path (Join-Path $DestDir "adb.exe")) {
    if (Test-Path (Join-Path $DestDir "scrcpy-server")) {
        Write-Host "Android tools (adb + scrcpy-server) already installed at $DestDir"
        exit 0
    }
}

$devCache = Join-Path $env:USERPROFILE ".pocketdeck-tools\scrcpy"
if ((Test-Path (Join-Path $devCache "adb.exe")) -and (Test-Path (Join-Path $devCache "scrcpy-server"))) {
    New-Item -ItemType Directory -Force -Path $DestDir | Out-Null
    Copy-Item -Path (Join-Path $devCache "*") -Destination $DestDir -Recurse -Force
    Write-Host "Copied cached Android tools to $DestDir"
    exit 0
}

$url = "https://github.com/Genymobile/scrcpy/releases/download/v5.0.1/scrcpy-win64-v5.0.1.zip"
$parent = Split-Path -Parent $DestDir
New-Item -ItemType Directory -Force -Path $parent | Out-Null
$zipFile = Join-Path $parent "scrcpy-temp.zip"
$extractDir = Join-Path $parent "scrcpy-extract-tmp"

Write-Host "Downloading Genymobile/scrcpy v5.0.1 (adb + scrcpy-server)..."
Invoke-WebRequest -Uri $url -OutFile $zipFile -UseBasicParsing

if (Test-Path $extractDir) { Remove-Item -Recurse -Force $extractDir }
Expand-Archive -Path $zipFile -DestinationPath $extractDir -Force
$inner = Get-ChildItem -Path $extractDir | Select-Object -First 1
if (Test-Path $DestDir) { Remove-Item -Recurse -Force $DestDir }
Move-Item -Path $inner.FullName -Destination $DestDir -Force
Remove-Item -Recurse -Force $extractDir, $zipFile
Write-Host "Installed Android tools at $DestDir"
