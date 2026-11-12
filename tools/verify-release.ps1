[CmdletBinding()]
param(
    [string]$HostApk = $env:DOUAI_TEST_APK,
    [string]$SdkRoot = $env:ANDROID_HOME,
    [string]$Python = 'python',
    [string]$OutputRoot = ''
)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$verifier = Join-Path $PSScriptRoot 'release_verification.py'
if (-not $HostApk -or -not (Test-Path -LiteralPath $HostApk -PathType Leaf)) {
    throw 'Strict release verification requires -HostApk (or DOUAI_TEST_APK) pointing to the original host APK.'
}
$HostApk = (Resolve-Path -LiteralPath $HostApk).Path
if (-not $SdkRoot) { $SdkRoot = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$buildToolsRoot = Join-Path $SdkRoot 'build-tools'
$buildTools = Get-ChildItem -LiteralPath $buildToolsRoot -Directory |
    Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (-not $buildTools) { throw "Android build-tools not found in $buildToolsRoot" }
$aapt = Join-Path $buildTools.FullName 'aapt.exe'
$apksigner = Join-Path $buildTools.FullName 'apksigner.bat'
$zipalign = Join-Path $buildTools.FullName 'zipalign.exe'
foreach ($toolPath in @($aapt, $apksigner, $zipalign)) {
    if (-not (Test-Path -LiteralPath $toolPath -PathType Leaf)) { throw "Missing Android tool: $toolPath" }
}
Get-Command $Python -ErrorAction Stop | Out-Null
if (-not $OutputRoot) { $OutputRoot = Join-Path $projectRoot 'artifacts/release-verification' }
$OutputRoot = [IO.Path]::GetFullPath($OutputRoot)
$runName = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$runDir = Join-Path $OutputRoot $runName
New-Item -ItemType Directory -Path $runDir | Out-Null
$reportsDir = Join-Path $runDir 'reports'
New-Item -ItemType Directory -Path $reportsDir | Out-Null
$previousDirectory = Get-Location
$previousAndroidHome = $env:ANDROID_HOME
$previousHostApk = $env:DOUAI_TEST_APK
$previousReleaseApk = $env:DOUAI_VERIFY_RELEASE_APK

function Invoke-Checked {
    param([string]$Executable, [string[]]$Arguments, [string]$LogPath)
    $savedPreference = $ErrorActionPreference
    try {
        # Windows PowerShell turns native stderr into non-terminating error records.
        $ErrorActionPreference = 'Continue'
        if ($LogPath) { & $Executable @Arguments 2>&1 | Tee-Object -FilePath $LogPath | Out-Host }
        else { & $Executable @Arguments 2>&1 | Out-Host }
        $commandExitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedPreference }
    if ($commandExitCode -ne 0) { throw "Command failed with exit code $commandExitCode ($Executable). See verification logs." }
}

function Save-Reports {
    $junit = Join-Path $projectRoot 'app/build/test-results/testDebugUnitTest'
    if (Test-Path -LiteralPath $junit) { Copy-Item -LiteralPath $junit -Destination (Join-Path $reportsDir 'junit') -Recurse }
    $html = Join-Path $projectRoot 'app/build/reports/tests/testDebugUnitTest'
    if (Test-Path -LiteralPath $html) { Copy-Item -LiteralPath $html -Destination (Join-Path $reportsDir 'tests-html') -Recurse }
    foreach ($suffix in @('xml', 'html', 'txt')) {
        $lint = Join-Path $projectRoot "app/build/reports/lint-results-release.$suffix"
        if (Test-Path -LiteralPath $lint) { Copy-Item -LiteralPath $lint -Destination $reportsDir }
    }
    $mapping = Join-Path $projectRoot 'app/build/outputs/mapping/release'
    if (Test-Path -LiteralPath $mapping) { Copy-Item -LiteralPath $mapping -Destination (Join-Path $reportsDir 'mapping') -Recurse }
    $profile = Join-Path $projectRoot 'app/build/reports/release-verification/test-profile.json'
    if (Test-Path -LiteralPath $profile) { Copy-Item -LiteralPath $profile -Destination (Join-Path $runDir 'test-profile.json') }
}

function Save-HostBadging {
    param([string]$OriginalApk, [string]$ExpectedSha256, [string]$Aapt, [string]$RunDirectory)
    # Windows aapt cannot reliably open non-ASCII APK filenames. Keep the original
    # path for Java contracts and the manifest; this copy is only a tool input.
    $temporaryHostApk = Join-Path $RunDirectory 'host-badging-input.apk'
    try {
        Copy-Item -LiteralPath $OriginalApk -Destination $temporaryHostApk
        $copiedSha256 = (Get-FileHash -LiteralPath $temporaryHostApk -Algorithm SHA256).Hash
        if ($copiedSha256 -ne $ExpectedSha256) { throw 'Host badging copy differs from the locked original host APK.' }
        $hostOutput = & $Aapt dump badging $temporaryHostApk
        if ($LASTEXITCODE -ne 0) { throw 'aapt failed for host APK.' }
        $hostOutput | Set-Content -LiteralPath (Join-Path $RunDirectory 'host-badging.txt') -Encoding UTF8
    } finally {
        if (Test-Path -LiteralPath $temporaryHostApk -PathType Leaf) {
            Remove-Item -LiteralPath $temporaryHostApk -Force
        }
    }
}

try {
    Set-Location -LiteralPath $projectRoot
    $env:ANDROID_HOME = $SdkRoot
    $env:DOUAI_TEST_APK = $HostApk
    Invoke-Checked $Python @('-B', $verifier, 'prepare', '--root', $projectRoot, '--host-apk', $HostApk, '--run-dir', $runDir)
    $inputs = Get-Content -LiteralPath (Join-Path $runDir 'inputs.json') -Raw | ConvertFrom-Json
    $versionName = $inputs.identity.versionName
    # Desktop JVM regex accepts syntax rejected by Android's ICU engine.
    Invoke-Checked $Python @('-B', (Join-Path $PSScriptRoot 'verify_config_secrets_icu.py'), '--output', (Join-Path $runDir 'android-icu-placeholder.json')) (Join-Path $runDir 'android-icu-placeholder.log')
    $gradle = Join-Path $projectRoot 'gradlew.bat'
    $gradleArgs = @('-PreleaseVerification=true', '-Dorg.gradle.jvmargs=-Xmx768m -Dfile.encoding=UTF-8', '--max-workers=1', '--console=plain')
    Invoke-Checked $gradle (@(':app:assembleRelease') + $gradleArgs) (Join-Path $runDir 'assemble.log')
    $buildApk = Join-Path $projectRoot 'app/build/outputs/apk/release/app-release.apk'
    if (-not (Test-Path -LiteralPath $buildApk -PathType Leaf)) { throw 'Signed Release APK was not produced. Configure all four local signing settings.' }
    $archive = Join-Path $runDir "DouAi-$versionName-release.apk"
    Copy-Item -LiteralPath $buildApk -Destination $archive
    $packageOutput = & $aapt dump badging $archive
    if ($LASTEXITCODE -ne 0) { throw 'aapt failed for final APK.' }
    $packageOutput | Set-Content -LiteralPath (Join-Path $runDir 'apk-badging.txt') -Encoding UTF8
    Save-HostBadging $HostApk $inputs.hostApk.sha256 $aapt $runDir
    $signatureOutput = & $apksigner verify --verbose --print-certs $archive
    if ($LASTEXITCODE -ne 0) { throw 'Final APK signature verification failed.' }
    $signatureOutput | Set-Content -LiteralPath (Join-Path $runDir 'apk-signature.txt') -Encoding UTF8
    Invoke-Checked $zipalign @('-c', '-P', '16', '-v', '4', $archive) (Join-Path $runDir 'apk-alignment.txt')
    @{ command = @('zipalign', '-c', '-P', '16', '-v', '4');
       apkSha256 = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash;
       exitCode = 0 } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runDir 'apk-alignment.json') -Encoding UTF8
    Invoke-Checked $Python @('-B', $verifier, 'seal', '--root', $projectRoot, '--run-dir', $runDir, '--apk', $archive, '--build-apk', $buildApk)
    $env:DOUAI_VERIFY_RELEASE_APK = $archive
    try {
        # One full run checks the exact signed archive; --rerun-tasks prevents reused JUnit evidence.
        Invoke-Checked $gradle (@(':app:testDebugUnitTest', ':app:lintRelease', '--rerun-tasks') + $gradleArgs) (Join-Path $runDir 'verification.log')
    } finally { Save-Reports }
    Invoke-Checked $Python @('-B', $verifier, 'finish', '--root', $projectRoot, '--run-dir', $runDir)
    Write-Output "Local release verification complete: $(Join-Path $runDir 'manifest.json')"
} catch {
    @{status='failed'; failedAtUtc=(Get-Date).ToUniversalTime().ToString('o'); reason=$_.Exception.Message} |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runDir 'failure.json') -Encoding UTF8
    throw
} finally {
    $env:ANDROID_HOME = $previousAndroidHome
    $env:DOUAI_TEST_APK = $previousHostApk
    $env:DOUAI_VERIFY_RELEASE_APK = $previousReleaseApk
    Set-Location -LiteralPath $previousDirectory.Path
}
