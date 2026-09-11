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
    # Select exactly the same fixtures before updating the archive once. Keep the
    # wildcard scope local to each package, including matching inner classes.
    $fixtureClasses = [System.Collections.Generic.List[string]]::new()
    $fixtureClasses.Add($testClass)
    $fixtureClasses.Add('com/hexvane/strangematter/research/ResearchNoteVerification.class')
    $researchFixturePath = Join-Path $testClasses 'com/hexvane/strangematter/research'
    foreach ($fixture in Get-ChildItem -LiteralPath $researchFixturePath -Filter 'ResearchUnlockVerification*.class') {
        $fixtureClasses.Add("com/hexvane/strangematter/research/$($fixture.Name)")
    }
    $fixtureClasses.Add('com/hexvane/strangematter/machine/NativeMachineVerification.class')
    $fixtureClasses.Add('com/hexvane/strangematter/machine/NativeMachineWorkVerification.class')
    $machineFixturePath = Join-Path $testClasses 'com/hexvane/strangematter/machine'
    foreach ($fixture in Get-ChildItem -LiteralPath $machineFixturePath -Filter 'NativeNullifierContentVerification*.class') {
        $fixtureClasses.Add("com/hexvane/strangematter/machine/$($fixture.Name)")
    }
    $fixtureClasses.Add('com/hexvane/strangematter/machine/MachineControlsVerification.class')
    $anomalyFixturePath = Join-Path $testClasses 'com/hexvane/strangematter/anomaly'
    foreach ($fixture in Get-ChildItem -LiteralPath $anomalyFixturePath -Filter 'Native*.class') {
        $fixtureClasses.Add("com/hexvane/strangematter/anomaly/$($fixture.Name)")
    }
    $equipmentFixturePath = Join-Path $testClasses 'com/hexvane/strangematter/equipment'
    $uiFixturePath = Join-Path $testClasses 'com/hexvane/strangematter/ui'
    foreach ($fixture in Get-ChildItem -LiteralPath $uiFixturePath -Filter 'Native*.class') {
        $fixtureClasses.Add("com/hexvane/strangematter/ui/$($fixture.Name)")
    }
    $effectsFixturePath = Join-Path $testClasses 'com/hexvane/strangematter/effects'
    foreach ($fixture in Get-ChildItem -LiteralPath $effectsFixturePath -Filter 'Native*.class') {
        $fixtureClasses.Add("com/hexvane/strangematter/effects/$($fixture.Name)")
    }
    $blockFixturePath = Join-Path $testClasses 'com/hexvane/strangematter/block'
    foreach ($fixture in Get-ChildItem -LiteralPath $blockFixturePath -Filter 'Native*.class') {
        $fixtureClasses.Add("com/hexvane/strangematter/block/$($fixture.Name)")
    }
    foreach ($fixture in Get-ChildItem -LiteralPath $equipmentFixturePath -Filter 'Native*.class') {
        $fixtureClasses.Add("com/hexvane/strangematter/equipment/$($fixture.Name)")
    }
    $jarArguments = [System.Collections.Generic.List[string]]::new()
    foreach ($argument in @('--update', '--file', $testJar)) { $jarArguments.Add($argument) }
    $selectedFixtures = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    foreach ($relativeClass in $fixtureClasses) {
        # NativeWorldVerification is also matched by equipment/Native*.class.
        # Repeated old updates replaced the same entry; one update needs it once.
        if (-not $selectedFixtures.Add($relativeClass)) { continue }
        if (-not (Test-Path -LiteralPath (Join-Path $testClasses $relativeClass) -PathType Leaf)) {
            throw "Missing native world fixture: $relativeClass"
        }
        foreach ($argument in @('-C', $testClasses, $relativeClass)) { $jarArguments.Add($argument) }
    }
    foreach ($argument in @('-C', $manifestPath, 'manifest.json')) { $jarArguments.Add($argument) }
    # A UTF8 argument file avoids Windows command length limits. Quote every token
    # using jar's argument file grammar, not shell interpolation. Preserve spaces,
    # backslashes and the dollar signs in compiled inner class filenames.
    $jarArgsPath = Join-Path $runPath 'package-native-world.args'
    $argumentLines = [string[]]@($jarArguments | ForEach-Object { '"' + $_.Replace('\', '\\').Replace('"', '\"') + '"' })
    [System.IO.File]::WriteAllLines($jarArgsPath, $argumentLines, [System.Text.UTF8Encoding]::new($false))
    & $jarCommand ("@" + $jarArgsPath)
    if ($LASTEXITCODE -ne 0) { throw "Could not package the native world test jar: exit $LASTEXITCODE" }
    # This avoids Hytale 0.6.4's bare-mode first-run temporary-writer bug; this isolated file grants no user permissions.
    Set-Content -LiteralPath (Join-Path $runPath 'permissions.json') -Value '{"users":{},"groups":{}}' -Encoding utf8
    Push-Location $runPath
    try {
        # Bare mode skips network-port binding and normal worlds. The test creates one flat world and shuts down after assertions.
        & $javaCommand -Xmx3G -jar $serverJar --bare --assets $assetsZip --disable-sentry --disable-file-watcher --auth-mode offline --log 'HytaleServer:INFO,PluginManager:INFO,Strange Matter|P:INFO' *> native-world.log
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
