@echo off
setlocal
cd /d "%~dp0"

if exist "dist\PocketDeck\PocketDeck.exe" (
    start "" "dist\PocketDeck\PocketDeck.exe" %*
    exit /b 0
)

if exist ".venv\Scripts\pythonw.exe" (
    start "" ".venv\Scripts\pythonw.exe" "windows\pocketdeck.pyw" %*
    exit /b 0
)

if exist "%USERPROFILE%\.pocketdeck-tools\venv\Scripts\pythonw.exe" (
    start "" "%USERPROFILE%\.pocketdeck-tools\venv\Scripts\pythonw.exe" "windows\pocketdeck.pyw" %*
    exit /b 0
)

where pythonw >nul 2>nul
if %errorlevel%==0 (
    start "" pythonw "windows\pocketdeck.pyw" %*
    exit /b 0
)

echo Environment not found. Running install.bat...
call "%~dp0install.bat"
if exist ".venv\Scripts\pythonw.exe" (
    start "" ".venv\Scripts\pythonw.exe" "windows\pocketdeck.pyw" %*
)
exit /b 0
