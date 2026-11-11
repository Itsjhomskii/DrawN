@echo off
setlocal EnableExtensions
cd /d "%~dp0"
mvn -q javafx:run
if errorlevel 1 (
    echo.
    echo GUI application failed to start.
    pause
    exit /b 1
)
