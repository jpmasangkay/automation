@echo off
setlocal EnableDelayedExpansion
title Rebuilding Site Content Comparator...

echo.
echo  ============================================================
echo    REBUILD — Site Content Comparator
echo  ============================================================
echo.

:: ── Find Java 21+ ─────────────────────────────────────────────
set JAVA_EXE=
where java >nul 2>&1
if not errorlevel 1 (
    for /f "tokens=3 delims= " %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do set _V=%%v
    set _V=!_V:"=!
    set _M=0
    for /f "delims=." %%m in ("!_V!") do set /a _M=%%m 2>nul
    if !_M! GEQ 21 ( set JAVA_EXE=java & goto :java_ok )
)
for %%J in (
    "%USERPROFILE%\.jdks\openjdk-25\bin\java.exe"
    "%USERPROFILE%\.jdks\ms-25.0.1\bin\java.exe"
    "%USERPROFILE%\.jdks\openjdk-23.0.2\bin\java.exe"
    "%USERPROFILE%\.jdks\openjdk-23.0.1\bin\java.exe"
    "%USERPROFILE%\.jdks\temurin-21\bin\java.exe"
    "%USERPROFILE%\.jdks\openjdk-21\bin\java.exe"
) do (
    if exist %%J ( set JAVA_EXE=%%~J & goto :java_ok )
)
echo  [ERROR] Java 21+ not found. Install from https://adoptium.net/
pause & exit /b 1
:java_ok
echo  [OK] Java found: %JAVA_EXE%

:: ── Find Maven ────────────────────────────────────────────────
set MVN_EXE=
where mvn >nul 2>&1
if not errorlevel 1 ( set MVN_EXE=mvn & goto :mvn_ok )
for %%E in (M2_HOME MAVEN_HOME) do (
    if defined %%E ( if exist "!%%E!\bin\mvn.cmd" ( set MVN_EXE=!%%E!\bin\mvn.cmd & goto :mvn_ok ) )
)
:: Scan Maven Wrapper cache dynamically
if exist "%USERPROFILE%\.m2\wrapper\dists" (
    for /d %%D in ("%USERPROFILE%\.m2\wrapper\dists\apache-maven-*") do (
        for /d %%H in ("%%D\*") do (
            for /d %%A in ("%%H\apache-maven-*") do (
                if exist "%%A\bin\mvn.cmd" ( set MVN_EXE=%%A\bin\mvn.cmd & goto :mvn_ok )
            )
        )
    )
)
for %%D in (
    "C:\Program Files\apache-maven*" "C:\tools\apache-maven*" "C:\maven*"
    "D:\maven*" "D:\tools\apache-maven*" "%USERPROFILE%\scoop\apps\maven\current"
) do (
    for /d %%M in ("%%~D") do (
        if exist "%%M\bin\mvn.cmd" ( set MVN_EXE=%%M\bin\mvn.cmd & goto :mvn_ok )
    )
)
echo  [ERROR] Maven not found. Install from https://maven.apache.org/download.cgi
pause & exit /b 1
:mvn_ok
echo  [OK] Maven found: %MVN_EXE%

:: Set JAVA_HOME for Maven
if not "%JAVA_EXE%"=="java" (
    for %%I in ("%JAVA_EXE%") do set _JBIN=%%~dpI
    set JAVA_HOME=!_JBIN:~0,-5!
    echo  [OK] JAVA_HOME set to: !JAVA_HOME!
)

echo.
echo  Cleaning and rebuilding automation-runner.jar...
echo.
call "%MVN_EXE%" clean package --no-transfer-progress -f "%~dp0pom.xml"

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
