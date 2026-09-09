param([switch]$SkipBuild, [switch]$ValidateBaseInstances)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    if (-not $SkipBuild) { & "$PSScriptRoot/build.ps1" -Tasks build }
    $gamePath = Join-Path $env:APPDATA 'Hytale/install/release/package/game/latest'
    $serverJar = Join-Path $projectRoot 'build/deps/HytaleServer.jar'
    $releaseVersion = (Get-Content -LiteralPath 'src/main/resources/manifest.json' -Raw | ConvertFrom-Json).Version
    $modJar = Join-Path $projectRoot "build/libs/StrangeMatter-$releaseVersion.jar"
    $validationRoot = Join-Path $projectRoot 'build/validation-run'
    $runName = (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '-' + ([guid]::NewGuid().ToString('N').Substring(0,8))
    $validationPath = Join-Path $validationRoot $runName
    $modPath = Join-Path $validationPath 'mods'
    New-Item -ItemType Directory -Force $modPath | Out-Null
    Copy-Item -LiteralPath $modJar -Destination $modPath
    Push-Location $validationPath
    try {
        # Full --validate-assets also loads every vanilla instance. September's server rejects
        # its own zip-backed instance paths; opt in when diagnosing that independent engine issue.
        $validationFlags = if ($ValidateBaseInstances) { @('--validate-assets') } else { @() }
        & java -Xmx4G -jar $serverJar --bare --assets "$gamePath/Assets.zip" @validationFlags --shutdown-after-validate --disable-sentry --disable-file-watcher --auth-mode offline --log "HytaleServer:INFO,PluginManager:INFO,StrangeMatter|P:INFO" 2>&1 | Tee-Object -FilePath asset-validation.log
        Copy-Item -LiteralPath asset-validation.log -Destination (Join-Path $validationRoot 'asset-validation.log')
        if ($LASTEXITCODE -ne 0) { throw "Hytale asset validation exited with code $LASTEXITCODE. See build/validation-run/asset-validation.log." }
        $text = Get-Content -LiteralPath asset-validation.log -Raw
        $records = [regex]::Split($text, '(?=\[\d{4}/\d{2}/\d{2})')
        $failures = $records | Where-Object { $_ -match 'SM_|Hexvane:StrangeMatter|StrangeMatter\|P' -and $_ -match 'WARN|SEVERE|ERROR' }
        if ($failures) { throw "Strange Matter asset warnings/errors remain. See build/validation-run/asset-validation.log." }
    } finally { Pop-Location }
} finally { Pop-Location }
