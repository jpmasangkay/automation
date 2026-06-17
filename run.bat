@echo off
setlocal EnableDelayedExpansion
title Site Content Comparator

echo.
echo  ============================================================
echo    SITE CONTENT COMPARATOR
echo    Automated Web Page Comparison Tool
echo  ============================================================
echo.

:: ════════════════════════════════════════════════════════════
::  STEP 1 — Find or Auto-Download Java 21+
:: ════════════════════════════════════════════════════════════
set JAVA_EXE=
set JAVA_VER_NUM=0

:: 1a. java on PATH
where java >nul 2>&1
if not errorlevel 1 (
    java -version >"%TEMP%\~jver.txt" 2>&1
    set _MAJ=0
    for /f "tokens=3" %%a in ('findstr /i "version" "%TEMP%\~jver.txt"') do (
        set _V=%%~a
        for /f "delims=.+-" %%m in ("!_V!") do set /a _MAJ=%%m 2>nul
    )
    del "%TEMP%\~jver.txt" >nul 2>&1
    if !_MAJ! GEQ 21 (
        set JAVA_EXE=java
        set JAVA_VER_NUM=!_MAJ!
        echo  [OK] Java !_MAJ! found on PATH.
        goto :java_found
    ) else (
        echo  [INFO] Java !_MAJ! on PATH is too old. Searching for Java 21+...
    )
)

:: 1b. JAVA_HOME
if defined JAVA_HOME (
    call :test_java "%JAVA_HOME%\bin\java.exe"
    if not errorlevel 1 goto :java_found
)

:: 1c. Previously auto-downloaded Java (any subfolder)
if exist "%USERPROFILE%\.jdks\automation-java" (
    for /d %%K in ("%USERPROFILE%\.jdks\automation-java\*") do (
        if exist "%%K\bin\java.exe" (
            call :test_java "%%K\bin\java.exe"
            if not errorlevel 1 (
                echo  [OK] Using previously downloaded Java !JAVA_VER_NUM!.
                goto :java_found
            )
        )
    )
)

:: 1d. Well-known IntelliJ / IDE managed JDK paths
for %%J in (
    "%USERPROFILE%\.jdks\openjdk-25\bin\java.exe"
    "%USERPROFILE%\.jdks\ms-25.0.1\bin\java.exe"
    "%USERPROFILE%\.jdks\openjdk-23.0.2\bin\java.exe"
    "%USERPROFILE%\.jdks\openjdk-23.0.1\bin\java.exe"
    "%USERPROFILE%\.jdks\temurin-21\bin\java.exe"
    "%USERPROFILE%\.jdks\openjdk-21\bin\java.exe"
) do (
    call :test_java "%%~J"
    if not errorlevel 1 goto :java_found
)

:: 1e. Scan common install directories
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
            if exist "%%K\bin\java.exe" (
                call :test_java "%%K\bin\java.exe"
                if not errorlevel 1 (
                    echo  [OK] Java %JAVA_VER_NUM% found at: %%K
                    goto :java_found
                )
            )
        )
    )
)

:: 1f. Auto-download portable Java 21 from Adoptium (official, free)
echo.
echo  [SETUP] Java 21+ not found. Downloading automatically (one-time, ~180 MB)...
echo.

set "_JDK_DEST=%USERPROFILE%\.jdks\automation-java"
if not exist "%_JDK_DEST%" mkdir "%_JDK_DEST%"
set "_JDK_ZIP=%TEMP%\jdk21_%RANDOM%%RANDOM%.zip"

%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe -NoProfile -ExecutionPolicy Bypass -Command ^
    "$ErrorActionPreference='Stop';" ^
    "$url='https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk';" ^
    "$zip='%_JDK_ZIP%';" ^
    "$dest='%_JDK_DEST%';" ^
    "Write-Host '  Downloading Java 21 from Adoptium...';" ^
    "Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing;" ^
    "Write-Host '  Extracting...';" ^
    "Expand-Archive -Path $zip -DestinationPath $dest -Force;" ^
    "Remove-Item $zip -ErrorAction SilentlyContinue;" ^
    "Write-Host '  Done!'"

if errorlevel 1 (
    echo.
    echo  [ERROR] Automatic download failed.
    echo  Please install Java 21 manually from: https://adoptium.net/
    echo  Then run this script again.
    echo.
    pause
    exit /b 1
)

for /d %%K in ("%_JDK_DEST%\*") do (
    if exist "%%K\bin\java.exe" (
        call :test_java "%%K\bin\java.exe"
        if not errorlevel 1 (
            echo  [OK] Java %JAVA_VER_NUM% downloaded and ready.
            goto :java_found
        )
    )
)

echo  [ERROR] Could not find java.exe after download. Install from https://adoptium.net/
pause
exit /b 1

:java_found
echo  [OK] Using Java %JAVA_VER_NUM%.


