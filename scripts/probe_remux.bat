@echo off
set "PYTHON_EXE=%LOCALAPPDATA%\Programs\Python\Python314\python.exe"
set "SCRIPT_DIR=%~dp0.."

if not exist "%PYTHON_EXE%" (
    echo [ERROR] No se encontro Python en %PYTHON_EXE%
    pause
    exit /b 1
)

"%PYTHON_EXE%" "%SCRIPT_DIR%\probe_remux.py"
pause
