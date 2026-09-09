param()
$ErrorActionPreference = 'Stop'
$projectRoot = (Split-Path -Parent $PSScriptRoot)
$probeName = 'StrangeMatterSyncProbe_' + [guid]::NewGuid().ToString('N') + '.txt'
$relativeProbe = 'Server/' + $probeName
$sourceProbe = Join-Path $projectRoot ('src/main/resources/' + $relativeProbe)
$builtProbe = Join-Path $projectRoot ('build/resources/main/' + $relativeProbe)
$initScript = Join-Path $projectRoot ('build/' + $probeName + '.init.gradle')
$before = 'Before the asset editor'
$after = 'Edited in the development asset pack'
Push-Location $projectRoot
try {
    New-Item -ItemType Directory -Force (Split-Path -Parent $builtProbe) | Out-Null
    Set-Content -LiteralPath $sourceProbe -Value $before -Encoding utf8
    Set-Content -LiteralPath $builtProbe -Value $after -Encoding utf8
    $scriptText = @'
gradle.projectsEvaluated {
    def project = rootProject
    project.tasks.named('syncAssets').configure { include('PROBE_PATH') }
    project.tasks.register('verifyDevConfiguration') {
        doLast {
            def run = project.tasks.getByName('runServer')
            def noSync = project.tasks.getByName('runServerNoSync')
            def sync = project.tasks.getByName('syncAssets')
            assert run.finalizedBy.getDependencies(run).contains(sync)
            assert !noSync.finalizedBy.getDependencies(noSync).contains(sync)
            assert sync.excludes.contains('manifest.json')
            assert run.classpath.files.contains(project.file('build/resources/main'))
            assert noSync.classpath.files == run.classpath.files
            assert run.mainClass.get() == 'com.hypixel.hytale.Main'
            assert run.jvmArgs.every { it != null && !it.isBlank() }
            println('PASS: development resource classpath, sync finalizer, NoSync isolation, manifest exclusion and Windows JVM arguments.')
        }
    }
}
'@
    $scriptText.Replace('PROBE_PATH', $relativeProbe) | Set-Content -LiteralPath $initScript -Encoding utf8
    # Restrict this invocation of the real Copy task to our new probe, so no user
    # asset can be overwritten while another source edit is in progress.
    & "$PSScriptRoot/build.ps1" -Tasks @('--init-script', $initScript, 'verifyDevConfiguration', 'syncAssets')
    if ((Get-Content -LiteralPath $sourceProbe -Raw).Trim() -ne $after) { throw 'Asset-editor changes did not copy back to source.' }
    Write-Output 'PASS: real syncAssets task copied the edited resource back into source.'
} finally {
    foreach ($probe in @($sourceProbe, $builtProbe, $initScript)) {
        $resolvedProbe = [IO.Path]::GetFullPath($probe)
        if (-not $resolvedProbe.StartsWith([IO.Path]::GetFullPath($projectRoot) + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Probe cleanup escaped the workspace.' }
        if (Test-Path -LiteralPath $resolvedProbe) { Remove-Item -LiteralPath $resolvedProbe -Force }
    }
    Pop-Location
}
