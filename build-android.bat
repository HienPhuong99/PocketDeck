@echo off
setlocal
cd /d "%~dp0"

echo ============================================================
echo   PocketDeck for Android - Build APK (Gradle Wrapper)
echo ============================================================

if "%JAVA_HOME%"=="" (
    if exist "%USERPROFILE%\.pocketdeck-tools\jdk-17\bin\java.exe" (
        set "JAVA_HOME=%USERPROFILE%\.pocketdeck-tools\jdk-17"
    ) else if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" (
        set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
    )
)

if "%ANDROID_HOME%"=="" (
    if not "%ANDROID_SDK_ROOT%"=="" (
        set "ANDROID_HOME=%ANDROID_SDK_ROOT%"
    ) else if exist "%USERPROFILE%\.pocketdeck-tools\android-sdk" (
        set "ANDROID_HOME=%USERPROFILE%\.pocketdeck-tools\android-sdk"
    ) else if exist "%LOCALAPPDATA%\Android\Sdk" (
        set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
    )
)
set "ANDROID_SDK_ROOT=%ANDROID_HOME%"

pushd android
call gradlew.bat testDebugUnitTest assembleDebug --no-daemon
if errorlevel 1 (
    popd
    echo [ERROR] Android APK build failed.
    exit /b 1
)
popd

if not exist "dist-apk" mkdir "dist-apk"
copy /Y "android\app\build\outputs\apk\debug\app-debug.apk" "dist-apk\PocketDeck-debug.apk" >nul
echo [DONE] APK exported to dist-apk\PocketDeck-debug.apk
exit /b 0
