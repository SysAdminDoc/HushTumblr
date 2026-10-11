<#
.SYNOPSIS
    Apply every patch in the bundle and reject partial or unsuccessful output.

.DESCRIPTION
    The per-patch check with --exclusive answers one patch at a time. This applies all of
    them in a single run and validates the desktop CLI process, result report, patch names,
    target version and saved APK. A result file can be written from a finally block after a
    failed compile, so a zero failed-patch count is not enough to call the run successful.

    The bundle declares one compatible version, and the CLI refuses any other APK unless it is
    told to go ahead with -f. -Force does that for a retained newer build: the stock APK's own
    version is read with aapt2 and the result is held to that instead of the catalog's, so the
    run answers "which patches still apply on this build" rather than being refused before it
    starts. The package still has to be the catalog's.

    APKMirror ships Tumblr as a split bundle (.apkm). A universal APK, like the fixture bundletool
    builds from it, is used as it is; a split bundle (.xapk or .apkm) is merged into one APK first, with the CLI's own merger
    (Get-MergedApk), and the CLI patches that merge either way. A clean run reads the manifests of
    the merge (or the plain APK) and the patched APK
    the way the release receipt does and prints what patching changed: permissions asked for or
    dropped, components exported or no longer. A change scripts/manifest-delta-allowlist.txt
    doesn't approve stops the run there, before the release. It then holds the patched APK's
    resource table to the merge's with ResourceTableCheck.java: every resource of every package
    has to resolve by its id with its type and each configuration's value, and every file and
    reference the patched values name has to be there. A failure names the id. What the patches
    rewrote, what the rebuild renamed and what was added go in a report beside the result file.

    Last, verify-injected-registers.ps1 holds the patched dex to the stock dex: register counts,
    branch targets, invoke registers, parameter kinds, try ranges and the contract file's rules. Its
    report goes beside the result file too.

    -KeepIn names a folder where a run that passes all of that is kept, under a key made of the
    bundle, the APK, the catalog, the patch selection, the desktop CLI, the SDK stubs and every
    verifier file (Get-AppliedRecordKey in common.ps1). A later run with -KeepIn and the same key
    says it's reusing that run and stops before patching. A failed or partial run is never kept.
    The release push gate passes it, and build-release-receipt.ps1 and patch-for-device.ps1 read
    what it kept. HUSHTUMBLR_APPLY_RECORDS stands in for -KeepIn when it isn't given, so a
    machine that sets it keeps every passing local run for the gate to find. Without either,
    nothing is kept or read, and every run patches. -NoReuse patches in full even when a kept run
    matches, and keeps the new run in its place when it passes.

.EXAMPLE
    scripts/verify-all-patches.ps1 -Apk C:\path\to\native-fixture.apk `
        -DesktopJar C:\path\to\morphe-desktop.jar -WorkDir C:\path\to\scratch

.EXAMPLE
    scripts/verify-all-patches.ps1 -Apk C:\fixtures\tumblr-older.apk -Force `
        -DesktopJar C:\path\to\morphe-desktop.jar -WorkDir C:\path\to\scratch
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [Parameter(Mandatory = $true)][string]$DesktopJar,
    [Parameter(Mandatory = $true)][string]$WorkDir,
    [string]$Bundle,
    [string]$PatchList,
    [string]$Java,
    [switch]$Force,
    [string]$Aapt2,
    [string]$AndroidJar,
    [string]$ApiVersions,
    [string[]]$PatchNames,
    # The applied record store to reuse a passing run from and keep this one in.
    [string]$KeepIn,
    # Patch even when a run kept under the same key matches. A pass is still kept.
    [switch]$NoReuse
)

$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'Resolve-Java.ps1')
$Java = Resolve-Java -Explicit $Java
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'patch-report.ps1')
. (Join-Path $PSScriptRoot 'patch-target.ps1')
. (Join-Path $PSScriptRoot 'common.ps1')

