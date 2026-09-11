param([switch]$SkipBuild, [string]$Baseline, [switch]$FailOnRegression, [ValidateRange(1, 10)][int]$Repetitions = 3)
$ErrorActionPreference = 'Stop'
if ($FailOnRegression -and -not $Baseline) { throw '-FailOnRegression requires -Baseline.' }
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    if (-not $SkipBuild) { & "$PSScriptRoot/build.ps1" -Tasks @('jar', 'testClasses') }
    for ($iteration = 1; $iteration -le $Repetitions; $iteration++) {
        $runName = (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '-' + ([guid]::NewGuid().ToString('N').Substring(0, 8))
        & "$PSScriptRoot/verify-native-world.ps1" -SkipBuild -Benchmarks -RunName $runName
        $runPath = Join-Path $projectRoot "build/performance-runs/$runName"
        $reportPath = Join-Path $runPath 'performance-report.json'
        $report = Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json
        $report | Add-Member -NotePropertyName serverSha256 -NotePropertyValue (Get-FileHash -LiteralPath 'build/deps/HytaleServer.jar' -Algorithm SHA256).Hash.ToLowerInvariant()
        $report | Add-Member -NotePropertyName testedArchiveSha256 -NotePropertyValue (Get-FileHash -LiteralPath (Join-Path $runPath 'mods/StrangeMatter-Smoke.jar') -Algorithm SHA256).Hash.ToLowerInvariant()
        $report | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $reportPath -Encoding utf8
        $checkArguments = @("$PSScriptRoot/diagnostics/check_reports.py", '--performance', $reportPath)
        if ($Baseline) { $checkArguments += @('--baseline', $Baseline) }
        if ($FailOnRegression) { $checkArguments += '--strict' }
        & python @checkArguments
        if ($LASTEXITCODE -ne 0) { throw "Performance check failed: $reportPath" }
        Write-Output "Benchmark $iteration of ${Repetitions}: $reportPath"
    }
} finally { Pop-Location }
