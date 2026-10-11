<#
.SYNOPSIS
    Rebuilds the two tiny APKs the AppInventory contract reads.
.DESCRIPTION
    Compiles inventory-fixture/base into before.apk, and base plus inventory-fixture/added into
    after.apk, each a zip holding one classes.dex. Run it after changing the fixture sources, then
    commit both APKs. The contract reads the committed files, so a normal test run needs no SDK.
    Needs a JDK (JAVA_HOME) and the Android SDK's android.jar and d8 (ANDROID_HOME, or the default
    %LOCALAPPDATA%\Android\Sdk).
.EXAMPLE
    pwsh -NoProfile -File scripts/checks/build-inventory-fixtures.ps1
#>
[CmdletBinding()]
param(
    [string]$Sdk,
    [int]$Platform = 36
)

$ErrorActionPreference = 'Stop'
$here = Join-Path $PSScriptRoot 'inventory-fixture'
if (-not $Sdk) { $Sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' } }
$androidJar = Join-Path $Sdk "platforms\android-$Platform\android.jar"
if (-not (Test-Path -LiteralPath $androidJar)) { throw "No android.jar at $androidJar. Pass -Sdk or -Platform." }
$buildTools = Get-ChildItem -LiteralPath (Join-Path $Sdk 'build-tools') -Directory |
    Sort-Object { [version]($_.Name -replace '[^0-9.].*$', '') } -Descending | Select-Object -First 1
if (-not $buildTools) { throw "No build-tools under $Sdk." }
$d8 = Join-Path $buildTools.FullName 'd8.bat'
$javac = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\javac.exe' } else { 'javac' }

$work = Join-Path ([IO.Path]::GetTempPath()) ('hushtumblr-inventory-fixture-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work | Out-Null
try {
    foreach ($build in @(@{ Name = 'before'; Sources = @('base') }, @{ Name = 'after'; Sources = @('base', 'added') })) {
        $classes = Join-Path $work "$($build.Name)-classes"
        New-Item -ItemType Directory -Path $classes | Out-Null
        $files = @(foreach ($folder in $build.Sources) { Get-ChildItem -LiteralPath (Join-Path $here $folder) -Recurse -Filter '*.java' | ForEach-Object FullName })
        & $javac --release 11 -nowarn -classpath $androidJar -d $classes @files
        if ($LASTEXITCODE -ne 0) { throw "javac failed for $($build.Name)." }
        $zip = Join-Path $work "$($build.Name).zip"
        $classFiles = @(Get-ChildItem -LiteralPath $classes -Recurse -Filter '*.class' | ForEach-Object FullName)
        & $d8 --release --min-api 29 --lib $androidJar --output $zip @classFiles
        if ($LASTEXITCODE -ne 0) { throw "d8 failed for $($build.Name)." }
        Copy-Item -LiteralPath $zip -Destination (Join-Path $here "$($build.Name).apk") -Force
        Write-Host "[fixture] wrote $(Join-Path $here "$($build.Name).apk")"
    }
} finally {
    Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
}
