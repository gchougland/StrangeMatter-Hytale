param([string[]]$Tasks = @('build'))
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    $installedJar = Join-Path $env:APPDATA 'Hytale/install/release/package/game/latest/Server/HytaleServer.jar'
    if (-not (Test-Path -LiteralPath $installedJar)) { throw "Install Hytale release or use Gradle -PhytaleServerJar=/path/to/HytaleServer.jar" }
    New-Item -ItemType Directory -Force build/deps | Out-Null
    $localJar = Join-Path $projectRoot 'build/deps/HytaleServer.jar'
    if (-not (Test-Path -LiteralPath $localJar) -or (Get-Item -LiteralPath $localJar).Length -ne (Get-Item -LiteralPath $installedJar).Length -or (Get-Item -LiteralPath $installedJar).LastWriteTimeUtc -gt (Get-Item -LiteralPath $localJar).LastWriteTimeUtc) { Copy-Item -LiteralPath $installedJar -Destination $localJar }
    $gradleCache = Join-Path $env:USERPROFILE '.gradle/wrapper/dists/gradle-9.2.1-bin'
    $localGradle = Get-ChildItem -LiteralPath $gradleCache -Filter gradle.bat -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
    # Share Aetherhaven's cached Hytale Gradle plugin and toolchain resolution.
    $buildArgs = @('--no-daemon', '-PhytaleServerJar=build/deps/HytaleServer.jar', '--offline') + $Tasks
    if ($localGradle) { & $localGradle.FullName @buildArgs }
    else { & ./gradlew.bat @buildArgs }
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
} finally { Pop-Location }
