@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

mvn -q compile exec:java -Dexec.mainClass=drawn.DrawN
if errorlevel 1 (
    echo.
    echo Java compilation failed.
    pause
    exit /b 1
)

pause
