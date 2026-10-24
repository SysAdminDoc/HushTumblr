<# Synthetic evidence for offline walk contracts. Never used by a release command. #>
function New-WalkTestData {
    param([string]$Directory, [string]$BundlePath)
    $catalogPath = Join-Path $Directory 'patches-list.json'
    Copy-Item -LiteralPath (Join-Path (Split-Path -Parent $PSScriptRoot) 'patches-list.json') -Destination $catalogPath
    $catalog = Get-Content -LiteralPath $catalogPath -Raw | ConvertFrom-Json
    $target = Get-PatchTarget $catalog
    $bundleHash = (Get-FileHash -LiteralPath $BundlePath -Algorithm SHA256).Hash.ToLowerInvariant()
    $output = [ordered]@{ sha256 = '4' * 64; package = $target.PackageName; versionName = $target.PackageVersion
        versionCode = [string]$target.PackageVersionCodes[$target.PackageVersion][0]; signers = @('a' * 64) }
    $provenance = [ordered]@{ schema = 1; bundleSha256 = $bundleHash; sourceSha256 = '1' * 64
        inputIdentity = '2' * 64; mergedApkSha256 = '3' * 64
        catalogSha256 = (Get-FileHash -LiteralPath $catalogPath -Algorithm SHA256).Hash.ToLowerInvariant()
        patches = @($catalog.patches | ForEach-Object { $_.name }); options = @(); output = $output }
    $releasePath = Join-Path $Directory 'release-receipt.json'
    $release = @{ bundle = @{ sha256 = $bundleHash }; targets = @(@{ source = @{
        sha256 = $provenance.sourceSha256; package = $output.package; versionName = $output.versionName
        versionCode = $output.versionCode; forced = $false } }) }
    [IO.File]::WriteAllText($releasePath, ($release | ConvertTo-Json -Depth 8))
    $routes = @(foreach ($route in Get-WalkPlan -PatchNames @($provenance.patches)) {
        [ordered]@{ id = $route.id; status = 'PASS'; observation = [ordered]@{
            activity = $route.activity; resourceIds = @($route.ids | ForEach-Object { "com.tumblr:id/$_" })
            markerMatches = $true; markerValue = $route.value; blocked = $false; settingsHome = $route.id -ceq 'settings.home'
            pageTitleMatches = [bool]$route.page
        } }
    })
    return @{ Provenance = $provenance; Output = $output; BundleHash = $bundleHash
        Routes = $routes; Arguments = @{ CatalogPath = $catalogPath; ReleaseReceiptPath = $releasePath } }
}
