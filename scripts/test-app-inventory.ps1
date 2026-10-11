<# Contracts for app-inventory.ps1 and AppInventory.java, on the two tiny real-dex APKs in scripts/checks/inventory-fixture. #>
[CmdletBinding()]
param([string]$DesktopJar)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')

function Assert-InventoryContract([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

# The lines of one section, without the header or the blank line before the next one.
function Get-InventorySection([string[]]$Lines, [string]$Heading) {
    $start = [Array]::IndexOf($Lines, $Heading)
    if ($start -lt 0) { return $null }
    $body = [System.Collections.Generic.List[string]]::new()
    for ($i = $start + 1; $i -lt $Lines.Count -and $Lines[$i] -ne ''; $i++) { $body.Add($Lines[$i]) }
    return , $body.ToArray()
}

$root = Split-Path -Parent $PSScriptRoot
$DesktopJar = Resolve-DesktopCli -Explicit $DesktopJar -Root $root -Required
$fixture = Join-Path $PSScriptRoot 'checks/inventory-fixture'
$before = Join-Path $fixture 'before.apk'
$after = Join-Path $fixture 'after.apk'
foreach ($apk in @($before, $after)) {
    Assert-InventoryContract (Test-Path -LiteralPath $apk) "$apk is missing. Run scripts/checks/build-inventory-fixtures.ps1."
}

$out = Join-Path ([IO.Path]::GetTempPath()) ('hushtumblr-inventory-contract-' + [guid]::NewGuid().ToString('N'))
try {
    & (Join-Path $PSScriptRoot 'app-inventory.ps1') -Apk $after -BaseApk $before -OutDir $out -DesktopJar $DesktopJar | Out-Null

    $inventory = @(Get-Content -LiteralPath (Join-Path $out 'after.inventory.txt'))
    Assert-InventoryContract ($inventory[0] -eq '# AppInventory 1') "The inventory's format line changed: $($inventory[0])"
    Assert-InventoryContract ($inventory -contains 'apk = after.apk') 'The inventory doesn''t name its APK.'
    $hash = (Get-FileHash -LiteralPath $after -Algorithm SHA256).Hash.ToLowerInvariant()
    Assert-InventoryContract ($inventory -contains "sha256 = $hash") 'The inventory''s sha256 isn''t the APK''s.'
    Assert-InventoryContract ($inventory -contains 'dex = 1 files, 12 classes') 'The inventory miscounted the fixture''s dex or classes.'

    $expected = [ordered]@{
        '[endpoints] 3'     = @('DELETE v3/fixture/old/ = 1 method', 'GET v3/fixture/feed/ = 1 method', 'POST v3/fixture/event/ = 1 method')
        '[startup-tasks] 2' = @('TAG_FIXTURE_ONE = read in 1 place', 'TAG_FIXTURE_TWO = read in 0 places')
        '[hosts] 1'         = @('api.example.com = com/example/fixture')
        '[transport] 1'     = @('URL.openConnection = com/example/fixture')
        '[sdk] 1'           = @('com/example = 12 classes')
        '[identifiers] 1'   = @('android-id Settings.Secure.getString("android_id") = com/example/fixture')
    }
    foreach ($heading in $expected.Keys) {
        $body = Get-InventorySection -Lines $inventory -Heading $heading
        Assert-InventoryContract ($null -ne $body) "The inventory has no ""$heading"" section."
        Assert-InventoryContract (($body -join "`n") -ceq ($expected[$heading] -join "`n")) `
            "The inventory's $heading section changed:`n$($body -join "`n")"
    }

    $diff = @(Get-Content -LiteralPath (Join-Path $out 'before--after.diff.txt'))
    Assert-InventoryContract ($diff[0] -eq '# AppInventory diff 1') "The diff's format line changed: $($diff[0])"
    $expectedDiff = [ordered]@{
        '[endpoints] 2 -> 3: 1 added, 0 removed, 0 changed'   = @('+ DELETE v3/fixture/old/ = 1 method')
        '[startup-tasks] 2 -> 2: 0 added, 0 removed, 0 changed' = @()
        '[hosts] 1 -> 1: 0 added, 0 removed, 0 changed'         = @()
        '[transport] 1 -> 1: 0 added, 0 removed, 0 changed'     = @()
        '[sdk] 1 -> 1: 0 added, 0 removed, 1 changed'           = @('~ com/example = 11 classes -> 12 classes')
        '[identifiers] 1 -> 1: 0 added, 0 removed, 0 changed'   = @()
    }
    foreach ($heading in $expectedDiff.Keys) {
        $body = Get-InventorySection -Lines $diff -Heading $heading
        Assert-InventoryContract ($null -ne $body) "The diff has no ""$heading"" section:`n$($diff -join "`n")"
        Assert-InventoryContract (($body -join "`n") -ceq ($expectedDiff[$heading] -join "`n")) `
            "The diff's $heading section changed:`n$($body -join "`n")"
    }
    Assert-InventoryContract ($diff -contains '- dex = 1 files, 11 classes' -and $diff -contains '+ dex = 1 files, 12 classes') `
        'The diff doesn''t carry both headers.'

    $refused = $false
    try { & (Join-Path $PSScriptRoot 'app-inventory.ps1') -Apk (Join-Path $fixture 'base/com/example/fixture/Fixture.java') -OutDir $out -DesktopJar $DesktopJar | Out-Null }
    catch { $refused = "$_" -like '*isn''t an .apk*' }
    Assert-InventoryContract $refused 'app-inventory.ps1 took a file that isn''t an APK.'
} finally {
    Remove-Item -LiteralPath $out -Recurse -Force -ErrorAction SilentlyContinue
}
Write-Host '[scripts] app inventory contracts passed'
