@echo off
setlocal EnableExtensions
cd /d "%~dp0"

if not exist "%~dp0mvnw.cmd" (
    echo Maven Wrapper was not found in the DrawN project folder.
    pause
    exit /b 1
)

call "%~dp0mvnw.cmd" -q compile exec:java -Dexec.mainClass=drawn.DrawN
if errorlevel 1 (
    echo.
    echo DrawN console launch failed.
    pause
    exit /b 1
)

pause
