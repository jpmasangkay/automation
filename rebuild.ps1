$ErrorActionPreference = 'Continue'
$Host.UI.RawUI.WindowTitle = "Rebuild - Site Content Comparator"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

Write-Host ""
Write-Host "  ============================================================" -ForegroundColor Cyan
Write-Host "    REBUILD -- Site Content Comparator" -ForegroundColor Cyan
Write-Host "  ============================================================" -ForegroundColor Cyan
Write-Host ""

function Get-JavaMajorVersion($javaExe) {
    try {
        $out = & $javaExe -version 2>&1 | Out-String
        if ($out -match 'version "(\d+)[.\+]') { return [int]$Matches[1] }
        if ($out -match 'version "1\.(\d+)') { return [int]$Matches[1] }
    } catch {}
    return 0
}

$JavaExe = $null

if (Get-Command java -ErrorAction SilentlyContinue) {
    $ver = Get-JavaMajorVersion "java"
    if ($ver -ge 21) { 
        $JavaExe = "java"
        Write-Host "  [OK] Java $ver found on PATH." -ForegroundColor Green
    }
}

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
            $c = Join-Path $_.FullName "bin\java.exe"
            if (Test-Path $c) {
                $v = Get-JavaMajorVersion $c
                if ($v -ge 21) { return [pscustomobject]@{Exe=$c; Ver=$v} }
            }
        } | Select-Object -First 1
        if ($found) { 
            $JavaExe = $found.Exe
            Write-Host "  [OK] Java $($found.Ver) found." -ForegroundColor Green
            break 
        }
    }
}

if (-not $JavaExe) {
    Write-Host "  [ERROR] Java 21+ not found. Run run.bat first to auto-download it." -ForegroundColor Red
    Read-Host "Press Enter to exit"
    exit 1
}

if ($JavaExe -ne "java") {
    $env:JAVA_HOME = Split-Path (Split-Path $JavaExe -Parent) -Parent
    Write-Host "  [OK] JAVA_HOME set to: $($env:JAVA_HOME)" -ForegroundColor Green
}

$MvnExe = Join-Path $ScriptDir "mvnw.cmd"
Write-Host "  [OK] Maven Wrapper detected." -ForegroundColor Green
Write-Host ""
Write-Host "  [BUILD] Cleaning and rebuilding project... (Please wait)" -ForegroundColor Yellow
Write-Host ""

$buildOutput = & $MvnExe clean package --no-transfer-progress -f (Join-Path $ScriptDir "pom.xml") 2>&1

if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "  [ERROR] Build failed! Output details:" -ForegroundColor Red
    $buildOutput | Out-String | Write-Host -ForegroundColor DarkRed
    Read-Host "Press Enter to exit"
    exit 1
}

Write-Host "  [OK] Build complete! automation-runner.jar is ready in target/" -ForegroundColor Green
Write-Host "  Run run.bat to start the comparator." -ForegroundColor Cyan
Write-Host ""
Read-Host "Press Enter to exit"
