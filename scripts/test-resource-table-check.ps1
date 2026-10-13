<#
.SYNOPSIS
    Exercise ResourceTableCheck.java against small APKs that aapt2 builds.

.DESCRIPTION
    The stock APK is Tumblr's own package, id 0x7f: two colors (one with a night value), a theme
    on a base theme, and one layout. A resource patch has Morphe rebuild the whole table, and a
    layout the rebuild drops only fails when the app inflates it (Hushfeed upstream #84, where
    this check came from). Each patched APK moves one thing. An unchanged table, a rewritten color
    and a renamed entry pass and are reported. A lost layout, a layout file missing from the
    archive, a changed layout file, a lost style item, a changed parent, a lost night value, a
    reference to nothing, a renamed type and a missing package fail, and each failure names what
    it lost. APKMirror ships Tumblr as a split bundle, and merging one into one APK drops the
    split descriptor bundletool
    wrote (xml/splits0): that loss passes and is reported, and any other lost xml resource still
    fails.

    A real split bundle too, built by aapt2 and merged by the CLI's own merger through
    MergeSplits.java, in both shapes such a bundle can come in: an XAPK laid out the way APKPure
    lays out a split Tumblr build (the base named for the package, config splits, manifest.json and
    icon.png) and an .apkm. Those APKs are unsigned, so they go through the steps Get-MergedApk
    takes once the signatures pass (unpack, lock and hash, merge exactly those bytes), called by
    this test alone. A resource only the split carries is in each merge, and a table that lost it
    fails against the merge while base.apk would only have called it added. Get-MergedApk refuses
    an unsigned plain APK and a bundle with no APK before anything is copied or merged.
    MergeSplits.java itself refuses a bundle handed to it straight, a directory holding anything
    but regular .apk files, and a directory that isn't exactly the APKs it was handed hashes of,
    with exactly those bytes.

    Then the same APKs signed with keytool and apksigner, one key standing for Tumblr's and one for
    anybody else's: a bundle whose APKs all carry the declared key merges, with a record of the
    check beside it that Assert-AuthenticMerge accepts, and an unsigned universal APK passes as the
    merge of the same-named bundle beside it. An unsigned split, a split signed by the other key,
    a split changed after signing, and a universal APK whose dex, native libraries, assets or res
    files aren't the bundle's are each refused before the JDK starts, leaving nothing behind. A
    universal APK made only of the bundle's own files is still refused when it isn't the bundle's
    merge: one that left an asset out, one with the base APK's resource table and nothing the split
    adds, and one with a signed res file under another name. Those take the merge to tell, so the
    JDK runs for them, and they leave nothing behind either.

    Last, what a device with 16 KB pages needs of a patched APK (Assert-PageAlignment), with the
    build-tools' zipalign and apksigner: an APK aligned and then signed passes, the same files left
    as jar packed them fail zipalign -c -P 16 -v 4, and an added library with a 4 KB LOAD segment,
    a GNU_RELRO that ends inside a 16 KB page or program headers that run past its end is refused
    on its own in an aligned APK. The same faults in a library the stock APK holds come back as
    target limitations on a signed APK, and a stock library rewritten to fit is refused.
#>
[CmdletBinding()]
param(
    [string]$Root,
    [string]$Java,
    [string]$DesktopJar,
    [string]$Aapt2
)

$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
. (Join-Path $PSScriptRoot 'Resolve-Java.ps1')
. (Join-Path $PSScriptRoot 'common.ps1')
. (Join-Path $PSScriptRoot 'release-receipt.ps1')

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

# Native calls run with Continue: under Stop, Windows PowerShell 5.1 (the hook's fallback host)
# turns any stderr line, a JDK warning included, into a terminating error. The exit code is judged.
function Invoke-Checked {
    param([string]$Program, [string[]]$Arguments, [string]$Description)
    $ErrorActionPreference = 'Continue'
    $output = @(& $Program @Arguments 2>&1 | ForEach-Object { "$_" })
    if ($LASTEXITCODE -ne 0) {
        throw "$Description exited $LASTEXITCODE.`n$($output -join "`n")"
    }
}

$package = 'com.tumblr'
$manifest = @"
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$package">
    <application android:theme="@style/SearchTheme" />
</manifest>
"@
$colors = @'
<resources>
    <color name="accent">#ffff3b5c</color>
    <color name="surface">#ff121212</color>
</resources>
'@
$nightColors = @'
<resources>
    <color name="surface">#ff000000</color>
</resources>
'@
$styles = @'
<resources>
    <style name="BaseTheme">
        <item name="android:windowNoTitle">true</item>
    </style>
    <style name="SearchTheme" parent="BaseTheme">
        <item name="android:windowBackground">@color/surface</item>
        <item name="android:textColorPrimary">@color/accent</item>
    </style>
</resources>
'@
$layout = @'
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent" android:layout_height="match_parent"
    android:background="@color/surface" />
'@

<#
    Links one APK from the files given, keyed by path under res/. With -EmitIds the ids aapt2
    chose are written out; with -StableIds every build keeps the stock build's ids.
#>
function New-ResourceApk {
    param([string]$Name, [System.Collections.IDictionary]$Files, [string]$EmitIds, [string]$StableIds, [string]$PackageId = '0x7f')
    $dir = Join-Path $caseRoot $Name
    foreach ($entry in $Files.GetEnumerator()) {
        $path = Join-Path $dir ($entry.Key -replace '/', [System.IO.Path]::DirectorySeparatorChar)
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $path) | Out-Null
        [System.IO.File]::WriteAllText($path, $entry.Value, [System.Text.UTF8Encoding]::new($false))
    }
    $manifestPath = Join-Path $dir 'AndroidManifest.xml'
    [System.IO.File]::WriteAllText($manifestPath, $manifest, [System.Text.UTF8Encoding]::new($false))
    $compiled = Join-Path $dir 'compiled.zip'
    Invoke-Checked -Program $Aapt2 -Arguments @('compile', '--dir', (Join-Path $dir 'res'), '-o', $compiled) `
        -Description "aapt2 compile for $Name"
    $apk = Join-Path $caseRoot "$Name.apk"
    $link = @('link', '-o', $apk, '-I', $androidJar, '--manifest', $manifestPath,
        '--package-id', $PackageId, '--allow-reserved-package-id')
    if ($EmitIds) { $link += @('--emit-ids', $EmitIds) }
    if ($StableIds) { $link += @('--stable-ids', $StableIds) }
    Invoke-Checked -Program $Aapt2 -Arguments ($link + @($compiled)) -Description "aapt2 link for $Name"
    return $apk
}

function Copy-ApkWith {
    param([string]$From, [string]$Name, [scriptblock]$Change)
    $apk = Join-Path $caseRoot "$Name.apk"
    Copy-Item -LiteralPath $From -Destination $apk
    $archive = [System.IO.Compression.ZipFile]::Open($apk, [System.IO.Compression.ZipArchiveMode]::Update)
    try { & $Change $archive } finally { $archive.Dispose() }
    return $apk
}

function Edit-Table {
    param([string]$Name, [byte[]]$Find, [byte[]]$Replace, [string]$What)
    return Copy-ApkWith -From $stockApk -Name $Name -Change {
        param($archive)
        $entry = $archive.GetEntry('resources.arsc')
        $stream = $entry.Open()
        try { $buffer = [System.IO.MemoryStream]::new(); $stream.CopyTo($buffer); $bytes = $buffer.ToArray() } finally { $stream.Dispose() }
        $hits = @()
        for ($i = 0; $i -le $bytes.Length - $Find.Length; $i++) {
            $match = $true
            for ($j = 0; $j -lt $Find.Length; $j++) { if ($bytes[$i + $j] -ne $Find[$j]) { $match = $false; break } }
            if ($match) { $hits += $i }
        }
        if ($hits.Count -ne 1) { throw "expected one $What in the table, found $($hits.Count)" }
        $Replace.CopyTo($bytes, $hits[0])
        $entry.Delete()
        $replacement = $archive.CreateEntry('resources.arsc', [System.IO.Compression.CompressionLevel]::NoCompression)
        $out = $replacement.Open()
        try { $out.Write($bytes, 0, $bytes.Length) } finally { $out.Dispose() }
    }
}

function Invoke-Check {
    param([string]$Patched, [string]$Name, [string]$Stock = $stockApk)
    $report = Join-Path $caseRoot "$Name-report.txt"
    $ErrorActionPreference = 'Continue'
    $global:LASTEXITCODE = 0
    $output = @(& $Java '-Xmx1g' '-cp' $DesktopJar (Join-Path $PSScriptRoot 'ResourceTableCheck.java') `
        $Stock $Patched $report 2>&1 | ForEach-Object { "$_" })
    [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = ($output -join "`n"); Report = $report }
}

# The call lines themselves, not any mention: the help text names the check too, and the gate's
# path list names this suite without running it.
$verifyText = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'verify-all-patches.ps1') -Raw
Assert-True ($verifyText -match '(?m)^[^#\r\n]*&\s*\$Java\b[^\r\n]*ResourceTableCheck\.java') `
    'verify-all-patches.ps1 does not run ResourceTableCheck.java.'
$prePushText = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'pre-push.ps1') -Raw
Assert-True ($prePushText -match "(?m)^\s*\`$suites \+= , @\('scripts/test-resource-table-check\.ps1'") `
    'The push gate does not run the resource table check fixtures.'

$Java = Resolve-Java -Explicit $Java
$DesktopJar = Resolve-DesktopCli -Explicit $DesktopJar -Root $Root -Required
$Aapt2 = Resolve-Aapt2 -Explicit $Aapt2 -Root $Root
# The platform jar beside the SDK's build-tools: sdk/build-tools/<version>/aapt2 -> sdk/platforms.
$sdk = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $Aapt2))
$androidJar = Get-ChildItem -LiteralPath (Join-Path $sdk 'platforms') -Directory -ErrorAction SilentlyContinue |
    ForEach-Object { Join-Path $_.FullName 'android.jar' } |
    Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -Last 1
