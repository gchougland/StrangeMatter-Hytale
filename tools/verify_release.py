"""Compare the release with current resources/classes and its actual isolated native test archives."""
from pathlib import Path
import hashlib
import json
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
resources = [p for p in (ROOT / 'src/main/resources').rglob('*') if p.is_file()]
classes = list((ROOT / 'build/classes/java/main').rglob('*.class'))
with zipfile.ZipFile(jar) as z, zipfile.ZipFile(run / 'mods/StrangeMatter-Smoke.jar') as native, \
        zipfile.ZipFile(validation / 'mods' / jar.name) as assets:
    names = set(z.namelist())
    assert len(names) == len(z.namelist()), 'Duplicate ZIP entry'
    assert json.loads(z.read('manifest.json'))['Version'] == version
    assert z.read('META-INF/LICENSE.txt') == (ROOT / 'LICENSE.txt').read_bytes(), 'Packaged license differs from repository license'
    assert 'All Rights Reserved.' in (ROOT / 'LICENSE.txt').read_text(encoding='utf-8-sig'), 'Release must carry the requested license'
    resource_names = {p.relative_to(ROOT / 'src/main/resources').as_posix() for p in resources}
    archived_assets = {name for name in names if name.startswith(('Common/', 'Server/')) and not name.endswith('/')}
    source_assets = {name for name in resource_names if name.startswith(('Common/', 'Server/'))}
    assert archived_assets == source_assets, f'Stale or missing packaged assets: {sorted(archived_assets ^ source_assets)}'
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

report = {
    'version': version, 'jar': str(jar), 'bytes': jar.stat().st_size,
    'sha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
    'sourceResourcesMatched': len(resources), 'compiledClassesMatched': len(classes),
    'testFixturesExcluded': True, 'nativeWorld': 'PASS', 'nativeAssets': 'PASS',
    'nativeWorldRun': run.relative_to(ROOT).as_posix(),
    'nativeAssetRun': validation.relative_to(ROOT).as_posix(),
    'nativeTestArchiveMatchesRelease': True, 'nativeValidatedAssetsMatchRelease': True,
    'packagedAssetInventoryMatchesSource': True,
    'liveClientVerified': False,
    'license': 'All Rights Reserved', 'packagedLicenseMatchesRepository': True,
}
(ROOT / 'build' / f'release-verification-{version}.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
