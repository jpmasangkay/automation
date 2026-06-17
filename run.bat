@echo off
setlocal EnableDelayedExpansion
title Site Content Comparator

:: ============================================================
::  SITE CONTENT COMPARATOR  —  Plug & Run Launcher
::  Just double-click this file — no programming knowledge needed!
:: ============================================================

echo.
echo  ============================================================
echo    SITE CONTENT COMPARATOR
echo    Automated Web Page Comparison Tool
echo  ============================================================
echo.

:: ════════════════════════════════════════════════════════════
::  STEP 1 — Find Java 21+
:: ════════════════════════════════════════════════════════════
set JAVA_EXE=
set JAVA_VER_NUM=0

:: Quick helper: test a java.exe candidate and set JAVA_EXE if version >= 21
:: Usage: call :test_java "<path>\java.exe"
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

:: 1a. Check PATH first
where java >nul 2>&1
if not errorlevel 1 (
    for /f "tokens=3 delims= " %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do set _RAW=%%v
    set _RAW=!_RAW:"=!
    set _MAJ=0
    for /f "delims=." %%m in ("!_RAW!") do set /a _MAJ=%%m 2>nul
    if !_MAJ! GEQ 21 (
        set JAVA_EXE=java
        set JAVA_VER_NUM=!_MAJ!
        echo  [OK] Java !_MAJ! found on PATH.
        goto :java_found
    ) else (
        echo  [INFO] Java !_MAJ! on PATH is too old. Searching for Java 21+...
    )
)

:: 1b. Check JAVA_HOME env var
if defined JAVA_HOME (
    call :test_java "%JAVA_HOME%\bin\java.exe"
    if not errorlevel 1 (
        echo  [OK] Java %JAVA_VER_NUM% found via JAVA_HOME.
        goto :java_found
    )
)

:: 1c. Probe well-known locations (covers IntelliJ-downloaded JDKs + standard installs)
for %%J in (
    "%USERPROFILE%\.jdks\openjdk-25\bin\java.exe"
    "%USERPROFILE%\.jdks\ms-25.0.1\bin\java.exe"
    "%USERPROFILE%\.jdks\openjdk-23.0.2\bin\java.exe"
    "%USERPROFILE%\.jdks\openjdk-23.0.1\bin\java.exe"
    "%USERPROFILE%\.jdks\temurin-21\bin\java.exe"
    "%USERPROFILE%\.jdks\openjdk-21\bin\java.exe"
) do (
    call :test_java %%J
    if not errorlevel 1 (
        echo  [OK] Java %JAVA_VER_NUM% found at: %%~dpJ
        goto :java_found
    )
)

:: 1d. Check previously auto-downloaded Java
call :test_java "%USERPROFILE%\.jdks\automation-java\jdk-21\bin\java.exe"
if not errorlevel 1 (
    echo  [OK] Auto-downloaded Java %JAVA_VER_NUM% found.
    goto :java_found
)

:: 1e. Scan common install folders dynamically
for %%D in (
    "%USERPROFILE%\.jdks"
    "C:\Program Files\Eclipse Adoptium"
    "C:\Program Files\Microsoft"
    "C:\Program Files\Java"
    "C:\Program Files\BellSoft"
    "C:\Program Files\Amazon Corretto"
    "C:\Program Files\Azul Systems"
    "C:\tools\jdk"
    "C:\tools\java"
    "D:\Java"
    "D:\Tools\jdk"
) do (
    if exist "%%~D" (
        for /d %%K in ("%%~D\*") do (
            call :test_java "%%K\bin\java.exe"
            if not errorlevel 1 (
                echo  [OK] Java %JAVA_VER_NUM% found at: %%K
                goto :java_found
            )
        )
    )
)

echo  [WARNING] Java 21+ was not found on this machine.
echo  [SETUP] Downloading portable Java 21 automatically...
echo  [SETUP] This is a one-time ~180MB download. Please wait...
echo.

