@echo off
setlocal EnableExtensions
cd /d "%~dp0"

if not exist "%~dp0mvnw.cmd" (
    echo Maven Wrapper was not found in the DrawN project folder.
    pause
    exit /b 1
)

call "%~dp0mvnw.cmd" -q javafx:run
if errorlevel 1 (
    echo.
    echo GUI application failed to start.
    pause
    exit /b 1
)
