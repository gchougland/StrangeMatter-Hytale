param([switch]$SkipBuild, [switch]$Benchmarks, [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9-]{5,90}$')][string]$RunName, [string]$PowerSnapshotRegion)
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
    $runRoot = Join-Path $projectRoot $(if ($Benchmarks) { 'build/performance-runs' } else { 'build/native-world-run' })
    if (-not $RunName) { $RunName = (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '-' + ([guid]::NewGuid().ToString('N').Substring(0, 8)) }
    $runPath = Join-Path $runRoot $runName
    if (Test-Path -LiteralPath $runPath) { throw "Test run already exists: $runPath" }
    $modsPath = Join-Path $runPath 'mods'
    $manifestPath = Join-Path $runPath 'test-manifest'
    New-Item -ItemType Directory -Force $modsPath, $manifestPath | Out-Null
    $snapshotCopy = $null
    $snapshotOriginal = $null
    $snapshotHash = $null
    if ($PowerSnapshotRegion) {
        $snapshotOriginal = (Resolve-Path -LiteralPath $PowerSnapshotRegion).Path
        if ([System.IO.Path]::GetFileName($snapshotOriginal) -ne '-2.0.region.bin') { throw 'The saved power probe accepts only the named -2.0.region.bin snapshot.' }
        $snapshotDirectory = Join-Path $runPath 'power-snapshot'
        New-Item -ItemType Directory -Path $snapshotDirectory | Out-Null
        $snapshotCopy = Join-Path $snapshotDirectory '-2.0.region.bin'
        $snapshotHash = (Get-FileHash -LiteralPath $snapshotOriginal -Algorithm SHA256).Hash
        Copy-Item -LiteralPath $snapshotOriginal -Destination $snapshotCopy
        if ((Get-FileHash -LiteralPath $snapshotCopy -Algorithm SHA256).Hash -ne $snapshotHash -or
            (Get-FileHash -LiteralPath $snapshotOriginal -Algorithm SHA256).Hash -ne $snapshotHash) {
            throw 'The snapshot changed while copying; the native probe will not read an inconsistent copy.'
        }
        Set-Content -LiteralPath (Join-Path $snapshotDirectory 'source-sha256.txt') -Value $snapshotHash -Encoding ascii
    }
    $testJar = Join-Path $modsPath 'StrangeMatter-Smoke.jar'
    Copy-Item -LiteralPath $modJar.FullName -Destination $testJar
    $manifest = Get-Content -LiteralPath 'src/main/resources/manifest.json' -Raw | ConvertFrom-Json
    $manifest.Main = 'com.hexvane.strangematter.equipment.NativeWorldVerification'
    $manifest.Dependencies = @{ 'Hytale:Universe' = '*'; 'Hytale:Memories' = '*' }
    $manifest | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $manifestPath 'manifest.json') -Encoding utf8
    # Select exactly the same fixtures before updating the archive once. Keep the
    # wildcard scope local to each package, including matching inner classes.
    $fixtureClasses = [System.Collections.Generic.List[string]]::new()
    $fixtureClasses.Add($testClass)
    foreach ($package in @('dev/zero/atlasaudit', 'com/hexvane/strangematter/diagnostics')) {
        foreach ($fixture in Get-ChildItem -LiteralPath (Join-Path $testClasses $package) -Filter '*.class') {
            $fixtureClasses.Add("$package/$($fixture.Name)")
        }
    }
    $fixtureClasses.Add('com/hexvane/strangematter/research/ResearchNoteVerification.class')
    $researchFixturePath = Join-Path $testClasses 'com/hexvane/strangematter/research'
    foreach ($fixture in Get-ChildItem -LiteralPath $researchFixturePath -Filter 'GadgetEnergyResearchVerification*.class') {
        $fixtureClasses.Add("com/hexvane/strangematter/research/$($fixture.Name)")
    }
    foreach ($fixture in Get-ChildItem -LiteralPath $researchFixturePath -Filter 'ResearchUnlockVerification*.class') {
        $fixtureClasses.Add("com/hexvane/strangematter/research/$($fixture.Name)")
    }
    foreach ($fixture in Get-ChildItem -LiteralPath $researchFixturePath -Filter 'ResearchAdminVerification*.class') {
        $fixtureClasses.Add("com/hexvane/strangematter/research/$($fixture.Name)")
    }
    $fixtureClasses.Add('com/hexvane/strangematter/machine/NativeMachineVerification.class')
    $fixtureClasses.Add('com/hexvane/strangematter/machine/NativeMachineWorkVerification.class')
    $machineFixturePath = Join-Path $testClasses 'com/hexvane/strangematter/machine'
    foreach ($fixture in Get-ChildItem -LiteralPath $machineFixturePath -Filter 'NativePower*.class') {
        $fixtureClasses.Add("com/hexvane/strangematter/machine/$($fixture.Name)")
    }
    foreach ($fixture in Get-ChildItem -LiteralPath $machineFixturePath -Filter 'NativeNullifierContentVerification*.class') {
        $fixtureClasses.Add("com/hexvane/strangematter/machine/$($fixture.Name)")
    }
    $fixtureClasses.Add('com/hexvane/strangematter/machine/MachineControlsVerification.class')
    $anomalyFixturePath = Join-Path $testClasses 'com/hexvane/strangematter/anomaly'
    foreach ($fixture in Get-ChildItem -LiteralPath $anomalyFixturePath -Filter 'Native*.class') {
        $fixtureClasses.Add("com/hexvane/strangematter/anomaly/$($fixture.Name)")
    }
    $equipmentFixturePath = Join-Path $testClasses 'com/hexvane/strangematter/equipment'
    $automationFixturePath = Join-Path $testClasses 'com/hexvane/strangematter/automation'
    if (Test-Path -LiteralPath $automationFixturePath) {
        foreach ($fixture in Get-ChildItem -LiteralPath $automationFixturePath -Filter 'Native*.class') {
            $fixtureClasses.Add("com/hexvane/strangematter/automation/$($fixture.Name)")
        }
    }
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
    foreach ($argument in @('-C', (Join-Path $PSScriptRoot 'diagnostics'), 'atlas-audit-LICENSE.txt')) { $jarArguments.Add($argument) }
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
        # The current server rejects bare mode's empty listener list. An ephemeral loopback
        # listener permits normal startup without exposing this isolated test to the network.
        $benchmarkFlag = '-Dstrangematter.benchmarks=' + $Benchmarks.IsPresent.ToString().ToLowerInvariant()
        $snapshotFlags = @()
        if ($snapshotCopy) { $snapshotFlags += '-Dstrangematter.powerSnapshotRegion=' + $snapshotCopy }
        & $javaCommand -Xmx3G $benchmarkFlag @snapshotFlags -jar $serverJar --bind 127.0.0.1:0 --assets $assetsZip --disable-sentry --disable-file-watcher --auth-mode offline --log 'HytaleServer:INFO,PluginManager:INFO,Strange Matter|P:INFO' *> native-world.log
        $serverExit = $LASTEXITCODE
        if ($snapshotCopy -and ((Get-FileHash -LiteralPath $snapshotCopy -Algorithm SHA256).Hash -ne $snapshotHash -or
            (Get-FileHash -LiteralPath $snapshotOriginal -Algorithm SHA256).Hash -ne $snapshotHash)) {
            throw 'Power snapshot integrity check failed: the original and disposable copy must remain byte-identical.'
        }
        $logPath = Join-Path $runPath 'native-world.log'
        Copy-Item -LiteralPath $logPath -Destination (Join-Path $runRoot 'native-world.log')
        $resultPath = Join-Path $runPath 'native-world-result.txt'
        if (Test-Path -LiteralPath $resultPath) { Copy-Item -LiteralPath $resultPath -Destination (Join-Path $runRoot 'native-world-result.txt') }
        $resultMarker = if ($Benchmarks) { 'NATIVE_BENCHMARKS' } else { 'NATIVE_WORLD_VERIFICATION' }
        $passed = Select-String -LiteralPath $logPath -Pattern "${resultMarker}_PASSED:" -Quiet
        $failed = Select-String -LiteralPath $logPath -Pattern "${resultMarker}_FAILED:" -Quiet
        if ($serverExit -ne 0 -or -not $passed -or $failed) { throw "Native world verification did not pass (server exit $serverExit). See $logPath" }
        if ($snapshotCopy -and -not (Select-String -LiteralPath $logPath -Pattern 'NATIVE_SAVED_POWER_REGION_VERIFICATION_PASSED:' -Quiet)) {
            throw "The requested saved-region probe did not pass. See $logPath"
        }
        & python (Join-Path $PSScriptRoot 'diagnostics/check_reports.py') --atlas (Join-Path $runPath 'atlas-audit-report.json')
        if ($LASTEXITCODE -ne 0) { throw "Atlas budget check failed. See $runPath" }
        Get-Content -LiteralPath $resultPath
        Write-Output "Isolated world and log: $runPath"
    } finally { Pop-Location }
} finally { Pop-Location }
