<# Offline contracts for walk-receipt.ps1: publish takes only a full, passing walk of the bundle it ships. #>
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'walk-receipt.ps1')

function Assert-WalkContract([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

$work = Join-Path ([IO.Path]::GetTempPath()) ('hushtumblr-walk-contract-' + [guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($work)
try {
    $bundle = Join-Path $work 'patches-0.0.9.mpp'
    [IO.File]::WriteAllText($bundle, 'the signed bundle')
    $walkPath = Join-Path $work 'walk-receipt.json'
    # The receipt a walk of that bundle writes. -Change bends one field.
    function Write-Walk([hashtable]$Change = @{}) {
        $walk = New-WalkReceipt -Result PASS -Full $true -Serial 'TESTPHONE01' -Device 'SM-S908U1' -AppVersion '46.5.0.109' `
            -BundleSha256 (Get-FileHash -LiteralPath $bundle -Algorithm SHA256).Hash -InstalledApkSha256 ('4' * 64) `
            -Started '2026-10-21T16:00:00Z' -Finished '2026-10-21T16:30:00Z' -Screens 40 -Crashes 0
        foreach ($key in $Change.Keys) { $walk[$key] = $Change[$key] }
        [IO.File]::WriteAllText($walkPath, ($walk | ConvertTo-Json -Depth 4))
    }

    # Either release phone or an emulator walks for a release.
    foreach ($walker in @(@{}, @{ device = 'SM-S938B' }, @{ serial = 'emulator-5554'; device = 'sdk_gphone64_x86_64'; emulator = $true })) {
        Write-Walk $walker
        $good = Test-WalkReceipt -Path $walkPath -BundlePath $bundle
        Assert-WalkContract ($good.Valid -and $good.Receipt.screens -eq 40) "A full PASS walk of the bundle was refused: $($good.Reason)"
    }

    foreach ($badWalk in @(
            @(@{ result = 'FAIL' }, '*the walk ended FAIL, not PASS*'),
            @(@{ result = 'PARTIAL'; full = $false }, '*the walk ended PARTIAL, not PASS*'),
            @(@{ result = $null }, '*ended with no result*'),
            @(@{ full = $false }, '*was not the whole app*'),
            @(@{ screens = 0 }, "*doesn't say it opened any screen*"),
            @(@{ crashes = 1 }, '*recorded a Tumblr crash*'),
            @(@{ device = $null }, "*doesn't say which device walked it*"),
            @(@{ device = 'Pixel 9' }, "*Pixel 9 isn't one of the release walk phones*"),
            @(@{ device = 'Pixel 9'; emulator = $true }, "*Pixel 9 isn't one of the release walk phones*"),
            @(@{ bundleSha256 = ('5' * 64) }, '*patched with bundle*and this one is*'),
            @(@{ schema = 2 }, "*isn't schema 1*"))) {
        Write-Walk $badWalk[0]
        $bad = Test-WalkReceipt -Path $walkPath -BundlePath $bundle
        Assert-WalkContract (-not $bad.Valid -and $bad.Reason -like $badWalk[1]) "Expected the walk refusal [$($badWalk[1])], got [$($bad.Reason)]."
    }
    [IO.File]::WriteAllText($walkPath, 'not json')
    $bad = Test-WalkReceipt -Path $walkPath -BundlePath $bundle
    Assert-WalkContract (-not $bad.Valid -and $bad.Reason -like "*doesn't read as JSON*") "A receipt that isn't JSON read as: $($bad.Reason)"
    $bad = Test-WalkReceipt -Path (Join-Path $work 'none.json') -BundlePath $bundle
    Assert-WalkContract (-not $bad.Valid -and $bad.Reason -like "*there's no walk receipt at*") "A missing receipt read as: $($bad.Reason)"

    # A walk of one bundle never backs another, even a rebuild of the same commit.
    Write-Walk
    [IO.File]::WriteAllText($bundle, 'the signed bundle, rebuilt')
    $bad = Test-WalkReceipt -Path $walkPath -BundlePath $bundle
    Assert-WalkContract (-not $bad.Valid -and $bad.Reason -like '*patched with bundle*and this one is*') "A walk backed another bundle: $($bad.Reason)"
    $bad = Test-WalkReceipt -Path $walkPath -BundlePath (Join-Path $work 'gone.mpp')
    Assert-WalkContract (-not $bad.Valid -and $bad.Reason -like "*there's no bundle at*") "A walk backed a bundle that isn't there: $($bad.Reason)"
} finally {
    Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
}

# Publish asks for the walk once the signed assets check out, before GitHub or the index.
$publishText = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'release/publish-release.ps1'))
$assetsAt = $publishText.IndexOf('$built = Test-ReleaseAssets')
$walkAt = $publishText.IndexOf('$walked = Test-WalkReceipt -Path $Walk')
$refuseAt = $publishText.IndexOf('if (-not $walked.Valid) {')
foreach ($later in @('Get-PublishedRelease', 'gh release create', 'gh repo edit', 'Invoke-ReleasePush')) {
    $laterAt = $publishText.IndexOf($later, $publishText.IndexOf('Assert-ReleaseClean $Root'))
    Assert-WalkContract ($assetsAt -gt 0 -and $walkAt -gt $assetsAt -and $refuseAt -gt $walkAt -and $laterAt -gt $refuseAt) `
        "publish-release.ps1 reaches $later before it holds the bundle to its walk."
}
$releaseText = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'release/release.ps1'))
Assert-WalkContract ($releaseText -match '(?m)^\s*\[string\]\$Walk,' -and $releaseText -match 'Walk = \$Walk') 'release.ps1 -Stage publish drops -Walk.'
Write-Host '[scripts] walk receipt contracts passed'
