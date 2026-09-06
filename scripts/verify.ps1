[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repositoryRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repositoryRoot

try {
    if (-not $env:ANDROID_HOME) {
        $defaultAndroidSdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
        if (Test-Path -LiteralPath $defaultAndroidSdk) {
            $env:ANDROID_HOME = $defaultAndroidSdk
        }
    }

    & .\gradlew.bat --no-daemon --stacktrace clean `
        androidJvmTest `
        allMetadataJar `
        :android:app:testDebugUnitTest `
        :android:app:lintDebug `
        :android:app:assembleDebug
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle verification failed with exit code $LASTEXITCODE"
    }

    $debugApk = Join-Path $repositoryRoot "android\app\build\outputs\apk\debug\app-debug.apk"
    if (-not (Test-Path -LiteralPath $debugApk)) {
        throw "Android verification did not produce the debug APK"
    }

    $mergedManifest = Join-Path $repositoryRoot "android\app\build\intermediates\merged_manifest\debug\processDebugMainManifest\AndroidManifest.xml"
    if (-not (Test-Path -LiteralPath $mergedManifest)) {
        throw "Android verification did not produce the merged manifest"
    }
    if (Select-String -LiteralPath $mergedManifest -SimpleMatch "android.permission.INTERNET" -Quiet) {
        throw "The Android foundation must not request Internet permission"
    }

    git diff --check
    if ($LASTEXITCODE -ne 0) {
        throw "git diff --check found whitespace errors"
    }
}
finally {
    Pop-Location
}
