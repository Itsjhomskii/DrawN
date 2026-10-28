@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

if not exist "out" mkdir out

set "SOURCES_FILE=%TEMP%\drawn-sources-%RANDOM%-%RANDOM%.txt"
for /r "src" %%f in (*.java) do (
    set "SOURCE=%%f"
    echo "!SOURCE:\=/!">>"%SOURCES_FILE%"
)

javac --release 17 -d out @"%SOURCES_FILE%"
set "COMPILE_RESULT=%ERRORLEVEL%"
del /q "%SOURCES_FILE%" >nul 2>&1

if not "%COMPILE_RESULT%"=="0" (
    echo.
    echo Java compilation failed.
    pause
    exit /b 1
)

java -cp out drawn.DrawN
if errorlevel 1 (
    echo.
    echo Application failed to start.
    pause
    exit /b 1
)

pause
