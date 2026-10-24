param(
    [Parameter(Mandatory = $true)]
    [string]$TvAddress,
    [string]$Adb = "adb"
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$Target = if ($TvAddress.Contains(":")) { $TvAddress } else { "${TvAddress}:5555" }
& $Adb connect $TvAddress | Out-Null
$TvSdk = ((& $Adb -s $Target shell getprop ro.build.version.sdk) -replace "[^0-9]", "")
if (-not $TvSdk -or [int]$TvSdk -lt 26) {
    throw "Android API 26 or newer is required; the TV reported '$TvSdk'."
}

& ./gradlew.bat --no-daemon testReleaseUnitTest lintRelease assembleRelease
if ($LASTEXITCODE -ne 0) { throw "Gradle build failed." }

$Apk = "app/build/outputs/apk/release/app-release.apk"
& $Adb -s $Target install -r $Apk
if ($LASTEXITCODE -ne 0) { throw "APK installation failed." }

& $Adb -s $Target shell am start -W -n com.gathin.tvhome/.MainActivity
& $Adb -s $Target shell cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity
$HomeNow = (& $Adb -s $Target shell cmd package resolve-activity --brief `
    -a android.intent.action.MAIN -c android.intent.category.HOME | Select-Object -Last 1).Trim()

if (-not $HomeNow.StartsWith("com.gathin.tvhome/")) {
    throw "Installed, but Home resolves to '$HomeNow'. Choose Nothing TV manually in Android TV Settings."
}

Write-Host "Installed. Home resolves to $HomeNow"