if not exist "%USERPROFILE%\.jdks\automation-java" mkdir "%USERPROFILE%\.jdks\automation-java"
%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe -NoProfile -Command "$ErrorActionPreference = 'Stop'; $url = 'https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk'; $zip = '%TEMP%\jdk21.zip'; $dest = '%USERPROFILE%\.jdks\automation-java'; Write-Host 'Downloading Java 21...'; Invoke-WebRequest -Uri $url -OutFile $zip; Write-Host 'Extracting...'; Expand-Archive -Path $zip -DestinationPath $dest -Force; Remove-Item $zip; Write-Host 'Java download complete!'"

if errorlevel 1 (
    echo.
    echo  [ERROR] Failed to download Java automatically.
    echo  Please install it manually from https://adoptium.net/
    pause
    exit /b 1
)

:: Find the exact extracted jdk folder (it has a dynamic name like jdk-21.0.2+13)
for /d %%K in ("%USERPROFILE%\.jdks\automation-java\*") do (
    call :test_java "%%K\bin\java.exe"
    if not errorlevel 1 (
        echo  [OK] Auto-downloaded Java %JAVA_VER_NUM% is ready.
        goto :java_found
    )
)

echo  [ERROR] Could not locate java.exe after downloading.
pause
exit /b 1

:java_found
echo  [OK] Java %JAVA_VER_NUM% will be used.


:: ════════════════════════════════════════════════════════════
::  STEP 2 — Set Maven (Using bundled Maven Wrapper)
:: ════════════════════════════════════════════════════════════
set MVN_EXE="%~dp0mvnw.cmd"
echo  [OK] Using bundled Maven Wrapper: !MVN_EXE!


:: ════════════════════════════════════════════════════════════
::  STEP 3 — Build fat JAR (only if not already built)
:: ════════════════════════════════════════════════════════════
set JAR_PATH=%~dp0target\automation-runner.jar

if not exist "%JAR_PATH%" (
    echo.
    echo  [BUILD] First-time setup: building automation-runner.jar...
    echo  [BUILD] This takes 1-3 minutes. Please wait.
    echo.

    :: Set JAVA_HOME so Maven uses the correct JDK
    if not "%JAVA_EXE%"=="java" (
        for %%I in ("%JAVA_EXE%") do set _JAVA_BIN=%%~dpI
        set JAVA_HOME=!_JAVA_BIN:~0,-5!
    )

    set "MAVEN_OPTS=-Dmaven.compiler.executable=%JAVA_EXE%"
    call !MVN_EXE! clean package --no-transfer-progress -f "%~dp0pom.xml" 2>&1

    if errorlevel 1 (
        echo.
        echo  [ERROR] Build failed. See output above for details.
        echo.
        pause
        exit /b 1
    )
    echo.
    echo  [OK] Build complete!
) else (
    echo  [OK] automation-runner.jar is ready (use rebuild.bat to force a rebuild).
)


:: ════════════════════════════════════════════════════════════
::  STEP 4 — Install Playwright browser (first-run only)
:: ════════════════════════════════════════════════════════════
set PW_CACHE=%USERPROFILE%\.cache\ms-playwright
set PW_INSTALLED=0
if exist "%PW_CACHE%" (
    for /d %%B in ("%PW_CACHE%\chromium*") do set PW_INSTALLED=1
)

if "%PW_INSTALLED%"=="0" (
    echo.
    echo  [SETUP] Installing Chromium browser for Playwright (first-time only)...
    echo  [SETUP] This downloads ~150 MB — may take a few minutes.
    echo.
    "%JAVA_EXE%" -cp "%JAR_PATH%" com.microsoft.playwright.CLI install chromium
    if errorlevel 1 (
        echo  [WARNING] Browser install may have partially failed. Continuing anyway...
    ) else (
        echo  [OK] Browser installed successfully.
    )
) else (
    echo  [OK] Playwright browser already installed.
)


:: ════════════════════════════════════════════════════════════
::  STEP 5 — Find your Excel (.xlsx) input file
:: ════════════════════════════════════════════════════════════
echo.
echo  ── Looking for your Excel file ─────────────────────────────
set EXCEL_FILE=

