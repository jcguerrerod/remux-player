@echo off
setlocal enabledelayedexpansion

echo ========================================================
echo  Android TV Remux Player - Build Master
echo ========================================================

set "PROJECT_ROOT=%~dp0.."
set "JAVA_HOME=%PROJECT_ROOT%\..\tools\jdk-17"
set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [ERROR] No se encontro JDK 17 portable en: %JAVA_HOME%
    exit /b 1
)

if not exist "%ANDROID_HOME%" (
    echo [ERROR] No se encontro Android SDK en: %ANDROID_HOME%
    exit /b 1
)

echo [OK] Usando JDK 17: %JAVA_HOME%
echo [OK] Usando Android SDK: %ANDROID_HOME%

cd /d "%PROJECT_ROOT%"
echo [BUILD] Compilando APK con Gradle Wrapper...
call gradlew.bat assembleDebug

if %ERRORLEVEL% equ 0 (
    echo ========================================================
    echo  COMPILACION EXITOSA!
    echo  APK generado en:
    echo  app\build\outputs\apk\debug\app-debug.apk
    echo ========================================================
) else (
    echo [ERROR] Fallo la compilacion. Codigo: %ERRORLEVEL%
)

pause
