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
    $releaseManifest = Join-Path $repositoryRoot "android\app\build\intermediates\merged_manifest\release\processReleaseMainManifest\AndroidManifest.xml"
    if (-not (Test-Path -LiteralPath $releaseManifest) -or
        (Select-String -LiteralPath $releaseManifest -Pattern 'senderfixture|android.permission.INTERNET' -Quiet)) {
        throw "Release manifest must stay isolated from fixtures and network permissions"
    }
    # ADR 0022 R1 and ADR 0023: the only receiver is the Dexcom one, declared disabled and exported, guarded by the
    # producer's permission (ADR 0023, part I, point 1), with a single intent filter holding only the observed action.
    # Next requests that permission and nothing else of Dexcom (I-6), and never QUERY_ALL_PACKAGES until the Pixel test
    # of I-3 shows it is needed: the com.dexcom. values of the merged manifest are exactly action, receiver permission
    # and the requested permission.
    foreach ($manifest in @($mergedManifest, $releaseManifest)) {
        [xml]$manifestXml = Get-Content -Raw -LiteralPath $manifest
        $androidNs = "http://schemas.android.com/apk/res/android"
        $receivers = @($manifestXml.SelectNodes("//receiver"))
        $dexcomValues = @($manifestXml.SelectNodes("//@*[starts-with(., 'com.dexcom.')]"))
        $dexcomRequests = @($manifestXml.SelectNodes("/manifest/uses-permission") |
            Where-Object { $_.GetAttribute("name", $androidNs).StartsWith("com.dexcom.") })
        $queryAll = @($manifestXml.SelectNodes("//@*[. = 'android.permission.QUERY_ALL_PACKAGES']"))
        $receiver = if ($receivers.Count -eq 1) { $receivers[0] } else { $null }
        $filters = @(if ($null -ne $receiver) { $receiver.SelectNodes("intent-filter") })
        $filterChildren = @(if ($filters.Count -eq 1) { $filters[0].SelectNodes("*") })
        if ($null -eq $receiver -or
                $receiver.GetAttribute("name", $androidNs) -cne "org.bolusai.next.glucose.dexcom.DexcomG7Receiver" -or
                $receiver.GetAttribute("enabled", $androidNs) -cne "false" -or
                $receiver.GetAttribute("exported", $androidNs) -cne "true" -or
                $receiver.GetAttribute("permission", $androidNs) -cne "com.dexcom.cgm.EXTERNAL_PERMISSION" -or
                $filters.Count -ne 1 -or $filterChildren.Count -ne 1 -or
                $filterChildren[0].LocalName -cne "action" -or
                $filterChildren[0].GetAttribute("name", $androidNs) -cne "com.dexcom.cgm.EXTERNAL_BROADCAST" -or
                $dexcomRequests.Count -ne 1 -or
                $dexcomRequests[0].GetAttribute("name", $androidNs) -cne "com.dexcom.cgm.EXTERNAL_PERMISSION" -or
                $queryAll.Count -ne 0 -or
                $dexcomValues.Count -ne 3) {
            throw "The only receiver must be the disabled Dexcom receiver for the observed action, guarded by the Dexcom permission that Next requests, without QUERY_ALL_PACKAGES (ADR 0022, ADR 0023): $manifest"
        }
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

    # ADR 0017: the instrumentation barrier activates in Instrumentation.onCreate, after Android has instantiated the
    # Application, its component factory and the content providers of the app under test. None of them may be app
    # code. The check reads the binary manifests packaged in the very APKs installed below (the merged result of the
    # app, its dependencies and its variant), proves the test APK instruments that app with the isolating runner, and
    # first proves with synthetic APKs that each route is rejected.
    . (Join-Path $PSScriptRoot "InstrumentationIsolation.ps1")
    $androidTestApk = Join-Path $repositoryRoot "android\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
    $isolationRunner = "org.bolusai.next.ProfileIsolationTestRunner"
    $compileSdkMatch = Select-String -LiteralPath (Join-Path $repositoryRoot "android\app\build.gradle.kts") -Pattern '^\s*compileSdk\s*=\s*(\d+)\s*$' |
        Select-Object -First 1
    if ($null -eq $compileSdkMatch) {
        throw "Could not read compileSdk for the instrumentation isolation self-test"
    }
    $aapt2 = Get-InstrumentationAapt2 -AndroidHome $env:ANDROID_HOME
    $androidJar = Get-InstrumentationAndroidJar -AndroidHome $env:ANDROID_HOME -CompileSdk ([int]$compileSdkMatch.Matches[0].Groups[1].Value)
    Invoke-InstrumentationIsolationSelfTest -Aapt2 $aapt2 -AndroidJar $androidJar -Runner $isolationRunner `
        -FixtureDirectory (Join-Path $PSScriptRoot "fixtures\instrumentation-isolation") |
        ForEach-Object { Write-Output "Instrumentation isolation self-test: $_" }
    $instrumentationIsolation = Assert-InstrumentationIsolation -Aapt2 $aapt2 -AppApk $debugApk -TestApk $androidTestApk -Runner $isolationRunner
    Write-Output ("Instrumentation isolation: {0} ({1}) instrumented by {2} with {3}; component factory {4}; app SHA-256 {5}; test SHA-256 {6}" -f
        $instrumentationIsolation.AppPackage, $instrumentationIsolation.AppApk, $instrumentationIsolation.TestApk,
        $instrumentationIsolation.Runner, $instrumentationIsolation.ComponentFactory, $instrumentationIsolation.AppSha256,
        $instrumentationIsolation.TestSha256)

    # ADR 0018: the observed Dexcom G7 payload parser is pure and has no consumer yet. It must not depend on the
    # engine, the profile, the meals or Android. ADR 0019 limits its consumers below.
    $dexcomParserRoot = Join-Path $repositoryRoot "shared\dexcom-g7-payload"
    $dexcomParserDependencies = @(Get-ChildItem -LiteralPath $dexcomParserRoot -Recurse -File |
        Where-Object { $_.FullName -notmatch '\\build\\' } |
        Select-String -Pattern 'org\.bolusai\.(engine|profile|meals|next)|import\s+(android|java)\.|project\(' -List)
    if ($dexcomParserDependencies.Count -gt 0) {
        throw "The Dexcom G7 payload parser must stay pure and dependency-free: $($dexcomParserDependencies.Path -join ', ')"
    }
    # ADR 0019 to ADR 0023: only the Dexcom reception composition, its Bundle adapter, the observation log, the read-only
    # Diagnostico view of its counts, the receiver, the permission gate, the read-only presentation of Inicio and Bolo
    # (C1), the test calculation that may use the shown reading (ADR 0024) and their tests may use the parser or the log.
    $allowedDexcomParserConsumers = @(
        "android\app\build.gradle.kts",
        "android\app\src\main\java\org\bolusai\next\glucose\dexcom\DexcomPayloadExtras.kt",
        "android\app\src\main\java\org\bolusai\next\glucose\dexcom\DexcomG7Reception.kt",
        "android\app\src\test\java\org\bolusai\next\glucose\dexcom\DexcomPayloadExtrasTest.kt",
        "android\app\src\test\java\org\bolusai\next\glucose\dexcom\DexcomG7ReceptionTest.kt",
        "android\app\src\androidTest\java\org\bolusai\next\glucose\dexcom\DexcomBundleExtrasDeviceTest.kt",
        "android\app\src\main\java\org\bolusai\next\glucose\dexcom\storage\DexcomObservationSchema.kt",
        "android\app\src\main\java\org\bolusai\next\glucose\dexcom\storage\SqliteDexcomObservationStore.kt",
        "android\app\src\main\java\org\bolusai\next\glucose\dexcom\storage\DexcomG7Recorder.kt",
        "android\app\src\test\java\org\bolusai\next\glucose\dexcom\storage\DexcomG7RecorderTest.kt",
        "android\app\src\androidTest\java\org\bolusai\next\glucose\dexcom\storage\SqliteDexcomObservationStoreDeviceTest.kt",
        "android\app\src\main\java\org\bolusai\next\glucose\dexcom\storage\DexcomLogReader.kt",
        "android\app\src\main\java\org\bolusai\next\glucose\dexcom\storage\DexcomStorageGuard.kt",
        "android\app\src\androidTest\java\org\bolusai\next\glucose\dexcom\storage\DexcomLogIsolationDeviceTest.kt",
        "android\app\src\androidTest\java\org\bolusai\next\SyntheticDexcomLog.kt",
        "android\app\src\androidTest\java\org\bolusai\next\ProfileIsolationTestRunner.kt",
        "android\app\src\main\java\org\bolusai\next\ui\DexcomLogBlock.kt",
        "android\app\src\main\java\org\bolusai\next\ui\DexcomLogModel.kt",
        "android\app\src\main\java\org\bolusai\next\MainActivity.kt",
        "android\app\src\test\java\org\bolusai\next\DexcomLogBlockTest.kt",
        "android\app\src\androidTest\java\org\bolusai\next\DexcomLogDiagnosticsDeviceTest.kt",
        "android\app\src\main\java\org\bolusai\next\glucose\dexcom\DexcomG7Receiver.kt",
        "android\app\src\test\java\org\bolusai\next\glucose\dexcom\DexcomG7IntakeTest.kt",
        "android\app\src\androidTest\java\org\bolusai\next\glucose\dexcom\DexcomG7ReceiverDeviceTest.kt",
        "android\app\src\main\java\org\bolusai\next\glucose\dexcom\DexcomPermissionSetPolicy.kt",
        "android\app\src\main\java\org\bolusai\next\glucose\dexcom\DexcomPermissionIntake.kt",
        "android\app\src\main\java\org\bolusai\next\glucose\dexcom\DexcomPresentationReader.kt",
        "android\app\src\main\java\org\bolusai\next\ui\DexcomReadingBlock.kt",
        "android\app\src\main\java\org\bolusai\next\application\TestCalculation.kt",
        "android\app\src\test\java\org\bolusai\next\glucose\dexcom\DexcomPermissionGateTest.kt",
        "android\app\src\test\java\org\bolusai\next\DexcomReadingBlockTest.kt",
        "android\app\src\test\java\org\bolusai\next\TestCalculationTest.kt"
    ) | ForEach-Object { Join-Path $repositoryRoot $_ }
    $dexcomParserConsumers = @(Get-ChildItem -LiteralPath (Join-Path $repositoryRoot "android"), (Join-Path $repositoryRoot "shared") -Recurse -File |
        Where-Object { $_.FullName -notmatch '\\build\\' -and -not $_.FullName.StartsWith($dexcomParserRoot + [IO.Path]::DirectorySeparatorChar) } |
        Select-String -Pattern 'dexcom-g7-payload|org\.bolusai\.dexcomg7|DexcomG7Reception|DexcomPayloadExtras|readDexcomPayload|BundleExtrasView|DexcomObservation|DexcomG7Recorder|dexcom-observations' -List |
        Where-Object { $allowedDexcomParserConsumers -notcontains $_.Path })
    if ($dexcomParserConsumers.Count -gt 0) {
        throw "Only the Dexcom reception and observation files of ADR 0019 to ADR 0022 may use the parser or that composition: $($dexcomParserConsumers.Path -join ', ')"
    }
    # ADR 0022, R6: production keeps the Pending policy. No approved anchors, no other policy source and no subclass of
    # the receiver exist in production code; only instrumentation subclasses it or observes its receipts.
    $receiverSource = Join-Path $repositoryRoot "android\app\src\main\java\org\bolusai\next\glucose\dexcom\DexcomG7Receiver.kt"
    if (-not (Select-String -LiteralPath $receiverSource -SimpleMatch -Quiet -Pattern 'val policy: DexcomSenderTrustPolicy = DexcomSenderTrustPolicy.Pending')) {
        throw "The production Dexcom receiver policy must stay Pending (ADR 0022, R6)"
    }
    $productionTrust = @(Get-ChildItem -LiteralPath (Join-Path $repositoryRoot "android\app\src\main") -Recurse -File -Include *.kt, *.java |
        Select-String -Pattern 'DexcomSenderTrustPolicy\.Approved\(|:\s*DexcomG7Receiver\(|DexcomG7Receiver\.receipts\s*=' -List)
    if ($productionTrust.Count -gt 0) {
        throw "Production code must not approve Dexcom senders, subclass the receiver or observe its receipts (ADR 0022, R6): $($productionTrust.Path -join ', ')"
    }

    # ADR 0023, I-5 (a): the approved anchors live in production code and must equal the table of the accepted ADR.
    $anchorSource = Get-Content -Raw -LiteralPath (Join-Path $repositoryRoot "android\app\src\main\java\org\bolusai\next\glucose\dexcom\DexcomPermissionSetPolicy.kt")
    $anchorAdr = Get-Content -Raw -LiteralPath (Join-Path $repositoryRoot "docs\adr\0023-dexcom-existing-transport-trust.md")
    $approvedAnchors = @(
        'com.dexcom.cgm.EXTERNAL_PERMISSION',
        'com.dexcom.g7',
        'org.bolusai.companion',
        'com.wtachtsugar',
        'd30e0d3f08b70cedbc0804c96f2ec4599f243b5e149ef73a601757ad94c74145',
        '1d1f25bf2aadfd82956610084f3acbf06bb69225b0c4fb031df8eae24f4f6a8d'
    )
    foreach ($anchor in $approvedAnchors) {
        if (-not $anchorSource.Contains($anchor) -or -not $anchorAdr.Contains($anchor)) {
            throw "Approved anchor missing from production code or ADR 0023: $anchor"
        }
    }
    if ($anchorSource -notmatch 'ApprovedMember\("com\.dexcom\.g7", exactVersionCode = 4537L' -or
            $anchorAdr -notmatch '\| 10451 \| 4537 \|' -or
            ([regex]::Matches($anchorSource, 'exactVersionCode = null')).Count -ne 2 -or
            ([regex]::Matches($anchorSource, 'ApprovedMember\("')).Count -ne 3) {
        throw "Anchors must keep the exact Dexcom version and only package and signers for Legacy and the watch (ADR 0023, I-2 b)"
    }
    # ADR 0023, plan step 6: the clinical boundary still has no glucose. ReadOverview, the local source port and the
    # engine never read the log or its presentation; the engine receives glucose only through an explicit request.
    foreach ($boundary in @("android\app\src\main\java\org\bolusai\next\application\ReadOverview.kt",
            "android\app\src\main\java\org\bolusai\next\glucose\LocalGlucoseSourcePort.kt")) {
        if (Select-String -LiteralPath (Join-Path $repositoryRoot $boundary) -Pattern 'org\.bolusai\.dexcomg7|readDexcomPresentation|LatestReadingPresentation|candidate' -Quiet) {
            throw "The clinical glucose boundary must not read the Dexcom log, its presentation or the test engine (ADR 0023): $boundary"
        }
    }
    $engineDexcom = @(Get-ChildItem -LiteralPath (Join-Path $repositoryRoot "shared\bolus-engine\src\commonMain") -Recurse -File |
        Where-Object { $_.FullName -notmatch '\\build\\' } |
        Select-String -Pattern 'org\.bolusai\.(dexcomg7|meals|next)|import\s+(android|java)\.' -List)
    if ($engineDexcom.Count -gt 0) {
        throw "The bolus engine must stay pure: no Dexcom, meals, Android or JVM imports: $($engineDexcom.Path -join ', ')"
    }
    # ADR 0024: test results stay in memory. The test calculation never persists, confirms or synchronises anything.
    foreach ($testCalculation in @("android\app\src\main\java\org\bolusai\next\application\TestCalculation.kt",
            "android\app\src\main\java\org\bolusai\next\ui\TestCalculationScreen.kt")) {
        if (Select-String -LiteralPath (Join-Path $repositoryRoot $testCalculation) -Pattern 'Sqlite|Repository|appendConfirmation|\.record\(|\.save\(|\.select\(' -Quiet) {
            throw "The test calculation must not persist, confirm or record anything (ADR 0024): $testCalculation"
        }
    }

    # ADR 0021: ScreenRenderer only receives composed lines; it never reads the log nor knows its domain types.
    if (Select-String -LiteralPath (Join-Path $repositoryRoot "android\app\src\main\java\org\bolusai\next\ui\ScreenRenderer.kt") -Pattern 'org\.bolusai\.dexcomg7|DexcomLogModel|readDexcomObservationLog' -Quiet) {
        throw "ScreenRenderer must only receive the composed Dexcom log lines (ADR 0021)"
    }

    # ADR 0012 and ADR 0024: the engine never depends on the profile module; the Android test calculation translates
    # the profile into explicit engine inputs. The Bolo overview keeps reporting the profile as unavailable.
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
    # The runtime barrier is the real protection: the app tests run with the runner that activates it in
    # Instrumentation.onCreate, before Application.onCreate and any activity. The authoritative check of what runs
    # earlier reads the packaged APK manifests (above). This early check of the main source manifest only gives a
    # clearer message at the source. These static checks only keep it wired.
    [xml]$appManifest = Get-Content -LiteralPath (Join-Path $repositoryRoot "android\app\src\main\AndroidManifest.xml") -Raw
    $androidNamespace = "http://schemas.android.com/apk/res/android"
    $appApplication = $appManifest.SelectSingleNode("/manifest/application")
    if ($null -eq $appApplication -or $appApplication.HasAttribute("name", $androidNamespace) -or
            $appManifest.SelectNodes("//provider").Count -gt 0) {
        throw "The app must declare no Application class or content provider: they run before the instrumentation barrier (ADR 0017)"
    }
    if (-not (Select-String -LiteralPath (Join-Path $repositoryRoot "android\app\build.gradle.kts") -SimpleMatch -Quiet `
            -Pattern 'testInstrumentationRunner = "org.bolusai.next.ProfileIsolationTestRunner"')) {
        throw "App instrumentation must run with ProfileIsolationTestRunner (ADR 0017)"
    }
    if (-not (Select-String -LiteralPath (Join-Path $mainSources "java\org\bolusai\next\profile\SqliteClinicalProfileRepository.kt") -SimpleMatch -Quiet `
            -Pattern 'init { ProfileStorageGuard.checkDatabase(context.applicationContext, name) }')) {
        throw "The profile repository must check the instrumentation barrier before opening its file (ADR 0017)"
    }
    # ADR 0021, follow-up to the 2026-10-11 review: the same barrier protects the app's own dexcom-observations.db. The
    # runner activates it, the store and the Diagnostico reader check it before touching the file, the reader only
    # summarises an existing log after checking it read-only, and every test that visits Diagnostico injects a synthetic
    # reader.
    $dexcomStorageSources = Join-Path $mainSources "java\org\bolusai\next\glucose\dexcom\storage"
    if (-not (Select-String -LiteralPath (Join-Path $repositoryRoot "android\app\src\androidTest\java\org\bolusai\next\ProfileIsolationTestRunner.kt") -SimpleMatch -Quiet `
            -Pattern 'DexcomStorageGuard.activateForInstrumentation()')) {
        throw "The instrumentation runner must activate the Dexcom storage barrier (ADR 0021)"
    }
    if (-not (Select-String -LiteralPath (Join-Path $dexcomStorageSources "SqliteDexcomObservationStore.kt") -SimpleMatch -Quiet `
            -Pattern 'init { DexcomStorageGuard.checkDatabase(context.applicationContext, name) }')) {
        throw "The Dexcom observation store must check the instrumentation barrier before opening its file (ADR 0021)"
    }
    $dexcomLogReaderSource = Join-Path $dexcomStorageSources "DexcomLogReader.kt"
    if (-not (Select-String -LiteralPath $dexcomLogReaderSource -SimpleMatch -Quiet -Pattern 'DexcomStorageGuard.checkDatabase(context.applicationContext, name)') -or
            -not (Select-String -LiteralPath $dexcomLogReaderSource -SimpleMatch -Quiet -Pattern '.use { it.summaryOfExisting() }') -or
            (Select-String -LiteralPath $dexcomLogReaderSource -Pattern '\.summary\(\)|\.read\(\)|\.record\(' -Quiet)) {
        throw "Diagnostico must read the Dexcom log only through the guarded read-only summary of an existing file (ADR 0021, F4)"
    }
    $unisolatedDiagnostics = @(Get-ChildItem -LiteralPath (Join-Path $repositoryRoot "android\app\src\androidTest") -Recurse -File -Include *.kt |
        Where-Object { (Select-String -LiteralPath $_.FullName -Pattern 'ActivityScenario\.launch\(MainActivity' -Quiet) -and
            (Select-String -LiteralPath $_.FullName -Pattern 'native/diagnostics|Destination\.DIAGNOSTICS|Destination\.entries' -Quiet) -and
            -not (Select-String -LiteralPath $_.FullName -Pattern 'dexcomLogReader\s*=\s*\{' -Quiet) })
    if ($unisolatedDiagnostics.Count -gt 0) {
        throw "Instrumentation visiting Diagnostico must inject a synthetic Dexcom log reader (ADR 0021): $($unisolatedDiagnostics.FullName -join ', ')"
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
        # The files about to be installed must be the ones whose manifests passed the isolation check (ADR 0017).
        if ((Get-FileHash -LiteralPath $debugApk -Algorithm SHA256).Hash -ne $instrumentationIsolation.AppSha256 -or
            (Get-FileHash -LiteralPath $androidTestApk -Algorithm SHA256).Hash -ne $instrumentationIsolation.TestSha256) {
            throw "The APKs changed after the instrumentation isolation check; refusing to install them (ADR 0017)"
        }
        $installedTestPackages = [System.Collections.Generic.List[string]]::new()
        try {
            adb install -r $debugApk
            if ($LASTEXITCODE -ne 0) { throw "Next APK installation failed" }
            $testApks = @(
                @{ Package = $fixturePackage; Path = Join-Path $repositoryRoot "android\sender-fixture\build\outputs\apk\debug\sender-fixture-debug.apk" },
                @{ Package = $testPackage; Path = $androidTestApk }
            )
            foreach ($testApk in $testApks) {
                adb install -t $testApk.Path
                if ($LASTEXITCODE -ne 0) { throw "Synthetic test APK installation failed" }
                $installedTestPackages.Add($testApk.Package)
            }
            # Direct instrumentation avoids collecting unrelated device logcat or clinical data.
            $instrumentation = @(adb shell am instrument -w -r `
                -e class org.bolusai.next.glucose.dexcom.AndroidDexcomSenderEvidenceTest,org.bolusai.next.NavigationDeviceTest,org.bolusai.next.MealDraftDeviceTest,org.bolusai.next.MealHistoryDeviceTest,org.bolusai.next.MealRestoreDeviceTest,org.bolusai.next.meals.SqliteMealRepositoryDeviceTest,org.bolusai.next.DarkThemeDeviceTest,org.bolusai.next.profile.SqliteClinicalProfileRepositoryDeviceTest,org.bolusai.next.profile.ClinicalProfileConfirmationDeviceTest,org.bolusai.next.ClinicalProfileDeviceTest,org.bolusai.next.ClinicalProfileConfirmationUiDeviceTest,org.bolusai.next.ClinicalProfileUnavailabilityDeviceTest,org.bolusai.next.BolusProfileDetailsDeviceTest,org.bolusai.next.ProfileStorageGuardDeviceTest,org.bolusai.next.glucose.dexcom.DexcomBundleExtrasDeviceTest,org.bolusai.next.glucose.dexcom.storage.SqliteDexcomObservationStoreDeviceTest,org.bolusai.next.DexcomLogDiagnosticsDeviceTest,org.bolusai.next.glucose.dexcom.storage.DexcomLogIsolationDeviceTest,org.bolusai.next.glucose.dexcom.DexcomG7ReceiverDeviceTest `
                "$testPackage/$isolationRunner")
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
