<#
.SYNOPSIS
    The helpers the release and verification scripts share.

.DESCRIPTION
    Dot-source this beside patch-target.ps1 and patch-report.ps1:

        . (Join-Path $PSScriptRoot 'common.ps1')

    Each of these existed in two to four copies that had already drifted apart. The path guard
    was identical in three scripts; the cleanup helper recursed unconditionally in one and only
    on request in another; the version read appeared four times, twice without -LiteralPath; and
    the desktop CLI was looked up by two functions with different search orders, one returning
    $null and one throwing. Copies of a guard drift in the direction of whichever caller was
    edited last, which is the direction nobody checked.
#>

function Resolve-WithinRoot {
    <#
    .SYNOPSIS
        A generated path, proved to be inside the work directory, or a throw.
    .DESCRIPTION
        Every path these scripts hand to the patcher or delete afterwards goes through this, so
        a run identifier that came out wrong cannot reach outside the directory the caller owns.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Root
    )
    $candidate = [System.IO.Path]::GetFullPath($Path)
    $prefix = $Root.TrimEnd('\') + '\'
    if (-not $candidate.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to use a generated path outside the work directory: $candidate"
    }
    return $candidate
}

function Remove-GeneratedPath {
    <#
    .SYNOPSIS
        Delete something this run generated, inside the work directory, warning rather than
        failing when it will not go.
    .DESCRIPTION
        Recursive by default, because every caller is deleting a run directory and the one copy
        that made it optional had its single caller pass -Recurse anyway. Pass -NoRecurse for a
        single file. A failure here is reported and swallowed on purpose: leaving scratch behind
        is not a reason to fail a run that has already produced its answer.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Root,
        [switch]$NoRecurse
    )
    try {
        $safe = Resolve-WithinRoot -Path $Path -Root $Root
        if (-not (Test-Path -LiteralPath $safe)) { return }
        if ($NoRecurse) { Remove-Item -LiteralPath $safe -Force -ErrorAction Stop }
        else { Remove-Item -LiteralPath $safe -Recurse -Force -ErrorAction Stop }
    } catch {
        Write-Warning "Could not remove generated path: $($_.Exception.Message)"
    }
}

function Get-BundleVersion {
    <#
    .SYNOPSIS
        The version in gradle.properties, which every generated name follows.
    .DESCRIPTION
        -LiteralPath, which two of the four copies of this read were missing: a repository path
        holding a bracket is read as a wildcard otherwise, and the read silently finds nothing.
    #>
    param([Parameter(Mandatory = $true)][string]$Root)

    $path = Join-Path $Root 'gradle.properties'
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "There is no gradle.properties at $path, so the bundle version is unknown."
    }
    $line = @(Get-Content -LiteralPath $path | Where-Object { $_ -match '^\s*version\s*=' }) |
        Select-Object -First 1
    if (-not $line) { throw "gradle.properties names no version: $path" }
    $version = ($line -replace '^\s*version\s*=\s*', '').Trim()
    if (-not $version) { throw "gradle.properties has an empty version: $path" }
    return $version
}

function Get-ReleaseBundlePath {
    <#
    .SYNOPSIS
        Where :patches:buildAndroid leaves the bundle a release publishes.
    .DESCRIPTION
        patches/build/release, never patches/build/libs. The Morphe plugin's buildAndroid adds the
        DEX payload to the jar task's own output in place, so any later task that reruns
        :patches:jar (:patches:test does) wrote the plain jar back over the finished bundle under
        the same name. v0.43.0 shipped that jar, and on 2026-09-21 it happened again between the
        build and the index push. buildAndroid now ends by copying the finished bundle here, where
        no other task writes, with bundle.sha256 beside it.
    #>
    param([Parameter(Mandatory = $true)][string]$Root, [string]$Version)

    if (-not $Version) { $Version = Get-BundleVersion -Root $Root }
    return Join-Path $Root "patches/build/release/patches-$Version.mpp"
}

function Get-SourcesNewerThanBundle {
    <#
    .SYNOPSIS
        The source files written after the bundle was built, newest first.
    .DESCRIPTION
        Only :patches:buildAndroid writes the release bundle, and :patches:test rebuilds
        build/libs without it, so a device build made after a patch change and a test run
        patched with the previous hooks (2026-09-23, Swipe-left controls). Counted: the sources of
        the patches module and its submodules (patches/src/main, patches/<submodule>/src/main,
        the compile-only stubs among them, whose constants can be inlined into patch code), the
        sources of every extension module (extensions/<module>/src/main and
        extensions/<module>/<submodule>/src/main), the Gradle files that shape them, the R8
        rules (*.pro at extensions/ and in each module, extensions/proguard-rules.pro being the
        one every extension's R8 step reads), and NOTICE, which :extensions:tumblr compiles
        into the payload for its Licenses row. Build output is never under src/main, so it is not
        walked. build-release-receipt.ps1 refuses a bundle this finds anything newer than.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$Bundle
    )

    $built = (Get-Item -LiteralPath $Bundle).LastWriteTimeUtc
    $sourceRoots = @()
    $gradleFiles = @('gradle.properties', 'settings.gradle.kts', 'build.gradle.kts', 'gradle/libs.versions.toml', 'NOTICE') |
        ForEach-Object { Join-Path $Root $_ }
    $ruleDirs = @()
    $patches = Join-Path $Root 'patches'
    $moduleDirs = @()
    if (Test-Path -LiteralPath $patches -PathType Container) {
        $moduleDirs += @(Get-Item -LiteralPath $patches) + @(Get-ChildItem -LiteralPath $patches -Directory |
            Where-Object { $_.Name -notin @('src', 'build') })
    }
    $extensions = Join-Path $Root 'extensions'
    if (Test-Path -LiteralPath $extensions -PathType Container) {
        $ruleDirs += $extensions
        foreach ($module in Get-ChildItem -LiteralPath $extensions -Directory) {
            $moduleDirs += @($module) + @(Get-ChildItem -LiteralPath $module.FullName -Directory |
                Where-Object { $_.Name -notin @('src', 'build') })
        }
    }
    foreach ($dir in $moduleDirs) {
        $sourceRoots += Join-Path $dir.FullName 'src/main'
        $gradleFiles += Join-Path $dir.FullName 'build.gradle.kts'
        $ruleDirs += $dir.FullName
    }
    foreach ($dir in $ruleDirs) {
        $gradleFiles += @(Get-ChildItem -LiteralPath $dir -File -Filter '*.pro' -ErrorAction SilentlyContinue |
            ForEach-Object FullName)
    }
    $newer = New-Object System.Collections.Generic.List[System.IO.FileInfo]
    foreach ($sourceRoot in $sourceRoots) {
        if (-not (Test-Path -LiteralPath $sourceRoot -PathType Container)) { continue }
        foreach ($file in Get-ChildItem -LiteralPath $sourceRoot -File -Recurse) {
            if ($file.LastWriteTimeUtc -gt $built) { $newer.Add($file) }
        }
    }
    foreach ($path in $gradleFiles) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
        $file = Get-Item -LiteralPath $path
        if ($file.LastWriteTimeUtc -gt $built) { $newer.Add($file) }
    }
    return @($newer | Sort-Object LastWriteTimeUtc -Descending)
}

