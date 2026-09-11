"""Compare the release with current resources/classes and its actual isolated native test archives."""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
version = json.loads((ROOT / 'src/main/resources/manifest.json').read_text(encoding='utf-8-sig'))['Version']
jar = ROOT / 'build/libs' / f'StrangeMatter-{version}.jar'
run = max((p for p in (ROOT / 'build/native-world-run').iterdir()
           if p.is_dir() and (p / 'native-world-result.txt').is_file()), key=lambda p: p.stat().st_mtime)
validation = max((p for p in (ROOT / 'build/validation-run').iterdir()
                  if p.is_dir() and (p / 'asset-validation.log').is_file()
                  and (p / 'mods' / jar.name).is_file()), key=lambda p: p.stat().st_mtime)
assert 'NATIVE_WORLD_VERIFICATION_PASSED:' in (run / 'native-world-result.txt').read_text()
assert 'Asset validation passed' in (validation / 'asset-validation.log').read_text()
atlas_report = run / 'atlas-audit-report.json'
subprocess.run([sys.executable, str(ROOT / 'tools/diagnostics/check_reports.py'), '--atlas', str(atlas_report)], check=True, capture_output=True, text=True)
resources = [p for p in (ROOT / 'src/main/resources').rglob('*') if p.is_file()]
classes = list((ROOT / 'build/classes/java/main').rglob('*.class'))
with zipfile.ZipFile(jar) as z, zipfile.ZipFile(run / 'mods/StrangeMatter-Smoke.jar') as native, \
        zipfile.ZipFile(validation / 'mods' / jar.name) as assets:
    names = set(z.namelist())
    assert len(names) == len(z.namelist()), 'Duplicate ZIP entry'
    manifest = json.loads(z.read('manifest.json'))
    assert manifest['Name'] == 'Strange Matter', 'Native mod lists must display the spaced name'
    assert manifest['Version'] == version
    assert manifest.get('IncludesAssetPack') is True, 'Native UI documents and images must be advertised to clients'
    assert z.read('META-INF/LICENSE.txt') == (ROOT / 'LICENSE.txt').read_bytes(), 'Packaged license differs from repository license'
    assert 'All Rights Reserved.' in (ROOT / 'LICENSE.txt').read_text(encoding='utf-8-sig'), 'Release must carry the requested license'
    resource_names = {p.relative_to(ROOT / 'src/main/resources').as_posix() for p in resources}
    archived_assets = {name for name in names if name.startswith(('Common/', 'Server/')) and not name.endswith('/')}
    source_assets = {name for name in resource_names if name.startswith(('Common/', 'Server/'))}
    assert archived_assets == source_assets, f'Stale or missing packaged assets: {sorted(archived_assets ^ source_assets)}'
    # Check the exact archive casing of both client document appends and server-side
    # classpath templates. A Windows file existence check alone would hide a case error.
    referenced_ui = set()
    for java in (ROOT / 'src/main/java').rglob('*.java'):
        for reference in re.findall(r'"([^"\r\n]+\.ui)"', java.read_text(encoding='utf-8-sig')):
            path = reference.lstrip('/')
            if path.startswith('StrangeMatter/'):
                path = 'Common/UI/Custom/' + path
            if path.startswith('Common/UI/Custom/'):
                assert path in names, f'UI resource referenced by {java.name} is absent from release: {path}'
                referenced_ui.add(path)
    for p in resources:
        name = p.relative_to(ROOT / 'src/main/resources').as_posix()
        assert z.read(name) == p.read_bytes(), f'Stale release resource: {name}'
        assert z.read(name) == assets.read(name), f'Asset-validation mismatch: {name}'
    for p in classes:
        name = p.relative_to(ROOT / 'build/classes/java/main').as_posix()
        assert z.read(name) == p.read_bytes(), f'Stale release class: {name}'
    for name in names:
        if name.endswith('/') or name == 'manifest.json':
            continue
        assert z.read(name) == native.read(name), f'Native-world test archive mismatch: {name}'
    test_names = {p.relative_to(ROOT / 'build/classes/java/test').as_posix()
                  for p in (ROOT / 'build/classes/java/test').rglob('*.class')}
    assert not names.intersection(test_names), 'Test fixture leaked into release'
    tube_hashes = {name: hashlib.sha256(z.read(name)).hexdigest() for name in names
                   if name.startswith('com/hexvane/strangematter/automation/Tube') and name.endswith('.class')}

# Process-level crash checks use the exact current transport classes and native server.
# Separate fixture plugins intentionally have a different manifest and extra test classes.
crash_runs = {}
expected_cuts = {'journal', 'mutated', 'source', 'destination', 'both', 'complete'}
server_hash = hashlib.sha256((ROOT / 'build/deps/HytaleServer.jar').read_bytes()).hexdigest()
for candidate in sorted((ROOT / 'build/tube-crash-runs').glob('*/results.json'), key=lambda p: p.stat().st_mtime):
    evidence_path = candidate.with_name('evidence.json')
    if not evidence_path.exists():
        continue
    evidence = json.loads(evidence_path.read_text(encoding='utf-8-sig'))
    if evidence.get('tubeClasses') != tube_hashes or evidence.get('serverSha256') != server_hash:
        continue
    cases = json.loads(candidate.read_text(encoding='utf-8-sig'))
    if not isinstance(cases, list) or {case['cut'] for case in cases} != expected_cuts:
        continue
    for case in cases:
        stages = case['stages']
        assert len(stages) == 3 and stages[0]['forced'] is True
        assert all(stage['exit'] == 0 and stage['forced'] is False and
                   stage['result'] == f"NATIVE_TUBE_CRASH_PASS {case['cut']} round={i}"
                   for i, stage in enumerate(stages[1:], 1))
    storage = cases[0]['storage']
    assert all(case['storage'] == storage for case in cases)
    crash_runs[storage] = candidate.relative_to(ROOT).as_posix()
assert {'Hytale', 'RocksDb'} <= crash_runs.keys(), 'Current transport needs all six process crash cuts on both native storage backends'

report = {
    'name': manifest['Name'], 'version': version, 'jar': str(jar), 'bytes': jar.stat().st_size,
    'sha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
    'sourceResourcesMatched': len(resources), 'compiledClassesMatched': len(classes),
    'testFixturesExcluded': True, 'nativeWorld': 'PASS', 'nativeAssets': 'PASS',
    'nativeWorldRun': run.relative_to(ROOT).as_posix(),
    'nativeAssetRun': validation.relative_to(ROOT).as_posix(),
    'nativeTestArchiveMatchesRelease': True, 'nativeValidatedAssetsMatchRelease': True,
    'packagedAssetInventoryMatchesSource': True,
    'referencedUiDocumentsPackaged': len(referenced_ui),
    'liveClientVerified': False,
    'nativeAtlasAudit': 'PASS',
    'nativeAtlasReport': atlas_report.relative_to(ROOT).as_posix(),
    'tubeProcessCrashRecovery': crash_runs,
    'tubeCrashClassesMatchRelease': True,
    'license': 'All Rights Reserved', 'packagedLicenseMatchesRepository': True,
}
(ROOT / 'build' / f'release-verification-{version}.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
