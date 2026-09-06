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

    $workflowDirectory = Join-Path $repositoryRoot ".github\workflows"
    $canonicalWorkflow = Join-Path $workflowDirectory "verify.yml"
    if (-not (Test-Path -LiteralPath $canonicalWorkflow -PathType Leaf)) {
        throw "The canonical Windows verification workflow is missing"
    }
    $canonicalWorkflowContent = Get-Content -Raw -LiteralPath $canonicalWorkflow
    if ([string]::IsNullOrWhiteSpace($canonicalWorkflowContent)) {
        throw "The canonical Windows verification workflow is empty"
    }
    if ($canonicalWorkflowContent -notmatch "(?i)windows-latest" -or
        $canonicalWorkflowContent -notmatch "(?i)scripts\\verify\.ps1") {
        throw "The canonical workflow must run scripts/verify.ps1 on windows-latest"
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
}
finally {
    Pop-Location
}
