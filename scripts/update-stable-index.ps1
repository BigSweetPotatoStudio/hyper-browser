param(
    [Parameter(Mandatory = $true)]
    [int] $VersionCode,

    [Parameter(Mandatory = $true)]
    [string] $VersionName,

    [Parameter(Mandatory = $true)]
    [string] $Notes,

    [string] $Tag = "v$VersionName",
    [string] $Repository = "BigSweetPotatoStudio/hyper-browser",
    [string] $SignedApkDir = "app/build/outputs/apk/release/signed",
    [string] $OutputPath = "update/stable.json",
    [int] $MinSdk = 26
)

$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$apkDir = Join-Path $root $SignedApkDir
$outputFile = Join-Path $root $OutputPath
$abis = @("arm64-v8a", "armeabi-v7a", "x86_64")

if (-not (Test-Path -LiteralPath $apkDir)) {
    throw "Signed APK directory does not exist: $apkDir"
}

$assets = foreach ($abi in $abis) {
    $apkName = "HyperBrowser-$abi-release.apk"
    $apkPath = Join-Path $apkDir $apkName
    if (-not (Test-Path -LiteralPath $apkPath)) {
        throw "Missing signed APK for $abi: $apkPath"
    }

    $item = Get-Item -LiteralPath $apkPath
    [ordered] @{
        abi = $abi
        url = "https://github.com/$Repository/releases/download/$Tag/$apkName"
        sha256 = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
        sizeBytes = $item.Length
    }
}

$index = [ordered] @{
    channel = "stable"
    versionCode = $VersionCode
    versionName = $VersionName
    minSdk = $MinSdk
    notes = $Notes
    releaseUrl = "https://github.com/$Repository/releases/tag/$Tag"
    assets = $assets
}

$json = $index | ConvertTo-Json -Depth 8
Set-Content -LiteralPath $outputFile -Value $json -Encoding UTF8
Write-Host "Updated $OutputPath for $Tag with $($assets.Count) APK assets."