if (-not $androidJar) { throw "No platforms/*/android.jar in the SDK at $sdk for aapt2 to link against." }

$tempBase = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
$caseRoot = [System.IO.Path]::GetFullPath((Join-Path $tempBase `
    ('hushtumblr-resource-check-' + [guid]::NewGuid().ToString('N'))))
$requiredPrefix = $tempBase.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + `
    [System.IO.Path]::DirectorySeparatorChar
if (-not $caseRoot.StartsWith($requiredPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to create test files outside the temporary directory: $caseRoot"
}

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
# The merges below are of fixture-sized bundles, so they run straight away rather than waiting in
# the machine's build queue behind real builds. The queue itself is held by test-script-contracts.ps1.
$savedQueueScript = $env:BUILD_QUEUE_SCRIPT
$env:BUILD_QUEUE_SCRIPT = $null
try {
    New-Item -ItemType Directory -Path $caseRoot | Out-Null
    $ids = Join-Path $caseRoot 'stock-ids.txt'
    $stockFiles = [ordered]@{
        'res/values/colors.xml' = $colors
        'res/values-night/colors.xml' = $nightColors
        'res/values/styles.xml' = $styles
        'res/layout/feed_story.xml' = $layout
    }
    $stockApk = New-ResourceApk -Name 'stock' -Files $stockFiles -EmitIds $ids
    $idOf = @{}
    foreach ($line in Get-Content -LiteralPath $ids) {
        if ($line -match '^[^:]+:(\S+) = (0x[0-9a-f]{8})$') { $idOf[$Matches[1]] = $Matches[2] }
    }
    Assert-True ($idOf['layout/feed_story'] -like '0x7f*' -and $idOf['color/surface'] -like '0x7f*') `
        "aapt2 did not build package 0x7f: $($idOf.Keys -join ', ')"

    $same = Invoke-Check -Patched $stockApk -Name 'unchanged'
    Assert-True ($same.ExitCode -eq 0) "An unchanged table failed.`n$($same.Output)"
    Assert-True ($same.Output -match 'resolves in the patched table') "An unchanged table was not called whole.`n$($same.Output)"

    $blackFiles = [ordered]@{
        'res/values/colors.xml' = $colors -replace '#ffff3b5c', '#ff000000'
        'res/values/styles.xml' = $styles
        'res/layout/feed_story.xml' = $layout
        'res/values-night/colors.xml' = $nightColors
    }
    $black = Invoke-Check -Patched (New-ResourceApk -Name 'rewritten' -Files $blackFiles -StableIds $ids) -Name 'rewritten'
    Assert-True ($black.ExitCode -eq 0) "A rewritten color failed the check.`n$($black.Output)"
    Assert-True ($black.Output -match [regex]::Escape("$($idOf['color/accent']) color/accent [default] #ffff3b5c -> #ff000000")) `
        "The rewritten color was not reported with its id and both values.`n$($black.Output)"

    $renamedIds = Join-Path $caseRoot 'renamed-ids.txt'
    [System.IO.File]::WriteAllText($renamedIds, ((Get-Content -LiteralPath $ids -Raw) -replace ':color/accent =', ':color/accent_renamed ='))
    $renamedFiles = [ordered]@{
        'res/values/colors.xml' = $colors -replace '"accent"', '"accent_renamed"'
        'res/values/styles.xml' = $styles -replace '@color/accent<', '@color/accent_renamed<'
        'res/layout/feed_story.xml' = $layout
        'res/values-night/colors.xml' = $nightColors
    }
    $renamed = Invoke-Check -Patched (New-ResourceApk -Name 'renamed' -Files $renamedFiles -StableIds $renamedIds) -Name 'renamed'
    Assert-True ($renamed.ExitCode -eq 0) "A renamed entry that keeps its id failed.`n$($renamed.Output)"
    Assert-True ($renamed.Output -match [regex]::Escape("$($idOf['color/accent']) color/accent is color/accent_renamed")) `
        "The renamed entry was not reported.`n$($renamed.Output)"

    $noLayoutFiles = [ordered]@{
        'res/values/colors.xml' = $colors
        'res/values/styles.xml' = $styles
        'res/values-night/colors.xml' = $nightColors
    }
    $lost = Invoke-Check -Patched (New-ResourceApk -Name 'lost-layout' -Files $noLayoutFiles -StableIds $ids) -Name 'lost-layout'
    Assert-True ($lost.ExitCode -eq 1) "A table that lost layout/feed_story passed.`n$($lost.Output)"
    Assert-True ($lost.Output -match [regex]::Escape("FAIL $($idOf['layout/feed_story']) layout/feed_story: not in the patched table")) `
        "The lost layout's failure did not name its id.`n$($lost.Output)"
    Assert-True ((Get-Content -LiteralPath $lost.Report -Raw) -match [regex]::Escape($idOf['layout/feed_story'])) `
        'The report file did not name the lost id.'

    $noFile = Copy-ApkWith -From $stockApk -Name 'lost-file' -Change {
        param($archive)
        $entry = $archive.GetEntry('res/layout/feed_story.xml')
        if (-not $entry) { throw 'the stock APK has no res/layout/feed_story.xml to remove' }
        $entry.Delete()
    }
    $file = Invoke-Check -Patched $noFile -Name 'lost-file'
    Assert-True ($file.ExitCode -eq 1) "A layout whose file left the archive passed.`n$($file.Output)"
    Assert-True ($file.Output -match [regex]::Escape("FAIL $($idOf['layout/feed_story']) layout/feed_story [default]: names res/layout/feed_story.xml, which is not in the patched archive")) `
        "The missing file's failure did not name the id and the path.`n$($file.Output)"

    $noItemFiles = [ordered]@{
        'res/values/colors.xml' = $colors
        'res/values/styles.xml' = $styles -replace '(?m)^\s*<item name="android:textColorPrimary">@color/accent</item>\r?\n', ''
        'res/layout/feed_story.xml' = $layout
        'res/values-night/colors.xml' = $nightColors
    }
    $item = Invoke-Check -Patched (New-ResourceApk -Name 'lost-item' -Files $noItemFiles -StableIds $ids) -Name 'lost-item'
    Assert-True ($item.ExitCode -eq 1) "A style that lost an item passed.`n$($item.Output)"
    Assert-True ($item.Output -match ([regex]::Escape("FAIL $($idOf['style/SearchTheme']) style/SearchTheme [default] item 0x01010036") + '.*the item is gone')) `
        "The lost item's failure did not name the style and the attr.`n$($item.Output)"

    $noNightFiles = [ordered]@{
        'res/values/colors.xml' = $colors
        'res/values/styles.xml' = $styles
        'res/layout/feed_story.xml' = $layout
    }
    $night = Invoke-Check -Patched (New-ResourceApk -Name 'lost-night' -Files $noNightFiles -StableIds $ids) -Name 'lost-night'
    Assert-True ($night.ExitCode -eq 1) "A color that lost its night value passed.`n$($night.Output)"
    Assert-True ($night.Output -match ([regex]::Escape("FAIL $($idOf['color/surface']) color/surface [night") + '[^\]]*\]: the value is gone')) `
        "The lost night value's failure did not name the id and the configuration.`n$($night.Output)"

    $orphanFiles = [ordered]@{
        'res/values/colors.xml' = $colors
        'res/values-night/colors.xml' = $nightColors
        'res/values/styles.xml' = $styles -replace ' parent="BaseTheme"', ''
        'res/layout/feed_story.xml' = $layout
    }
    $orphan = Invoke-Check -Patched (New-ResourceApk -Name 'lost-parent' -Files $orphanFiles -StableIds $ids) -Name 'lost-parent'
    Assert-True ($orphan.ExitCode -eq 1) "A style that lost its parent passed.`n$($orphan.Output)"
    Assert-True ($orphan.Output -match ([regex]::Escape("FAIL $($idOf['style/SearchTheme']) style/SearchTheme [default]: its parent @$($idOf['style/BaseTheme']) became @null"))) `
        "The lost parent's failure did not name the style and both parents.`n$($orphan.Output)"

    $changedFile = Copy-ApkWith -From $stockApk -Name 'changed-file' -Change {
        param($archive)
        $entry = $archive.GetEntry('res/layout/feed_story.xml')
        $stream = $entry.Open()
        try { $buffer = [System.IO.MemoryStream]::new(); $stream.CopyTo($buffer); $bytes = $buffer.ToArray() } finally { $stream.Dispose() }
        $entry.Delete()
        $replacement = $archive.CreateEntry('res/layout/feed_story.xml', [System.IO.Compression.CompressionLevel]::NoCompression)
        $out = $replacement.Open()
        try { $out.Write($bytes, 0, $bytes.Length); $out.Write([byte[]](0, 0, 0, 0), 0, 4) } finally { $out.Dispose() }
    }
    $changed = Invoke-Check -Patched $changedFile -Name 'changed-file'
    Assert-True ($changed.ExitCode -eq 1) "A layout file with other bytes passed.`n$($changed.Output)"
    Assert-True ($changed.Output -match [regex]::Escape("FAIL $($idOf['layout/feed_story']) layout/feed_story [default]: res/layout/feed_story.xml is not the file the stock archive holds for it")) `
        "The changed file's failure did not name the id and the path.`n$($changed.Output)"

    # A type renamed in the table's own type pool: every color resolves to a type called colox.
    # The pool is UTF-16 as aapt2 writes type names (length 5, then the characters); UTF-8 is the
    # other layout a pool can have.
    try {
        $typeRenamed = Edit-Table -Name 'renamed-type' -What 'UTF-16 type name "color"' `
            -Find ([byte[]](5, 0, 99, 0, 111, 0, 108, 0, 111, 0, 114, 0)) -Replace ([byte[]](5, 0, 99, 0, 111, 0, 108, 0, 111, 0, 120, 0))
    } catch {
        $typeRenamed = Edit-Table -Name 'renamed-type-utf8' -What 'UTF-8 type name "color"' `
            -Find ([byte[]](5, 5, 99, 111, 108, 111, 114)) -Replace ([byte[]](5, 5, 99, 111, 108, 111, 120))
    }
    $type = Invoke-Check -Patched $typeRenamed -Name 'renamed-type'
    Assert-True ($type.ExitCode -eq 1) "A table whose color type changed name passed.`n$($type.Output)"
    Assert-True ($type.Output -match ([regex]::Escape("FAIL $($idOf['color/accent']) color/accent: is colox/accent in the patched table"))) `
        "The changed type's failure did not name the id and both types.`n$($type.Output)"

    $otherPackage = New-ResourceApk -Name 'other-package' -Files $stockFiles -PackageId '0x7e'
    $package = Invoke-Check -Patched $otherPackage -Name 'other-package'
    Assert-True ($package.ExitCode -eq 1) "A table without the stock package passed.`n$($package.Output)"
    Assert-True ($package.Output -match 'FAIL package 0x7f com.tumblr: not in the patched table') `
        "The missing package's failure did not name the package.`n$($package.Output)"

    # A value that points at nothing: the theme's window background, as bytes, moved to an id the
    # table does not have.
    $surface = [Convert]::ToInt32($idOf['color/surface'].Substring(2), 16)
    $nowhere = $surface -bor 0xfff0
    $dangling = Copy-ApkWith -From $stockApk -Name 'dangling' -Change {
        param($archive)
        $entry = $archive.GetEntry('resources.arsc')
        $stream = $entry.Open()
        try { $buffer = [System.IO.MemoryStream]::new(); $stream.CopyTo($buffer); $bytes = $buffer.ToArray() } finally { $stream.Dispose() }
        $pattern = [byte[]](@(8, 0, 0, 1) + [BitConverter]::GetBytes([int]$surface))
        $hits = @()
        for ($i = 0; $i -le $bytes.Length - $pattern.Length; $i++) {
            $match = $true
            for ($j = 0; $j -lt $pattern.Length; $j++) { if ($bytes[$i + $j] -ne $pattern[$j]) { $match = $false; break } }
            if ($match) { $hits += $i }
        }
        if ($hits.Count -ne 1) { throw "expected one reference to color/surface in the table, found $($hits.Count)" }
        [BitConverter]::GetBytes([int]$nowhere).CopyTo($bytes, $hits[0] + 4)
        $entry.Delete()
        $replacement = $archive.CreateEntry('resources.arsc', [System.IO.Compression.CompressionLevel]::NoCompression)
        $out = $replacement.Open()
        try { $out.Write($bytes, 0, $bytes.Length) } finally { $out.Dispose() }
    }
    $pointer = Invoke-Check -Patched $dangling -Name 'dangling'
    $nowhereHex = '0x{0:x8}' -f $nowhere
    Assert-True ($pointer.ExitCode -eq 1) "A reference to nothing passed.`n$($pointer.Output)"
    Assert-True ($pointer.Output -match ([regex]::Escape("FAIL $($idOf['style/SearchTheme']) style/SearchTheme [default] item 0x01010054") + ".*points at $nowhereHex, which is not in the patched table")) `
        "The dangling reference's failure did not name the style, the attr and the id.`n$($pointer.Output)"

    # The split descriptor a merge drops on purpose, beside an xml resource it must not drop.
    $splitIds = Join-Path $caseRoot 'split-ids.txt'
    $splitFiles = [ordered]@{
        'res/values/colors.xml' = $colors
        'res/values-night/colors.xml' = $nightColors
        'res/values/styles.xml' = $styles
        'res/layout/feed_story.xml' = $layout
        'res/xml/splits0.xml' = '<splits><module name="base" /></splits>'
        'res/xml/feed_prefs.xml' = '<PreferenceScreen />'
    }
    $splitStock = New-ResourceApk -Name 'split-stock' -Files $splitFiles -EmitIds $splitIds
    $splitIdOf = @{}
    foreach ($line in Get-Content -LiteralPath $splitIds) {
        if ($line -match '^[^:]+:(\S+) = (0x[0-9a-f]{8})$') { $splitIdOf[$Matches[1]] = $Matches[2] }
    }
    $mergedFiles = [ordered]@{}
    foreach ($entry in $splitFiles.GetEnumerator()) { if ($entry.Key -ne 'res/xml/splits0.xml') { $mergedFiles[$entry.Key] = $entry.Value } }
    $merged = Invoke-Check -Stock $splitStock -Patched (New-ResourceApk -Name 'merged' -Files $mergedFiles -StableIds $splitIds) -Name 'merged'
    Assert-True ($merged.ExitCode -eq 0) "A merge that dropped only the split descriptor failed.`n$($merged.Output)"
    Assert-True ($merged.Output -match ('dropped with the split metadata when the bundle was merged: 1\s+' +
        [regex]::Escape("$($splitIdOf['xml/splits0']) xml/splits0"))) `
        "The dropped split descriptor was not reported with its id.`n$($merged.Output)"
    $lostXmlFiles = [ordered]@{}
    foreach ($entry in $splitFiles.GetEnumerator()) { if ($entry.Key -ne 'res/xml/feed_prefs.xml') { $lostXmlFiles[$entry.Key] = $entry.Value } }
    $lostXml = Invoke-Check -Stock $splitStock -Patched (New-ResourceApk -Name 'lost-xml' -Files $lostXmlFiles -StableIds $splitIds) -Name 'lost-xml'
    Assert-True ($lostXml.ExitCode -eq 1) "A table that lost xml/feed_prefs passed as a split descriptor.`n$($lostXml.Output)"
    Assert-True ($lostXml.Output -match [regex]::Escape("FAIL $($splitIdOf['xml/feed_prefs']) xml/feed_prefs: not in the patched table")) `
        "The lost xml resource's failure did not name its id.`n$($lostXml.Output)"

    # A real split bundle, merged by the CLI's own merger through MergeSplits.java.
    # aapt2 puts every xxhdpi value in a config split, so dimen/tray_height, which has no other
    # value, is in the split alone. Held to base.apk, the merge's copy of it is only "added" and
    # never compared, which is how 7,588 resources of the Facebook sibling's 580 went unchecked
    # once the CLI stopped leaving its merge behind; held to the merge, a table that lost it fails
    # by name.
    $bundleDir = Join-Path $caseRoot 'bundle'
    New-Item -ItemType Directory -Force -Path (Join-Path $bundleDir 'res/values'), (Join-Path $bundleDir 'res/values-xxhdpi'),
        (Join-Path $bundleDir 'out') | Out-Null
    [System.IO.File]::WriteAllText((Join-Path $bundleDir 'res/values/dimens.xml'),
        '<resources><dimen name="gap">4dp</dimen></resources>', [System.Text.UTF8Encoding]::new($false))
    [System.IO.File]::WriteAllText((Join-Path $bundleDir 'res/values-xxhdpi/dimens.xml'),
        '<resources><dimen name="gap">6dp</dimen><dimen name="tray_height">96dp</dimen></resources>',
        [System.Text.UTF8Encoding]::new($false))
    $bundleManifest = Join-Path $bundleDir 'AndroidManifest.xml'
    # The package by name: $package holds the other-package case's result by now. A version code,
    # which aapt2 copies into the split's manifest, because the signature check holds every APK of a
    # bundle to one build.
    [System.IO.File]::WriteAllText($bundleManifest, ('<manifest xmlns:android="http://schemas.android.com/apk/res/android" ' +
        'package="com.tumblr" android:versionCode="1460500109"><application /></manifest>'), [System.Text.UTF8Encoding]::new($false))
    $bundleCompiled = Join-Path $bundleDir 'compiled.zip'
    Invoke-Checked -Program $Aapt2 -Arguments @('compile', '--dir', (Join-Path $bundleDir 'res'), '-o', $bundleCompiled) `
        -Description 'aapt2 compile for the split bundle'
    $baseApk = Join-Path $bundleDir 'out/base.apk'
    $splitApk = Join-Path $bundleDir 'out/split_config.xxhdpi.apk'
    # aapt2 separates a split's path from its configurations with the platform's path separator.
    Invoke-Checked -Program $Aapt2 -Arguments @('link', '-o', $baseApk, '-I', $androidJar, '--manifest', $bundleManifest,
        '--package-id', '0x7f', '--allow-reserved-package-id', '--split', ($splitApk + [System.IO.Path]::PathSeparator + 'xxhdpi'),
        $bundleCompiled) -Description 'aapt2 link for the split bundle'
    # APKPure's layout of Tumblr: the base named for the package, each split as config.<name>.apk,
    # an icon, and a manifest.json whose split_apks name the base by id.
    $bundle = Join-Path $bundleDir 'tumblr-split.xapk'
    $archive = [System.IO.Compression.ZipFile]::Open($bundle, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $baseApk, 'com.tumblr.apk') | Out-Null
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $splitApk, 'config.xxhdpi.apk') | Out-Null
        $writer = New-Object System.IO.StreamWriter($archive.CreateEntry('manifest.json').Open())
        try {
            $writer.Write('{"xapk_version":2,"package_name":"com.tumblr","name":"Tumblr","split_apks":[' +
                '{"file":"com.tumblr.apk","id":"base"},{"file":"config.xxhdpi.apk","id":"config.xxhdpi"}]}')
        } finally { $writer.Dispose() }
        $archive.CreateEntry('icon.png').Open().Dispose()
    } finally { $archive.Dispose() }
    # These APKs come straight from aapt2 and carry no signature, which Get-MergedApk refuses (the
    # signed cases are further down). So the resource cases merge them through the steps it takes
    # once the signatures have passed: unpack with every member name checked, lock and hash, merge
    # exactly those bytes. Only a test calls these steps itself; no script can skip the check.
    function Invoke-TestSplitMerge([string]$Bundle, [string]$Destination) {
        $splits = Expand-SplitBundle -Apk $Bundle -Destination $Destination
        try {
            Lock-SplitFiles -Splits $splits
            return Invoke-SplitMerge -Splits $splits -Destination $Destination -Java $Java -DesktopJar $DesktopJar
        } finally {
            Remove-SplitDirectory -Splits $splits
        }
    }
    $mergedApk = Invoke-TestSplitMerge -Bundle $bundle -Destination (Join-Path $bundleDir 'merged/stock-merged.apk')
    Assert-True ($mergedApk -eq (Join-Path $bundleDir 'merged/stock-merged.apk') -and (Test-Path -LiteralPath $mergedApk -PathType Leaf)) `
        "The split bundle was not merged into the APK asked for: $mergedApk"
    # The same two APKs as an .apkm merge the same way, and that merge carries the split's resource too.
    $apkm = Join-Path $bundleDir 'tumblr-split.apkm'
    $archive = [System.IO.Compression.ZipFile]::Open($apkm, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($apk in $baseApk, $splitApk) {
            [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $apk, (Split-Path -Leaf $apk)) | Out-Null
        }
    } finally { $archive.Dispose() }
    $mergedApkm = Invoke-TestSplitMerge -Bundle $apkm -Destination (Join-Path $bundleDir 'merged/apkm-merged.apk')
    $apkmCaught = Invoke-Check -Stock $mergedApkm -Patched $baseApk -Name 'apkm-merged-stock'
    Assert-True ($apkmCaught.ExitCode -eq 1 -and $apkmCaught.Output -match 'FAIL 0x7f\w{6} dimen/tray_height: not in the patched table') `
        "A table that lost the split's resource passed against the .apkm's merge.`n$($apkmCaught.Output)"
    $blind = Invoke-Check -Stock $baseApk -Patched $mergedApk -Name 'base-stock'
    Assert-True ($blind.ExitCode -eq 0 -and $blind.Output -match 'added resources: 1\s+0x7f\w{6} dimen/tray_height') `
        "The merge does not carry the split's own resource, or base.apk compared it after all.`n$($blind.Output)"
    $caught = Invoke-Check -Stock $mergedApk -Patched $baseApk -Name 'merged-stock'
    Assert-True ($caught.ExitCode -eq 1 -and $caught.Output -match 'FAIL 0x7f\w{6} dimen/tray_height: not in the patched table') `
        "A table that lost the split's resource passed against the merge.`n$($caught.Output)"
    $whole = Invoke-Check -Stock $mergedApk -Patched $mergedApk -Name 'merged-whole'
    Assert-True ($whole.ExitCode -eq 0) "The merge failed against itself.`n$($whole.Output)"
    # Get-MergedApk itself, on these unsigned APKs. A plain APK that doesn't verify, with no bundle
    # of its name beside it to be the merge of, is refused before anything is copied, and no JDK is
    # started for it. A bundle with no APK is refused before anything is unpacked.
    $apksigner = Resolve-Apksigner -Aapt2 $Aapt2
    $noJava = Join-Path $bundleDir 'no-java.exe'
    $unsignedSigner = 'a' * 64
    $refused = $null
    try {
        Get-MergedApk -Apk $baseApk -Destination (Join-Path $bundleDir 'never.apk') -Java $noJava -DesktopJar $DesktopJar `
            -Aapt2 $Aapt2 -PackageName 'com.tumblr' -Signers $unsignedSigner | Out-Null
    } catch {
        $refused = $_.Exception.Message
    }
    Assert-True ($refused -like '*Refusing base.apk: it does not verify (*), and no .apkm, .xapk or .apks of the same name beside it*' -and
        -not (Test-Path -LiteralPath (Join-Path $bundleDir 'never.apk')) -and
        @(Get-ChildItem -LiteralPath $bundleDir -Force -Filter '.splits-*').Count -eq 0) `
        "An unsigned plain APK was not refused before it was copied: $refused"
    $empty = Join-Path $bundleDir 'empty.apkm'
    $archive = [System.IO.Compression.ZipFile]::Open($empty, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        $writer = New-Object System.IO.StreamWriter($archive.CreateEntry('info.json').Open())
        try { $writer.Write('{}') } finally { $writer.Dispose() }
    } finally { $archive.Dispose() }
    $refused = $null
    try {
        Get-MergedApk -Apk $empty -Destination (Join-Path $bundleDir 'merged/empty-merged.apk') -Java $Java -DesktopJar $DesktopJar `
            -Aapt2 $Aapt2 -PackageName 'com.tumblr' -Signers $unsignedSigner | Out-Null
    } catch {
        $refused = $_.Exception.Message
    }
    Assert-True ($refused -like '*empty.apkm holds no APK. Nothing was unpacked.*' -and
        -not (Test-Path -LiteralPath (Join-Path $bundleDir 'merged/empty-merged.apk'))) `
        "A bundle holding no APK was not refused before the merge: $refused"
    Assert-True (@(Get-ChildItem -LiteralPath (Join-Path $bundleDir 'merged') -Force -Filter '.splits-*').Count -eq 0) `
        'A merge left the folder it unpacked a bundle into behind.'

    # MergeSplits.java reads a directory of APKs the scripts unpacked and checked, and nothing else.
    # Handed a bundle straight, the merger would unpack it where its member names point, so that is
    # refused, and so is a directory holding anything but regular .apk files, or none, all before
    # the merger runs. Each APK's hash, taken where its signature was checked, comes after the two
    # paths, and the directory has to hold exactly the APKs listed, with exactly those bytes.
    function Invoke-MergeSplits([string]$Splits, [string]$Merged, [string[]]$Digests = @()) {
        $ErrorActionPreference = 'Continue'
        $global:LASTEXITCODE = -1
        $said = @(& $Java '-Xmx1g' '-cp' $DesktopJar (Join-Path $PSScriptRoot 'MergeSplits.java') $Splits $Merged @Digests 2>&1 |
            ForEach-Object { "$_" })
        [pscustomobject]@{ ExitCode = $LASTEXITCODE; Text = $said -join "`n" }
    }
    function Get-TestSha256([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
    $baseDigest = "base.apk:$(Get-TestSha256 $baseApk)"
    $usage = Invoke-MergeSplits $apkm (Join-Path $bundleDir 'merged/usage.apk')
    Assert-True ($usage.ExitCode -eq 2 -and $usage.Text -like '*usage: MergeSplits*' -and
        -not (Test-Path -LiteralPath (Join-Path $bundleDir 'merged/usage.apk'))) `
        "MergeSplits.java went ahead without the hashes of what was checked.`n$($usage.Text)"
    $direct = Invoke-MergeSplits $apkm (Join-Path $bundleDir 'merged/direct.apk') @($baseDigest)
    Assert-True ($direct.ExitCode -eq 2 -and $direct.Text -like '*is not a directory of split APKs*' -and
        -not (Test-Path -LiteralPath (Join-Path $bundleDir 'merged/direct.apk'))) `
        "MergeSplits.java merged a bundle handed to it straight.`n$($direct.Text)"
    $mixedSplits = Join-Path $bundleDir 'mixed-splits'
    New-Item -ItemType Directory -Force -Path (Join-Path $mixedSplits 'nested') | Out-Null
    Copy-Item -LiteralPath $baseApk -Destination (Join-Path $mixedSplits 'base.apk')
    $nested = Invoke-MergeSplits $mixedSplits (Join-Path $bundleDir 'merged/mixed.apk') @($baseDigest)
    Assert-True ($nested.ExitCode -eq 2 -and $nested.Text -like '*holds nested, which is not a split APK*' -and
        -not (Test-Path -LiteralPath (Join-Path $bundleDir 'merged/mixed.apk'))) `
        "MergeSplits.java merged a directory holding a folder.`n$($nested.Text)"
    Remove-Item -LiteralPath (Join-Path $mixedSplits 'nested') -Force
    [System.IO.File]::WriteAllText((Join-Path $mixedSplits 'info.json'), '{}')
    $other = Invoke-MergeSplits $mixedSplits (Join-Path $bundleDir 'merged/mixed.apk') @($baseDigest)
    Assert-True ($other.ExitCode -eq 2 -and $other.Text -like '*holds info.json, which is not a split APK*' -and
        -not (Test-Path -LiteralPath (Join-Path $bundleDir 'merged/mixed.apk'))) `
        "MergeSplits.java merged a directory holding another file.`n$($other.Text)"
    Remove-Item -LiteralPath (Join-Path $mixedSplits 'info.json') -Force
    $noSplits = Join-Path $bundleDir 'no-splits'
    New-Item -ItemType Directory -Force -Path $noSplits | Out-Null
    $none = Invoke-MergeSplits $noSplits (Join-Path $bundleDir 'merged/none.apk') @($baseDigest)
    Assert-True ($none.ExitCode -eq 2 -and $none.Text -like '*holds no split APK*') `
        "MergeSplits.java went ahead with no split APK.`n$($none.Text)"
    # The digests themselves: one that isn't a name and a SHA-256, a name listed twice, an APK
    # listed that isn't there, an APK there that isn't listed, and bytes other than the ones hashed.
    $hashCases = @(
        @{ Name = 'malformed'; Digests = @('base.apk:NOT-A-DIGEST'); Says = '*base.apk:NOT-A-DIGEST is not <name>.apk:<sha256>*' }
        @{ Name = 'twice'; Digests = @($baseDigest, $baseDigest); Says = '*base.apk is listed twice*' }
        @{ Name = 'missing'; Digests = @($baseDigest, "split.apk:$('0' * 64)"); Says = '*holds `[base.apk`], and the APKs that were checked are `[base.apk, split.apk`]*' }
        @{ Name = 'other-bytes'; Digests = @("base.apk:$('0' * 64)"); Says = "*base.apk hashes to $(Get-TestSha256 $baseApk), not the $('0' * 64) that was checked*" })
    foreach ($case in $hashCases) {
        $result = Invoke-MergeSplits $mixedSplits (Join-Path $bundleDir "merged/$($case.Name).apk") $case.Digests
        Assert-True ($result.ExitCode -eq 2 -and $result.Text -like $case.Says -and
            -not (Test-Path -LiteralPath (Join-Path $bundleDir "merged/$($case.Name).apk"))) `
            "MergeSplits.java merged against the hashes '$($case.Digests -join ' ')'.`n$($result.Text)"
    }
    Copy-Item -LiteralPath $splitApk -Destination (Join-Path $mixedSplits 'extra.apk')
    $unlisted = Invoke-MergeSplits $mixedSplits (Join-Path $bundleDir 'merged/unlisted.apk') @($baseDigest)
    Assert-True ($unlisted.ExitCode -eq 2 -and $unlisted.Text -like '*holds `[base.apk, extra.apk`], and the APKs that were checked are `[base.apk`]*' -and
        -not (Test-Path -LiteralPath (Join-Path $bundleDir 'merged/unlisted.apk'))) `
        "MergeSplits.java merged an APK nobody checked.`n$($unlisted.Text)"

    # Signed for real: two keystores, A standing for Tumblr's certificate and B for anybody else's,
    # and apksigner from the build-tools aapt2 came from. The base carries a classes.dex, as Tumblr's
    # does, so a universal APK can be held to it, and an asset and two raw files for a universal APK
    # to leave out or swap.
    $keytool = Join-Path (Split-Path -Parent $Java) 'keytool.exe'
    if (-not (Test-Path -LiteralPath $keytool -PathType Leaf)) { throw "Required tool not found: $keytool" }
    $keys = Join-Path $bundleDir 'keys'
    $signed = Join-Path $bundleDir 'signed'
    New-Item -ItemType Directory -Force -Path $keys, $signed | Out-Null
    foreach ($key in 'a', 'b') {
        Invoke-Checked -Program $keytool -Arguments @('-genkeypair', '-keystore', (Join-Path $keys "$key.p12"), '-storetype', 'PKCS12',
            '-storepass', 'hushtest', '-keypass', 'hushtest', '-alias', 'test', '-keyalg', 'RSA', '-keysize', '2048',
            '-validity', '3650', '-dname', "CN=HushTumblr test $key", '-noprompt') -Description "keytool for key $key"
    }
    function New-SignedApk([string]$From, [string]$Key, [string]$Name) {
        $out = Join-Path $signed $Name
        Invoke-Checked -Program $apksigner -Arguments @('sign', '--ks', (Join-Path $keys "$Key.p12"), '--ks-pass', 'pass:hushtest',
            '--key-pass', 'pass:hushtest', '--ks-key-alias', 'test', '--out', $out, $From) -Description "apksigner sign $Name with key $Key"
        return $out
    }
    function New-TestApkm([string]$Name, [string[]]$Apks) {
        $path = Join-Path $signed $Name
        $zip = [System.IO.Compression.ZipFile]::Open($path, [System.IO.Compression.ZipArchiveMode]::Create)
        try {
            for ($i = 0; $i -lt $Apks.Count; $i++) {
                $member = if ($i -eq 0) { 'base.apk' } else { 'split_config.xxhdpi.apk' }
                [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $Apks[$i], $member) | Out-Null
            }
        } finally { $zip.Dispose() }
        return $path
    }
    $dexBase = Copy-ApkWith -From $baseApk -Name 'dex-base' -Change {
        param($archive)
        foreach ($file in @(@{ Name = 'classes.dex'; Text = "dex`n035`0 stand-in code" }, @{ Name = 'assets/kept.txt'; Text = 'an asset the app reads' },
                @{ Name = 'res/raw/one.txt'; Text = 'the first raw file' }, @{ Name = 'res/raw/two.txt'; Text = 'the second raw file' })) {
            $out = $archive.CreateEntry($file.Name).Open()
            try { $bytes = [System.Text.Encoding]::ASCII.GetBytes($file.Text); $out.Write($bytes, 0, $bytes.Length) } finally { $out.Dispose() }
        }
    }
    $baseA = New-SignedApk -From $dexBase -Key 'a' -Name 'base-a.apk'
    $splitA = New-SignedApk -From $splitApk -Key 'a' -Name 'split-a.apk'
    $splitB = New-SignedApk -From $splitApk -Key 'b' -Name 'split-b.apk'
    $splitTampered = Join-Path $signed 'split-tampered.apk'
    Copy-Item -LiteralPath $splitA -Destination $splitTampered
    $archive = [System.IO.Compression.ZipFile]::Open($splitTampered, [System.IO.Compression.ZipArchiveMode]::Update)
    try {
        $writer = New-Object System.IO.StreamWriter($archive.CreateEntry('res/raw/added.txt').Open())
        try { $writer.Write('added after signing') } finally { $writer.Dispose() }
    } finally { $archive.Dispose() }
    $signerA = Get-ApkSigners -Apk $baseA -Apksigner $apksigner
    $signerB = Get-ApkSigners -Apk $splitB -Apksigner $apksigner
    Assert-True ($signerA.Verified -and @($signerA.Signers).Count -eq 1 -and $signerB.Verified -and @($signerB.Signers).Count -eq 1 -and
        $signerA.Signers[0] -cne $signerB.Signers[0]) "apksigner did not report one certificate for each test key: $($signerA.Said) / $($signerB.Said)"
    $pinned = @($signerA.Signers)

    # A bundle whose APKs all carry A merges, with a record of what was checked beside the merge,
    # and the merge carries the split's resource.
    $signedApkm = New-TestApkm -Name 'signed.apkm' -Apks @($baseA, $splitA)
    $signedMerged = Join-Path $bundleDir 'merged/signed-merged.apk'
    Assert-True ((Get-MergedApk -Apk $signedApkm -Destination $signedMerged -Java $Java -DesktopJar $DesktopJar `
            -Aapt2 $Aapt2 -PackageName 'com.tumblr' -Signers $pinned) -eq $signedMerged) 'The signed bundle was not merged where asked.'
    $record = Get-Content -LiteralPath "$signedMerged.authentic.json" -Raw | ConvertFrom-Json
    Assert-True ($record.package -ceq 'com.tumblr' -and [string]$record.versionCode -ceq '1460500109' -and
        (@($record.signers) -join ',') -ceq $pinned[0] -and $record.baseSha256 -ceq (Get-TestSha256 $baseA) -and
        $record.mergedSha256 -ceq (Get-TestSha256 $signedMerged) -and @($record.apks).Count -eq 2 -and
        # AAPT2 adds the density qualifier's API 4 floor to the manifest split name.
        (@($record.apks | ForEach-Object { "$($_.member)=$($_.split)" } | Sort-Object) -join ';') -ceq 'base.apk=;split_config.xxhdpi.apk=config.xxhdpi_v4') `
        "The record of the signed merge is wrong: $($record | ConvertTo-Json -Depth 5 -Compress)"
    Assert-AuthenticMerge -Merged $signedMerged -Base $baseA -PackageName 'com.tumblr'
    $signedCaught = Invoke-Check -Stock $signedMerged -Patched $baseApk -Name 'signed-merged-stock'
    Assert-True ($signedCaught.ExitCode -eq 1 -and $signedCaught.Output -match 'FAIL 0x7f\w{6} dimen/tray_height: not in the patched table') `
        "The signed bundle's merge does not carry the split's resource.`n$($signedCaught.Output)"

    # A universal APK, which merging leaves unsigned, passes as the merge of the same-named bundle
    # beside it, and is copied for the CLI with a record naming that bundle. It is held to that
    # bundle's merge, made again for the check and deleted, so this one needs the JDK. One whose dex
    # isn't the bundle's base dex is refused, and so is one carrying a native library the bundle doesn't:
    # both comparisons once walked each digest map as a single object and matched whatever they got.
    $universal = Join-Path $signed 'universal.apk'
    Copy-Item -LiteralPath $signedMerged -Destination $universal
    Copy-Item -LiteralPath $signedApkm -Destination (Join-Path $signed 'universal.apkm')
    $universalCopy = Join-Path $bundleDir 'merged/universal-copy.apk'
    Get-MergedApk -Apk $universal -Destination $universalCopy -Java $Java -DesktopJar $DesktopJar `
        -Aapt2 $Aapt2 -PackageName 'com.tumblr' -Signers $pinned | Out-Null
    $universalRecord = Get-Content -LiteralPath "$universalCopy.authentic.json" -Raw | ConvertFrom-Json
    Assert-True ((Get-TestSha256 $universalCopy) -ceq (Get-TestSha256 $universal) -and
        $universalRecord.signedForm -ceq (Join-Path $signed 'universal.apkm') -and $universalRecord.baseSha256 -ceq (Get-TestSha256 $baseA) -and
        @(Get-ChildItem -LiteralPath (Join-Path $bundleDir 'merged') -Force -Filter '.trusted-*').Count -eq 0) `
        "The universal APK was not passed as the signed bundle's merge, or the merge it was held to was left behind: $($universalRecord | ConvertTo-Json -Depth 5 -Compress)"
    $otherDex = Copy-ApkWith -From $universal -Name 'other-dex' -Change {
        param($archive)
        $archive.GetEntry('classes.dex').Delete()
        $out = $archive.CreateEntry('classes.dex').Open()
        try { $bytes = [System.Text.Encoding]::ASCII.GetBytes("dex`n035`0 other code"); $out.Write($bytes, 0, $bytes.Length) } finally { $out.Dispose() }
    }
    Move-Item -LiteralPath $otherDex -Destination (Join-Path $signed 'other-dex.apk')
    Copy-Item -LiteralPath $signedApkm -Destination (Join-Path $signed 'other-dex.apkm')
    $otherLibs = Copy-ApkWith -From $universal -Name 'other-libs' -Change {
        param($archive)
        $out = $archive.CreateEntry('lib/arm64-v8a/libstandin.so').Open()
        try { $bytes = [System.Text.Encoding]::ASCII.GetBytes('stand-in library'); $out.Write($bytes, 0, $bytes.Length) } finally { $out.Dispose() }
    }
    Move-Item -LiteralPath $otherLibs -Destination (Join-Path $signed 'other-libs.apk')
    Copy-Item -LiteralPath $signedApkm -Destination (Join-Path $signed 'other-libs.apkm')
    # Past the code and libraries, everything but the manifest and resource table has to be one of
    # the bundle's files too: an added asset is refused by name, and a res file by its content,
    # since the merge may move a res file to a folder without its -v4 or -v21 qualifier.
    foreach ($added in @(@{ Name = 'other-asset'; Entry = 'assets/standin.txt' }, @{ Name = 'other-res'; Entry = 'res/raw/standin.txt' })) {
        $entryName = $added.Entry
        $copy = Copy-ApkWith -From $universal -Name $added.Name -Change {
            param($archive)
            $out = $archive.CreateEntry($entryName).Open()
            try { $bytes = [System.Text.Encoding]::ASCII.GetBytes('stand-in file'); $out.Write($bytes, 0, $bytes.Length) } finally { $out.Dispose() }
        }
        Move-Item -LiteralPath $copy -Destination (Join-Path $signed "$($added.Name).apk")
        Copy-Item -LiteralPath $signedApkm -Destination (Join-Path $signed "$($added.Name).apkm")
    }
    # A universal APK made of nothing but the bundle's own files passes all of that, and still isn't
    # the bundle's merge: an asset left out, base.apk's resource table in place of the merged one
    # (so the resource only the split carries is gone), one raw file holding the other's bytes, and
    # a raw file there a second time under a name the merge doesn't have. Only the merge tells.
    $baseArchive = [System.IO.Compression.ZipFile]::OpenRead($baseA)
    try {
        $stream = $baseArchive.GetEntry('resources.arsc').Open()
        try { $buffer = [System.IO.MemoryStream]::new(); $stream.CopyTo($buffer); $baseTable = $buffer.ToArray() } finally { $stream.Dispose() }
    } finally { $baseArchive.Dispose() }
    $secondRaw = [System.Text.Encoding]::ASCII.GetBytes('the second raw file')
    function Set-TestEntry($Archive, [string]$Name, [byte[]]$Bytes) {
        $existing = $Archive.GetEntry($Name)
        if ($existing) { $existing.Delete() }
        $out = $Archive.CreateEntry($Name).Open()
        try { $out.Write($Bytes, 0, $Bytes.Length) } finally { $out.Dispose() }
    }
    foreach ($unwhole in @(
            @{ Name = 'missing-asset'; Change = { param($archive) $archive.GetEntry('assets/kept.txt').Delete() } },
            @{ Name = 'lost-resource'; Change = { param($archive) Set-TestEntry $archive 'resources.arsc' $baseTable } },
            @{ Name = 'swapped-res'; Change = { param($archive) Set-TestEntry $archive 'res/raw/one.txt' $secondRaw } },
            @{ Name = 'copied-res'; Change = { param($archive) Set-TestEntry $archive 'res/raw/three.txt' $secondRaw } })) {
        $copy = Copy-ApkWith -From $universal -Name $unwhole.Name -Change $unwhole.Change
        Move-Item -LiteralPath $copy -Destination (Join-Path $signed "$($unwhole.Name).apk")
        Copy-Item -LiteralPath $signedApkm -Destination (Join-Path $signed "$($unwhole.Name).apkm")
    }

    # Every refusal leaves no merge, no record and no unpacked folder. The ones a signature or a
    # file of the bundle's decides come before the JDK would start (it isn't there). The last four
    # are held to the bundle's merge, which the JDK makes, and that merge is gone afterwards too.
    $refusals = @(
        @{ Apk = (New-TestApkm -Name 'unsigned-split.apkm' -Apks @($baseA, $splitApk))
            Says = '*Refusing unsigned-split.apkm: split_config.xxhdpi.apk does not verify (*Nothing was merged or patched.*' }
        @{ Apk = (New-TestApkm -Name 'foreign-split.apkm' -Apks @($baseA, $splitB))
            Says = "*Refusing foreign-split.apkm: split_config.xxhdpi.apk is signed by $($signerB.Signers[0]), which isn't a certificate patches-list.json declares for com.tumblr*" }
        @{ Apk = (New-TestApkm -Name 'tampered-split.apkm' -Apks @($baseA, $splitTampered))
            Says = '*Refusing tampered-split.apkm: split_config.xxhdpi.apk does not verify (*Nothing was merged or patched.*' }
        @{ Apk = (Join-Path $signed 'other-dex.apk')
            Says = "*Refusing other-dex.apk: it does not verify, and its dex files aren't those of the base APK of other-dex.apkm*" }
        @{ Apk = (Join-Path $signed 'other-libs.apk')
            Says = "*Refusing other-libs.apk: it does not verify, and its native libraries aren't those of other-libs.apkm*" }
        @{ Apk = (Join-Path $signed 'other-asset.apk')
            Says = "*Refusing other-asset.apk: it does not verify, and its assets/standin.txt isn't in any APK of other-asset.apkm*" }
        @{ Apk = (Join-Path $signed 'other-res.apk')
            Says = "*Refusing other-res.apk: it does not verify, and its res/raw/standin.txt isn't in any APK of other-res.apkm*" }
        @{ Apk = (Join-Path $signed 'missing-asset.apk'); Merged = $true
            Says = '*Refusing missing-asset.apk: it does not verify, and it has no assets/kept.txt, which the merge of missing-asset.apkm holds*' }
        @{ Apk = (Join-Path $signed 'lost-resource.apk'); Merged = $true
            Says = "*Refusing lost-resource.apk: it does not verify, and its resources.arsc isn't the one the merge of lost-resource.apkm holds*" }
        @{ Apk = (Join-Path $signed 'swapped-res.apk'); Merged = $true
            Says = "*Refusing swapped-res.apk: it does not verify, and its res/raw/one.txt isn't the one the merge of swapped-res.apkm holds*" }
        @{ Apk = (Join-Path $signed 'copied-res.apk'); Merged = $true
            Says = "*Refusing copied-res.apk: it does not verify, and its res/raw/three.txt isn't in the merge of copied-res.apkm*" })
    foreach ($case in $refusals) {
        $destination = Join-Path $bundleDir ("merged/refused-" + [System.IO.Path]::GetFileNameWithoutExtension($case.Apk) + '.apk')
        $caseJava = if ($case.Merged) { $Java } else { $noJava }
        $refused = $null
        try {
            Get-MergedApk -Apk $case.Apk -Destination $destination -Java $caseJava -DesktopJar $DesktopJar `
                -Aapt2 $Aapt2 -PackageName 'com.tumblr' -Signers $pinned | Out-Null
        } catch {
            $refused = $_.Exception.Message
        }
        Assert-True ($refused -like $case.Says -and -not (Test-Path -LiteralPath $destination) -and
            -not (Test-Path -LiteralPath "$destination.authentic.json") -and
            @(Get-ChildItem -LiteralPath (Join-Path $bundleDir 'merged') -Force -Filter '.splits-*').Count -eq 0 -and
            @(Get-ChildItem -LiteralPath (Join-Path $bundleDir 'merged') -Force -Filter '.trusted-*').Count -eq 0) `
            "$(Split-Path -Leaf $case.Apk) was not refused before anything was handed on: $refused"
    }

    # What a device with 16 KB pages needs of a patched APK (Assert-PageAlignment), with the
    # build-tools' own zipalign and apksigner on archives the JDK's jar packs uncompressed. A
    # library here is an ELF header and its program headers, which is all the check reads: one LOAD
    # segment, and a GNU_RELRO from 0x4000 up to -RelroEnd when one is asked for.
    $zipalign = Resolve-Zipalign -Aapt2 $Aapt2
    $jar = Join-Path (Split-Path -Parent $Java) 'jar.exe'
    if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw "Required tool not found: $jar" }
    $pageDir = Join-Path $caseRoot 'pages'
    function New-TestLibrary([long]$LoadAlign = 0x4000, [long]$RelroEnd = 0) {
        $bytes = New-Object byte[] 176
        ([byte[]]@(0x7F, 0x45, 0x4C, 0x46, 2, 1, 1)).CopyTo($bytes, 0)
        [System.BitConverter]::GetBytes([uint64]64).CopyTo($bytes, 0x20)
        [System.BitConverter]::GetBytes([uint16]56).CopyTo($bytes, 0x36)
        [System.BitConverter]::GetBytes([uint16]$(if ($RelroEnd) { 2 } else { 1 })).CopyTo($bytes, 0x38)
        [System.BitConverter]::GetBytes([uint32]1).CopyTo($bytes, 64)
        [System.BitConverter]::GetBytes([uint64]$LoadAlign).CopyTo($bytes, 64 + 48)
        if ($RelroEnd) {
            [System.BitConverter]::GetBytes([uint32]0x6474e552).CopyTo($bytes, 120)
            [System.BitConverter]::GetBytes([uint64]0x4000).CopyTo($bytes, 120 + 16)
            [System.BitConverter]::GetBytes([uint64]($RelroEnd - 0x4000)).CopyTo($bytes, 120 + 40)
        }
        return , $bytes
    }
    # The libraries and the stock APK's manifest, which apksigner reads the API floor from. Packed
    # as they come, or aligned and then signed, the order a build has to keep.
    function New-NativeApk([string]$Name, [System.Collections.IDictionary]$Libraries, [switch]$AsPacked) {
        $tree = Join-Path $pageDir $Name
        foreach ($library in $Libraries.GetEnumerator()) {
            $path = Join-Path $tree ("lib/$($library.Key)" -replace '/', [System.IO.Path]::DirectorySeparatorChar)
            New-Item -ItemType Directory -Force -Path (Split-Path -Parent $path) | Out-Null
            [System.IO.File]::WriteAllBytes($path, $library.Value)
        }
        $archive = [System.IO.Compression.ZipFile]::OpenRead($stockApk)
        try { [System.IO.Compression.ZipFileExtensions]::ExtractToFile($archive.GetEntry('AndroidManifest.xml'), (Join-Path $tree 'AndroidManifest.xml')) }
        finally { $archive.Dispose() }
        $packed = Join-Path $pageDir "$Name-packed.apk"
        Invoke-Checked -Program $jar -Arguments @('--create', '--no-compress', '--no-manifest', '--file', $packed, '-C', $tree, '.') `
            -Description "jar for $Name"
        if ($AsPacked) { return $packed }
        $aligned = Join-Path $pageDir "$Name-aligned.apk"
        Invoke-Checked -Program $zipalign -Arguments @('-f', '-P', '16', '4', $packed, $aligned) -Description "zipalign for $Name"
        $out = Join-Path $pageDir "$Name.apk"
        Invoke-Checked -Program $apksigner -Arguments @('sign', '--ks', (Join-Path $keys 'a.p12'), '--ks-pass', 'pass:hushtest',
            '--key-pass', 'pass:hushtest', '--ks-key-alias', 'test', '--out', $out, $aligned) -Description "apksigner sign $Name"
        return $out
    }
    function Invoke-PageCheck([string]$Apk, [string]$Stock) {
        try { return [pscustomobject]@{ Limits = @(Assert-PageAlignment -Apk $Apk -Stock $Stock -Aapt2 $Aapt2); Refused = $null } }
        catch { return [pscustomobject]@{ Limits = @(); Refused = $_.Exception.Message } }
    }
    # Tumblr's own, as far as these cases go: a library with a GNU_RELRO that ends on a 16 KB
    # boundary, one with none, a 32-bit one with 4 KB segments and a dex named .so. The last two
    # aren't read.
    $thirtyTwo = New-TestLibrary -LoadAlign 0x1000
    $thirtyTwo[4] = 1
    $clean = @{ 'arm64-v8a/libgood.so' = (New-TestLibrary -RelroEnd 0x8000); 'arm64-v8a/libplain.so' = (New-TestLibrary)
        'armeabi-v7a/libold.so' = $thirtyTwo; 'arm64-v8a/libcode.dex.so' = [System.Text.Encoding]::ASCII.GetBytes("dex`n035") }
    $cleanStock = New-NativeApk -Name 'clean-stock' -Libraries $clean -AsPacked
    $accepted = New-NativeApk -Name 'accepted' -Libraries $clean
    $said = Invoke-PageCheck -Apk $accepted -Stock $cleanStock
    Assert-True ($null -eq $said.Refused -and $said.Limits.Count -eq 0 -and (Get-ApkSigners -Apk $accepted -Apksigner $apksigner).Verified) `
        "An APK aligned before it was signed, with Tumblr's libraries as they were, was not accepted: $($said.Refused) $($said.Limits -join ' ')"
    # The same files as jar packed them: every library sits wherever the entry before it ended.
    $said = Invoke-PageCheck -Apk $cleanStock -Stock $cleanStock
    Assert-True ($said.Refused -like "*zipalign -c -P 16 -v 4 didn't pass clean-stock-packed.apk (exit 1)*lib/arm64-v8a/lib*.so (BAD*") `
        "An APK whose libraries aren't on 16 KB boundaries in the ZIP was not refused for that: $($said.Refused)"
    # A library Tumblr didn't ship, in an aligned ZIP: one LOAD segment aligned to 4 KB, one whose
    # GNU_RELRO ends 4 KB into a 16 KB page, and one that counts more program headers than it holds.
    $cutShort = New-TestLibrary
    $cutShort[0x38] = 9
    foreach ($added in @(
            @{ Name = 'small-load'; Library = (New-TestLibrary -LoadAlign 0x1000); Says = 'LOAD segment 0 is aligned to 0x1000, under 0x4000' },
            @{ Name = 'short-relro'; Library = (New-TestLibrary -RelroEnd 0x5000)
                Says = "GNU_RELRO segment 1 (VirtAddr 0x4000, MemSiz 0x1000) doesn't end on a 16 KB boundary" },
            @{ Name = 'cut-short'; Library = $cutShort; Says = 'its program headers run past the end of the file' })) {
        $said = Invoke-PageCheck -Apk (New-NativeApk -Name $added.Name -Libraries ($clean + @{ 'arm64-v8a/libadded.so' = $added.Library })) -Stock $cleanStock
        Assert-True ($said.Refused -like ("*lib/arm64-v8a/libadded.so in $($added.Name).apk, which Tumblr didn't ship, can't be mapped on a " +
                "device with 16 KB pages: $($added.Says).*")) "$($added.Name).apk was not refused for its library alone: $($said.Refused)"
    }
    # The same two faults in libraries the stock APK holds too are Tumblr's to fix. The APK is
    # taken, signature and all, and each one is named for the caller to report. Putting one right
    # by rewriting it is refused: it isn't Tumblr's library any more.
    $limited = $clean + @{ 'arm64-v8a/libinherited.so' = (New-TestLibrary -RelroEnd 0x6000)
        'x86_64/libinherited.so' = (New-TestLibrary -LoadAlign 0x1000) }
    $limitedStock = New-NativeApk -Name 'limited-stock' -Libraries $limited -AsPacked
    $limitedApk = New-NativeApk -Name 'limited' -Libraries $limited
    $said = Invoke-PageCheck -Apk $limitedApk -Stock $limitedStock
    $expectedLimits = @(
        "16 KB target limitation: lib/arm64-v8a/libinherited.so is Tumblr's own library, left as it is, and GNU_RELRO segment 1 (VirtAddr 0x4000, MemSiz 0x2000) doesn't end on a 16 KB boundary.",
        "16 KB target limitation: lib/x86_64/libinherited.so is Tumblr's own library, left as it is, and LOAD segment 0 is aligned to 0x1000, under 0x4000.")
    Assert-True ($null -eq $said.Refused -and (@($said.Limits | Sort-Object) -join "`n") -ceq ($expectedLimits -join "`n") -and
        (Get-ApkSigners -Apk $limitedApk -Apksigner $apksigner).Verified) `
        "Tumblr's own libraries that don't fit 16 KB pages were not reported as the target's limit on a signed APK: $($said.Refused) $($said.Limits -join ' ')"
    $rewritten = $limited.Clone()
    $rewritten['arm64-v8a/libinherited.so'] = New-TestLibrary -RelroEnd 0xC000
    $said = Invoke-PageCheck -Apk (New-NativeApk -Name 'rewritten' -Libraries $rewritten) -Stock $limitedStock
    Assert-True ($said.Refused -like "*lib/arm64-v8a/libinherited.so in rewritten.apk isn't the library Tumblr shipped*") `
        "An APK with one of Tumblr's libraries rewritten to fit was not refused: $($said.Refused) $($said.Limits -join ' ')"
} finally {
    $env:BUILD_QUEUE_SCRIPT = $savedQueueScript
    if ($caseRoot.StartsWith($requiredPrefix, [System.StringComparison]::OrdinalIgnoreCase) -and `
        (Test-Path -LiteralPath $caseRoot)) {
        Remove-Item -LiteralPath $caseRoot -Recurse -Force
    }
}

$global:LASTEXITCODE = 0
Write-Host '[scripts] resource table check contracts passed'