function Resolve-DesktopCli {
    <#
    .SYNOPSIS
        The Morphe desktop CLI jar, or $null, or a throw when the caller cannot do without it.
    .DESCRIPTION
        One search order for both callers: -Explicit, HUSHTUMBLR_DESKTOP_JAR, HUSHTUMBLR_WORKDIR,
        then the repository's own build/morphe-tools. Newest by write time rather than by name,
        because the jar ships under its version and sorting those as text puts 1.9.0 above
        1.15.0. -Required turns "nothing found" into a throw naming what to set, which is what
        the second copy of this did and the first did not.
    #>
    param([string]$Explicit, [string]$Root, [switch]$Required)

    # Made absolute through PowerShell's location, the one Test-Path looked in. [IO.Path]::GetFullPath
    # reads the process directory, which Set-Location doesn't move: a relative -DesktopJar that
    # Test-Path found came back as a jar somewhere else, and DexDiff compiled without dexlib2.
    $found = $null
    if ($Explicit -and (Test-Path -LiteralPath $Explicit -PathType Leaf)) {
        $found = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Explicit)
    } elseif ($Explicit) {
        # Named and not there: say so rather than quietly searching somewhere else.
        throw "No Morphe desktop CLI at the path given: $Explicit"
    } elseif ($env:HUSHTUMBLR_DESKTOP_JAR -and
            (Test-Path -LiteralPath $env:HUSHTUMBLR_DESKTOP_JAR -PathType Leaf)) {
        $found = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($env:HUSHTUMBLR_DESKTOP_JAR)
    } else {
        $directories = @($env:HUSHTUMBLR_WORKDIR)
        if ($Root) { $directories += (Join-Path $Root 'build/morphe-tools') }
        foreach ($directory in $directories) {
            if (-not $directory -or -not (Test-Path -LiteralPath $directory -PathType Container)) { continue }
            $candidate = @(Get-ChildItem -LiteralPath $directory -Filter 'morphe-desktop*.jar' -File `
                -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending |
                Select-Object -First 1)
            if ($candidate.Count -eq 1) { $found = $candidate[0].FullName; break }
        }
    }

    if (-not $found -and $Required) {
        throw ('No Morphe desktop CLI. Pass -DesktopJar, or set HUSHTUMBLR_DESKTOP_JAR or ' +
            'HUSHTUMBLR_WORKDIR, or put the jar in build/morphe-tools.')
    }
    return $found
}

function Enter-HushTumblrQueue {
    <#
    .SYNOPSIS
        Waits for a slot in the machine's build queue for one heavy job, or returns $null at once
        when BUILD_QUEUE_SCRIPT names no queue. Hand what it returns to Exit-HushTumblrQueue in
        a finally block.
    .DESCRIPTION
        A CLI patch run, a split bundle merge and the resource table and dex checks each start a
        JVM with a heap of 4 to 8 GB. Started beside the Gradle builds the queue already holds,
        they ran on the same cores, and build-queue.ps1 -Status never showed them. The script
        BUILD_QUEUE_SCRIPT names defines Enter-BuildQueue, Exit-BuildQueue and
        Get-BuildQueueMask. The job waits there as "hushtumblr <Job>", then runs on its slot's
        cores at below normal priority, which the JVM it starts inherits. A release run sets
        BUILD_QUEUE_PRIORITY=release, which the queue reads to put it ahead of everyday builds,
        and a job started inside a slot (BUILD_QUEUE_TICKET) goes straight through.

        The queue's Invoke-InBuildQueue takes a script block and sends its output to the host.
        The callers here keep each job's output and read its exit code where they make the call,
        so the queue is entered and left around that call instead.
    #>
    param([Parameter(Mandatory = $true)][string]$Job)

    $queueScript = $env:BUILD_QUEUE_SCRIPT
    if ([string]::IsNullOrWhiteSpace($queueScript)) { return $null }
    if (-not (Test-Path -LiteralPath $queueScript -PathType Leaf)) {
        throw ("BUILD_QUEUE_SCRIPT names $queueScript, which is not there. Correct it, or clear it " +
            'to run without the build queue.')
    }
    $queueLabel = "hushtumblr $Job"
    $exitCode = $global:LASTEXITCODE
    # In a scope of its own: the queue script's parameters, -Label among them, bind wherever it's
    # dot-sourced, and here they would replace this function's variables.
    $entered = @(& {
        . $queueScript
        $ticket = Enter-BuildQueue -Label $queueLabel
        $mask = if ($ticket) { Get-BuildQueueMask -Slot $ticket.slot } else { $null }
        [pscustomobject]@{ Ticket = $ticket; Mask = $mask }
    })[-1]
    $process = [System.Diagnostics.Process]::GetCurrentProcess()
    $held = [pscustomobject]@{
        Script        = $queueScript
        Ticket        = $entered.Ticket
        Affinity      = $process.ProcessorAffinity
        PriorityClass = $process.PriorityClass
    }
    try {
        if ($null -ne $entered.Mask) { $process.ProcessorAffinity = $entered.Mask }
        $process.PriorityClass = [System.Diagnostics.ProcessPriorityClass]::BelowNormal
    } catch {
        Exit-HushTumblrQueue -Held $held
        throw
    }
    $global:LASTEXITCODE = $exitCode
    return $held
}

function Exit-HushTumblrQueue {
    <#
    .SYNOPSIS
        Gives back what Enter-HushTumblrQueue took: the cores, the priority and the slot. Keeps
        $LASTEXITCODE, the job's exit code, for the caller to read.
    #>
    param($Held)

    if ($null -eq $Held) { return }
    $exitCode = $global:LASTEXITCODE
    try {
        $process = [System.Diagnostics.Process]::GetCurrentProcess()
        $process.ProcessorAffinity = $Held.Affinity
        $process.PriorityClass = $Held.PriorityClass
    } finally {
        if ($null -ne $Held.Ticket) {
            $queueScript = $Held.Script
            $queueTicket = $Held.Ticket
            & {
                . $queueScript
                Exit-BuildQueue -Ticket $queueTicket
            }
        }
        $global:LASTEXITCODE = $exitCode
    }
}

function Get-BaseApk {
    <#
    .SYNOPSIS
        The APK whose manifest and resource table describe an app: the file itself, or the base
        APK a split bundle carries, copied out to -Destination.
    .DESCRIPTION
        Tumblr ships as a split bundle (APKPure's .xapk, APKMirror's .apkm), not as one APK, and
        aapt2 and the resource check read an APK. The base APK holds the manifest and the app's own
        resource table; the splits hold densities, languages and native code. An .apks or .apkm
        names it base.apk. An .xapk names it after the package and says so in its manifest.json,
        and its config.arm64_v8a split can be the larger file, so size alone can pick the wrong
        one. The largest APK that isn't a config or split_ file is the fallback when nothing names
        the base, and the largest of any is the last resort. A base declaring more than -Limit
        bytes, or a manifest.json declaring more than 1 MiB, is refused before anything is read
        or written, and neither unpacks past what it declares (Copy-BundleMember).

        Both paths are resolved against PowerShell's location before .NET sees them, and the
        answer is a full path. .NET reads a relative path against the process's own directory,
        which a hook, a scheduled task or a session that moved with Set-Location leaves somewhere
        else: `-Apk fixtures\tumblr.xapk` then named a file that wasn't there.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Apk,
        [Parameter(Mandatory = $true)][string]$Destination,
        [long]$Limit = 1GB
    )

    $Apk = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Apk)
    $Destination = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Destination)
    if ([System.IO.Path]::GetExtension($Apk).ToLowerInvariant() -eq '.apk') { return $Apk }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($Apk)
    $label = Split-Path -Leaf $Apk
    try {
        $entry = $zip.Entries | Where-Object { $_.FullName -eq 'base.apk' } | Select-Object -First 1
        $manifestEntry = $zip.Entries | Where-Object { $_.FullName -eq 'manifest.json' } | Select-Object -First 1
        if (-not $entry -and $manifestEntry) {
            # An .xapk's own record of its files: split_apks names the base by id, and the base is
            # <package_name>.apk when that list is missing. It's a few kilobytes, so one declaring
            # more than a megabyte is refused unread, and it never inflates past what it declares:
            # read straight to the end, a crafted one could fill memory on Windows PowerShell 5.1.
            if ($manifestEntry.Length -gt 1MB) {
                throw ("Refusing ${label}: its manifest.json would unpack to $($manifestEntry.Length) bytes, more than " +
                    "the $(1MB) bytes it may take. Nothing was unpacked.")
            }
            $buffer = New-Object System.IO.MemoryStream
            Copy-BundleMember -Entry $manifestEntry -Sink $buffer -Label $label
            $buffer.Position = 0
            $reader = New-Object System.IO.StreamReader($buffer)
            try { $manifestText = $reader.ReadToEnd() } finally { $reader.Dispose() }
            $manifest = $null
            try { $manifest = $manifestText | ConvertFrom-Json } catch { $manifest = $null }
            if ($null -ne $manifest) {
                $named = @(@($manifest.PSObject.Properties['split_apks'] | ForEach-Object { $_.Value }) |
                    Where-Object { $null -ne $_ -and "$($_.id)" -eq 'base' } | ForEach-Object { "$($_.file)" }) +
                    @($manifest.PSObject.Properties['package_name'] | Where-Object { $_.Value } | ForEach-Object { "$($_.Value).apk" })
                foreach ($name in $named) {
                    $entry = $zip.Entries | Where-Object { $_.FullName -eq $name } | Select-Object -First 1
                    if ($entry) { break }
                }
            }
        }
        if (-not $entry) {
            $apks = @($zip.Entries | Where-Object { $_.FullName -like '*.apk' } | Sort-Object Length -Descending)
            $entry = @($apks | Where-Object { $_.Name -notmatch '^(?i)(config\.|split_)' }) + $apks | Select-Object -First 1
        }
        if (-not $entry) { throw "$label holds no APK." }
        if ($entry.Length -gt $Limit) {
            throw ("Refusing ${label}: its base APK '$($entry.FullName)' would unpack to $($entry.Length) bytes, more than " +
                "the $Limit bytes one APK may take. Nothing was unpacked.")
        }
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Destination) | Out-Null
        $sink = [System.IO.File]::Open($Destination, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write,
            [System.IO.FileShare]::None)
        $copied = $false
        try {
            Copy-BundleMember -Entry $entry -Sink $sink -Label $label
            $copied = $true
        } finally {
            $sink.Dispose()
            if (-not $copied) { Remove-Item -LiteralPath $Destination -Force -ErrorAction SilentlyContinue }
        }
    } finally {
        $zip.Dispose()
    }
    return $Destination
}

function Copy-BundleMember {
    <#
    .SYNOPSIS
        One member of a bundle written to -Sink, refusing a byte more than the member declares.
    .DESCRIPTION
        The size limits read each member's declared size before anything is written, and the
        archive is what declares it, so nothing past it is written: .NET Framework's inflater
        (Windows PowerShell 5.1) reads a deflated member to its end whatever it declares, where
        newer .NET stops at the declared size. A member that fails to inflate is refused the same
        way. The caller deletes what was written.
    #>
    param(
        [Parameter(Mandatory = $true)]$Entry,
        [Parameter(Mandatory = $true)][System.IO.Stream]$Sink,
        [Parameter(Mandatory = $true)][string]$Label
    )

    $source = $Entry.Open()
    try {
        $buffer = New-Object byte[] 1048576
        $written = 0L
        while ($true) {
            try { $read = $source.Read($buffer, 0, $buffer.Length) }
            catch { throw "Refusing ${Label}: its member '$($Entry.FullName)' doesn't unpack cleanly ($($_.Exception.Message)). Nothing was kept." }
            if ($read -le 0) { break }
            $written += $read
            if ($written -gt $Entry.Length) {
                throw "Refusing ${Label}: its member '$($Entry.FullName)' unpacks to more than the $($Entry.Length) bytes it declares. Nothing was kept."
            }
            $Sink.Write($buffer, 0, $read)
        }
    } finally {
        $source.Dispose()
    }
}

function Get-SplitMemberProblem {
    <#
    .SYNOPSIS
        What is wrong with one member name of a split bundle, or $null when nothing is.
    .DESCRIPTION
        Every member of an .apkm, .apks or .xapk is held to this before anything is unpacked
        (Expand-SplitBundle). A member name is the archive's own text and can say anything: climb
        out with .., start at a root, a drive or a UNC share, use a backslash, which Windows reads
        as a separator and the archive doesn't, or end a segment in a dot or a space, which Windows
        drops, so two names the archive keeps apart land on one file. Each is refused rather than
        cleaned up, because no distributor's bundle needs one. Names in the archive are separated
        by / alone.
    #>
    param([AllowEmptyString()][string]$Name)

    if ([string]::IsNullOrEmpty($Name)) { return 'has no name' }
    if ($Name -match '[\x00-\x1f\x7f]') { return 'holds a control character' }
    if ($Name.Contains('\')) { return 'uses a backslash, which Windows reads as a path separator' }
    if ($Name.Contains(':')) { return 'holds a colon, which names a drive or a stream on Windows' }
    if ($Name.StartsWith('/')) { return 'is rooted' }
    if ($Name.IndexOfAny([char[]]'*?"<>|') -ge 0) { return 'holds a character Windows does not allow in a file name' }
    # Windows opens these as devices whatever extension follows.
    $devices = @('CON', 'PRN', 'AUX', 'NUL', 'CONIN$', 'CONOUT$') + @(0..9 | ForEach-Object { "COM$_"; "LPT$_" })
    $path = if ($Name.EndsWith('/')) { $Name.Substring(0, $Name.Length - 1) } else { $Name }
    foreach ($segment in $path.Split('/')) {
        if ($segment.Length -eq 0) { return 'has an empty path segment' }
        if ($segment -eq '.' -or $segment -eq '..') { return 'climbs with a . or .. segment' }
        if ($segment.EndsWith('.') -or $segment.EndsWith(' ')) {
            return 'ends a segment with a dot or a space, which Windows drops'
        }
        if ($devices -contains $segment.Split('.')[0]) { return 'names a Windows device' }
    }
    return $null
}

function Expand-SplitBundle {
    <#
    .SYNOPSIS
        A split bundle's APKs, unpacked into a directory of their own beside -Destination, once
        every member name has passed.
    .DESCRIPTION
        The CLI's merger unpacked a bundle itself, into tmp_<hash> beside it, writing each .apk
        member where its name pointed and truncating whatever was there, so a member named
        ../<anything>.apk wrote outside the folder. Nothing is written here until every member
        has passed Get-SplitMemberProblem, no two members are one path once case is ignored, as
        Windows ignores it, and no two APKs share a file name. Then the APKs alone go into a new
        .splits-<guid> directory beside -Destination, each created fresh at its file name and
        resolved to a path that has to sit directly in that directory. An APK in a folder of the
        archive (an .apks keeps them in splits/) is unpacked by its file name, as the merger
        named each split by its file name. The extension is written as .apk, which is what the
        merger reads. An APK declaring more than -MemberLimit bytes, or APKs declaring more than
        -TotalLimit together, are refused before anything is written, so a crafted bundle can't
        fill the disk, and no member unpacks past what it declares (Copy-BundleMember).

        The archive stays open, and so unwritable, from the first name read to the last byte
        unpacked. The result goes to Lock-SplitFiles, then Invoke-SplitMerge, and to
        Remove-SplitDirectory when done. Production scripts call none of these themselves: they
        go through Get-MergedApk, which checks every APK's signature between the lock and the
        merge. Only the resource table test calls them straight, for the unsigned APKs aapt2
        builds there.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Apk,
        [Parameter(Mandatory = $true)][string]$Destination,
        [long]$MemberLimit = 1GB,
        [long]$TotalLimit = 2GB
    )

    $Apk = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Apk)
    $Destination = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Destination)
    $label = Split-Path -Leaf $Apk
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($Apk)
    $directory = $null
    $files = New-Object System.Collections.Generic.List[object]
    try {
        $paths = @{}
        $leaves = @{}
        $plan = New-Object System.Collections.Generic.List[object]
        foreach ($entry in @($zip.Entries)) {
            $name = $entry.FullName
            $problem = Get-SplitMemberProblem -Name $name
            if (-not $problem) {
                $key = $name.TrimEnd('/').ToUpperInvariant()
                if ($paths.ContainsKey($key)) { $problem = "is the same path as '$($paths[$key])' once case is ignored" }
                else { $paths[$key] = $name }
            }
            if (-not $problem -and -not $name.EndsWith('/')) {
                $leaf = $name.Substring($name.LastIndexOf('/') + 1)
                if ($leaf -match '\.apk$') {
                    if ($leaf.Length -gt 128 -or $leaf -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]*\.apk$') {
                        $problem = 'is an APK whose file name holds more than letters, digits, dots, dashes and underscores'
                    } elseif ($entry.Length -gt $MemberLimit) {
                        $problem = "would unpack to $($entry.Length) bytes, more than the $MemberLimit bytes one APK may take"
                    } else {
                        $target = $leaf.Substring(0, $leaf.Length - 4) + '.apk'
                        $key = $target.ToUpperInvariant()
                        if ($leaves.ContainsKey($key)) { $problem = "unpacks to $target, as '$($leaves[$key])' does" }
                        else {
                            $leaves[$key] = $name
                            $plan.Add([pscustomobject]@{ Entry = $entry; Member = $name; Name = $target })
                        }
                    }
                }
            }
            if ($problem) { throw "Refusing ${label}: its member '$name' $problem. Nothing was unpacked." }
        }
        if ($plan.Count -eq 0) { throw "$label holds no APK. Nothing was unpacked." }
        $declared = 0L
        foreach ($item in $plan) { $declared += $item.Entry.Length }
        if ($declared -gt $TotalLimit) {
            throw "Refusing ${label}: its APKs would unpack to $declared bytes, more than the $TotalLimit bytes a bundle may take. Nothing was unpacked."
        }

        $parent = Split-Path -Parent $Destination
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
        $candidate = Join-Path $parent ('.splits-' + [guid]::NewGuid().ToString('N'))
        # Without -Force, New-Item refuses a directory that is already there, so this one is ours.
        New-Item -ItemType Directory -Path $candidate -ErrorAction Stop | Out-Null
        $directory = [System.IO.Path]::GetFullPath($candidate)
        foreach ($item in $plan) {
            $path = [System.IO.Path]::GetFullPath([System.IO.Path]::Combine($directory, $item.Name))
            if (-not [System.IO.Path]::GetDirectoryName($path).Equals($directory, [System.StringComparison]::OrdinalIgnoreCase) -or
                    [System.IO.Path]::GetFileName($path) -cne $item.Name) {
                throw "Refusing ${label}: its member '$($item.Member)' would unpack to $path, outside $directory."
            }
            # CreateNew: a file already at that path, however it got there, is refused.
            $sink = [System.IO.File]::Open($path, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write,
                [System.IO.FileShare]::None)
            try { Copy-BundleMember -Entry $item.Entry -Sink $sink -Label $label } finally { $sink.Dispose() }
            $files.Add([pscustomobject]@{ Name = $item.Name; Path = $path; Member = $item.Member; Sha256 = $null; Handle = $null })
        }
    } catch {
        if ($directory) { Remove-Item -LiteralPath $directory -Recurse -Force -ErrorAction SilentlyContinue }
        throw
    } finally {
        $zip.Dispose()
    }
    return [pscustomobject]@{ Bundle = $Apk; Directory = $directory; Files = $files.ToArray() }
}

function Test-SplitBundlePath {
    <#
    .SYNOPSIS
        Whether a path names a split bundle by its extension: the CLI's own list (BundleFormats).
    #>
    param([Parameter(Mandatory = $true)][string]$Path)

    return [System.IO.Path]::GetExtension($Path).TrimStart('.').ToLowerInvariant() -in @('apkm', 'apks', 'xapk')
}

function Copy-ApkToSplitDirectory {
    <#
    .SYNOPSIS
        A plain APK, copied into a new .splits-<guid> directory beside -Destination as input.apk,
        in the shape Expand-SplitBundle returns.
    .DESCRIPTION
        So a plain APK is checked, and patched, from a copy nothing else can write, as a bundle's
        APKs are. The name is fixed, so nothing in the caller's file name reaches a tool.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Apk,
        [Parameter(Mandatory = $true)][string]$Destination
    )

    $Apk = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Apk)
    $Destination = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Destination)
    $parent = Split-Path -Parent $Destination
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
    $candidate = Join-Path $parent ('.splits-' + [guid]::NewGuid().ToString('N'))
    # Without -Force, New-Item refuses a directory that is already there, so this one is ours.
    New-Item -ItemType Directory -Path $candidate -ErrorAction Stop | Out-Null
    $directory = [System.IO.Path]::GetFullPath($candidate)
    $path = Join-Path $directory 'input.apk'
    try {
        $source = [System.IO.File]::OpenRead($Apk)
        try {
            $sink = [System.IO.File]::Open($path, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write,
                [System.IO.FileShare]::None)
            try { $source.CopyTo($sink) } finally { $sink.Dispose() }
        } finally { $source.Dispose() }
    } catch {
        Remove-Item -LiteralPath $directory -Recurse -Force -ErrorAction SilentlyContinue
        throw
    }
    return [pscustomobject]@{ Bundle = $Apk; Directory = $directory
        Files = @([pscustomobject]@{ Name = 'input.apk'; Path = $path; Member = (Split-Path -Leaf $Apk); Sha256 = $null; Handle = $null }) }
}

function Lock-SplitFiles {
    <#
    .SYNOPSIS
        Open each unpacked APK so it can be read and nothing else, and hash it through that handle.
    .DESCRIPTION
        From here until Remove-SplitDirectory, apksigner, aapt2 and the merger can read the files
        and nothing can write, replace or delete them, so the bytes whose signatures are checked
        are the bytes the merger reads. MergeSplits.java holds each file to these hashes again
        before it merges.
    #>
    param([Parameter(Mandatory = $true)]$Splits)

    foreach ($file in @($Splits.Files)) {
        $handle = [System.IO.File]::Open($file.Path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read,
            [System.IO.FileShare]::Read)
        $file.Handle = $handle
        $sha = [System.Security.Cryptography.SHA256]::Create()
        try {
            $handle.Position = 0
            $file.Sha256 = ([System.BitConverter]::ToString($sha.ComputeHash($handle)) -replace '-', '').ToLowerInvariant()
        } finally { $sha.Dispose() }
    }
}

function Remove-SplitDirectory {
    <#
    .SYNOPSIS
        Let go of the unpacked APKs and delete their directory, warning rather than failing.
    #>
    param($Splits)

    if ($null -eq $Splits) { return }
    foreach ($file in @($Splits.Files)) {
        if ($null -ne $file -and $file.Handle) {
            $file.Handle.Dispose()
            $file.Handle = $null
        }
    }
    if (-not $Splits.Directory) { return }
    try {
        if (Test-Path -LiteralPath $Splits.Directory) { Remove-Item -LiteralPath $Splits.Directory -Recurse -Force -ErrorAction Stop }
    } catch {
        Write-Warning "Could not remove the unpacked splits at $($Splits.Directory): $($_.Exception.Message)"
    }
}

function Invoke-SplitMerge {
    <#
    .SYNOPSIS
        The APKs Expand-SplitBundle unpacked and Lock-SplitFiles hashed, merged into -Destination by
        the CLI's own merger (MergeSplits.java), or a throw.
    .DESCRIPTION
        The merger is handed the directory, never the archive, so it reads the files that were
        checked and unpacks nothing itself, and it is handed each file's hash, which it holds the
        directory to before it merges: exactly those files, with exactly those bytes. There is
        nothing to fall back to, so a merge that fails or writes no APK throws.
    #>
    param(
        [Parameter(Mandatory = $true)]$Splits,
        [Parameter(Mandatory = $true)][string]$Destination,
        [Parameter(Mandatory = $true)][string]$Java,
        [Parameter(Mandatory = $true)][string]$DesktopJar
    )

    $label = Split-Path -Leaf $Splits.Bundle
    $digests = @(foreach ($file in @($Splits.Files)) {
        if (-not $file.Sha256) {
            throw "$($file.Name) in $($Splits.Directory) was never hashed (Lock-SplitFiles), so the merge can't be held to the bytes that were checked."
        }
        "$($file.Name):$($file.Sha256)"
    })
    $Destination = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Destination)
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Destination) | Out-Null
    if (Test-Path -LiteralPath $Destination) { Remove-Item -LiteralPath $Destination -Force }
    # Continue for the call alone: Windows PowerShell 5.1 turns a JDK warning on stderr into a
    # terminating error under Stop. The exit code and the file are what decide. The merge waits
    # for a slot in the machine's build queue first, as the patch run after it does.
    $preference = $ErrorActionPreference
    $queued = Enter-HushTumblrQueue -Job 'merge'
    try {
        $ErrorActionPreference = 'Continue'
        $global:LASTEXITCODE = -1
        $output = @(& $Java '-Xmx6g' '-cp' $DesktopJar (Join-Path $PSScriptRoot 'MergeSplits.java') `
            $Splits.Directory $Destination @digests 2>&1 | ForEach-Object { "$_" })
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $preference
        Exit-HushTumblrQueue $queued
    }
    # What the merger said, without the stack frames under an exception.
    $said = @($output | Where-Object { $_ -notmatch '^\s+at ' } | Select-Object -Last 3) -join ' '
    if ($exitCode -ne 0) {
        throw "Could not merge $label into one APK (exit $exitCode): $said"
    }
    if (-not (Test-Path -LiteralPath $Destination -PathType Leaf) -or (Get-Item -LiteralPath $Destination).Length -eq 0) {
        throw ("The merge of $label wrote no APK at $Destination, and base.apk is not what " +
            'the CLI patches, so there is nothing to hold the patched APK to.')
    }
    return $Destination
}

