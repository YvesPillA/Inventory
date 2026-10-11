$ErrorActionPreference = 'Stop'
$inventoryRoot = Split-Path -Parent $PSScriptRoot
$inventoryTools = Join-Path $inventoryRoot '.toolchain'
$inventoryDownloads = Join-Path $inventoryTools 'downloads'
New-Item -ItemType Directory -Force -Path $inventoryDownloads | Out-Null
$inventoryAssets = Invoke-RestMethod -Uri 'https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse'
$inventoryJdk = ($inventoryAssets | Select-Object -First 1).binary.package
$inventoryPackages = @(
    @{ Name='jdk.zip'; Url=$inventoryJdk.link; Hash=$inventoryJdk.checksum; Destination='jdk' },
    @{ Name='cmdtools.zip'; Url='https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip'; Hash='90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a'; Destination='cmdtools' },
    @{ Name='gradle.zip'; Url='https://services.gradle.org/distributions/gradle-8.9-bin.zip'; Hash=(Invoke-RestMethod -Uri 'https://services.gradle.org/distributions/gradle-8.9-bin.zip.sha256').Trim(); Destination='gradle' }
)
foreach ($inventoryPackage in $inventoryPackages) {
    $inventoryArchive = Join-Path $inventoryDownloads $inventoryPackage.Name
    if (-not (Test-Path -LiteralPath $inventoryArchive)) {
        Write-Output "Downloading $($inventoryPackage.Name)"
        & curl.exe --fail --location --retry 3 --silent --show-error --output $inventoryArchive $inventoryPackage.Url
        if ($LASTEXITCODE -ne 0) { throw "Download failed: $($inventoryPackage.Name)" }
    }
    if ((Get-FileHash -LiteralPath $inventoryArchive -Algorithm SHA256).Hash -ne $inventoryPackage.Hash) { throw "Checksum mismatch: $($inventoryPackage.Name)" }
    $inventoryDestination = Join-Path $inventoryTools $inventoryPackage.Destination
    if (-not (Test-Path -LiteralPath $inventoryDestination)) {
        Write-Output "Extracting $($inventoryPackage.Name)"
        Expand-Archive -LiteralPath $inventoryArchive -DestinationPath $inventoryDestination
    }
}
$env:JAVA_HOME = (Get-ChildItem -LiteralPath (Join-Path $inventoryTools 'jdk') -Directory | Select-Object -First 1).FullName
$env:ANDROID_HOME = Join-Path $inventoryTools 'sdk'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
New-Item -ItemType Directory -Force -Path $env:ANDROID_HOME | Out-Null
$inventorySdkCmdlineRoot = Join-Path $env:ANDROID_HOME 'cmdline-tools'
New-Item -ItemType Directory -Force -Path $inventorySdkCmdlineRoot | Out-Null
if (-not (Test-Path -LiteralPath (Join-Path $inventorySdkCmdlineRoot 'latest'))) {
    Copy-Item -LiteralPath (Join-Path $inventoryTools 'cmdtools\cmdline-tools') -Destination (Join-Path $inventorySdkCmdlineRoot 'latest') -Recurse
}
$inventorySdkManager = Join-Path $inventorySdkCmdlineRoot 'latest\bin\sdkmanager.bat'
Write-Output 'Installing Android SDK 35 and build tools'
('y' + [Environment]::NewLine) * 100 | & $inventorySdkManager --sdk_root=$env:ANDROID_HOME --licenses *> (Join-Path $inventoryTools 'sdk-licenses.log')
if ($LASTEXITCODE -ne 0) { throw 'SDK license setup failed; see .toolchain/sdk-licenses.log' }
& $inventorySdkManager --sdk_root=$env:ANDROID_HOME 'platform-tools' 'platforms;android-35' 'build-tools;34.0.0' *> (Join-Path $inventoryTools 'sdk-install.log')
if ($LASTEXITCODE -ne 0) { throw 'SDK install failed; see .toolchain/sdk-install.log' }
('sdk.dir=' + ($env:ANDROID_HOME -replace '\\','/')) | Set-Content -LiteralPath (Join-Path $inventoryRoot 'local.properties') -Encoding utf8
Write-Output 'Android toolchain ready.'
