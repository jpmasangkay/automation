$ErrorActionPreference = 'Continue'
$Host.UI.RawUI.WindowTitle = "Site Content Comparator"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

Write-Host ""
Write-Host "  ============================================================"
Write-Host "    SITE CONTENT COMPARATOR"
Write-Host "    Automated Web Page Comparison Tool"
Write-Host "  ============================================================"
Write-Host ""

# ============================================================
#  STEP 1 -- Find or auto-download Java 21+
# ============================================================

function Get-JavaMajorVersion($javaExe) {
    try {
        $out = & $javaExe -version 2>&1 | Out-String
        # Java 9+: openjdk version "21.0.2" ...
        if ($out -match 'version "(\d+)[.\+]') { return [int]$Matches[1] }
        # Java 8: java version "1.8.0_xxx"
        if ($out -match 'version "1\.(\d+)') { return [int]$Matches[1] }
    } catch {}
    return 0
}

$JavaExe = $null

# 1a. java on PATH
if (Get-Command java -ErrorAction SilentlyContinue) {
    $ver = Get-JavaMajorVersion "java"
    if ($ver -ge 21) {
        $JavaExe = "java"
        Write-Host "  [OK] Java $ver found on PATH."
    } else {
        Write-Host "  [INFO] Java $ver on PATH is too old (need 21+). Searching..."
    }
}

# 1b. JAVA_HOME
if (-not $JavaExe -and $env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME "bin\java.exe"
    if (Test-Path $candidate) {
        $ver = Get-JavaMajorVersion $candidate
        if ($ver -ge 21) { $JavaExe = $candidate; Write-Host "  [OK] Java $ver found via JAVA_HOME." }
    }
}

# 1c. Scan well-known locations
if (-not $JavaExe) {
    $searchRoots = @(
        "$env:USERPROFILE\.jdks\automation-java",
        "$env:USERPROFILE\.jdks",
        "C:\Program Files\Eclipse Adoptium",
        "C:\Program Files\Microsoft",
        "C:\Program Files\Java",
        "C:\Program Files\BellSoft",
        "C:\Program Files\Amazon Corretto",
        "C:\Program Files\Azul Systems",
        "C:\tools\jdk",
        "C:\tools\java",
        "D:\Java",
        "D:\Tools\jdk"
    )
    foreach ($root in $searchRoots) {
        if (-not (Test-Path $root)) { continue }
        $found = Get-ChildItem $root -Directory | ForEach-Object {
            $candidate = Join-Path $_.FullName "bin\java.exe"
            if (Test-Path $candidate) {
                $ver = Get-JavaMajorVersion $candidate
                if ($ver -ge 21) { return [pscustomobject]@{Exe=$candidate; Ver=$ver; Dir=$_.FullName} }
            }
        } | Select-Object -First 1
        if ($found) {
            $JavaExe = $found.Exe
            Write-Host "  [OK] Java $($found.Ver) found at: $($found.Dir)"
            break
        }
    }
}

# 1d. Auto-download Java 21 from Adoptium
if (-not $JavaExe) {
    Write-Host ""
    Write-Host "  [SETUP] Java 21+ not found. Downloading automatically (~180 MB)..."
    Write-Host "  [SETUP] This is a one-time download. Please wait..."
    Write-Host ""

    $jdkDest = "$env:USERPROFILE\.jdks\automation-java"
    if (-not (Test-Path $jdkDest)) { New-Item -ItemType Directory -Path $jdkDest -Force | Out-Null }
    $jdkZip = [System.IO.Path]::Combine([System.IO.Path]::GetTempPath(), "jdk21_$(Get-Random).zip")
    $jdkUrl  = "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk"

    try {
        Write-Host "  Downloading Java 21..."
        Invoke-WebRequest -Uri $jdkUrl -OutFile $jdkZip -UseBasicParsing
        Write-Host "  Extracting..."
        Expand-Archive -Path $jdkZip -DestinationPath $jdkDest -Force
        Remove-Item $jdkZip -ErrorAction SilentlyContinue
        Write-Host "  Done!"

        $found = Get-ChildItem $jdkDest -Directory | ForEach-Object {
            $candidate = Join-Path $_.FullName "bin\java.exe"
            if (Test-Path $candidate) {
                $ver = Get-JavaMajorVersion $candidate
                if ($ver -ge 21) { return [pscustomobject]@{Exe=$candidate; Ver=$ver} }
            }
        } | Select-Object -First 1

        if ($found) {
            $JavaExe = $found.Exe
            Write-Host "  [OK] Java $($found.Ver) downloaded and ready."
        }
    } catch {
        Write-Host ""
        Write-Host "  [ERROR] Download failed: $_"
        Write-Host "  Please install Java 21 manually from: https://adoptium.net/"
        Write-Host "  Then run this script again."
        Write-Host ""
        Read-Host "Press Enter to exit"
        exit 1
    }
}

if (-not $JavaExe) {
    Write-Host "  [ERROR] Java 21+ could not be found or installed."
    Write-Host "  Please install from: https://adoptium.net/"
    Read-Host "Press Enter to exit"
    exit 1
}

Write-Host "  [OK] Using Java $(Get-JavaMajorVersion $JavaExe)."

# ============================================================
#  STEP 2 -- Maven Wrapper (bundled, no install needed)
# ============================================================
$MvnExe = Join-Path $ScriptDir "mvnw.cmd"
Write-Host "  [OK] Using bundled Maven Wrapper."

# ============================================================
#  STEP 3 -- Build fat JAR (first run only, ~1-2 min)
# ============================================================
$JarPath = Join-Path $ScriptDir "target\automation-runner.jar"

