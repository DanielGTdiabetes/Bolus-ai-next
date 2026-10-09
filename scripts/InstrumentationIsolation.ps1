# Instrumentation isolation of the clinical profile (ADR 0017).
#
# ProfileIsolationTestRunner activates ProfileStorageGuard in Instrumentation.onCreate. Android instantiates the
# Application, its component factory and the content providers of the app under test before that call, so none of them
# may be app code. These functions read the binary manifest packaged in the APKs that verify.ps1 installs, which is the
# final result of merging the app, its dependencies and its build variant, never the source manifest alone.
#
# Windows PowerShell 5.1 and PowerShell 7 compatible. Only reads APKs and builds synthetic ones in a temporary folder.

Set-StrictMode -Version Latest

$script:InstrumentationAndroidNamespace = "http://schemas.android.com/apk/res/android"
# Library factory that instantiates components through their default constructors and runs no app code.
$script:InstrumentationAllowedComponentFactories = @("androidx.core.app.CoreComponentFactory")

function Get-InstrumentationAapt2 {
    param([Parameter(Mandatory = $true)][string]$AndroidHome)
    $buildTools = Join-Path $AndroidHome "build-tools"
    $candidates = @(Get-ChildItem -LiteralPath $buildTools -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^\d+(\.\d+)*' -and (Test-Path -LiteralPath (Join-Path $_.FullName "aapt2.exe")) } |
        Sort-Object { [version](($_.Name -replace '[^0-9.].*$', '').TrimEnd('.')) } -Descending)
    if ($candidates.Count -eq 0) {
        throw "instrumentation_isolation.aapt2_missing: no build-tools with aapt2.exe under $buildTools"
    }
    return (Join-Path $candidates[0].FullName "aapt2.exe")
}

function Get-InstrumentationAndroidJar {
    param([Parameter(Mandatory = $true)][string]$AndroidHome, [Parameter(Mandatory = $true)][int]$CompileSdk)
    $jar = Join-Path $AndroidHome "platforms\android-$CompileSdk\android.jar"
    if (-not (Test-Path -LiteralPath $jar)) {
        throw "instrumentation_isolation.android_jar_missing: $jar"
    }
    return $jar
}

function Invoke-InstrumentationAapt2 {
    param([Parameter(Mandatory = $true)][string]$Aapt2, [Parameter(Mandatory = $true)][string[]]$Arguments)
    # aapt2 may report warnings on stderr; only its exit code decides.
    $ErrorActionPreference = "Continue"
    $output = @(& $Aapt2 @Arguments 2>&1 | ForEach-Object { "$_" })
    return [pscustomobject]@{ ExitCode = $LASTEXITCODE; Lines = $output }
}

function New-InstrumentationNode {
    param([string]$Name, $Parent, [int]$Indent)
    return [pscustomobject]@{
        Name       = $Name
        Attributes = @{}
        Children   = [System.Collections.Generic.List[object]]::new()
        Parent     = $Parent
        Indent     = $Indent
    }
}

# Parses the text of `aapt2 dump xmltree`. Any line it does not recognise fails closed.
function ConvertFrom-InstrumentationXmlTree {
    param([Parameter(Mandatory = $true)][AllowEmptyCollection()][string[]]$Lines)
    $root = New-InstrumentationNode -Name "#document" -Parent $null -Indent -1
    $current = $root
    foreach ($line in $Lines) {
        if ([string]::IsNullOrWhiteSpace($line) -or $line -match '^\s*N: ') { continue }
        if ($line -match '^(\s*)E: (\S+) \(line=\d+\)\s*$') {
            $indent = $Matches[1].Length
            $name = $Matches[2]
            while ($current.Indent -ge $indent) { $current = $current.Parent }
            $node = New-InstrumentationNode -Name $name -Parent $current -Indent $indent
            $current.Children.Add($node)
            $current = $node
            continue
        }
        if ($line -match '^(\s*)A: (.+?)(?:\(0x[0-9a-fA-F]+\))?=(.*)$') {
            $indent = $Matches[1].Length
            $attribute = $Matches[2]
            $raw = $Matches[3]
            $owner = $current
            while ($owner.Indent -ge $indent) { $owner = $owner.Parent }
            if ($null -eq $owner.Parent) { throw "instrumentation_isolation.manifest_unparsed: $line" }
            $value = if ($raw -match '^"((?:[^"\\]|\\.)*)"') { $Matches[1] } else { $raw.Trim() }
            $owner.Attributes[$attribute] = $value
            continue
        }
        throw "instrumentation_isolation.manifest_unparsed: $line"
    }
    if (@($root.Children | Where-Object { $_.Name -eq "manifest" }).Count -ne 1) {
        throw "instrumentation_isolation.manifest_unparsed: no single manifest element"
    }
    return $root
}

