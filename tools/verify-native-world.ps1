param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    if (-not $SkipBuild) { & "$PSScriptRoot/build.ps1" -Tasks @('jar', 'testClasses') }
    $serverJar = Join-Path $projectRoot 'build/deps/HytaleServer.jar'
    $gamePath = Join-Path $env:APPDATA 'Hytale/install/release/package/game/latest'
    $assetsZip = Join-Path $gamePath 'Assets.zip'
    $modJar = Get-ChildItem -LiteralPath (Join-Path $projectRoot 'build/libs') -Filter 'StrangeMatter-*.jar' |
        Where-Object Name -NotMatch '-(sources|javadoc)\.jar$' | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    $testClasses = Join-Path $projectRoot 'build/classes/java/test'
    $testClass = 'com/hexvane/strangematter/equipment/NativeWorldVerification.class'
    foreach ($required in @($serverJar, $assetsZip, (Join-Path $testClasses $testClass))) {
        if (-not (Test-Path -LiteralPath $required)) { throw "Missing test input: $required. Build the project and install Hytale first." }
    }
    if (-not $modJar) { throw 'The production StrangeMatter jar has not been built.' }
    $javaCommand = (Get-Command java -ErrorAction Stop).Source
    $jarCommand = (Get-Command jar -ErrorAction Stop).Source
    $runRoot = Join-Path $projectRoot 'build/native-world-run'
    $runName = (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '-' + ([guid]::NewGuid().ToString('N').Substring(0, 8))
    $runPath = Join-Path $runRoot $runName
    $modsPath = Join-Path $runPath 'mods'
    $manifestPath = Join-Path $runPath 'test-manifest'
    New-Item -ItemType Directory -Force $modsPath, $manifestPath | Out-Null
    $testJar = Join-Path $modsPath 'StrangeMatter-Smoke.jar'
    Copy-Item -LiteralPath $modJar.FullName -Destination $testJar
    $manifest = Get-Content -LiteralPath 'src/main/resources/manifest.json' -Raw | ConvertFrom-Json
    $manifest.Main = 'com.hexvane.strangematter.equipment.NativeWorldVerification'
    $manifest.Dependencies = @{ 'Hytale:Universe' = '*' }
    $manifest | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $manifestPath 'manifest.json') -Encoding utf8
    & $jarCommand --update --file $testJar -C $testClasses $testClass -C $manifestPath manifest.json
    if ($LASTEXITCODE -ne 0) { throw "Could not package the native world test jar: exit $LASTEXITCODE" }
    & $jarCommand --update --file $testJar -C $testClasses 'com/hexvane/strangematter/research/ResearchNoteVerification.class'
    if ($LASTEXITCODE -ne 0) { throw 'Could not package the native research note fixture' }
    $researchFixturePath = Join-Path $testClasses 'com/hexvane/strangematter/research'
    foreach ($fixture in Get-ChildItem -LiteralPath $researchFixturePath -Filter 'ResearchUnlockVerification*.class') {
        & $jarCommand --update --file $testJar -C $testClasses "com/hexvane/strangematter/research/$($fixture.Name)"
        if ($LASTEXITCODE -ne 0) { throw "Could not package native research fixture $($fixture.Name)" }
    }
    & $jarCommand --update --file $testJar -C $testClasses 'com/hexvane/strangematter/machine/NativeMachineVerification.class'
    if ($LASTEXITCODE -ne 0) { throw 'Could not package the native machine fixture' }
    & $jarCommand --update --file $testJar -C $testClasses 'com/hexvane/strangematter/machine/MachineControlsVerification.class'
    if ($LASTEXITCODE -ne 0) { throw 'Could not package the machine controls fixture' }
    $anomalyFixturePath = Join-Path $testClasses 'com/hexvane/strangematter/anomaly'
    foreach ($fixture in Get-ChildItem -LiteralPath $anomalyFixturePath -Filter 'Native*.class') {
        & $jarCommand --update --file $testJar -C $testClasses "com/hexvane/strangematter/anomaly/$($fixture.Name)"
        if ($LASTEXITCODE -ne 0) { throw "Could not package native anomaly fixture $($fixture.Name)" }
    }
    $equipmentFixturePath = Join-Path $testClasses 'com/hexvane/strangematter/equipment'
    foreach ($fixture in Get-ChildItem -LiteralPath $equipmentFixturePath -Filter 'Native*.class') {
        & $jarCommand --update --file $testJar -C $testClasses "com/hexvane/strangematter/equipment/$($fixture.Name)"
        if ($LASTEXITCODE -ne 0) { throw "Could not package native equipment fixture $($fixture.Name)" }
    }
    # This avoids Hytale 0.6.4's bare-mode first-run temporary-writer bug; this isolated file grants no user permissions.
    Set-Content -LiteralPath (Join-Path $runPath 'permissions.json') -Value '{"users":{},"groups":{}}' -Encoding utf8
    Push-Location $runPath
    try {
        # Bare mode skips network-port binding and normal worlds. The test creates one flat world and shuts down after assertions.
        & $javaCommand -Xmx3G -jar $serverJar --bare --assets $assetsZip --disable-sentry --disable-file-watcher --auth-mode offline --log 'HytaleServer:INFO,PluginManager:INFO,StrangeMatter|P:INFO' *> native-world.log
        $serverExit = $LASTEXITCODE
        $logPath = Join-Path $runPath 'native-world.log'
        Copy-Item -LiteralPath $logPath -Destination (Join-Path $runRoot 'native-world.log')
        $resultPath = Join-Path $runPath 'native-world-result.txt'
        if (Test-Path -LiteralPath $resultPath) { Copy-Item -LiteralPath $resultPath -Destination (Join-Path $runRoot 'native-world-result.txt') }
        $passed = Select-String -LiteralPath $logPath -Pattern 'NATIVE_WORLD_VERIFICATION_PASSED:' -Quiet
        $failed = Select-String -LiteralPath $logPath -Pattern 'NATIVE_WORLD_VERIFICATION_FAILED:' -Quiet
        if ($serverExit -ne 0 -or -not $passed -or $failed) { throw "Native world verification did not pass (server exit $serverExit). See $logPath" }
        Get-Content -LiteralPath $resultPath
        Write-Output "Isolated world and log: $runPath"
    } finally { Pop-Location }
} finally { Pop-Location }