if (-not $Bundle) {
    $version = Get-BundleVersion -Root $root
    $Bundle = Get-ReleaseBundlePath -Root $root -Version $version
    if (-not (Test-Path -LiteralPath $Bundle -PathType Leaf)) {
        throw "No bundle for version $version at $Bundle. Run :patches:generatePatchesList then :patches:buildAndroid."
    }
}
if (-not $PatchList) { $PatchList = Join-Path $root 'patches-list.json' }
if (-not $Bundle -or -not (Test-Path -LiteralPath $Bundle -PathType Leaf)) { throw "No bundle found. Run :patches:buildAndroid first." }
if (-not (Test-Path -LiteralPath $PatchList -PathType Leaf)) { throw "No patch list found: $PatchList" }
if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) { throw "APK not found: $Apk" }
if (-not (Test-Path -LiteralPath $DesktopJar -PathType Leaf)) { throw "Desktop CLI jar not found: $DesktopJar" }

try {
    $catalog = Get-Content -LiteralPath $PatchList -Raw | ConvertFrom-Json
    $names = @($catalog.patches | ForEach-Object { $_.name })
} catch {
    throw "Could not read patch list ${PatchList}: $($_.Exception.Message)"
}
$expectedTarget = Get-PatchTarget -PatchList $catalog
if ($names.Count -eq 0 -or @($names | Where-Object { [string]::IsNullOrWhiteSpace($_) }).Count -ne 0) {
    throw "No valid patches listed in $PatchList."
}
if ($PSBoundParameters.ContainsKey('PatchNames')) {
    if ($PatchNames.Count -eq 1) { $PatchNames = $PatchNames[0].Split(',') }
    $requested = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($name in $PatchNames) {
        if ($names -cnotcontains $name -or -not $requested.Add($name)) {
            throw "Requested patch is unknown or repeated: $name"
        }
    }
    if ($requested.Count -eq 0) { throw 'Select at least one patch for verification.' }
    $names = @($names | Where-Object { $requested.Contains($_) })
}
$dependencyNames = @(Get-PatchDependencyNames -PatchList $catalog -RequestedNames $names)
Write-Host "[verify] $($names.Count) patches from $(Split-Path -Leaf $Bundle)"

