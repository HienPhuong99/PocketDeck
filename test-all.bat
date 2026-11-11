@echo off
setlocal
cd /d "%~dp0"

echo ============================================================
echo   PocketDeck - Full Cross-Platform Verification Suite
echo ============================================================

set "PYTEST_EXE="
if exist ".venv\Scripts\pytest.exe" (
    set "PYTEST_EXE=.venv\Scripts\pytest.exe"
) else if exist "%USERPROFILE%\.pocketdeck-tools\venv\Scripts\pytest.exe" (
    set "PYTEST_EXE=%USERPROFILE%\.pocketdeck-tools\venv\Scripts\pytest.exe"
) else (
    where pytest >nul 2>nul
    if %errorlevel%==0 set "PYTEST_EXE=pytest"
)

if "%PYTEST_EXE%"=="" (
    echo [ERROR] pytest not found. Run install.bat first.
    exit /b 1
)

if "%JAVA_HOME%"=="" (
    if exist "%USERPROFILE%\.pocketdeck-tools\jdk-17\bin\java.exe" (
        set "JAVA_HOME=%USERPROFILE%\.pocketdeck-tools\jdk-17"
    )
)
if "%ANDROID_HOME%"=="" (
    if exist "%USERPROFILE%\.pocketdeck-tools\android-sdk" (
        set "ANDROID_HOME=%USERPROFILE%\.pocketdeck-tools\android-sdk"
    ) else if exist "%LOCALAPPDATA%\Android\Sdk" (
        set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
    )
)
set "ANDROID_SDK_ROOT=%ANDROID_HOME%"

echo [1/2] Running Android Kotlin JUnit tests (DeckLink + 21 FairPlay vectors)...
pushd android
call gradlew.bat testDebugUnitTest printDebugUnitTestClasspath --no-daemon
if errorlevel 1 (
    popd
    echo [ERROR] Android unit tests failed.
    exit /b 1
)
popd

echo [2/2] Running Windows Pytest suite + Live Python-Kotlin TCP Bridge...
set "QT_QPA_PLATFORM=offscreen"
set "QT_QPA_FONTDIR=%WINDIR%\Fonts"
"%PYTEST_EXE%" windows/tests -v
if errorlevel 1 (
    echo [ERROR] Windows / Cross-platform test suite failed.
    exit /b 1
)

echo.
echo [PASS] All Android, Windows, and Cross-Platform Bridge tests passed!
exit /b 0