function Read-InstrumentationApkManifest {
    param([Parameter(Mandatory = $true)][string]$Aapt2, [Parameter(Mandatory = $true)][string]$Apk)
    if (-not (Test-Path -LiteralPath $Apk)) { throw "instrumentation_isolation.apk_missing: $Apk" }
    $dump = Invoke-InstrumentationAapt2 -Aapt2 $Aapt2 -Arguments @("dump", "xmltree", "--file", "AndroidManifest.xml", $Apk)
    if ($dump.ExitCode -ne 0) {
        throw "instrumentation_isolation.manifest_unreadable: $Apk`n$($dump.Lines -join "`n")"
    }
    return (ConvertFrom-InstrumentationXmlTree -Lines $dump.Lines)
}

function Find-InstrumentationElements {
    param([Parameter(Mandatory = $true)]$Node, [Parameter(Mandatory = $true)][string]$Name)
    foreach ($child in $Node.Children) {
        if ($child.Name -eq $Name) { $child }
        Find-InstrumentationElements -Node $child -Name $Name
    }
}

function Get-InstrumentationAndroidAttribute {
    param([Parameter(Mandatory = $true)]$Node, [Parameter(Mandatory = $true)][string]$Name)
    $key = "$($script:InstrumentationAndroidNamespace):$Name"
    if ($Node.Attributes.ContainsKey($key)) { return $Node.Attributes[$key] }
    return $null
}

# Code that Android runs in the process before Instrumentation.onCreate: an Application subclass, a component factory
# other than the library one, and any content provider. Returns stable violation identifiers.
function Get-InstrumentationEarlyCodeViolations {
    param([Parameter(Mandatory = $true)]$Manifest)
    $violations = [System.Collections.Generic.List[string]]::new()
    $applications = @(Find-InstrumentationElements -Node $Manifest -Name "application")
    if ($applications.Count -ne 1 -or $applications[0].Parent.Name -ne "manifest") {
        $violations.Add("application_count:$($applications.Count)")
    } else {
        $applicationClass = Get-InstrumentationAndroidAttribute -Node $applications[0] -Name "name"
        if ($null -ne $applicationClass) { $violations.Add("application_class:$applicationClass") }
        $factory = Get-InstrumentationAndroidAttribute -Node $applications[0] -Name "appComponentFactory"
        if ($null -ne $factory -and $script:InstrumentationAllowedComponentFactories -notcontains $factory) {
            $violations.Add("component_factory:$factory")
        }
    }
    foreach ($provider in @(Find-InstrumentationElements -Node $Manifest -Name "provider")) {
        $violations.Add("provider:$(Get-InstrumentationAndroidAttribute -Node $provider -Name 'name')")
    }
    return $violations.ToArray()
}

function Get-InstrumentationPackage {
    param([Parameter(Mandatory = $true)]$Manifest)
    $manifestElement = @($Manifest.Children | Where-Object { $_.Name -eq "manifest" })[0]
    if (-not $manifestElement.Attributes.ContainsKey("package")) { return $null }
    return $manifestElement.Attributes["package"]
}

# Every violation of the app APK and of the test APK, and whether the test APK instruments that app with the runner
# that activates the barrier. An empty result means the pair is isolated.
function Get-InstrumentationIsolationViolations {
    param(
        [Parameter(Mandatory = $true)]$AppManifest,
        [Parameter(Mandatory = $true)]$TestManifest,
        [Parameter(Mandatory = $true)][string]$Runner
    )
    $violations = [System.Collections.Generic.List[string]]::new()
    foreach ($item in @(Get-InstrumentationEarlyCodeViolations -Manifest $AppManifest)) { $violations.Add("app:$item") }
    foreach ($item in @(Get-InstrumentationEarlyCodeViolations -Manifest $TestManifest)) { $violations.Add("test:$item") }
    $appPackage = Get-InstrumentationPackage -Manifest $AppManifest
    if ($null -eq $appPackage) { $violations.Add("app:package_missing") }
    $instrumentations = @(Find-InstrumentationElements -Node $TestManifest -Name "instrumentation")
    if ($instrumentations.Count -ne 1) {
        $violations.Add("test:instrumentation_count:$($instrumentations.Count)")
    } else {
        $target = Get-InstrumentationAndroidAttribute -Node $instrumentations[0] -Name "targetPackage"
        if ($target -ne $appPackage) { $violations.Add("test:target_package:$target") }
        $name = Get-InstrumentationAndroidAttribute -Node $instrumentations[0] -Name "name"
        if ($name -ne $Runner) { $violations.Add("test:runner:$name") }
    }
    return $violations.ToArray()
}

# Checks the packaged manifests of the APKs that are installed for instrumentation. Returns what was checked, with the
# SHA-256 of each APK so the caller can prove that the installed files are the inspected ones.
function Assert-InstrumentationIsolation {
    param(
        [Parameter(Mandatory = $true)][string]$Aapt2,
        [Parameter(Mandatory = $true)][string]$AppApk,
        [Parameter(Mandatory = $true)][string]$TestApk,
        [Parameter(Mandatory = $true)][string]$Runner
    )
    $appManifest = Read-InstrumentationApkManifest -Aapt2 $Aapt2 -Apk $AppApk
    $testManifest = Read-InstrumentationApkManifest -Aapt2 $Aapt2 -Apk $TestApk
    $violations = @(Get-InstrumentationIsolationViolations -AppManifest $appManifest -TestManifest $testManifest -Runner $Runner)
    if ($violations.Count -gt 0) {
        throw "instrumentation_isolation.violation (ADR 0017): app code could run before the profile barrier: $($violations -join '; ')"
    }
    $application = @(Find-InstrumentationElements -Node $appManifest -Name "application")[0]
    return [pscustomobject]@{
        AppApk           = (Resolve-Path -LiteralPath $AppApk).Path
        AppPackage       = Get-InstrumentationPackage -Manifest $appManifest
        ComponentFactory = Get-InstrumentationAndroidAttribute -Node $application -Name "appComponentFactory"
        TestApk          = (Resolve-Path -LiteralPath $TestApk).Path
        Runner           = $Runner
        AppSha256        = (Get-FileHash -LiteralPath $AppApk -Algorithm SHA256).Hash
        TestSha256       = (Get-FileHash -LiteralPath $TestApk -Algorithm SHA256).Hash
    }
}

# Proves that the check accepts the isolated case and rejects each route for early app code. Synthetic manifests are
# linked into synthetic APKs, so the very same reading path as for the real APKs is exercised. Nothing is installed.
function Invoke-InstrumentationIsolationSelfTest {
    param(
        [Parameter(Mandatory = $true)][string]$Aapt2,
        [Parameter(Mandatory = $true)][string]$AndroidJar,
        [Parameter(Mandatory = $true)][string]$FixtureDirectory,
        [Parameter(Mandatory = $true)][string]$Runner
    )
    $cases = @(
        @{ App = "app-accepted"; Test = "test-accepted"; Expected = @() },
        @{ App = "app-application"; Test = "test-accepted"; Expected = @("app:application_class:org.bolusai.synthetic.EarlyApplication") },
        @{ App = "app-provider"; Test = "test-accepted"; Expected = @("app:provider:org.bolusai.synthetic.EarlyProvider") },
        @{ App = "app-component-factory"; Test = "test-accepted"; Expected = @("app:component_factory:org.bolusai.synthetic.EarlyComponentFactory") },
        @{ App = "app-accepted"; Test = "test-provider"; Expected = @("test:provider:org.bolusai.synthetic.TestProvider") },
        @{ App = "app-accepted"; Test = "test-wrong-target"; Expected = @("test:target_package:org.bolusai.synthetic.other") },
        @{ App = "app-accepted"; Test = "test-wrong-runner"; Expected = @("test:runner:androidx.test.runner.AndroidJUnitRunner") }
    )
    $work = Join-Path ([System.IO.Path]::GetTempPath()) "bolusai-instrumentation-isolation-$([guid]::NewGuid().ToString('N'))"
    New-Item -ItemType Directory -Path $work | Out-Null
    try {
        $manifests = @{}
        $names = @($cases | ForEach-Object { $_.App; $_.Test } | Sort-Object -Unique)
        foreach ($name in $names) {
            $source = Join-Path $FixtureDirectory "$name.xml"
            $apk = Join-Path $work "$name.apk"
            $link = Invoke-InstrumentationAapt2 -Aapt2 $Aapt2 -Arguments @("link", "-o", $apk, "-I", $AndroidJar, "--manifest", $source)
            if ($link.ExitCode -ne 0) {
                throw "instrumentation_isolation.self_test_link_failed: $name`n$($link.Lines -join "`n")"
            }
            $manifests[$name] = Read-InstrumentationApkManifest -Aapt2 $Aapt2 -Apk $apk
        }
        foreach ($case in $cases) {
            $actual = @(Get-InstrumentationIsolationViolations -AppManifest $manifests[$case.App] -TestManifest $manifests[$case.Test] -Runner $Runner)
            if (($actual -join "|") -cne ($case.Expected -join "|")) {
                throw "instrumentation_isolation.self_test_failed: $($case.App) + $($case.Test) expected [$($case.Expected -join '; ')] got [$($actual -join '; ')]"
            }
            $outcome = if ($actual.Count -eq 0) { "accepted" } else { "rejected $($actual -join '; ')" }
            "$($case.App) + $($case.Test): $outcome"
        }
    } finally {
        Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
    }
}
