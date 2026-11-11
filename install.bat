@echo off
setlocal
cd /d "%~dp0"

echo ============================================================
echo   PocketDeck for Windows - Automated Installer
echo ============================================================

set "PYTHON_CMD="
where python >nul 2>nul
if %errorlevel%==0 set "PYTHON_CMD=python"
if "%PYTHON_CMD%"=="" (
    where py >nul 2>nul
    if %errorlevel%==0 set "PYTHON_CMD=py -3"
)
if "%PYTHON_CMD%"=="" (
    echo [ERROR] Python 3.11+ was not found. Install Python from https://www.python.org/downloads/
    if /I not "%~1"=="--no-pause" pause
    exit /b 1
)

if not exist ".venv\Scripts\python.exe" (
    echo [1/4] Creating Python virtual environment .venv...
    %PYTHON_CMD% -m venv .venv
    if errorlevel 1 exit /b 1
)

echo [2/4] Installing Python dependencies...
".venv\Scripts\python.exe" -m pip install --upgrade pip -q
".venv\Scripts\python.exe" -m pip install -r "windows\requirements.txt"
if errorlevel 1 exit /b 1

echo [3/4] Generating icon and preparing Android tools (ADB + scrcpy-server)...
".venv\Scripts\python.exe" "windows\tools\make_icon.py"
powershell -NoProfile -ExecutionPolicy Bypass -File "windows\tools\get_android_tools.ps1"

echo [4/4] Creating Desktop and Start Menu shortcuts...
powershell -NoProfile -ExecutionPolicy Bypass -File "windows\tools\shortcuts.ps1"

echo.
echo [DONE] PocketDeck installed successfully! Open PocketDeck from Desktop or run.bat.
if /I not "%~1"=="--no-pause" pause
exit /b 0
