@echo off
setlocal
cd /d "%~dp0"
if not exist "jre\bin\javaw.exe" (
    echo Bundled Java runtime not found: jre\bin\javaw.exe
    pause
    exit /b 1
)
if not exist "Mindustry.jar" (
    echo Mindustry.jar not found in this folder.
    pause
    exit /b 1
)
start "" "%~dp0jre\bin\javaw.exe" -jar "%~dp0Mindustry.jar" %*