:: ════════════════════════════════════════════════════════════
::  STEP 2 — Maven Wrapper (bundled — no install needed)
:: ════════════════════════════════════════════════════════════
set MVN_EXE="%~dp0mvnw.cmd"
echo  [OK] Using bundled Maven Wrapper.


:: ════════════════════════════════════════════════════════════
::  STEP 3 — Build fat JAR (first run only, ~1-2 min)
:: ════════════════════════════════════════════════════════════
set JAR_PATH=%~dp0target\automation-runner.jar

if not exist "%JAR_PATH%" (
    echo.
    echo  [BUILD] First-time setup: compiling automation-runner.jar...
    echo  [BUILD] This takes 1-3 minutes. Please wait.
    echo.

    if not "%JAVA_EXE%"=="java" (
        for %%I in ("%JAVA_EXE%") do set _JAVA_BIN=%%~dpI
        set JAVA_HOME=!_JAVA_BIN:~0,-5!
    )

    call %MVN_EXE% clean package --no-transfer-progress -f "%~dp0pom.xml" 2>&1

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
    echo  [OK] automation-runner.jar is ready.
)


:: ════════════════════════════════════════════════════════════
::  STEP 4 — Install Playwright browser (first run only)
:: ════════════════════════════════════════════════════════════
set PW_CACHE=%USERPROFILE%\.cache\ms-playwright
set PW_INSTALLED=0
if exist "%PW_CACHE%" (
    for /d %%B in ("%PW_CACHE%\chromium*") do set PW_INSTALLED=1
)

if "%PW_INSTALLED%"=="0" (
    echo.
    echo  [SETUP] Installing Chromium browser for Playwright (one-time, ~150 MB)...
    echo.
    "%JAVA_EXE%" -cp "%JAR_PATH%" com.microsoft.playwright.CLI install chromium
    if errorlevel 1 (
        echo  [WARNING] Browser install may have partially failed. Continuing anyway...
    ) else (
        echo  [OK] Browser installed.
    )
) else (
    echo  [OK] Playwright browser already installed.
)


:: ════════════════════════════════════════════════════════════
::  STEP 5 — Find Excel (.xlsx) file to process
:: ════════════════════════════════════════════════════════════
echo.
echo  ── Looking for your Excel file ─────────────────────────────
set EXCEL_FILE=

:: Priority 1: dragged & dropped onto run.bat
if not "%~1"=="" (
    if exist "%~1" (
        set EXCEL_FILE=%~1
        echo  [OK] Using dragged file: %~1
        goto :excel_found
    ) else (
        echo  [WARN] Dragged file not found: %~1
    )
)

:: Priority 2: scan the data\ folder
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

:: Priority 3: fallback sample file
if exist "%~dp0test_comparisons.xlsx" (
    set EXCEL_FILE=%~dp0test_comparisons.xlsx
    echo  [OK] Using sample file: test_comparisons.xlsx
    goto :excel_found
)

echo.
echo  +----------------------------------------------------------+
echo  ^|  No Excel file found!                                    ^|
echo  ^|                                                          ^|
echo  ^|  HOW TO USE:                                             ^|
echo  ^|  1. Put your .xlsx file in the  data\  folder           ^|
echo  ^|  2. Double-click run.bat again                          ^|
echo  ^|                                                          ^|
echo  ^|  Or drag your .xlsx file directly onto run.bat           ^|
echo  ^|                                                          ^|
echo  ^|  Excel format:                                           ^|
echo  ^|    Column A = Site A URL                                 ^|
echo  ^|    Column B = Site B URL                                 ^|
echo  +----------------------------------------------------------+
echo.
pause
exit /b 1

:excel_found


:: ════════════════════════════════════════════════════════════
::  STEP 6 — Run the comparison
:: ════════════════════════════════════════════════════════════
echo.
echo  ════════════════════════════════════════════════════════════
echo    Starting comparison...
echo    Reports will be saved to:  %~dp0reports\
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
    echo  [ERROR] The tool exited with an error ^(code %EXIT_CODE%^).
    echo  Check the output above for details.
)

echo.
pause
endlocal
goto :eof

:: ════════════════════════════════════════════════════════════
::  SUBROUTINE: test_java
::  Tests one java.exe path. Sets JAVA_EXE + JAVA_VER_NUM.
::  Returns 0 if version >= 21, else 1.
:: ════════════════════════════════════════════════════════════
:test_java
    if not exist "%~1" exit /b 1
    "%~1" -version >"%TEMP%\~jver.txt" 2>&1
    set _MAJ=0
    for /f "tokens=3" %%a in ('findstr /i "version" "%TEMP%\~jver.txt"') do (
        set _V=%%~a
        for /f "delims=.+-" %%m in ("!_V!") do set /a _MAJ=%%m 2>nul
    )
    del "%TEMP%\~jver.txt" >nul 2>&1
    if !_MAJ! GEQ 21 (
        set JAVA_EXE=%~1
        set JAVA_VER_NUM=!_MAJ!
        exit /b 0
    )
    exit /b 1
