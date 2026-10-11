param([string[]]$Tasks = @('assembleDebug','testDebugUnitTest','lintDebug'))
$ErrorActionPreference='Stop'
$inventoryRoot = Split-Path -Parent $PSScriptRoot
$inventoryTools = Join-Path $inventoryRoot '.toolchain'
$inventoryJdkDirectory = Join-Path $inventoryTools 'jdk'
if (Test-Path -LiteralPath $inventoryJdkDirectory) { $env:JAVA_HOME = (Get-ChildItem -LiteralPath $inventoryJdkDirectory -Directory | Select-Object -First 1).FullName }
if (Test-Path -LiteralPath (Join-Path $inventoryTools 'sdk')) { $env:ANDROID_HOME = Join-Path $inventoryTools 'sdk' }
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME or run tools/setup-android.ps1 first.' }
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:GRADLE_USER_HOME = Join-Path $inventoryTools 'gradle-cache'
$inventoryGradle = Join-Path $inventoryTools 'gradle\gradle-8.9\bin\gradle.bat'
Push-Location $inventoryRoot
try {
    & $inventoryGradle @Tasks --console=plain --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "Gradle exited with $LASTEXITCODE" }
} finally { Pop-Location }
