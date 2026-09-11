param(
    [string]$BeaconJar = "$env:APPDATA/Hytale/UserData/mods/Beacon v2.0.1.jar"
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$sourceJar = (Resolve-Path -LiteralPath $BeaconJar).Path
$sdkJar = (Resolve-Path -LiteralPath (Join-Path $projectRoot 'build/deps/HytaleServer.jar')).Path
$stamp = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff')
$run = Join-Path $projectRoot "build/beacon-verification/$stamp"
$classes = Join-Path $run 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$localJar = Join-Path $run 'Beacon.jar'
Copy-Item -LiteralPath $sourceJar -Destination $localJar
$sources = @(
    (Join-Path $projectRoot 'src/main/java/com/hexvane/strangematter/telemetry/BeaconTelemetry.java'),
    (Join-Path $projectRoot 'src/test/java/com/hexvane/strangematter/telemetry/BeaconVerification.java'),
    (Join-Path $projectRoot 'tools/beacon/BeaconInstalledVerification.java')
)
& javac -encoding UTF-8 -Xlint:deprecation -Xlint:removal -cp "$localJar;$sdkJar" -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw 'Beacon verification compilation failed' }
& java -cp $classes com.hexvane.strangematter.telemetry.BeaconVerification
if ($LASTEXITCODE -ne 0) { throw 'Beacon absent verification failed' }
& java '-Djava.util.logging.manager=com.hypixel.hytale.logger.backend.HytaleLogManager' -cp "$classes;$localJar;$sdkJar" com.hexvane.strangematter.telemetry.BeaconInstalledVerification (Join-Path $run 'isolated-data') (Join-Path $projectRoot 'src/main/resources/Server/Beacon/project.json')
if ($LASTEXITCODE -ne 0) { throw 'Beacon installed verification failed' }
$proof = [ordered]@{ beaconSha256 = (Get-FileHash -LiteralPath $localJar -Algorithm SHA256).Hash; sdkSha256 = (Get-FileHash -LiteralPath $sdkJar -Algorithm SHA256).Hash; bridgeSha256 = (Get-FileHash -LiteralPath $sources[0] -Algorithm SHA256).Hash; descriptorSha256 = (Get-FileHash -LiteralPath (Join-Path $projectRoot 'src/main/resources/Server/Beacon/project.json') -Algorithm SHA256).Hash; uploadCalls = 0; run = $run }
$proof | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $run 'verification.json') -Encoding UTF8
Write-Output "BEACON_VERIFICATION_REPORT: $run"