function Get-DeclaredSigners {
    <#
    .SYNOPSIS
        The signer certificate digests patches-list.json declares for a package, lower case.
    #>
    param(
        [Parameter(Mandatory = $true)]$PatchList,
        [Parameter(Mandatory = $true)][string]$PackageName
    )

    $signers = @($PatchList.patches | ForEach-Object { $_.compatibility } |
        Where-Object { $null -ne $_ -and $_.packageName -eq $PackageName } | ForEach-Object { $_.signatures } |
        Where-Object { $_ } | ForEach-Object { "$_".Trim().ToLowerInvariant() } | Sort-Object -Unique)
    if ($signers.Count -eq 0) { throw "patches-list.json declares no signer for $PackageName, so nothing says whose APKs to accept." }
    return $signers
}

function Resolve-Apksigner {
    <#
    .SYNOPSIS
        The apksigner of the build-tools aapt2 came from, or a throw.
    #>
    param([Parameter(Mandatory = $true)][string]$Aapt2)

    $tools = Split-Path -Parent $Aapt2
    foreach ($name in @('apksigner.bat', 'apksigner')) {
        $candidate = Join-Path $tools $name
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    }
    throw "No apksigner beside aapt2 in $tools, so the APKs can't be checked against Tumblr's signer."
}

function Get-ApkSigners {
    <#
    .SYNOPSIS
        Whether apksigner verifies an APK, and the SHA-256 digests of the signer certificates it
        prints, lower case.
    .DESCRIPTION
        Every signer apksigner prints counts (each scheme's signer, and the signers a v3.1 block
        targets at newer platforms), which is the set the fixture suite holds to the declared
        certificates (AppCompatibilitiesMatchFixturesTest). A Source Stamp certificate is the
        store's, not the app's, so it doesn't.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Apk,
        [Parameter(Mandatory = $true)][string]$Apksigner
    )

    # Continue for the call alone: a JDK warning on stderr is a terminating error in Windows
    # PowerShell 5.1 under Stop. The exit code is what gets judged.
    $preference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $global:LASTEXITCODE = -1
        $lines = @(& $Apksigner verify --print-certs $Apk 2>&1 | ForEach-Object { "$_" })
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $preference
    }
    $signers = @($lines | Where-Object { $_ -notmatch '^\s*Source Stamp' } | ForEach-Object {
            $match = [regex]::Match($_, 'certificate SHA-256 digest:\s*([0-9A-Fa-f]{64})\s*$')
            if ($match.Success) { $match.Groups[1].Value.ToLowerInvariant() }
        } | Sort-Object -Unique)
    return [pscustomobject]@{
        Verified = ($exitCode -eq 0)
        Signers  = $signers
        Said     = (@($lines | Where-Object { $_ } | Select-Object -Last 3) -join ' ')
    }
}

