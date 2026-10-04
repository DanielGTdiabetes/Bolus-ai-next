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
    foreach ($manifest in @($mergedManifest, $releaseManifest)) {
        $manifestText = Get-Content -Raw -LiteralPath $manifest
        if ($manifestText -notmatch 'android:allowBackup="false"' -or
            $manifestText -notmatch 'android:fullBackupContent="false"' -or
            $manifestText -notmatch 'android:dataExtractionRules="@xml/data_extraction_rules"') {
            throw "Local clinical data must stay excluded from Android backup and device transfer (ADR 0012)"
        }
    }
    [xml]$extractionRules = Get-Content -Raw -LiteralPath (Join-Path $repositoryRoot "android\app\src\main\res\xml\data_extraction_rules.xml")
    foreach ($section in @("cloud-backup", "device-transfer")) {
        $databaseExclusions = @($extractionRules.'data-extraction-rules'.$section.exclude |
            Where-Object { $_.domain -in @("database", "device_database") -and $_.path -eq "." })
        if ($databaseExclusions.Count -ne 2) {
            throw "Database files must stay excluded from $section (ADR 0012)"
        }
    }

    # ADR 0012: the clinical profile is capture only. The engine and the Bolo overview must not depend on it.
    $engineReferences = @(Get-ChildItem -LiteralPath (Join-Path $repositoryRoot "shared\bolus-engine") -Recurse -File |
        Where-Object { $_.FullName -notmatch '\\build\\' } |
        Select-String -Pattern 'clinical-profile|profile-unavailability|org\.bolusai\.profile' -List)
    if ($engineReferences.Count -gt 0) {
        throw "The bolus engine must not depend on the clinical profile: $($engineReferences.Path -join ', ')"
    }
    # ADR 0015, D8: the translator ProfileGateState -> contract v2 depends on the profile and the contract, never the
    # other way round, and no consumer is connected to it yet.
    $profileReferences = @(Get-ChildItem -LiteralPath (Join-Path $repositoryRoot "shared\clinical-profile") -Recurse -File |
        Where-Object { $_.FullName -notmatch '\\build\\' } |
        Select-String -Pattern 'bolus-engine|org\.bolusai\.engine|profile-unavailability|org\.bolusai\.profileunavailability' -List)
    if ($profileReferences.Count -gt 0) {
        throw "The clinical profile must not depend on the contract or its translator: $($profileReferences.Path -join ', ')"
    }
    # ADR 0016, option A, and ADR 0017: only the profile screen and the blocking details of Bolo and Diagnostico (their
    # model, their pure blocks, the screen and their tests) may use the translator. ReadOverview, ScreenRenderer, the
    # engine and any other Android source stay without it.
    $allowedTranslatorConsumers = @(
        "android\app\build.gradle.kts",
        "android\app\src\main\java\org\bolusai\next\ui\ProfileUnavailabilityBlock.kt",
        "android\app\src\main\java\org\bolusai\next\ui\BlockingDetails.kt",
        "android\app\src\main\java\org\bolusai\next\ui\ClinicalProfileModel.kt",
        "android\app\src\main\java\org\bolusai\next\ui\ClinicalProfileScreen.kt",
        "android\app\src\test\java\org\bolusai\next\ProfileUnavailabilityBlockTest.kt",
        "android\app\src\test\java\org\bolusai\next\BlockingDetailsTest.kt",
        "android\app\src\androidTest\java\org\bolusai\next\ClinicalProfileUnavailabilityDeviceTest.kt",
        "android\app\src\androidTest\java\org\bolusai\next\BolusProfileDetailsDeviceTest.kt"
    ) | ForEach-Object { Join-Path $repositoryRoot $_ }
    $translatorConsumers = @(Get-ChildItem -LiteralPath (Join-Path $repositoryRoot "android"), (Join-Path $repositoryRoot "shared\meal-drafts") -Recurse -File |
        Where-Object { $_.FullName -notmatch '\\build\\' -and $_.Extension -in @(".kt", ".kts", ".java", ".xml") } |
        Where-Object { $allowedTranslatorConsumers -notcontains $_.FullName } |
        Select-String -Pattern 'profile-unavailability|org\.bolusai\.profileunavailability|ProfileUnavailability' -List)
    if ($translatorConsumers.Count -gt 0) {
        throw "Only the profile screen and the blocking details may use the profile unavailability translator (ADR 0016, ADR 0017): $($translatorConsumers.Path -join ', ')"
    }
    # ADR 0017, section 14: only Ajustes -> Calculo and the destinations that show the profile report start profile
    # reads, through ensureLoaded(). Nothing outside the profile screen retries, and the destination list is closed.
    $mainSources = Join-Path $repositoryRoot "android\app\src\main"
    $allowedEnsureLoaded = @("ClinicalProfileModel.kt", "ClinicalProfileScreen.kt", "MainActivity.kt")
    $ensureLoadedCallers = @(Get-ChildItem -LiteralPath $mainSources -Recurse -File -Include *.kt, *.java |
        Where-Object { $allowedEnsureLoaded -notcontains $_.Name } |
        Select-String -Pattern 'ensureLoaded' -List)
    if ($ensureLoadedCallers.Count -gt 0) {
        throw "Only the profile screen and the blocking details may start profile reads (ADR 0017): $($ensureLoadedCallers.Path -join ', ')"
    }
    $mainActivityEnsureLoaded = @(Select-String -LiteralPath (Join-Path $mainSources "java\org\bolusai\next\MainActivity.kt") -Pattern 'ensureLoaded\(')
    if ($mainActivityEnsureLoaded.Count -ne 1) {
        throw "MainActivity must start profile reads only for the blocking details (ADR 0017)"
    }
    # The profile model is reachable only from its own screen and the activity, and the activity never retries.
    $profileModelUsers = @(Get-ChildItem -LiteralPath $mainSources -Recurse -File -Include *.kt, *.java |
        Where-Object { $allowedEnsureLoaded -notcontains $_.Name } |
        Select-String -Pattern 'ClinicalProfileModel' -List)
    if ($profileModelUsers.Count -gt 0) {
        throw "Only the profile screen and MainActivity may use the profile model (ADR 0017): $($profileModelUsers.Path -join ', ')"
    }
    if (Select-String -LiteralPath (Join-Path $mainSources "java\org\bolusai\next\MainActivity.kt") -Pattern 'profile\.retry\(' -Quiet) {
        throw "Bolo and Diagnostico never retry profile reads (ADR 0017, B6)"
    }
    # Every instrumentation class that launches the activity injects a synthetic profile repository: Bolo and
    # Diagnostico read the profile, and tests never open the app's own clinical-profile.db.
    $unisolatedLaunches = @(Get-ChildItem -LiteralPath (Join-Path $repositoryRoot "android\app\src\androidTest") -Recurse -File -Include *.kt |
        Where-Object { (Select-String -LiteralPath $_.FullName -Pattern 'ActivityScenario\.launch\(MainActivity' -Quiet) -and
            -not (Select-String -LiteralPath $_.FullName -Pattern 'profileRepositoryFactory\s*=\s*\{' -Quiet) })
    if ($unisolatedLaunches.Count -gt 0) {
        throw "Instrumentation launching MainActivity must inject a synthetic profile repository (ADR 0017): $($unisolatedLaunches.FullName -join ', ')"
    }
    # The runtime barrier is the real protection: the app tests run with the runner that activates it before the
    # application starts. These static checks only keep it wired.
    if (-not (Select-String -LiteralPath (Join-Path $repositoryRoot "android\app\build.gradle.kts") -SimpleMatch -Quiet `
            -Pattern 'testInstrumentationRunner = "org.bolusai.next.ProfileIsolationTestRunner"')) {
        throw "App instrumentation must run with ProfileIsolationTestRunner (ADR 0017)"
    }
    if (-not (Select-String -LiteralPath (Join-Path $mainSources "java\org\bolusai\next\profile\SqliteClinicalProfileRepository.kt") -SimpleMatch -Quiet `
            -Pattern 'init { ProfileStorageGuard.checkDatabase(context.applicationContext, name) }')) {
        throw "The profile repository must check the instrumentation barrier before opening its file (ADR 0017)"
    }
    $blockingDetailsSource = Join-Path $mainSources "java\org\bolusai\next\ui\BlockingDetails.kt"
    $expectedDestinations = 'setOf(Destination.BOLUS, Destination.MANUAL, Destination.OFFLINE_BOLUS, Destination.DIAGNOSTICS)'
    if (-not (Select-String -LiteralPath $blockingDetailsSource -SimpleMatch -Pattern $expectedDestinations -Quiet)) {
        throw "The blocking details destinations must stay Bolo and Diagnostico only (ADR 0017, B10)"
    }
    $mainActivitySource = Join-Path $repositoryRoot "android\app\src\main\java\org\bolusai\next\MainActivity.kt"
    if (Select-String -LiteralPath $mainActivitySource -Pattern 'InputUnavailability|UnavailabilityReasonV2' -Quiet) {
        throw "MainActivity must not build contract v2 reports (ADR 0016)"
    }
    $overviewSource = Join-Path $repositoryRoot "android\app\src\main\java\org\bolusai\next\application\ReadOverview.kt"
    if (Select-String -LiteralPath $overviewSource -Pattern 'org\.bolusai\.profile|ClinicalProfile|ProfileGate|InputUnavailability|UnavailabilityReasonV2' -Quiet) {
        throw "The Bolo overview must keep reporting the profile as unavailable (ADR 0012)"
    }
    # ADR 0014 and ADR 0017: the renderer receives composed lines only and never reads the profile, its confirmations
    # or contract v2 types.
    $rendererSource = Join-Path $repositoryRoot "android\app\src\main\java\org\bolusai\next\ui\ScreenRenderer.kt"
    if (Select-String -LiteralPath $rendererSource -Pattern 'org\.bolusai\.profile|ClinicalProfile|ProfileGate|Confirmation|InputUnavailability|UnavailabilityReasonV2' -Quiet) {
        throw "ScreenRenderer must not read the clinical profile, its confirmations or contract v2 types (ADR 0014, ADR 0017)"
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
                -e class org.bolusai.next.glucose.dexcom.AndroidDexcomSenderEvidenceTest,org.bolusai.next.NavigationDeviceTest,org.bolusai.next.MealDraftDeviceTest,org.bolusai.next.MealHistoryDeviceTest,org.bolusai.next.MealRestoreDeviceTest,org.bolusai.next.meals.SqliteMealRepositoryDeviceTest,org.bolusai.next.DarkThemeDeviceTest,org.bolusai.next.profile.SqliteClinicalProfileRepositoryDeviceTest,org.bolusai.next.profile.ClinicalProfileConfirmationDeviceTest,org.bolusai.next.ClinicalProfileDeviceTest,org.bolusai.next.ClinicalProfileConfirmationUiDeviceTest,org.bolusai.next.ClinicalProfileUnavailabilityDeviceTest,org.bolusai.next.BolusProfileDetailsDeviceTest,org.bolusai.next.ProfileStorageGuardDeviceTest `
                org.bolusai.next.test/org.bolusai.next.ProfileIsolationTestRunner)
            $instrumentationExit = $LASTEXITCODE
            $instrumentation | Write-Output
            $instrumentationText = $instrumentation -join "`n"
            if ($instrumentationExit -ne 0 -or $instrumentationText -notmatch 'OK \([1-9]\d* tests?\)' -or
                $instrumentationText -match 'FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg=|INSTRUMENTATION_STATUS_CODE: -[1-4]') {
                throw "Android sender/navigation instrumentation failed or did not complete"
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
