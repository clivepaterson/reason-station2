@echo off
setlocal

set "PROJECT_DIR=%~dp0"
set "SOURCE_FILE=%PROJECT_DIR%src\main\resources\static\scss\styles.scss"
set "TARGET_DIR=%PROJECT_DIR%target\classes\static\css"
set "TARGET_FILE=%TARGET_DIR%\styles.css"

if not exist "%SOURCE_FILE%" (
    echo Sass source file not found:
    echo %SOURCE_FILE%
    exit /b 1
)

where sass >nul 2>&1
if errorlevel 1 (
    echo Sass CLI was not found on PATH.
    echo Install Dart Sass and make sure the "sass" command is available.
    exit /b 1
)

if not exist "%TARGET_DIR%" mkdir "%TARGET_DIR%"

echo Compiling %SOURCE_FILE%
echo Watching for changes. Press Ctrl+C to stop.
sass --source-map --watch "%SOURCE_FILE%:%TARGET_FILE%"

endlocal