function Get-SignerProblem {
    <#
    .SYNOPSIS
        Why a set of signer digests isn't the declared signer, or $null when it is: at least one,
        and every one declared.
    #>
    param([string[]]$Signers, [Parameter(Mandatory = $true)][string[]]$Pinned, [string]$PackageName)

    $found = @($Signers | Where-Object { $_ })
    if ($found.Count -eq 0) { return 'carries no signer certificate apksigner reports' }
    $foreign = @($found | Where-Object { $Pinned -notcontains $_ })
    if ($foreign.Count -gt 0) {
        return "is signed by $($foreign -join ', '), which isn't a certificate patches-list.json declares for $PackageName"
    }
    return $null
}

function Get-SplitManifestIdentity {
    <#
    .SYNOPSIS
        An APK's package, version code and split name, read off its manifest element with aapt2.
    .DESCRIPTION
        A base APK names no split. Only the manifest element's own attributes are read, up to its
        first child element.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Apk,
        [Parameter(Mandatory = $true)][string]$Aapt2
    )

    $preference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $global:LASTEXITCODE = -1
        $dump = @(& $Aapt2 dump xmltree --file AndroidManifest.xml $Apk 2>&1 | ForEach-Object { "$_" })
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $preference
    }
    if ($exitCode -ne 0) { throw "aapt2 could not read the manifest of ${Apk}: $(@($dump | Select-Object -Last 2) -join ' ')" }
    $indent = -1
    $attributes = New-Object 'System.Collections.Generic.Dictionary[string,string]' ([System.StringComparer]::Ordinal)
    foreach ($line in $dump) {
        if ($indent -lt 0) {
            $element = [regex]::Match($line, '^(\s*)E:\s*manifest\s*\(')
            if ($element.Success) { $indent = $element.Groups[1].Length }
            continue
        }
        if ($line -match '^\s*E:') { break }
        $attribute = [regex]::Match($line, '^(\s*)A:\s*([^\s(=]+)(?:\(0x[0-9a-fA-F]+\))?=(.*)$')
        if (-not $attribute.Success -or $attribute.Groups[1].Length -le $indent) { continue }
        $value = $attribute.Groups[3].Value.Trim()
        $raw = $value.IndexOf(' (Raw: ')
        if ($raw -ge 0) { $value = $value.Substring(0, $raw).Trim() }
        if ($value.Length -ge 2 -and $value.StartsWith('"') -and $value.EndsWith('"')) { $value = $value.Substring(1, $value.Length - 2) }
        if ($attributes.ContainsKey($attribute.Groups[2].Value)) { throw "The manifest of $Apk repeats $($attribute.Groups[2].Value)." }
        $attributes.Add($attribute.Groups[2].Value, $value)
    }
    if ($indent -lt 0) { throw "aapt2 found no manifest element in $Apk." }
    $read = {
        param([string]$Name)
        $value = $null
        if ($attributes.TryGetValue($Name, [ref]$value)) { return $value }
        return ''
    }
    return [pscustomobject]@{
        Package     = (& $read 'package')
        VersionCode = (& $read 'http://schemas.android.com/apk/res/android:versionCode')
        Split       = (& $read 'split')
    }
}

function Get-ApkEntryDigests {
    <#
    .SYNOPSIS
        The SHA-256 of every entry of a locked APK whose name matches -Pattern, by name, read
        through the handle Lock-SplitFiles holds.
    #>
    param(
        [Parameter(Mandatory = $true)]$File,
        [Parameter(Mandatory = $true)][string]$Pattern
    )

    Add-Type -AssemblyName System.IO.Compression
    $File.Handle.Position = 0
    $zip = New-Object System.IO.Compression.ZipArchive($File.Handle, [System.IO.Compression.ZipArchiveMode]::Read, $true)
    try {
        $map = New-Object 'System.Collections.Generic.SortedDictionary[string,string]' ([System.StringComparer]::Ordinal)
        foreach ($entry in @($zip.Entries)) {
            if ($entry.FullName.EndsWith('/') -or $entry.FullName -cnotmatch $Pattern) { continue }
            if ($map.ContainsKey($entry.FullName)) { throw "$($File.Member) holds $($entry.FullName) twice." }
            $stream = $entry.Open()
            $sha = [System.Security.Cryptography.SHA256]::Create()
            try { $map.Add($entry.FullName, ([System.BitConverter]::ToString($sha.ComputeHash($stream)) -replace '-', '').ToLowerInvariant()) }
            finally { $sha.Dispose(); $stream.Dispose() }
        }
        return , $map
    } finally {
        $zip.Dispose()
    }
}

function Assert-SplitsAuthentic {
    <#
    .SYNOPSIS
        Every APK Expand-SplitBundle unpacked and Lock-SplitFiles locked, held to the declared
        signer and to one app build, or a throw before anything is merged.
    .DESCRIPTION
        apksigner has to verify each APK, and every signer it prints but a Source Stamp has to be
        a certificate patches-list.json declares for the package, the rule the fixture suite holds
        each bundle to (AppCompatibilitiesMatchFixturesTest). An unsigned split, one whose bytes
        changed after signing and one signed by anybody else are refused the same way. Then the
        manifests: each APK the declared package, all of one version code, exactly one base (an
        APK naming no split) and no split name twice. Returns the base, the version code and what
        each APK was found to be.
    #>
    param(
        [Parameter(Mandatory = $true)]$Splits,
        [Parameter(Mandatory = $true)][string]$Aapt2,
        [Parameter(Mandatory = $true)][string]$PackageName,
        [Parameter(Mandatory = $true)][string[]]$Signers
    )

    $label = Split-Path -Leaf $Splits.Bundle
    $pinned = @($Signers | ForEach-Object { "$_".Trim().ToLowerInvariant() } | Where-Object { $_ })
    if ($pinned.Count -eq 0) { throw "No signer is declared for $PackageName, so $label can't be checked." }
    $apksigner = Resolve-Apksigner -Aapt2 $Aapt2
    $refuse = { param([string]$Why) throw "Refusing ${label}: $Why. Nothing was merged or patched." }
    $apks = New-Object System.Collections.Generic.List[object]
    foreach ($file in @($Splits.Files)) {
        if (-not $file.Handle -or -not $file.Sha256) {
            throw "$($file.Name) in $($Splits.Directory) was not locked and hashed (Lock-SplitFiles) before its signature was checked."
        }
        $signed = Get-ApkSigners -Apk $file.Path -Apksigner $apksigner
        if (-not $signed.Verified) { & $refuse "$($file.Member) does not verify ($($signed.Said))" }
        $problem = Get-SignerProblem -Signers $signed.Signers -Pinned $pinned -PackageName $PackageName
        if ($problem) { & $refuse "$($file.Member) $problem" }
        $identity = Get-SplitManifestIdentity -Apk $file.Path -Aapt2 $Aapt2
        if ($identity.Package -cne $PackageName) { & $refuse "$($file.Member) is $($identity.Package), not $PackageName" }
        if (-not $identity.VersionCode) { & $refuse "$($file.Member) declares no version code" }
        $apks.Add([pscustomobject]@{ File = $file; Member = $file.Member; Name = $file.Name; Sha256 = $file.Sha256
            Split = $identity.Split; VersionCode = $identity.VersionCode; Signers = $signed.Signers })
    }
    $codes = @($apks | ForEach-Object { $_.VersionCode } | Sort-Object -Unique)
    if ($codes.Count -ne 1) { & $refuse "its APKs carry more than one version code ($($codes -join ', '))" }
    $bases = @($apks | Where-Object { -not $_.Split })
    if ($bases.Count -ne 1) {
        & $refuse "it holds $($bases.Count) base APKs, APKs naming no split, where an app has one ($(@($bases | ForEach-Object { $_.Member }) -join ', '))"
    }
    $splitNames = @{}
    foreach ($apk in @($apks | Where-Object { $_.Split })) {
        $key = $apk.Split.ToUpperInvariant()
        if ($splitNames.ContainsKey($key)) { & $refuse "$($splitNames[$key]) and $($apk.Member) are both split $($apk.Split)" }
        $splitNames[$key] = $apk.Member
    }
    return [pscustomobject]@{
        Package     = $PackageName
        VersionCode = $codes[0]
        Base        = $bases[0]
        Apks        = $apks.ToArray()
        Signers     = @($apks | ForEach-Object { $_.Signers } | Sort-Object -Unique)
    }
}

