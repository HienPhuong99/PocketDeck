@echo off
setlocal
cd /d "%~dp0"

echo ============================================================
echo   PocketDeck for Windows - Standalone PyInstaller Build
echo ============================================================

set "PY_EXE="
set "PYINSTALLER_EXE="

if exist ".venv\Scripts\python.exe" (
    set "PY_EXE=.venv\Scripts\python.exe"
    set "PYINSTALLER_EXE=.venv\Scripts\pyinstaller.exe"
) else if exist "%USERPROFILE%\.pocketdeck-tools\venv\Scripts\python.exe" (
    set "PY_EXE=%USERPROFILE%\.pocketdeck-tools\venv\Scripts\python.exe"
    set "PYINSTALLER_EXE=%USERPROFILE%\.pocketdeck-tools\venv\Scripts\pyinstaller.exe"
)

if "%PY_EXE%"=="" (
    echo Virtualenv not found. Running install.bat --no-pause...
    call "%~dp0install.bat" --no-pause
    set "PY_EXE=.venv\Scripts\python.exe"
    set "PYINSTALLER_EXE=.venv\Scripts\pyinstaller.exe"
)

if not exist "%PYINSTALLER_EXE%" (
    "%PY_EXE%" -m pip install pyinstaller
)

echo [1/3] Generating windows\assets\pocketdeck.ico and checking Android tools...
"%PY_EXE%" "windows\tools\make_icon.py"
if errorlevel 1 exit /b 1
powershell -NoProfile -ExecutionPolicy Bypass -File "windows\tools\get_android_tools.ps1"

echo [2/3] Running PyInstaller (dist\PocketDeck\PocketDeck.exe)...
"%PYINSTALLER_EXE%" --noconfirm --clean --distpath "dist" --workpath "build\pyinstaller" "windows\pocketdeck.spec"
if errorlevel 1 (
    echo [ERROR] PyInstaller build failed.
    exit /b 1
)

echo [3/3] Verifying standalone bundle...
if not exist "dist\PocketDeck\PocketDeck.exe" (
    echo [ERROR] dist\PocketDeck\PocketDeck.exe was not generated.
    exit /b 1
)

echo [DONE] Standalone Windows executable ready at: dist\PocketDeck\PocketDeck.exe
exit /b 0