:: Priority 1: file dragged & dropped onto run.bat
if not "%~1"=="" (
    if exist "%~1" (
        set EXCEL_FILE=%~1
        echo  [OK] Using provided file: %~1
        goto :excel_found
    ) else (
        echo  [WARN] Dragged file not found: %~1
    )
)

:: Priority 2: scan the dedicated data\ folder
set INPUT_DIR=%~dp0data
set XLSX_COUNT=0
set XLSX_LAST=

if exist "%INPUT_DIR%" (
    for %%F in ("%INPUT_DIR%\*.xlsx") do (
        set /a XLSX_COUNT+=1
        set XLSX_LAST=%%F
        set XLSX_FILE_!XLSX_COUNT!=%%F
        set XLSX_NAME_!XLSX_COUNT!=%%~nxF
    )
)

if !XLSX_COUNT! EQU 1 (
    set EXCEL_FILE=!XLSX_LAST!
    echo  [OK] Auto-detected: !XLSX_LAST!
    goto :excel_found
)

if !XLSX_COUNT! GTR 1 (
    echo.
    echo  Multiple Excel files found in the data\ folder.
    echo  Which one would you like to process?
    echo.
    for /l %%I in (1,1,!XLSX_COUNT!) do (
        echo    [%%I]  !XLSX_NAME_%%I!
    )
    echo.
    set /p _CHOICE="  Enter number (1-!XLSX_COUNT!): "
    set EXCEL_FILE=!XLSX_FILE_%_CHOICE%!
    if not defined EXCEL_FILE (
        echo  [ERROR] Invalid selection.
        pause & exit /b 1
    )
    echo  [OK] Selected: !EXCEL_FILE!
    goto :excel_found
)

:: Priority 3: fallback to test_comparisons.xlsx in the root folder
if exist "%~dp0test_comparisons.xlsx" (
    set EXCEL_FILE=%~dp0test_comparisons.xlsx
    echo  [OK] Using default file: test_comparisons.xlsx
    goto :excel_found
)

:: Nothing found — give clear instructions
echo.
echo  ┌─────────────────────────────────────────────────────────┐
echo  │  No Excel file found!                                   │
echo  │                                                         │
echo  │  HOW TO USE:                                            │
echo  │                                                         │
echo  │  Option A (recommended):                                │
echo  │    1. Put your .xlsx file in the  data\  folder         │
echo  │    2. Double-click run.bat again                        │
echo  │                                                         │
echo  │  Option B:                                              │
echo  │    Drag your .xlsx file onto run.bat                    │
echo  │                                                         │
echo  │  Your Excel file should look like this:                 │
echo  │    Column A            │ Column B                       │
echo  │    https://staging.com │ https://production.com         │
echo  │    https://staging.com │ https://production.com/page2   │
echo  │                                                         │
echo  │  A header row (e.g. "Site A" / "Site B") is fine —     │
echo  │  it will be automatically skipped.                      │
echo  └─────────────────────────────────────────────────────────┘
echo.
pause
exit /b 1
:excel_found


:: ════════════════════════════════════════════════════════════
::  STEP 6 — Run the comparison
:: ════════════════════════════════════════════════════════════
echo.
echo  ════════════════════════════════════════════════════════════
echo    Starting...  Reports will be saved to:  %~dp0reports\
echo  ════════════════════════════════════════════════════════════
echo.

"%JAVA_EXE%" -jar "%JAR_PATH%" "!EXCEL_FILE!"
set EXIT_CODE=%errorlevel%

echo.
if %EXIT_CODE% EQU 0 (
    echo  ════════════════════════════════════════════════════════════
    echo    DONE!  PDF reports saved to:
    echo    %~dp0reports\
    echo  ════════════════════════════════════════════════════════════
    echo.
    echo  Opening reports folder...
    start "" "%~dp0reports"
) else (
    echo  [ERROR] The tool exited with an error (code %EXIT_CODE%^).
    echo  Review the output above for details.
)

echo.
pause
endlocal