function Get-MergedApk {
    <#
    .SYNOPSIS
        The one APK the desktop CLI patches, written to -Destination once every APK it is made
        from has been checked against the declared signer: a split bundle merged the way the CLI
        merges it, or a copy of a plain APK.
    .DESCRIPTION
        morphe-desktop merges an .apkm, .apks or .xapk into <name>-merged.apk beside its output,
        patches that, and since 1.17.0 deletes it on the way out. The patched APK's resource table
        and manifest were rebuilt from that merge, so the checks compared them with the base APK
        instead, which lacks every resource the splits carry: on the Facebook sibling's 580 the
        patched table held 7,588 resources base.apk doesn't, and none of them was compared. So the
        scripts merge first,
        with the CLI's own merger and the arguments it passes (MergeSplits.java), and hand the CLI
        the merged APK, which it patches as it is. The bundle is unpacked here, into a directory
        of the run's own, once every member name has been checked (Expand-SplitBundle), and the
        merger reads that directory.

        Only base.apk's signer used to be checked, by some callers and after the merge, so an
        unsigned or re-signed split was merged, patched, signed and installed as Tumblr's. Now
        every APK is locked and hashed where it was unpacked (Lock-SplitFiles), and has to verify
        with apksigner, carry only declared signers and agree with the others on the package and
        the version code, with one base and no split twice (Assert-SplitsAuthentic), before the
        merger reads exactly those bytes. A plain APK is copied the same way and checked the same
        way; a universal APK that doesn't verify (merging drops every signature) passes only as
        the merge of the same-named .apkm, .xapk or .apks beside it, whose APKs all pass, with
        that bundle's base dex and every native library its APKs carry byte for byte, and nothing
        but the manifest and resource table that isn't one of that bundle's files (res files by
        content, since the merge can rename their folders). Then that bundle's APKs are merged here
        too, and the universal APK has to hold exactly that merge's files, each with the same bytes,
        so one that left an asset out, or carries a signed res file or resource table that isn't
        the merge's own, is refused. Fixtures.signedForm holds the fixture suite to the same rule.
        There is no switch to skip any of it. The
        checks live in Open-AuthenticInput, which Assert-AuthenticInput runs on its own for a caller
        that refuses a list of inputs before merging the first.

        Beside -Destination goes <Destination>.authentic.json, the hashes of what was merged and of
        the base APK whose signer stands for it, which verify-injected-registers.ps1 holds a
        -CleanMerged it is handed to (Assert-AuthenticMerge). A merge that fails or writes no APK
        throws, and so does every refusal, before -Destination is touched.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Apk,
        [Parameter(Mandatory = $true)][string]$Destination,
        [Parameter(Mandatory = $true)][string]$Java,
        [Parameter(Mandatory = $true)][string]$DesktopJar,
        [Parameter(Mandatory = $true)][string]$Aapt2,
        [Parameter(Mandatory = $true)][string]$PackageName,
        [Parameter(Mandatory = $true)][string[]]$Signers
    )

    $Apk = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Apk)
    $Destination = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Destination)
    $record = "$Destination.authentic.json"
    $opened = $null
    $writing = $false
    try {
        $opened = Open-AuthenticInput -Apk $Apk -Destination $Destination -Java $Java -DesktopJar $DesktopJar `
            -Aapt2 $Aapt2 -PackageName $PackageName -Signers $Signers
        $facts = $opened.Facts
        $writing = $true
        if (Test-Path -LiteralPath $record) { Remove-Item -LiteralPath $record -Force }
        if ($opened.Plain) {
            if (Test-Path -LiteralPath $Destination) { Remove-Item -LiteralPath $Destination -Force }
            # The CLI patches the bytes that were checked, read through the handle that kept them.
            $opened.Plain.Handle.Position = 0
            $sink = [System.IO.File]::Open($Destination, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write,
                [System.IO.FileShare]::None)
            try { $opened.Plain.Handle.CopyTo($sink) } finally { $sink.Dispose() }
        } else {
            Invoke-SplitMerge -Splits $opened.Splits -Destination $Destination -Java $Java -DesktopJar $DesktopJar | Out-Null
        }
        $merged = (Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash.ToLowerInvariant()
        $recordFacts = [ordered]@{
            input        = $Apk
            package      = $PackageName
            versionCode  = [string]$facts.VersionCode
            signers      = @($facts.Signers)
            signedForm   = if ($opened.SignedForm) { $opened.SignedForm.Bundle } else { $null }
            baseSha256   = [string]$facts.Base.Sha256
            mergedSha256 = $merged
            apks         = @($facts.Apks | ForEach-Object {
                [ordered]@{ member = [string]$_.Member; name = [string]$_.Name; sha256 = [string]$_.Sha256; split = [string]$_.Split } })
        }
        [System.IO.File]::WriteAllText($record, ($recordFacts | ConvertTo-Json -Depth 5), (New-Object System.Text.UTF8Encoding($false)))
        $writing = $false
        return $Destination
    } catch {
        if ($writing) { Remove-Item -LiteralPath $Destination, $record -Force -ErrorAction SilentlyContinue }
        throw
    } finally {
        Close-AuthenticInput -Opened $opened
    }
}

function Open-AuthenticInput {
    <#
    .SYNOPSIS
        Every APK -Apk is made of, unpacked or copied beside -Destination, locked, hashed and held
        to the declared signer and one build (Get-MergedApk's checks, with nothing merged), or a
        throw that leaves nothing behind.
    .DESCRIPTION
        The answer keeps the APKs open: Splits (a bundle's APKs, or the copy of a plain APK),
        SignedForm (the bundle beside an unsigned universal APK, checked in its place), Plain (the
        plain APK's file, when -Apk isn't a bundle) and Facts (what Assert-SplitsAuthentic found, or
        the same shape for a signed plain APK). Close-AuthenticInput lets go of them. Get-MergedApk
        merges or copies from it, and Assert-AuthenticInput only checks. Production scripts call
        neither this nor Close-AuthenticInput themselves. -Java and -DesktopJar are for the one
        input that needs a merge to be checked at all: an unsigned universal APK, held to the merge
        of the bundle beside it.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Apk,
        [Parameter(Mandatory = $true)][string]$Destination,
        [Parameter(Mandatory = $true)][string]$Java,
        [Parameter(Mandatory = $true)][string]$DesktopJar,
        [Parameter(Mandatory = $true)][string]$Aapt2,
        [Parameter(Mandatory = $true)][string]$PackageName,
        [Parameter(Mandatory = $true)][string[]]$Signers
    )

    $Apk = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Apk)
    $Destination = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Destination)
    $label = Split-Path -Leaf $Apk
    $refuse = { param([string]$Why) throw "Refusing ${label}: $Why. Nothing was merged or patched." }
    # Each folder goes on here the moment it exists, so a throw at any later step deletes it.
    $opened = [pscustomobject]@{ Apk = $Apk; Splits = $null; SignedForm = $null; Plain = $null; Facts = $null }
    try {
        if (Test-SplitBundlePath -Path $Apk) {
            $opened.Splits = Expand-SplitBundle -Apk $Apk -Destination $Destination
            Lock-SplitFiles -Splits $opened.Splits
            $opened.Facts = Assert-SplitsAuthentic -Splits $opened.Splits -Aapt2 $Aapt2 -PackageName $PackageName -Signers $Signers
        } else {
            $opened.Splits = Copy-ApkToSplitDirectory -Apk $Apk -Destination $Destination
            Lock-SplitFiles -Splits $opened.Splits
            $plain = $opened.Splits.Files[0]
            $pinned = @($Signers | ForEach-Object { "$_".Trim().ToLowerInvariant() } | Where-Object { $_ })
            $identity = Get-SplitManifestIdentity -Apk $plain.Path -Aapt2 $Aapt2
            if ($identity.Package -cne $PackageName) { & $refuse "it is $($identity.Package), not $PackageName" }
            if ($identity.Split) { & $refuse "it is the split $($identity.Split), not a whole app" }
            if (-not $identity.VersionCode) { & $refuse 'it declares no version code' }
            $signed = Get-ApkSigners -Apk $plain.Path -Apksigner (Resolve-Apksigner -Aapt2 $Aapt2)
            if ($signed.Verified) {
                $problem = Get-SignerProblem -Signers $signed.Signers -Pinned $pinned -PackageName $PackageName
                if ($problem) { & $refuse "it $problem" }
                $facts = [pscustomobject]@{ VersionCode = $identity.VersionCode; Signers = $signed.Signers
                    Base = [pscustomobject]@{ Sha256 = $plain.Sha256 }
                    Apks = @([pscustomobject]@{ Member = $label; Name = $plain.Name; Sha256 = $plain.Sha256; Split = '' }) }
            } else {
                $stem = Join-Path (Split-Path -Parent $Apk) ([System.IO.Path]::GetFileNameWithoutExtension($Apk))
                $bundle = @('.apkm', '.xapk', '.apks' | ForEach-Object { "$stem$_" } |
                    Where-Object { Test-Path -LiteralPath $_ -PathType Leaf }) | Select-Object -First 1
                if (-not $bundle) {
                    & $refuse ("it does not verify ($($signed.Said)), and no .apkm, .xapk or .apks of the same name beside it " +
                        'holds the signed build it could be a merge of')
                }
                $bundleLabel = Split-Path -Leaf $bundle
                $opened.SignedForm = Expand-SplitBundle -Apk $bundle -Destination $Destination
                Lock-SplitFiles -Splits $opened.SignedForm
                $facts = Assert-SplitsAuthentic -Splits $opened.SignedForm -Aapt2 $Aapt2 -PackageName $PackageName -Signers $Signers
                if ($identity.VersionCode -cne $facts.VersionCode) {
                    & $refuse "it does not verify, and it is version code $($identity.VersionCode) where $bundleLabel is $($facts.VersionCode)"
                }
                $signedCode = Get-ApkEntryDigests -File $facts.Base.File -Pattern '^classes\d*\.dex$'
                if ($signedCode.Count -eq 0) { & $refuse "it does not verify, and the base APK of $bundleLabel holds no dex to hold it to" }
                $libraries = New-Object 'System.Collections.Generic.SortedDictionary[string,string]' ([System.StringComparer]::Ordinal)
                # Every name and digest the bundle's APKs carry, and the digests of their res files,
                # for the last check below.
                $signedEntries = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::Ordinal)
                $signedRes = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::Ordinal)
                # Not $apk, which is -Apk: PowerShell names are case-insensitive. A dictionary goes
                # through GetEnumerator(): foreach over the dictionary itself runs once, with the
                # dictionary, so every key read as empty and every comparison below matched.
                foreach ($signedApk in @($facts.Apks)) {
                    $carried = Get-ApkEntryDigests -File $signedApk.File -Pattern '.'
                    foreach ($pair in $carried.GetEnumerator()) {
                        [void]$signedEntries.Add("$($pair.Key)`0$($pair.Value)")
                        if ($pair.Key.StartsWith('res/', [System.StringComparison]::Ordinal)) { [void]$signedRes.Add($pair.Value) }
                        if (-not $pair.Key.StartsWith('lib/', [System.StringComparison]::Ordinal)) { continue }
                        if ($libraries.ContainsKey($pair.Key)) { & $refuse "$bundleLabel carries $($pair.Key) in two of its APKs" }
                        $libraries.Add($pair.Key, $pair.Value)
                    }
                }
                $text = { param($Map) @(foreach ($pair in $Map.GetEnumerator()) { "$($pair.Key)=$($pair.Value)" }) -join "`n" }
                if ((& $text (Get-ApkEntryDigests -File $plain -Pattern '^classes\d*\.dex$')) -cne (& $text $signedCode)) {
                    & $refuse "it does not verify, and its dex files aren't those of the base APK of $bundleLabel, so it isn't that signed build's merge"
                }
                if ((& $text (Get-ApkEntryDigests -File $plain -Pattern '^lib/')) -cne (& $text $libraries)) {
                    & $refuse "it does not verify, and its native libraries aren't those of $bundleLabel, so it isn't that signed build's merge"
                }
                # The rest of the merge is the bundle's too, byte for byte: assets, Java resources,
                # META-INF and every res file. The merger copies them, but a res file can lose a -v4 or
                # -v21 qualifier from its folder name on the way (1,517 of them in 46.5.0.109), so res
                # files are matched by content alone. Only AndroidManifest.xml and resources.arsc are
                # rebuilt by the merge, and both are held to the bundle's own merge below.
                $plainEntries = Get-ApkEntryDigests -File $plain -Pattern '.'
                foreach ($pair in $plainEntries.GetEnumerator()) {
                    if ($pair.Key -ceq 'AndroidManifest.xml' -or $pair.Key -ceq 'resources.arsc') { continue }
                    $signedCopy = if ($pair.Key.StartsWith('res/', [System.StringComparison]::Ordinal)) { $signedRes.Contains($pair.Value) } else { $signedEntries.Contains("$($pair.Key)`0$($pair.Value)") }
                    if (-not $signedCopy) {
                        & $refuse "it does not verify, and its $($pair.Key) isn't in any APK of $bundleLabel, so it isn't that signed build's merge"
                    }
                }
                # None of that says the merge is whole, or that a file sits where the app looks for it:
                # an APK that left an asset out passed, and so did one with a signed res file under
                # another's name, or with the base APK's resource table and none of what the splits
                # add to it. So the bundle's APKs, checked and locked above, are merged the way the CLI
                # merges them, and this APK has to hold exactly that merge's files, each with the same
                # bytes, the manifest and resource table among them. The merge is what drops a
                # qualifier or keeps one copy of a file two APKs carry, so nothing here counts the
                # files the APKs held between them. The merge is read through a handle of its own and
                # deleted. Another process of the same user swapping files mid-run is a separate gap.
                $trustedPath = Join-Path (Split-Path -Parent $Destination) ('.trusted-' + [guid]::NewGuid().ToString('N') + '.apk')
                try {
                    Invoke-SplitMerge -Splits $opened.SignedForm -Destination $trustedPath -Java $Java -DesktopJar $DesktopJar | Out-Null
                    $trustedFile = [pscustomobject]@{ Member = "the merge of $bundleLabel"
                        Handle = [System.IO.File]::Open($trustedPath, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::Read) }
                    try { $trusted = Get-ApkEntryDigests -File $trustedFile -Pattern '.' } finally { $trustedFile.Handle.Dispose() }
                } finally {
                    Remove-Item -LiteralPath $trustedPath -Force -ErrorAction SilentlyContinue
                }
                foreach ($pair in $trusted.GetEnumerator()) {
                    $own = $null
                    if (-not $plainEntries.TryGetValue($pair.Key, [ref]$own)) {
                        & $refuse "it does not verify, and it has no $($pair.Key), which the merge of $bundleLabel holds, so it isn't that signed build's whole merge"
                    }
                    if ($own -cne $pair.Value) {
                        & $refuse "it does not verify, and its $($pair.Key) isn't the one the merge of $bundleLabel holds, so it isn't that signed build's merge"
                    }
                }
                foreach ($pair in $plainEntries.GetEnumerator()) {
                    if (-not $trusted.ContainsKey($pair.Key)) {
                        & $refuse "it does not verify, and its $($pair.Key) isn't in the merge of $bundleLabel, so it isn't that signed build's merge"
                    }
                }
            }
            $opened.Plain = $plain
            $opened.Facts = $facts
        }
        return $opened
    } catch {
        Close-AuthenticInput -Opened $opened
        throw
    }
}

