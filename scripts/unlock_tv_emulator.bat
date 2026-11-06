@echo off
echo Desbloqueando emulador Television_1080p y limpiando procesos zombis...
taskkill /F /IM emulator.exe /T 2>nul
taskkill /F /IM qemu-system-x86_64.exe /T 2>nul
timeout /t 1 /nobreak >nul
del /F /Q "%USERPROFILE%\.android\avd\Television_1080p.avd\*.lock" 2>nul
rmdir /S /Q "%USERPROFILE%\.android\avd\Television_1080p.avd\hardware-qemu.ini.lock" 2>nul
"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" kill-server
"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" start-server
echo [OK] Emulador desbloqueado. Ya puedes iniciarlo limpiamente desde Android Studio!