if (-not (Test-Path $JarPath)) {
    Write-Host ""
    Write-Host "  [BUILD] First-time setup: compiling automation-runner.jar..."
    Write-Host "  [BUILD] This takes 1-3 minutes. Please wait."
    Write-Host ""

    # Set JAVA_HOME so Maven uses the right JDK
    if ($JavaExe -ne "java") {
        $env:JAVA_HOME = Split-Path (Split-Path $JavaExe -Parent) -Parent
    }

    $env:MAVEN_ARGS="--no-transfer-progress"
    & $MvnExe clean package --no-transfer-progress -f (Join-Path $ScriptDir "pom.xml")

    if ($LASTEXITCODE -ne 0) {
        Write-Host ""
        Write-Host "  [ERROR] Build failed. See output above for details."
        Read-Host "Press Enter to exit"
        exit 1
    }
    Write-Host ""
    Write-Host "  [OK] Build complete!"
} else {
    Write-Host "  [OK] automation-runner.jar is ready."
}

# ============================================================
#  STEP 4 -- Install Playwright Chromium (first run only)
# ============================================================
$pwCache     = if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA "ms-playwright" } else { "$env:USERPROFILE\.cache\ms-playwright" }
$chromiumDir = Get-ChildItem $pwCache -Directory -Filter "chromium*" -ErrorAction SilentlyContinue | Select-Object -First 1

if (-not $chromiumDir) {
    Write-Host ""
    Write-Host "  [SETUP] Installing Chromium browser for Playwright (one-time, ~150 MB)..."
    Write-Host ""
    & $JavaExe -cp $JarPath com.microsoft.playwright.CLI install chromium
    if ($LASTEXITCODE -eq 0) {
        Write-Host "  [OK] Browser installed."
    } else {
        Write-Host "  [WARNING] Browser install may have partially failed. Continuing..."
    }
} else {
    Write-Host "  [OK] Playwright browser already installed."
}

# ============================================================
#  STEP 5 -- Find Excel (.xlsx) file
# ============================================================
Write-Host ""
Write-Host "  --- Looking for your Excel file ---"
$ExcelFile = $null

# Priority 1: dragged onto bat/ps1
if ($args.Count -gt 0 -and (Test-Path $args[0])) {
    $ExcelFile = $args[0]
    Write-Host "  [OK] Using: $ExcelFile"
}

# Priority 2: data\ folder
if (-not $ExcelFile) {
    $dataDir = Join-Path $ScriptDir "data"
    if (Test-Path $dataDir) {
        $xlsx = Get-ChildItem $dataDir -Filter "*.xlsx"
        if ($xlsx.Count -eq 1) {
            $ExcelFile = $xlsx[0].FullName
            Write-Host "  [OK] Auto-detected: $($xlsx[0].Name)"
        } elseif ($xlsx.Count -gt 1) {
            Write-Host ""
            Write-Host "  Multiple Excel files found. Which one to use?"
            for ($i = 0; $i -lt $xlsx.Count; $i++) {
                Write-Host "    [$($i+1)] $($xlsx[$i].Name)"
            }
            $choice = Read-Host "  Enter number"
            $idx = [int]$choice - 1
            if ($idx -ge 0 -and $idx -lt $xlsx.Count) {
                $ExcelFile = $xlsx[$idx].FullName
                Write-Host "  [OK] Selected: $($xlsx[$idx].Name)"
            }
        }
    }
}

# Priority 3: fallback sample file
if (-not $ExcelFile) {
    $fallback = Join-Path $ScriptDir "test_comparisons.xlsx"
    if (Test-Path $fallback) {
        $ExcelFile = $fallback
        Write-Host "  [OK] Using sample file: test_comparisons.xlsx"
    }
}

if (-not $ExcelFile) {
    Write-Host ""
    Write-Host "  +----------------------------------------------------------+"
    Write-Host "  |  No Excel file found!                                    |"
    Write-Host "  |                                                          |"
    Write-Host "  |  HOW TO USE:                                             |"
    Write-Host "  |  1. Put your .xlsx file in the  data\  folder           |"
    Write-Host "  |  2. Double-click run.bat again                          |"
    Write-Host "  |                                                          |"
    Write-Host "  |  Or drag your .xlsx file directly onto run.bat           |"
    Write-Host "  |                                                          |"
    Write-Host "  |  Excel format:                                           |"
    Write-Host "  |    Column A = Site A URL                                 |"
    Write-Host "  |    Column B = Site B URL                                 |"
    Write-Host "  +----------------------------------------------------------+"
    Write-Host ""
    Read-Host "Press Enter to exit"
    exit 1
}

# ============================================================
#  STEP 6 -- Run the comparison
# ============================================================
Write-Host ""
Write-Host "  ============================================================"
Write-Host "    Starting comparison..."
Write-Host "    Reports will be saved to: $(Join-Path $ScriptDir 'reports')"
Write-Host "  ============================================================"
Write-Host ""

& $JavaExe -jar $JarPath $ExcelFile
$exitCode = $LASTEXITCODE

Write-Host ""
if ($exitCode -eq 0) {
    Write-Host "  ============================================================"
    Write-Host "    DONE!  PDF reports saved to:"
    Write-Host "    $(Join-Path $ScriptDir 'reports')"
    Write-Host "  ============================================================"
    Write-Host ""
    Write-Host "  Opening reports folder..."
    $reportsDir = Join-Path $ScriptDir "reports"
    if (-not (Test-Path $reportsDir)) { New-Item -ItemType Directory $reportsDir -Force | Out-Null }
    Start-Process $reportsDir
} else {
    Write-Host "  [ERROR] The tool exited with an error (code $exitCode)."
    Write-Host "  Check the output above for details."
}

Write-Host ""
Read-Host "Press Enter to exit"