function Close-AuthenticInput {
    <#
    .SYNOPSIS
        Let go of the APKs Open-AuthenticInput holds and delete the folders they were unpacked or
        copied into, warning rather than failing.
    #>
    param($Opened)

    if ($null -eq $Opened) { return }
    Remove-SplitDirectory -Splits $Opened.Splits
    Remove-SplitDirectory -Splits $Opened.SignedForm
}

function Assert-AuthenticInput {
    <#
    .SYNOPSIS
        Get-MergedApk's checks on -Apk, or the throw Get-MergedApk would give, with nothing merged,
        copied or recorded.
    .DESCRIPTION
        The APKs are unpacked beside -Destination for the length of the call and are gone when it
        returns or throws. A caller given several inputs checks them all with this before it merges
        or patches the first (build-release-receipt.ps1), and still goes through Get-MergedApk for
        each, which checks again. An unsigned universal APK is the exception to "nothing merged":
        the bundle beside it is merged to hold it to, and that merge is deleted before this returns.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Apk,
        [Parameter(Mandatory = $true)][string]$Destination,
        [Parameter(Mandatory = $true)][string]$Java,
        [Parameter(Mandatory = $true)][string]$DesktopJar,
        [Parameter(Mandatory = $true)][string]$Aapt2,
        [Parameter(Mandatory = $true)][string]$PackageName,
        [Parameter(Mandatory = $true)][string[]]$Signers
    )

    $opened = $null
    try {
        $opened = Open-AuthenticInput -Apk $Apk -Destination $Destination -Java $Java -DesktopJar $DesktopJar `
            -Aapt2 $Aapt2 -PackageName $PackageName -Signers $Signers
    } finally {
        Close-AuthenticInput -Opened $opened
    }
}

function Assert-AuthenticMerge {
    <#
    .SYNOPSIS
        Hold a merged clean APK to the record Get-MergedApk wrote beside it, and its base APK to
        the one whose signer was checked, or throw.
    .DESCRIPTION
        A merge carries no signature of its own, so nothing in the file says its splits were
        Tumblr's. Get-MergedApk records the hash of what it wrote and of the base APK whose signer
        stands for it. A merged APK with no record, or bytes other than the ones recorded, or a
        base other than the recorded one, wasn't made from APKs that passed.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Merged,
        [Parameter(Mandatory = $true)][string]$Base,
        [Parameter(Mandatory = $true)][string]$PackageName
    )

    $record = "$Merged.authentic.json"
    if (-not (Test-Path -LiteralPath $record -PathType Leaf)) {
        throw ("$Merged has no record of the signature check Get-MergedApk makes before it merges ($record), so nothing says " +
            'the APKs it was made from were Tumblr''s. Leave -CleanMerged out to have the clean side merged and checked here.')
    }
    try { $facts = Get-Content -LiteralPath $record -Raw | ConvertFrom-Json }
    catch { throw "The record beside $Merged can't be read: $($_.Exception.Message)" }
    $mergedHash = (Get-FileHash -LiteralPath $Merged -Algorithm SHA256).Hash.ToLowerInvariant()
    $baseHash = (Get-FileHash -LiteralPath $Base -Algorithm SHA256).Hash.ToLowerInvariant()
    if ([string]$facts.package -cne $PackageName) { throw "$Merged was checked as $($facts.package), not $PackageName." }
    if ([string]$facts.mergedSha256 -cne $mergedHash) {
        throw "$Merged changed after the APKs it was made from were checked: it hashes to $mergedHash, and the record says $($facts.mergedSha256)."
    }
    if ([string]$facts.baseSha256 -cne $baseHash) {
        throw ("The clean base APK $Base hashes to $baseHash, and the base whose signer stands for $Merged hashed to " +
            "$($facts.baseSha256), so they aren't the same build.")
    }
}

function Get-NativeLibraryAlignment {
    <#
    .SYNOPSIS
        Every 64-bit ELF library under lib/ of an APK: its name, its SHA-256 and what keeps it off
        a device with 16 KB pages, if anything does.
    .DESCRIPTION
        Read off the program headers, the way Android's page size guide says to. Every LOAD
        segment has to be aligned to at least 2**14, and a GNU_RELRO segment has to end on a 16 KB
        boundary, (VirtAddr + MemSiz) % 0x4000 == 0. A library with no GNU_RELRO has nothing to
        end there, so that part passes. 32-bit libraries only run where pages are 4 KB, and a file
        under lib/ that isn't ELF is never mapped as code, so neither is read.
    #>
    param([Parameter(Mandatory = $true)][string]$Apk)

    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $page = [uint64]0x4000
    $libraries = New-Object System.Collections.Generic.List[object]
    # ZipFile reads a relative path against the process directory, not PowerShell's location.
    $zip = [System.IO.Compression.ZipFile]::OpenRead($ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Apk))
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        foreach ($entry in @($zip.Entries)) {
            if ($entry.FullName -cnotmatch '^lib/[^/]+/[^/]+$') { continue }
            $buffer = New-Object System.IO.MemoryStream
            $stream = $entry.Open()
            try { $stream.CopyTo($buffer) } finally { $stream.Dispose() }
            $bytes = $buffer.ToArray()
            # 0x7F 'E' 'L' 'F', then the class: 2 is 64-bit.
            if ($bytes.Length -lt 64 -or $bytes[0] -ne 0x7F -or $bytes[1] -ne 0x45 -or $bytes[2] -ne 0x4C -or
                $bytes[3] -ne 0x46 -or $bytes[4] -ne 2) { continue }
            $problems = New-Object System.Collections.Generic.List[string]
            $headers = [System.BitConverter]::ToUInt64($bytes, 0x20)
            $headerSize = [System.BitConverter]::ToUInt16($bytes, 0x36)
            $headerCount = [System.BitConverter]::ToUInt16($bytes, 0x38)
            $loads = 0
            if ($bytes[5] -ne 1) {
                $problems.Add('it isn''t a little-endian library, so its segments can''t be read')
            } elseif ($headerSize -lt 56 -or $headers -gt [uint64]$bytes.Length -or
                [uint64]$headerCount * [uint64]$headerSize -gt [uint64]$bytes.Length - $headers) {
                $problems.Add('its program headers run past the end of the file')
            } else {
                for ($i = 0; $i -lt $headerCount; $i++) {
                    $at = [int]$headers + $i * $headerSize
                    $type = [System.BitConverter]::ToUInt32($bytes, $at)
                    if ($type -eq 1) {
                        $loads++
                        $align = [System.BitConverter]::ToUInt64($bytes, $at + 48)
                        if ($align -lt $page) { $problems.Add(('LOAD segment {0} is aligned to 0x{1:x}, under 0x4000' -f $i, $align)) }
                    } elseif ($type -eq 0x6474e552) {
                        $address = [System.BitConverter]::ToUInt64($bytes, $at + 16)
                        $size = [System.BitConverter]::ToUInt64($bytes, $at + 40)
                        if ((($address % $page) + ($size % $page)) % $page -ne 0) {
                            $problems.Add(('GNU_RELRO segment {0} (VirtAddr 0x{1:x}, MemSiz 0x{2:x}) doesn''t end on a 16 KB boundary' -f
                                    $i, $address, $size))
                        }
                    }
                }
                if ($loads -eq 0) { $problems.Add('it has no LOAD segment') }
            }
            $libraries.Add([pscustomobject]@{
                    Name     = $entry.FullName
                    Sha256   = ([System.BitConverter]::ToString($sha.ComputeHash($bytes)) -replace '-', '').ToLowerInvariant()
                    Problems = $problems.ToArray()
                })
        }
    } finally {
        $sha.Dispose()
        $zip.Dispose()
    }
    return $libraries.ToArray()
}

function Resolve-Zipalign {
    <#
    .SYNOPSIS
        The zipalign of the build-tools aapt2 came from, or a throw.
    #>
    param([Parameter(Mandatory = $true)][string]$Aapt2)

    $tools = Split-Path -Parent $Aapt2
    foreach ($name in @('zipalign.exe', 'zipalign', 'zipalign.bat')) {
        $candidate = Join-Path $tools $name
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    }
    throw "No zipalign beside aapt2 in $tools, so the APK's layout can't be checked for devices with 16 KB pages."
}

function Assert-PageAlignment {
    <#
    .SYNOPSIS
        Hold a patched APK to what a device with 16 KB pages needs, or throw. Returns one line for
        each library of Tumblr's own that doesn't meet it, which is the target's limit to report.
    .DESCRIPTION
        zipalign -c -P 16 -v 4 has to pass, with the zipalign beside aapt2 (build-tools 35 or
        newer, the first to take -P). Nothing here aligns: zipalign after a signature breaks it,
        so an APK is held to this before it's signed and again once it is.

        Then the 64-bit libraries (Get-NativeLibraryAlignment). Patching leaves native code alone,
        so one the stock APK also holds has to be the same bytes. One that fails with Tumblr's own
        bytes can't be put right here without rewriting Tumblr's code: it comes back as a line for
        the caller to print, signed or not. A failing library Tumblr didn't ship is a throw.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Apk,
        # The stock APK the patched one was made from: a bundle's merge, or the plain APK.
        [Parameter(Mandatory = $true)][string]$Stock,
        [Parameter(Mandatory = $true)][string]$Aapt2
    )

    $label = Split-Path -Leaf $Apk
    $zipalign = Resolve-Zipalign -Aapt2 $Aapt2
    # Continue for the call alone: zipalign warns on stderr about headers a merge leaves, which is
    # a terminating error in Windows PowerShell 5.1 under Stop. The exit code is what gets judged.
    $preference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $global:LASTEXITCODE = -1
        $lines = @(& $zipalign -c -P 16 -v 4 $Apk 2>&1 | ForEach-Object { "$_" })
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $preference
    }
    if ($exitCode -ne 0) {
        $telling = @($lines | Where-Object { $_ -match '\(BAD' } | Select-Object -First 5)
        if ($telling.Count -eq 0) { $telling = @($lines | Where-Object { $_ } | Select-Object -First 2) }
        throw ("zipalign -c -P 16 -v 4 didn't pass $label (exit $exitCode), so it isn't accepted for devices with " +
            "16 KB pages: $(@($telling | ForEach-Object { $_.Trim() }) -join '; ')")
    }

    $limits = New-Object System.Collections.Generic.List[string]
    $libraries = @(Get-NativeLibraryAlignment -Apk $Apk)
    if ($libraries.Count -eq 0) { return $limits.ToArray() }
    $stockLibraries = New-Object 'System.Collections.Generic.Dictionary[string,string]' ([System.StringComparer]::Ordinal)
    foreach ($library in @(Get-NativeLibraryAlignment -Apk $Stock)) { $stockLibraries[$library.Name] = $library.Sha256 }
    foreach ($library in $libraries) {
        $shipped = $null
        $known = $stockLibraries.TryGetValue($library.Name, [ref]$shipped)
        if ($known -and $shipped -cne $library.Sha256) {
            throw ("$($library.Name) in $label isn't the library Tumblr shipped (SHA-256 $($library.Sha256), not $shipped). " +
                'Patching leaves native code as it is, so this APK isn''t accepted.')
        }
        if ($library.Problems.Count -eq 0) { continue }
        if (-not $known) {
            throw ("$($library.Name) in $label, which Tumblr didn't ship, can't be mapped on a device with 16 KB pages: " +
                "$($library.Problems -join '; ').")
        }
        $limits.Add("16 KB target limitation: $($library.Name) is Tumblr's own library, left as it is, and " +
            "$($library.Problems -join '; ').")
    }
    return $limits.ToArray()
}

function Assert-UrlReachable {
    <#
    .SYNOPSIS
        A HEAD request that has to answer 200, or a throw naming the address and what it said.
    .DESCRIPTION
        validate-release-facts.ps1 fetches the indexed bundle and the Morphe add-source page
        with this on every run. It lives here so the contract tests can call it on its own:
        through the release check, an address has to pass the shape, scheme and host checks
        before it is fetched, and the one case that meant to try a dead link tripped the shape
        check first and never reached the fetch.
    #>
    param(
        [Uri]$Uri,
        [string]$Description,
        [string]$FailureHint
    )
    # -SkipHttpErrorCheck is PowerShell 7 only, and the pre-push hook runs whichever shell it
    # found, so a 404 has to be read out of the thrown response instead. That is the answer this
    # check exists for: the index once named a tag that did not exist yet.
    $status = 0
    try {
        # -UseBasicParsing because Windows PowerShell otherwise hands the reply to the IE
        # parser, which throws a null reference on a HEAD with no body. PowerShell 7 accepts
        # the switch and ignores it.
        $response = Invoke-WebRequest -Uri $Uri -Method Head -MaximumRedirection 5 `
            -TimeoutSec 60 -UseBasicParsing
        $status = [int]$response.StatusCode
    } catch {
        $failed = $_.Exception.Response
        if ($failed -and $failed.StatusCode) {
            $status = [int]$failed.StatusCode
        } else {
            throw ("Could not reach the ${Description} ${Uri}: $($_.Exception.Message). " +
                'If the network is down, push with HUSHTUMBLR_SKIP_PRE_PUSH=1 and run this again later.')
        }
    }
    if ($status -ne 200) {
        throw ("The ${Description} ${Uri} answered HTTP ${status}. " + $FailureHint)
    }
    Write-Host ("[release] ${Description} answers 200: " + $Uri)
}