New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null
$workRoot = (Resolve-Path -LiteralPath $WorkDir).Path
$runId = [guid]::NewGuid().ToString('N')
$runDir = Join-Path $workRoot "verify-$runId"
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
$exitCode = 1
try {

# A distributor's build of Tumblr can come as a split bundle. aapt2 reads the version facts off
# one APK, the base, which holds the manifest, and the register check's clean side is the base
# too: it carries every dex and Tumblr's signature, which a merge doesn't keep. A plain APK,
# such as the universal fixture, stands in as its own base.
$stockApk = Get-BaseApk -Apk $Apk -Destination (Resolve-WithinRoot -Path (Join-Path $runDir 'stock-base.apk') -Root $workRoot)

# The version the result is held to: the stock APK's own, read the same way the receipt reads
# it. The catalog can declare more than one build, so a declared one (its version name and the code the
# catalog pins it to) is patched as a user's Manager patches it, and any other only with -Force,
# which tells the CLI to go ahead with -f.
. (Join-Path $PSScriptRoot 'release-receipt.ps1')
$Aapt2 = Resolve-Aapt2 -Explicit $Aapt2 -Root $root
$stock = Get-ApkManifestFacts -Apk $stockApk -Aapt2 $Aapt2
if ($stock.package -ne $expectedTarget.PackageName) {
    throw "$(Split-Path -Leaf $Apk) is $($stock.package), not the catalog's target $($expectedTarget.PackageName)."
}
if ([string]::IsNullOrWhiteSpace($stock.versionName)) {
    throw "$(Split-Path -Leaf $Apk) carries no versionName, so there is nothing to hold the result to."
}
$expectedVersion = $stock.versionName
# A declared build by version code too: another arm64 build of a declared version has its own dex.
$forced = -not (Test-DeclaredBuild -Target $expectedTarget -VersionName ([string]$stock.versionName) `
    -VersionCode ([string]$stock.versionCode))
if ($forced -and -not $Force) {
    throw ("$(Split-Path -Leaf $Apk) is $($stock.versionName), which the bundle does not declare, at version code " +
        "$($stock.versionCode). It declares $(Format-DeclaredBuilds -Target $expectedTarget). Pass -Force to patch it anyway.")
}
if ($forced) {
    Write-Host ("[verify] forcing the bundle onto $($stock.package) $($stock.versionName) ($($stock.versionCode)); " +
        "it declares $(Format-DeclaredBuilds -Target $expectedTarget)")
} else {
    Write-Host "[verify] $($stock.package) $($stock.versionName) is a declared target, so nothing is forced"
}

# A run kept under the same key already patched these bytes with this verifier and passed every
# check below, so it's handed back instead, once its report still reads as complete. Any input
# that moved gives another key and a full run.
$appliedIdentity = $null
if (-not $KeepIn -and $env:HUSHTUMBLR_APPLY_RECORDS) { $KeepIn = $env:HUSHTUMBLR_APPLY_RECORDS }
if ($KeepIn) {
    $hostStubs = Resolve-HostReferenceStubs -AndroidJar $AndroidJar -ApiVersions $ApiVersions -Aapt2 $Aapt2
    $AndroidJar = $hostStubs.AndroidJar
    $ApiVersions = $hostStubs.ApiVersions
    $KeepIn = Get-AppliedRecordStore -Path $KeepIn
    $appliedIdentity = Get-AppliedRecordKey -Bundle $Bundle -Fixture $Apk -PatchList $PatchList -PatchNames $names `
        -Forced $forced -DesktopJar $DesktopJar -AndroidJar $AndroidJar -ApiVersions $ApiVersions
    $recorded = if ($NoReuse) { $null } else { Find-AppliedRecord -Store $KeepIn -Key $appliedIdentity.Key }
    if ($NoReuse) {
        Write-Host "[verify] -NoReuse: patching in full, and keeping the run as $($appliedIdentity.Key) if it passes"
    } elseif ($recorded) {
        $recordedReport = $null
        try { $recordedReport = Get-Content -LiteralPath $recorded.Result -Raw | ConvertFrom-Json }
        catch { Write-Warning "Could not parse the kept result JSON: $($_.Exception.Message)" }
        $recordedCheck = Test-PatchingReport -Report $recordedReport -ExpectedNames $names `
            -AllowedDependencyNames $dependencyNames -OutputPath $recorded.PatchedApk `
            -ExpectedPackageName $expectedTarget.PackageName -ExpectedPackageVersion $expectedVersion
        if ($recordedCheck.Valid) {
            Write-Host ("[verify] reusing the run kept as $($appliedIdentity.Key) at $($recorded.RecordedAt): the " +
                'bundle, the APK, the patch selection, the tools and every verifier file are unchanged, and it ' +
                'passed every check, so nothing is patched again.')
            Write-Host "[verify] kept run: $($recorded.Directory)"
            $exitCode = 0
            exit $exitCode
        }
        Write-Warning "[verify] the kept run $($appliedIdentity.Key) doesn't pass the report check ($($recordedCheck.Reason)); patching again."
    } else {
        Write-Host "[verify] no run kept as $($appliedIdentity.Key) in $KeepIn, so this one patches in full"
    }
}

$out = Resolve-WithinRoot -Path (Join-Path $runDir 'verify-all.apk') -Root $workRoot
$temp = Resolve-WithinRoot -Path (Join-Path $runDir 'verify-all-tmp') -Root $workRoot
$result = Resolve-WithinRoot -Path (Join-Path $workRoot "verify-all-result-$runId.json") -Root $workRoot
    # What the CLI patches and what the patched table is held to, one file: a bundle's merge, made
    # here because the CLI deletes its own, or the APK itself. A bundle that won't merge stops the
    # run; base.apk alone lacks the splits' resources and would pass a table that lost them.
    $mergedApk = Resolve-WithinRoot -Path (Join-Path $runDir 'stock-merged.apk') -Root $workRoot
    $patchInput = Get-MergedApk -Apk $Apk -Destination $mergedApk -Java $Java -DesktopJar $DesktopJar
    if ($patchInput -eq $mergedApk) { Write-Host "[verify] merged $(Split-Path -Leaf $Apk) into one APK for the CLI" }
    $enable = @()
    foreach ($name in $names) { $enable += '-e'; $enable += $name }
    $arguments = @('patch', '--exclusive', '--continue-on-error', '--unsigned', '-p', $Bundle,
        '-o', $out, '-t', $temp, '-r', $result)
    if ($forced) { $arguments += '-f' }
    $arguments = $arguments + $enable + @($patchInput)

    # Continue for the call alone: the CLI logs WARNING and SEVERE on stderr, which Windows
    # PowerShell 5.1 turns into a terminating error under Stop. The report and the exit code decide.
    # The run waits for a slot in the machine's build queue first (Enter-HushTumblrQueue).
    $preference = $ErrorActionPreference
    $queued = Enter-HushTumblrQueue -Job 'verify patch'
    try {
        $ErrorActionPreference = 'Continue'
        $global:LASTEXITCODE = -1
        $cliOutput = @(& $Java '-jar' $DesktopJar @arguments 2>&1)
        $cliExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $preference
        Exit-HushTumblrQueue $queued
    }
    # WARNING lines are the patches' own: a patch that works down a list of targets names each one
    # the build lacks there, and still applies.
    $cliOutput | ForEach-Object {
        $line = [string]$_
        if ($line -match 'SEVERE|ERROR|WARNING|Exception|result saved|Saved to') { Write-Host "[verify] $line" }
    }

    $report = $null
    if (Test-Path -LiteralPath $result -PathType Leaf) {
        try { $report = Get-Content -LiteralPath $result -Raw | ConvertFrom-Json }
        catch { Write-Warning "Could not parse result JSON: $($_.Exception.Message)" }
    }
    $validation = Test-PatchingReport -Report $report -ExpectedNames $names `
        -AllowedDependencyNames $dependencyNames -OutputPath $out `
        -ExpectedPackageName $expectedTarget.PackageName -ExpectedPackageVersion $expectedVersion
    $reportApplied = if ($null -ne $report) { @($report.appliedPatches).Count } else { 0 }
    $reportFailed = if ($null -ne $report) { @($report.failedPatches).Count } else { 0 }
    $target = if ($null -ne $report) { "$($report.packageName) $($report.packageVersion)" } else { 'unknown target' }
    Write-Host "[verify] ${target}: applied $reportApplied, failed $reportFailed, CLI exit $cliExitCode"
    if ($null -ne $report) {
        foreach ($failure in @($report.failedPatches)) {
            $patchName = if ($null -ne $failure.patch) { $failure.patch.name } else { 'unknown patch' }
            Write-Host "[verify] FAILED ${patchName}: $($failure.reason -split "`n" | Select-Object -First 1)"
        }
    }
    Write-Host "[verify] result file: $result"
    if ($cliExitCode -ne 0) { Write-Warning "The desktop CLI exited with $cliExitCode." }
    if (-not $validation.Valid) { Write-Warning "[verify] $($validation.Reason)" }
    $unapprovedChanges = @()
    if ($cliExitCode -eq 0 -and $validation.Valid) {
        # What patching did to the manifest, read the way the release receipt reads it, against the
        # APK the CLI patched, and held to the same allowlist, so a change nobody approved stops this
        # run and not only the release. Expected transforms depend on the selected patch families.
        $stockManifest = Get-ApkManifestFacts -Apk $patchInput -Aapt2 $Aapt2
        $patchedManifest = Get-ApkManifestFacts -Apk $out -Aapt2 $Aapt2
        $floor = Test-PatchedMinSdk -StockMinSdk $stockManifest.minSdk -PatchedMinSdk $patchedManifest.minSdk
        if (-not $floor.Valid) { throw "[verify] $($floor.Reason)" }
        Write-Host "[verify] binary minSdk: $($stockManifest.minSdk) -> $($patchedManifest.minSdk) (max(stock, 26))"
        $manifestSelection = @($names) + @($dependencyNames)
        $approvedChanges = @(Read-ManifestDeltaAllowlist -Path (Join-Path $PSScriptRoot 'manifest-delta-allowlist.txt') `
            -SelectedPatchNames $manifestSelection)
        $manifestCheck = Test-ManifestDelta -Stock $stockManifest -Patched $patchedManifest `
            -SelectedPatchNames $manifestSelection -ApprovedManifestDelta $approvedChanges
        $manifestChanges = @($manifestCheck.Entries)
        if (-not $manifestCheck.Valid) { throw "[verify] $($manifestCheck.Reason)" }
        Write-Host "[verify] manifest delta: $($manifestChanges.Count) change(s), all match selected transforms"
        foreach ($change in $manifestChanges) {
            Write-Host "[verify]   $change (approved)"
        }
    }
    if ($cliExitCode -eq 0 -and $validation.Valid -and $unapprovedChanges.Count -gt 0) {
        Write-Warning ('[verify] the patched manifest changed in ways scripts/manifest-delta-allowlist.txt ' +
            "doesn't approve: $($unapprovedChanges -join ', ')")
    } elseif ($cliExitCode -eq 0 -and $validation.Valid) {
        # The rebuilt resource table against the stock one. A resource patch has Morphe decode and
        # rebuild the app's whole table, and an id the rebuild loses only fails when the app
        # inflates it (Hushfeed upstream #84, a layout in one of TikTok's feature packages). The
        # stock side is the APK the CLI patched, a split bundle's merge among them: that is the
        # table the patched APK was rebuilt from.
        $resourceReport = Resolve-WithinRoot -Path (Join-Path $workRoot "verify-all-resources-$runId.txt") -Root $workRoot
        # Continue for the call alone, as for the CLI: a JDK note or a stack trace on stderr would
        # otherwise end the run under Windows PowerShell 5.1 before the exit code is read.
        $preference = $ErrorActionPreference
        $queued = Enter-HushTumblrQueue -Job 'verify resources'
        try {
            $ErrorActionPreference = 'Continue'
            $global:LASTEXITCODE = -1
            $resourceOutput = @(& $Java '-Xmx4g' '-cp' $DesktopJar (Join-Path $PSScriptRoot 'ResourceTableCheck.java') `
                $patchInput $out $resourceReport 2>&1)
            $resourceExitCode = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $preference
            Exit-HushTumblrQueue $queued
        }
        $resourceOutput | ForEach-Object { Write-Host "[verify] $_" }
        Write-Host "[verify] resource report: $resourceReport"
        if ($resourceExitCode -eq 0) {
            # The injected code against Tumblr's: registers, branches, invokes, parameters and try
            # ranges, the shapes that pass the CLI and fail on a device. The rules of
            # injected-mutation-contracts.txt ride along; they're project contracts, which the
            # device verifier doesn't check.
            $registerReport = Resolve-WithinRoot -Path (Join-Path $workRoot "verify-all-registers-$runId.txt") -Root $workRoot
            $global:LASTEXITCODE = 0
            & (Join-Path $PSScriptRoot 'verify-injected-registers.ps1') -CleanApk $stockApk -CleanMerged $patchInput `
                -PatchedApk $out -ReportPath $registerReport -Java $Java -DesktopJar $DesktopJar -Aapt2 $Aapt2 `
                -SelectedPatchNames $manifestSelection -AndroidJar $AndroidJar -ApiVersions $ApiVersions
            $registerExitCode = $LASTEXITCODE
            Write-Host "[verify] register report: $registerReport"
            if ($registerExitCode -eq 0) {
                Write-Host ('[verify] success: every requested patch applied to a valid APK whose manifest changes ' +
                    'are all approved, whose resource table holds every stock resource and whose injected code ' +
                    'passes the structural checks.')
                # Kept only here, after every check passed. Not keeping it costs a later run a
                # patch, not this one its verdict, so a store that can't be written is a warning.
                if ($appliedIdentity) {
                    try {
                        $keptRun = Save-AppliedRecord -Store $KeepIn -Identity $appliedIdentity -PatchedApk $out `
                            -Result $result -Target $stock -Reports ([ordered]@{
                                'resources.txt'       = $resourceReport
                                'registers.txt'       = $registerReport
                                'host-references.txt' = "$registerReport.host-references.txt" })
                        Write-Host "[verify] kept this run as $($appliedIdentity.Key): $keptRun"
                    } catch {
                        Write-Warning "[verify] this run passed but could not be kept in ${KeepIn}: $($_.Exception.Message)"
                    }
                }
                $exitCode = 0
            } else {
                Write-Warning "[verify] the injected code failed its structural checks (exit $registerExitCode)."
            }
        } else {
            Write-Warning "[verify] the patched resource table failed its check against the stock one (exit $resourceExitCode)."
        }
    }
} finally {
    Remove-GeneratedPath -Path $runDir -Root $workRoot
}

exit $exitCode
