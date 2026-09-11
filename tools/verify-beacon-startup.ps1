param(
    [Parameter(Mandatory=$true)][string]$ModJar,
    [string]$BeaconJar = "$env:APPDATA/Hytale/UserData/mods/Beacon v2.0.1.jar"
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$production = (Resolve-Path -LiteralPath $ModJar).Path
$beacon = (Resolve-Path -LiteralPath $BeaconJar).Path
$sdk = (Resolve-Path -LiteralPath (Join-Path $projectRoot 'build/deps/HytaleServer.jar')).Path
$assets = Join-Path $env:APPDATA 'Hytale/install/release/package/game/latest/Assets.zip'
$run = Join-Path $projectRoot ('build/beacon-startup/' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff'))
$mods = Join-Path $run 'mods'
$classes = Join-Path $run 'probe-classes'
$settings = Join-Path $mods 'Alechilles_Beacon/Settings'
New-Item -ItemType Directory -Force -Path $mods, $classes, (Join-Path $settings 'projects') | Out-Null
# Never edit installed files. Disable all Beacon submissions before its plugin can initialize.
'{"enabled":false,"manualReports":{"enabled":false}}' | Set-Content -LiteralPath (Join-Path $settings 'runtime.json') -Encoding utf8
'{"enabled":false,"usage":{"enabled":false},"performance":{"enabled":false},"stats":{"enabled":false}}' | Set-Content -LiteralPath (Join-Path $settings 'projects/strange-matter.json') -Encoding utf8
'{"users":{},"groups":{}}' | Set-Content -LiteralPath (Join-Path $run 'permissions.json') -Encoding utf8
$copiedMod = Join-Path $mods 'StrangeMatter.jar'
Copy-Item -LiteralPath $production -Destination $copiedMod
Copy-Item -LiteralPath $beacon -Destination (Join-Path $mods 'Beacon.jar')
& javac -encoding UTF-8 -Xlint:deprecation -Xlint:removal -cp "$copiedMod;$sdk" -d $classes (Join-Path $PSScriptRoot 'beacon/BeaconStartupVerification.java')
if ($LASTEXITCODE -ne 0) { throw 'Beacon startup probe compilation failed' }
$manifest = [ordered]@{ Group='HexvaneTests'; Name='Beacon Verification'; Version='1.0.0'; Main='com.hexvane.strangematter.telemetry.BeaconStartupVerification'; ServerVersion='*'; IncludesAssetPack=$false; Dependencies=@{'Hexvane:Strange Matter'='*';'Alechilles:Beacon'='*';'Hytale:Universe'='*'} }
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $classes 'manifest.json') -Encoding utf8
& jar --create --file (Join-Path $mods 'BeaconVerification.jar') -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'Could not package Beacon startup probe' }
Push-Location $run
try {
    & java -Xmx2G -jar $sdk --bind 127.0.0.1:0 --assets $assets --disable-sentry --disable-file-watcher --auth-mode offline *> beacon-startup.log
    $serverExit = $LASTEXITCODE
} finally { Pop-Location }
$log = Join-Path $run 'beacon-startup.log'
if ($serverExit -ne 0 -or -not (Select-String -LiteralPath $log -Pattern 'BEACON_STARTUP_VERIFICATION_PASSED:' -Quiet) -or (Select-String -LiteralPath $log -Pattern 'BEACON_STARTUP_VERIFICATION_FAILED:' -Quiet)) {
    throw "Beacon startup probe failed (exit $serverExit): $log"
}
$savedRuntime = Get-Content -LiteralPath (Join-Path $settings 'runtime.json') -Raw | ConvertFrom-Json
if ($savedRuntime.enabled -ne $false) { throw 'Beacon changed disabled test settings' }
$payloads = @(Get-ChildItem -LiteralPath (Join-Path $mods 'Alechilles_Beacon') -File -Recurse | Where-Object { $_.FullName -match '[\\/](events|crash-reports|reports)[\\/]' -and $_.Length -gt 0 })
if ($payloads.Count -gt 0) { throw 'Disabled startup unexpectedly produced a telemetry payload' }
$proof = [ordered]@{ productionSha256=(Get-FileHash -LiteralPath $copiedMod -Algorithm SHA256).Hash; beaconSha256=(Get-FileHash -LiteralPath (Join-Path $mods 'Beacon.jar') -Algorithm SHA256).Hash; sdkSha256=(Get-FileHash -LiteralPath $sdk -Algorithm SHA256).Hash; runtimeEnabled=$false; projectEnabled=$false; run=$run }
$proof | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $run 'verification.json') -Encoding utf8
Get-Content -LiteralPath (Join-Path $run 'beacon-startup-result.txt')
Write-Output "BEACON_STARTUP_REPORT: $run"
