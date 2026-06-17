$ErrorActionPreference = 'Continue'
$Host.UI.RawUI.WindowTitle = "Rebuild - Site Content Comparator"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

Write-Host ""
Write-Host "  ============================================================"
Write-Host "    REBUILD -- Site Content Comparator"
Write-Host "  ============================================================"
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
    if ($ver -ge 21) { $JavaExe = "java"; Write-Host "  [OK] Java $ver found on PATH." }
}

if (-not $JavaExe) {
    $searchRoots = @(
        "$env:USERPROFILE\.jdks\automation-java",
        "$env:USERPROFILE\.jdks",
        "C:\Program Files\Eclipse Adoptium",
        "C:\Program Files\Microsoft",
        "C:\Program Files\Java"
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
        if ($found) { $JavaExe = $found.Exe; Write-Host "  [OK] Java $($found.Ver) found."; break }
    }
}

if (-not $JavaExe) {
    Write-Host "  [ERROR] Java 21+ not found. Run run.bat first to auto-download it."
    Read-Host "Press Enter to exit"
    exit 1
}

if ($JavaExe -ne "java") {
    $env:JAVA_HOME = Split-Path (Split-Path $JavaExe -Parent) -Parent
    Write-Host "  [OK] JAVA_HOME set to: $($env:JAVA_HOME)"
}

$MvnExe = Join-Path $ScriptDir "mvnw.cmd"
Write-Host "  [OK] Using bundled Maven Wrapper."
Write-Host ""
Write-Host "  Cleaning and rebuilding automation-runner.jar..."
Write-Host ""

& $MvnExe clean package --no-transfer-progress -f (Join-Path $ScriptDir "pom.xml")

if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "  [ERROR] Build failed. See output above."
    Read-Host "Press Enter to exit"
    exit 1
}

Write-Host ""
Write-Host "  [OK] Build complete! automation-runner.jar is ready in target\"
Write-Host "  Run run.bat to start the comparator."
Write-Host ""
Read-Host "Press Enter to exit"