function Find-MachineNames {
    <#
    .SYNOPSIS
        The lines of tracked files that name the maintainer's machine or a phone.
    .DESCRIPTION
        The working-notes folder .gitignore keeps out, the backup folders on the maintainer's
        machine that share its name, and an adb serial, a Samsung one being R5C and eight more
        letters or digits. Four fixture tests once fell back to one of those folders, which
        skipped quietly on every other machine and published this one's layout, and five scripts
        carried the test phone's serial. .gitignore is the one file allowed to name what it keeps
        out. Both patterns are built from parts, so the file holding them can't match itself.

        git grep reads bytes, so text in UTF-16 has patterns of its own: every letter followed by a
        NUL for little-endian, the way Windows PowerShell's > writes a file, or preceded by one for
        big-endian. They go through git grep's Perl regexes, which read \x00. A git built without
        them can't search, which throws like any search that doesn't run.

        With -Commit the files are read out of those commits, which is what a push publishes, and
        no worktree is needed: git grep reads them all in one pass and puts the commit in front of
        each hit, <commit>:<path>:<line>:<text>. Without it, the tracked files as they stand in the
        working tree. GIT_* variables are cleared for the search, so it reads the repository -Root
        names, and a search that doesn't run throws rather than reading as a clean tree. Every hit
        comes back as git grep prints it; none at all comes back as nothing, so callers wrap it in @().
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [string[]]$Commit,
        # A hundred commits to a git grep keeps its command line inside what Windows allows. The
        # contract tests pass a smaller one, to reach every batch with a handful of commits.
        [int]$BatchSize = 100
    )

    $commits = @($Commit | Where-Object { $_ })
    $batches = New-Object System.Collections.Generic.List[object]
    if ($commits.Count -eq 0) { $batches.Add(@()) }
    for ($i = 0; $i -lt $commits.Count; $i += $BatchSize) {
        $batches.Add(@($commits | Select-Object -Skip $i -First $BatchSize))
    }
    $hits = New-Object System.Collections.Generic.List[string]
    $saved = @{}
    foreach ($variable in @(Get-ChildItem Env: | Where-Object { $_.Name -like 'GIT_*' })) {
        $saved[$variable.Name] = $variable.Value
        Remove-Item -LiteralPath ('Env:\' + $variable.Name)
    }
    # Windows PowerShell 5.1 turns a native command's stderr into a terminating error under Stop,
    # even redirected, and git grep says why it could not search on stderr.
    $wideLe = { param([string[]]$Parts) ($Parts | ForEach-Object { "$_\x00" }) -join '' }
    $wideBe = { param([string[]]$Parts) ($Parts | ForEach-Object { "\x00$_" }) -join '' }
    $notes = @('c', 'l', 'a', 'u', 'd', 'e')
    $serial = @('R', '5', 'C')
    $scans = @(
        @{ Name = 'the notes folder'; Flags = @('-i', '-P')
            Pattern = @(($notes -join ''), (& $wideLe $notes), (& $wideBe $notes)) -join '|' }
        @{ Name = 'a phone serial'; Flags = @('-P')
            Pattern = @((($serial -join '') + '[A-Z0-9]{8}'), ((& $wideLe $serial) + '(?:[A-Z0-9]\x00){8}'),
                ((& $wideBe $serial) + '(?:\x00[A-Z0-9]){8}')) -join '|' }
    )
    $preference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        foreach ($scan in $scans) {
            foreach ($batch in $batches) {
                $arguments = @('-C', $Root, 'grep', '-n', '-a') + $scan.Flags + @('-e', $scan.Pattern) + @($batch) +
                    @('--', '.', ':!.gitignore')
                $found = @(& git @arguments 2>$null)
                # 1 is git grep's "no match". Anything above it means the search did not run.
                if ($LASTEXITCODE -gt 1) {
                    $what = if ($batch.Count -gt 0) { "commit $($batch -join ', ')" } else { 'the tracked files' }
                    throw "git grep could not search $what in $Root for $($scan.Name)."
                }
                foreach ($line in $found) { $hits.Add([string]$line) }
            }
        }
    } finally {
        $ErrorActionPreference = $preference
        foreach ($name in $saved.Keys) { Set-Item -LiteralPath ('Env:\' + $name) -Value $saved[$name] }
    }
    $global:LASTEXITCODE = 0
    return $hits.ToArray()
}

function Resolve-HostReferenceStubs {
    <#
    .SYNOPSIS
        The Android SDK public stubs and API history the host reference check reads, or a throw
        saying how to get them.
    .DESCRIPTION
        Taken from -AndroidJar and -ApiVersions when given. Otherwise platform 36 of the SDK aapt2
        sits in, then ANDROID_HOME, ANDROID_SDK_ROOT and the default SDK folder, with the API
        history beside it in data/. verify-injected-registers.ps1 runs the check with these, and
        an applied record's key hashes the same two files, so the record and the run can't name
        different stubs.
    #>
    param([string]$AndroidJar, [string]$ApiVersions, [string]$Aapt2)

    if (-not $AndroidJar) {
        $sdkRoots = @()
        if ($Aapt2) { $sdkRoots += Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $Aapt2)) }
        $sdkRoots += @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)
        if ($env:LOCALAPPDATA) { $sdkRoots += Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
        foreach ($sdk in $sdkRoots | Where-Object { $_ }) {
            $candidate = Join-Path $sdk 'platforms/android-36/android.jar'
            if (Test-Path -LiteralPath $candidate -PathType Leaf) { $AndroidJar = $candidate; break }
        }
    }
    if (-not $AndroidJar -or -not (Test-Path -LiteralPath $AndroidJar -PathType Leaf)) {
        throw 'Android SDK public stubs are required for host reference verification. Pass -AndroidJar and -ApiVersions, or install Android SDK Platform 36.'
    }
    if (-not $ApiVersions) { $ApiVersions = Join-Path (Split-Path -Parent $AndroidJar) 'data/api-versions.xml' }
    if (-not (Test-Path -LiteralPath $ApiVersions -PathType Leaf)) { throw "SDK API history is missing: $ApiVersions" }
    return [pscustomobject]@{ AndroidJar = $AndroidJar; ApiVersions = $ApiVersions }
}

function Get-AppliedRecordVerifierFiles {
    <#
    .SYNOPSIS
        The files under scripts/ whose content decides whether a fixture apply passes.
    .DESCRIPTION
        The scripts that patch and check (verify-all-patches.ps1 and everything it dot-sources or
        runs), the Java checks they start, DexDiff.java among them, and the contract and allowlist
        files those read. An applied record's key hashes every one, so an edit to any of them
        makes a kept result useless and the next run patches again. The contract tests hold this
        list to what verify-all-patches.ps1 and verify-injected-registers.ps1 actually load.
    #>
    return @(
        'verify-all-patches.ps1', 'verify-injected-registers.ps1', 'injected-register-contracts.ps1',
        'injected-register-device.ps1', 'device-lease.ps1', 'release-receipt.ps1', 'patch-report.ps1',
        'patch-target.ps1', 'common.ps1', 'Resolve-Java.ps1',
        'DexDiff.java', 'HostReferences.java', 'ResourceTableCheck.java', 'MergeSplits.java',
        'injected-mutation-contracts.txt', 'injected-register-removal-allowlist.txt',
        'host-reference-contracts.txt', 'manifest-delta-allowlist.txt'
    )
}

