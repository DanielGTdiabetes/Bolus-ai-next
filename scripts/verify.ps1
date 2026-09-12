[CmdletBinding()]
param([switch]$DeviceTests)

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
        :android:app:assembleDebug `
        :android:app:assembleDebugAndroidTest `
        :android:app:processReleaseMainManifest `
        :android:sender-fixture:assembleDebug `
        :android:sender-fixture:lintDebug
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
    if (Select-String -LiteralPath $mergedManifest -Pattern '<receiver\b|com\.dexcom\.' -Quiet) {
        throw "The preparatory Android foundation must not register receivers or Dexcom permissions"
    }
    $releaseManifest = Join-Path $repositoryRoot "android\app\build\intermediates\merged_manifest\release\processReleaseMainManifest\AndroidManifest.xml"
    if (-not (Test-Path -LiteralPath $releaseManifest) -or
        (Select-String -LiteralPath $releaseManifest -Pattern 'senderfixture|android.permission.INTERNET|<receiver\b|com\.dexcom\.' -Quiet)) {
        throw "Release manifest must stay isolated from fixtures, receivers and clinical/network permissions"
    }

    $workflowDirectory = Join-Path $repositoryRoot ".github\workflows"
    $canonicalWorkflow = Join-Path $workflowDirectory "verify.yml"
    if (-not (Test-Path -LiteralPath $canonicalWorkflow -PathType Leaf)) {
        throw "The canonical Windows verification workflow is missing"
    }
    $canonicalWorkflowContent = Get-Content -Raw -LiteralPath $canonicalWorkflow
    if ([string]::IsNullOrWhiteSpace($canonicalWorkflowContent)) {
        throw "The canonical Windows verification workflow is empty"
    }

    $expectedCanonicalWorkflow = @'
name: Verify

on:
  pull_request:
  push:
    branches:
      - main

permissions:
  contents: read

jobs:
  windows:
    name: Windows verification
    runs-on: windows-latest
    steps:
      - uses: actions/checkout@v5
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: "21"
      - uses: gradle/actions/setup-gradle@v6
      - name: Verify
        shell: pwsh
        run: .\scripts\verify.ps1
'@
    $normalizedWorkflow = ($canonicalWorkflowContent -replace "`r`n", "`n").Trim()
    $normalizedExpectedWorkflow = ($expectedCanonicalWorkflow -replace "`r`n", "`n").Trim()
    if ($normalizedWorkflow -cne $normalizedExpectedWorkflow) {
        throw "The canonical Windows verification workflow differs from the approved executable template"
    }

    $workflowFiles = @(
        Get-ChildItem -LiteralPath $workflowDirectory -File |
            Where-Object { $_.Extension -in @(".yml", ".yaml") }
    )
    if ($workflowFiles.Count -eq 0) {
        throw "No GitHub workflow files were found"
    }
    $forbiddenAutomaticIosPatterns = @(
        "(?i)macos",
        "(?i)iosSimulatorArm64Test",
        "(?i)linkDebugFrameworkIosSimulatorArm64",
        "(?i)verify-swift\.sh"
    )
    foreach ($workflowFile in $workflowFiles) {
        foreach ($pattern in $forbiddenAutomaticIosPatterns) {
            if (Select-String -LiteralPath $workflowFile.FullName -Pattern $pattern -Quiet) {
                throw "Automatic GitHub macOS/iOS verification is disabled by ADR 0004: $($workflowFile.Name) matches $pattern"
            }
        }
    }

    git diff --check
    if ($LASTEXITCODE -ne 0) {
        throw "git diff --check found whitespace errors"
    }

    if ($DeviceTests) {
        # Explicit opt-in: installs Next plus disposable synthetic test APKs only.
        $deviceRows = @(adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\S+\s+(device|offline|unauthorized)$' })
        if ($deviceRows.Count -ne 1 -or $deviceRows[0] -notmatch '\sdevice$') {
            throw "Device tests require exactly one authorized USB device"
        }
        $deviceApi = (adb shell getprop ro.build.version.sdk).Trim()
        if ($LASTEXITCODE -ne 0 -or $deviceApi -notmatch '^\d+$' -or [int]$deviceApi -lt 34) {
            throw "Cross-UID identity sharing tests require Android API 34 or newer"
        }
        $fixturePackage = "org.bolusai.next.senderfixture"
        $testPackage = "org.bolusai.next.test"
        foreach ($package in @($fixturePackage, $testPackage)) {
            $existingPackage = @(adb shell pm path $package)
            if ($existingPackage -match '^package:') {
                throw "Disposable test package already exists; refusing to overwrite or remove it: $package"
            }
        }
        $installedTestPackages = [System.Collections.Generic.List[string]]::new()
        try {
            adb install -r $debugApk
            if ($LASTEXITCODE -ne 0) { throw "Next APK installation failed" }
            $testApks = @(
                @{ Package = $fixturePackage; Path = "android\sender-fixture\build\outputs\apk\debug\sender-fixture-debug.apk" },
                @{ Package = $testPackage; Path = "android\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk" }
            )
            foreach ($testApk in $testApks) {
                adb install -t (Join-Path $repositoryRoot $testApk.Path)
                if ($LASTEXITCODE -ne 0) { throw "Synthetic test APK installation failed" }
                $installedTestPackages.Add($testApk.Package)
            }
            # Direct instrumentation avoids collecting unrelated device logcat or clinical data.
            $instrumentation = @(adb shell am instrument -w -r `
                -e class org.bolusai.next.glucose.dexcom.AndroidDexcomSenderEvidenceTest `
                org.bolusai.next.test/androidx.test.runner.AndroidJUnitRunner)
            $instrumentationExit = $LASTEXITCODE
            $instrumentation | Write-Output
            $instrumentationText = $instrumentation -join "`n"
            if ($instrumentationExit -ne 0 -or $instrumentationText -notmatch 'OK \([1-9]\d* tests?\)' -or
                $instrumentationText -match 'FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg=|INSTRUMENTATION_STATUS_CODE: -[1-4]') {
                throw "Android sender instrumentation failed or did not complete"
            }
        }
        finally {
            $cleanupFailures = [System.Collections.Generic.List[string]]::new()
            foreach ($package in $installedTestPackages) {
                adb uninstall $package
                if ($LASTEXITCODE -ne 0) { $cleanupFailures.Add($package) }
            }
            if ($cleanupFailures.Count -gt 0) {
                throw "Could not remove disposable test packages: $($cleanupFailures -join ', ')"
            }
        }
    }
}
finally {
    Pop-Location
}
