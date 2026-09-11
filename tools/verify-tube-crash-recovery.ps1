param(
    [ValidateSet('journal','mutated','source','destination','both','complete')]
    [string[]]$Cuts = @('journal','mutated','source','destination','both','complete'),
    [ValidateSet('Hytale','RocksDb')][string]$Storage = 'Hytale',
    [ValidateRange(30,300)][int]$ServerTimeoutSeconds = 120
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$serverJar = Join-Path $projectRoot 'build/deps/HytaleServer.jar'
$assetsZip = Join-Path $env:APPDATA 'Hytale/install/release/package/game/latest/Assets.zip'
$classes = Join-Path $projectRoot 'build/classes/java/test'
$fixturePackage = 'com/hexvane/strangematter/automation'
$fixtureClasses = @(Get-ChildItem -LiteralPath (Join-Path $classes $fixturePackage) -Filter 'NativeTubeCrashVerification*.class')
$modJar = Get-ChildItem -LiteralPath (Join-Path $projectRoot 'build/libs') -Filter 'StrangeMatter-*.jar' |
    Where-Object Name -NotMatch '-(sources|javadoc)\.jar$' | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
foreach ($required in @($serverJar,$assetsZip,(Join-Path $classes "$fixturePackage/NativeTubeCrashVerification.class"))) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing $required. Build jar and testClasses before running this isolated test." }
}
if (-not $modJar -or $fixtureClasses.Count -eq 0) { throw 'Compiled production jar and crash fixture are required.' }
$java = (Get-Command java -ErrorAction Stop).Source
$jar = (Get-Command jar -ErrorAction Stop).Source
$stamp = (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '-' + ([guid]::NewGuid().ToString('N').Substring(0,8))
$runRoot = Join-Path $projectRoot "build/tube-crash-runs/$stamp"
New-Item -ItemType Directory -Path $runRoot -Force | Out-Null
# Freeze the input once. A separate integration build may replace build/libs while this matrix runs.
$productionInput = Join-Path $runRoot 'production-input.jar'
Copy-Item -LiteralPath $modJar.FullName -Destination $productionInput
$fixtureInput = Join-Path $runRoot 'fixture-input'
$frozenFixturePackage = Join-Path $fixtureInput $fixturePackage
New-Item -ItemType Directory -Path $frozenFixturePackage -Force | Out-Null
foreach ($fixture in $fixtureClasses) { Copy-Item -LiteralPath $fixture.FullName -Destination (Join-Path $frozenFixturePackage $fixture.Name) }
$classes = $fixtureInput
$fixtureClasses = @(Get-ChildItem -LiteralPath $frozenFixturePackage -Filter 'NativeTubeCrashVerification*.class')
$utf8 = [System.Text.UTF8Encoding]::new($false)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$tubeClassHashes = [ordered]@{}
$archive = [System.IO.Compression.ZipFile]::OpenRead($productionInput)
try {
    foreach ($entry in $archive.Entries) {
        if ($entry.FullName -notlike 'com/hexvane/strangematter/automation/Tube*.class') { continue }
        $stream = $entry.Open()
        $digest = [System.Security.Cryptography.SHA256]::Create()
        try { $tubeClassHashes[$entry.FullName] = [System.BitConverter]::ToString($digest.ComputeHash($stream)).Replace('-','').ToLowerInvariant() }
        finally { $digest.Dispose(); $stream.Dispose() }
    }
} finally { $archive.Dispose() }
$fixtureHashes = [ordered]@{}
foreach ($fixture in $fixtureClasses) { $fixtureHashes[$fixture.Name] = (Get-FileHash -LiteralPath $fixture.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
$evidence = [ordered]@{
    productionJar = $modJar.FullName
    productionSha256 = (Get-FileHash -LiteralPath $productionInput -Algorithm SHA256).Hash.ToLowerInvariant()
    serverSha256 = (Get-FileHash -LiteralPath $serverJar -Algorithm SHA256).Hash.ToLowerInvariant()
    storage = $Storage
    cuts = $Cuts
    tubeClasses = $tubeClassHashes
    fixtureClasses = $fixtureHashes
    launcherSha256 = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant()
}
[System.IO.File]::WriteAllText((Join-Path $runRoot 'evidence.json'),($evidence | ConvertTo-Json -Depth 8),$utf8)
function Write-JavaArgs([string]$Path,[string[]]$Arguments) {
    $lines = [string[]]@($Arguments | ForEach-Object { '"' + $_.Replace('\','\\').Replace('"','\"') + '"' })
    [System.IO.File]::WriteAllLines($Path,$lines,$utf8)
}
function Start-Stage([string]$Directory,[string]$Cut,[string]$Stage,[int]$Round) {
    $prefix = "$Stage-$Round"
    $argsPath = Join-Path $Directory "$prefix.args"
    Write-JavaArgs $argsPath @('-Xmx2G',"-Dsm.tube.stage=$Stage","-Dsm.tube.cut=$Cut","-Dsm.tube.round=$Round","-Dsm.tube.storage=$Storage",
        '-jar',$serverJar,'--bind','127.0.0.1:0','--assets',$assetsZip,'--disable-sentry','--disable-file-watcher','--auth-mode','offline',
        '--log','HytaleServer:INFO,PluginManager:INFO,Strange Matter|P:INFO')
    $stdout = Join-Path $Directory "$prefix.stdout.log"
    $stderr = Join-Path $Directory "$prefix.stderr.log"
    # The saved Process object is the only process this harness is allowed to stop.
    $process = Start-Process -FilePath $java -ArgumentList ('"@' + $argsPath + '"') -WorkingDirectory $Directory -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    $null = $process.Handle
    $launchedPid = $process.Id
    $deadline = [DateTime]::UtcNow.AddSeconds($ServerTimeoutSeconds)
    try {
        while ($true) {
            $process.Refresh()
            if ($Stage -eq 'prepare') {
                $markerPath = Join-Path $Directory 'cut-marker.json'
                if (Test-Path -LiteralPath $markerPath) {
                    # The marker is forced by the fixture after native disk reads verify this cut.
                    $marker = Get-Content -LiteralPath $markerPath -Raw | ConvertFrom-Json
                    if ([long]$marker.pid -ne $launchedPid -or $marker.cut -ne $Cut -or $marker.stage -ne $Stage) {
                        throw "Crash marker does not belong to our launched process $launchedPid. No unrelated process will be stopped."
                    }
                    if ($process.HasExited) { throw "Prepare process $launchedPid exited before the deliberate stop. See $stderr" }
                    Stop-Process -InputObject $process -Force
                    if (-not $process.WaitForExit(15000)) { throw "Known process $launchedPid did not stop." }
                    return @{stage=$Stage;round=$Round;pid=$launchedPid;forced=$true;marker=$marker}
                }
            }
            if ($process.HasExited) {
                $process.WaitForExit()
                $result = Join-Path $Directory "result-$Stage-$Round.txt"
                if ($Stage -ne 'recover' -or $process.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $result) -or
                    -not (Select-String -LiteralPath $result -Pattern "^NATIVE_TUBE_CRASH_PASS $Cut round=$Round$" -Quiet)) {
                    throw "Tube crash stage $Cut/$Stage/$Round failed (exit $($process.ExitCode)). See $stdout and $stderr"
                }
                return @{stage=$Stage;round=$Round;pid=$launchedPid;forced=$false;exit=$process.ExitCode;result=(Get-Content -LiteralPath $result -Raw).Trim()}
            }
            if ([DateTime]::UtcNow -gt $deadline) { throw "Tube crash stage $Cut/$Stage/$Round timed out. See $stdout and $stderr" }
            Start-Sleep -Milliseconds 200
        }
    } finally {
        $process.Refresh()
        if (-not $process.HasExited) {
            Stop-Process -InputObject $process -Force
            [void]$process.WaitForExit(15000)
        }
        $process.Dispose()
    }
}
$results = [System.Collections.Generic.List[object]]::new()
foreach ($cut in $Cuts) {
    $casePath = Join-Path $runRoot $cut
    $mods = Join-Path $casePath 'mods'
    $manifestDir = Join-Path $casePath 'fixture-manifest'
    New-Item -ItemType Directory -Path $mods,$manifestDir -Force | Out-Null
    $testJar = Join-Path $mods 'StrangeMatter-TubeCrash.jar'
    Copy-Item -LiteralPath $productionInput -Destination $testJar
    $manifest = Get-Content -LiteralPath (Join-Path $projectRoot 'src/main/resources/manifest.json') -Raw | ConvertFrom-Json
    $manifest.Main = 'com.hexvane.strangematter.automation.NativeTubeCrashVerification'
    $manifest.Dependencies = @{ 'Hytale:Universe' = '*' }
    [System.IO.File]::WriteAllText((Join-Path $manifestDir 'manifest.json'),($manifest | ConvertTo-Json -Depth 12),$utf8)
    $jarArguments = [System.Collections.Generic.List[string]]::new()
    foreach ($arg in @('--update','--file',$testJar)) { $jarArguments.Add($arg) }
    foreach ($fixture in $fixtureClasses) {
        foreach ($arg in @('-C',$classes,"$fixturePackage/$($fixture.Name)")) { $jarArguments.Add($arg) }
    }
    foreach ($arg in @('-C',$manifestDir,'manifest.json')) { $jarArguments.Add($arg) }
    $packageArgs = Join-Path $casePath 'package.args'
    Write-JavaArgs $packageArgs $jarArguments.ToArray()
    & $jar ("@" + $packageArgs)
    if ($LASTEXITCODE -ne 0) { throw "Could not package dedicated test plugin: $LASTEXITCODE" }
    [System.IO.File]::WriteAllText((Join-Path $casePath 'permissions.json'),'{"users":{},"groups":{}}',$utf8)
    $stages = @()
    $stages += Start-Stage $casePath $cut 'prepare' 1
    $stages += Start-Stage $casePath $cut 'recover' 1
    $stages += Start-Stage $casePath $cut 'recover' 2
    $results.Add(@{cut=$cut;storage=$Storage;directory=$casePath;stages=$stages})
    [System.IO.File]::WriteAllText((Join-Path $runRoot 'results.json'),($results.ToArray() | ConvertTo-Json -Depth 10),$utf8)
    Write-Output "PASS $cut : deliberate process stop, native disk recovery, second restart"
}
Write-Output "Tube process restart evidence: $runRoot"