function Get-AppliedRecordStore {
    <#
    .SYNOPSIS
        The folder applied records are kept in: -Path, else HUSHTUMBLR_APPLY_RECORDS, else
        hushtumblr-fixture-apply in the temp folder. Not created here.
    .DESCRIPTION
        One folder for every checkout and gate worktree. A record's name is its key, which hashes
        what it was made from, so two checkouts can't read each other's record unless they patched
        the same bytes with the same verifier.
    #>
    param([string]$Path)

    if (-not $Path) { $Path = $env:HUSHTUMBLR_APPLY_RECORDS }
    if (-not $Path) { $Path = Join-Path ([System.IO.Path]::GetTempPath()) 'hushtumblr-fixture-apply' }
    return [System.IO.Path]::GetFullPath($ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Path))
}

function Get-AppliedRecordKey {
    <#
    .SYNOPSIS
        The key a fixture apply is kept under, and the inputs it was made from.
    .DESCRIPTION
        SHA-256 over one line per input: the bundle, the fixture, the catalog, the selected patch
        names in ordinal order, whether the run was forced, the desktop CLI, the SDK stubs the host
        reference check reads, and every file Get-AppliedRecordVerifierFiles names. A change to
        any one of them gives another key, so nothing kept before it can be found.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Bundle,
        [Parameter(Mandatory = $true)][string]$Fixture,
        [Parameter(Mandatory = $true)][string]$PatchList,
        [Parameter(Mandatory = $true)][string[]]$PatchNames,
        [Parameter(Mandatory = $true)][bool]$Forced,
        [Parameter(Mandatory = $true)][string]$DesktopJar,
        [Parameter(Mandatory = $true)][string]$AndroidJar,
        [Parameter(Mandatory = $true)][string]$ApiVersions,
        # The folder the verifier files are read from: the scripts folder this file is in.
        [string]$ScriptRoot = $PSScriptRoot
    )

    $hash = {
        param([string]$Path, [string]$What)
        if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Cannot key an applied record without the ${What}: $Path" }
        (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    $names = [string[]]@($PatchNames | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    if ($names.Count -eq 0) { throw 'Cannot key an applied record without a patch selection.' }
    [System.Array]::Sort($names, [System.StringComparer]::Ordinal)
    $files = [ordered]@{}
    foreach ($name in Get-AppliedRecordVerifierFiles) {
        $files[$name] = & $hash (Join-Path $ScriptRoot $name) "verifier file $name"
    }
    $inputs = [ordered]@{
        bundle      = & $hash $Bundle 'bundle'
        fixture     = & $hash $Fixture 'fixture'
        patchList   = & $hash $PatchList 'patch list'
        patches     = $names
        forced      = [bool]$Forced
        desktopCli  = & $hash $DesktopJar 'desktop CLI'
        androidJar  = & $hash $AndroidJar 'SDK public stubs'
        apiVersions = & $hash $ApiVersions 'SDK API history'
        files       = $files
    }
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add('hushtumblr applied record 1')
    foreach ($field in @('bundle', 'fixture', 'patchList')) { $lines.Add("$field $($inputs[$field])") }
    foreach ($name in $names) { $lines.Add("patch $name") }
    $lines.Add("forced $($inputs.forced.ToString().ToLowerInvariant())")
    foreach ($field in @('desktopCli', 'androidJar', 'apiVersions')) { $lines.Add("$field $($inputs[$field])") }
    foreach ($name in $files.Keys) { $lines.Add("file $name $($files[$name])") }
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $digest = $sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes(($lines -join "`n") + "`n"))
    } finally { $sha.Dispose() }
    $key = -join @($digest | ForEach-Object { $_.ToString('x2') })
    return [pscustomobject]@{ Key = $key; Inputs = $inputs }
}

function Get-AppliedRecords {
    <#
    .SYNOPSIS
        The record folders a store holds, newest first. Staging folders (named from a dot) aren't
        records and never come back from here.
    #>
    param([Parameter(Mandatory = $true)][string]$Store)

    if (-not (Test-Path -LiteralPath $Store -PathType Container)) { return @() }
    return @(Get-ChildItem -LiteralPath $Store -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^[0-9a-f]{64}$' } |
        Sort-Object @{ Expression = {
                $recordFile = Join-Path $_.FullName 'record.json'
                if (Test-Path -LiteralPath $recordFile -PathType Leaf) { (Get-Item -LiteralPath $recordFile).LastWriteTimeUtc } else { [datetime]::MinValue }
            } } -Descending)
}

function Find-AppliedRecord {
    <#
    .SYNOPSIS
        The record kept under -Key, or $null when there is none or it doesn't hold together.
    .DESCRIPTION
        A record counts only when its record.json names this key, and its patched APK and result
        report are there with the APK hashing to what was recorded. Anything less is said and
        passed over, so the caller patches in full.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Store,
        [Parameter(Mandatory = $true)][string]$Key
    )

    if ($Key -notmatch '^[0-9a-f]{64}$') { throw "Not an applied record key: $Key" }
    $directory = Join-Path $Store $Key
    $recordFile = Join-Path $directory 'record.json'
    if (-not (Test-Path -LiteralPath $recordFile -PathType Leaf)) { return $null }
    $problem = $null
    try {
        $record = Get-Content -LiteralPath $recordFile -Raw | ConvertFrom-Json
    } catch {
        $record = $null
        $problem = "record.json can't be read: $($_.Exception.Message)"
    }
    $patched = Join-Path $directory 'patched.apk'
    $result = Join-Path $directory 'result.json'
    if (-not $problem) {
        if ([int]$record.schema -ne 1 -or [string]$record.key -cne $Key) {
            $problem = 'record.json names another key'
        } elseif (-not (Test-Path -LiteralPath $patched -PathType Leaf) -or -not (Test-Path -LiteralPath $result -PathType Leaf)) {
            $problem = 'its patched APK or result report is missing'
        } elseif ((Get-FileHash -LiteralPath $patched -Algorithm SHA256).Hash.ToLowerInvariant() -cne [string]$record.patchedSha256) {
            $problem = 'its patched APK changed after it was kept'
        }
    }
    if ($problem) {
        Write-Warning "The applied record $Key in $Store doesn't hold together ($problem), so it isn't used."
        return $null
    }
    # PowerShell 7 reads an ISO time in JSON as a date, Windows PowerShell as the text.
    $recordedAt = if ($record.recordedAt -is [datetime]) {
        $record.recordedAt.ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    } else { [string]$record.recordedAt }
    return [pscustomobject]@{
        Directory  = $directory
        Key        = $Key
        PatchedApk = $patched
        Result     = $result
        RecordedAt = $recordedAt
        Record     = $record
    }
}

function Save-AppliedRecord {
    <#
    .SYNOPSIS
        Keeps a fixture apply that passed every check, under its key, and returns its folder.
    .DESCRIPTION
        Call it only once the run has passed: the report, the manifest delta, the resource table,
        DexDiff and the host references. Everything is copied into a staging folder named from a
        dot, record.json last, and the folder is renamed to the key in one step, so a run that
        stops halfway leaves nothing under a key (Find-AppliedRecord and Get-AppliedRecords never
        read a staging folder). The newest -Keep records stay and older ones go, as do staging
        folders a killed run left more than a day ago.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Store,
        [Parameter(Mandatory = $true)]$Identity,
        [Parameter(Mandatory = $true)][string]$PatchedApk,
        [Parameter(Mandatory = $true)][string]$Result,
        # Name in the record to the report it copies. A report the run didn't write is left out.
        [System.Collections.IDictionary]$Reports = @{},
        $Target,
        # Each record holds a patched Tumblr, about 85 MB, and one release needs one per
        # declared fixture.
        [int]$Keep = 3
    )

    $key = [string]$Identity.Key
    if ($key -notmatch '^[0-9a-f]{64}$') { throw "Not an applied record key: $key" }
    foreach ($required in @($PatchedApk, $Result)) {
        if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Nothing to keep: $required is missing." }
    }
    New-Item -ItemType Directory -Force -Path $Store | Out-Null
    $storeRoot = (Resolve-Path -LiteralPath $Store).Path
    $final = Resolve-WithinRoot -Path (Join-Path $storeRoot $key) -Root $storeRoot
    $staging = Resolve-WithinRoot -Path (Join-Path $storeRoot ('.partial-' + [guid]::NewGuid().ToString('N'))) -Root $storeRoot
    New-Item -ItemType Directory -Path $staging | Out-Null
    try {
        Copy-Item -LiteralPath $PatchedApk -Destination (Join-Path $staging 'patched.apk')
        Copy-Item -LiteralPath $Result -Destination (Join-Path $staging 'result.json')
        $kept = @()
        foreach ($name in @($Reports.Keys)) {
            if ([string]$name -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]*$' -or $name -in @('patched.apk', 'result.json', 'record.json')) {
                throw "Not a report name a record can hold: $name"
            }
            $source = [string]$Reports[$name]
            if ($source -and (Test-Path -LiteralPath $source -PathType Leaf)) {
                Copy-Item -LiteralPath $source -Destination (Join-Path $staging $name)
                $kept += [string]$name
            }
        }
        $targetFacts = $null
        if ($null -ne $Target) {
            $targetFacts = [ordered]@{ package = [string]$Target.package; versionName = [string]$Target.versionName
                versionCode = [string]$Target.versionCode }
        }
        $record = [ordered]@{
            schema        = 1
            key           = $key
            recordedAt    = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
            inputs        = $Identity.Inputs
            target        = $targetFacts
            patchedSha256 = (Get-FileHash -LiteralPath (Join-Path $staging 'patched.apk') -Algorithm SHA256).Hash.ToLowerInvariant()
            reports       = $kept
        }
        [System.IO.File]::WriteAllText((Join-Path $staging 'record.json'), ($record | ConvertTo-Json -Depth 8),
            (New-Object System.Text.UTF8Encoding($false)))

        if (Test-Path -LiteralPath $final) {
            # A record under this key that Find-AppliedRecord passed over. Moved aside first, so the
            # key never names a folder that's half deleted.
            $stale = Resolve-WithinRoot -Path (Join-Path $storeRoot ('.stale-' + [guid]::NewGuid().ToString('N'))) -Root $storeRoot
            [System.IO.Directory]::Move($final, $stale)
            Remove-Item -LiteralPath $stale -Recurse -Force -ErrorAction SilentlyContinue
        }
        try {
            [System.IO.Directory]::Move($staging, $final)
        } catch [System.IO.IOException] {
            # Another run kept the same key first. Its record is the same result.
            if (-not (Find-AppliedRecord -Store $storeRoot -Key $key)) { throw }
        }
    } finally {
        if (Test-Path -LiteralPath $staging) { Remove-Item -LiteralPath $staging -Recurse -Force -ErrorAction SilentlyContinue }
    }

    foreach ($old in @(Get-AppliedRecords -Store $storeRoot | Select-Object -Skip $Keep)) {
        if ($old.Name -ne $key) { Remove-GeneratedPath -Path $old.FullName -Root $storeRoot }
    }
    $abandoned = (Get-Date).ToUniversalTime().AddDays(-1)
    foreach ($left in @(Get-ChildItem -LiteralPath $storeRoot -Directory -Force -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match '^\.(partial|stale)-[0-9a-f]{32}$' -and $_.LastWriteTimeUtc -lt $abandoned })) {
        Remove-GeneratedPath -Path $left.FullName -Root $storeRoot
    }
    return $final
}
