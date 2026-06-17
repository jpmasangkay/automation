@echo off
setlocal EnableDelayedExpansion
title Rebuilding Site Content Comparator...

echo.
echo  ============================================================
echo    REBUILD — Site Content Comparator
echo  ============================================================
echo.

:: Quick helper: test a java.exe candidate and set JAVA_EXE if version >= 21
goto :skip_func

:test_java
    if not exist "%~1" exit /b 1
    set _RAW=
    for /f "usebackq tokens=3 delims= " %%v in (`""%~1" -version 2^>^&1 ^| findstr /i "version""`) do set _RAW=%%v
    set _RAW=!_RAW:"=!
    set _MAJ=0
    for /f "delims=." %%m in ("!_RAW!") do set /a _MAJ=%%m 2>nul
    if !_MAJ! GEQ 21 (
        set JAVA_EXE=%~1
        set JAVA_VER_NUM=!_MAJ!
        exit /b 0
    )
    exit /b 1

:skip_func

:: ── Find Java 21+ ─────────────────────────────────────────────
set JAVA_EXE=
set JAVA_VER_NUM=0

where java >nul 2>&1
if not errorlevel 1 (
    call :test_java "java"
    if not errorlevel 1 goto :java_ok
)

call :test_java "%USERPROFILE%\.jdks\automation-java\jdk-21\bin\java.exe"
if not errorlevel 1 goto :java_ok

for %%J in (
    "%USERPROFILE%\.jdks\openjdk-25\bin\java.exe"
    "%USERPROFILE%\.jdks\ms-25.0.1\bin\java.exe"
    "%USERPROFILE%\.jdks\openjdk-23.0.2\bin\java.exe"
    "%USERPROFILE%\.jdks\openjdk-23.0.1\bin\java.exe"
    "%USERPROFILE%\.jdks\temurin-21\bin\java.exe"
    "%USERPROFILE%\.jdks\openjdk-21\bin\java.exe"
) do (
    call :test_java %%J
    if not errorlevel 1 goto :java_ok
)

echo  [WARNING] Java 21+ not found. Auto-downloading...
if not exist "%USERPROFILE%\.jdks\automation-java" mkdir "%USERPROFILE%\.jdks\automation-java"
%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe -NoProfile -Command "$ErrorActionPreference = 'Stop'; $url = 'https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk'; $zip = '%TEMP%\jdk21.zip'; $dest = '%USERPROFILE%\.jdks\automation-java'; Write-Host 'Downloading Java 21...'; Invoke-WebRequest -Uri $url -OutFile $zip; Write-Host 'Extracting...'; Expand-Archive -Path $zip -DestinationPath $dest -Force; Remove-Item $zip; Write-Host 'Java download complete!'"

if errorlevel 1 (
    echo  [ERROR] Auto-download failed. Install from https://adoptium.net/
    pause & exit /b 1
)

for /d %%K in ("%USERPROFILE%\.jdks\automation-java\*") do (
    call :test_java "%%K\bin\java.exe"
    if not errorlevel 1 goto :java_ok
)

echo  [ERROR] Could not locate java.exe after downloading.
pause & exit /b 1

:java_ok
echo  [OK] Java found: %JAVA_EXE%

:: ── Set Maven (Using bundled Maven Wrapper) ──────────────────────
set MVN_EXE="%~dp0mvnw.cmd"
echo  [OK] Using bundled Maven Wrapper: !MVN_EXE!

:: Set JAVA_HOME for Maven
if not "%JAVA_EXE%"=="java" (
    for %%I in ("%JAVA_EXE%") do set _JBIN=%%~dpI
    set JAVA_HOME=!_JBIN:~0,-5!
    echo  [OK] JAVA_HOME set to: !JAVA_HOME!
)

echo.
echo  Cleaning and rebuilding automation-runner.jar...
echo.
call !MVN_EXE! clean package --no-transfer-progress -f "%~dp0pom.xml"

if errorlevel 1 (
    echo.
    echo  [ERROR] Build failed. See output above.
    pause & exit /b 1
)

echo.
echo  [OK] Build complete! automation-runner.jar is ready in target\
echo  Run run.bat to start the comparator.
echo.
pause
endlocal
