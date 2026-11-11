param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path,
    [switch]$Desktop = $true,
    [switch]$StartMenu = $true
)

$ErrorActionPreference = "Stop"

$builtExe = Join-Path $RepoRoot "dist\PocketDeck\PocketDeck.exe"
$venvPyw = Join-Path $RepoRoot ".venv\Scripts\pythonw.exe"
$devPyw = Join-Path $env:USERPROFILE ".pocketdeck-tools\venv\Scripts\pythonw.exe"
$pywScript = Join-Path $RepoRoot "windows\pocketdeck.pyw"
$iconPath = Join-Path $RepoRoot "windows\assets\pocketdeck.ico"

$targetPath = ""
$arguments = ""
$workDir = $RepoRoot

if (Test-Path $builtExe) {
    $targetPath = $builtExe
    $workDir = Split-Path -Parent $builtExe
} elseif (Test-Path $venvPyw) {
    $targetPath = $venvPyw
    $arguments = "`"$pywScript`""
} elseif (Test-Path $devPyw) {
    $targetPath = $devPyw
    $arguments = "`"$pywScript`""
} else {
    Write-Warning "Chua tim thay PocketDeck.exe hoac .venv; bo qua tao shortcut."
    exit 0
}

$wsh = New-Object -ComObject WScript.Shell

function New-PocketDeckShortcut([string]$lnkFile) {
    $parent = Split-Path -Parent $lnkFile
    if (-not (Test-Path $parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    $sc = $wsh.CreateShortcut($lnkFile)
    $sc.TargetPath = $targetPath
    if ($arguments) {
        $sc.Arguments = $arguments
    }
    $sc.WorkingDirectory = $workDir
    $sc.Description = "PocketDeck - Chieu man hinh, dieu khien va truyen file Android"
    if (Test-Path $iconPath) {
        $sc.IconLocation = "$iconPath,0"
    }
    $sc.Save()
    Write-Host "Created shortcut: $lnkFile"
}

if ($Desktop) {
    $deskDir = [Environment]::GetFolderPath("Desktop")
    if ($deskDir) {
        New-PocketDeckShortcut (Join-Path $deskDir "PocketDeck.lnk")
    }
}

if ($StartMenu) {
    $programsDir = [Environment]::GetFolderPath("Programs")
    if ($programsDir) {
        New-PocketDeckShortcut (Join-Path $programsDir "PocketDeck.lnk")
    }
}
