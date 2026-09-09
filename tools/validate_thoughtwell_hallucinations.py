"""Read-only, stdlib asset/privacy contract checks for recognizable hallucinations."""
from pathlib import Path
import json
import os
import zipfile

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'src/main/resources'
NATIVE = ROOT.parent / 'HytaleSourceCode/hytale-shared-source/HytaleAssets'


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def validate():
    report = read(ROOT / 'tools/assets/thoughtwell-hallucinations.json')
    installed = Path(os.environ.get('APPDATA', '')) / 'Hytale/install/release/package/game/latest/Assets.zip'
    native_zip = zipfile.ZipFile(installed) if not NATIVE.exists() and installed.exists() else None
    def native_exists(name):
        return (NATIVE / name).is_file() or (native_zip is not None and name in native_zip.namelist())
    assert report['maximumPhantomsPerPlayer'] == 1 and report['lifetimeSeconds'] == 2.15
    assert len(report['models']) == 3
    for entry in report['models']:
        model = read(RES / f"Server/Models/StrangeMatter/{entry['id']}.json")
        assert model['Model'] == entry['model'] and model['Texture'] == entry['texture']
        assert model['Model'].startswith('NPC/') and model['Texture'].startswith('NPC/')
        assert native_exists('Common/' + model['Model']) and native_exists('Common/' + model['Texture'])
        assert model['HitBox']['Max']['Y'] >= .9
        assert set(model['AnimationSets']) == {'Idle', 'Run'}
        for value in model['AnimationSets'].values():
            for animation in value['Animations']:
                assert animation['Looping'] and native_exists('Common/' + animation['Animation'])
                assert 'SoundEventId' not in animation, 'Keep cue sounds in the private recipient path'
    name = 'SM_Thoughtwell_Phantom_Vanish'
    effect = read(RES / f'Server/Particles/StrangeMatter/{name}.particlesystem')
    spawner = read(RES / f'Server/Particles/StrangeMatter/Spawners/{name}.particlespawner')
    assert effect['Spawners'] == [{'SpawnerId': name}] and effect['LifeSpan'] <= .68
    assert spawner['MaxConcurrentParticles'] == spawner['TotalParticles']['Max'] == 14
    assert 0 < spawner['LifeSpan'] <= .06 and spawner['ParticleLifeSpan']['Max'] <= .58
    assert (RES / 'Common' / spawner['Particle']['Texture']).exists()
    assert spawner['Particle']['Animation']['100']['Opacity'] == 0
    source = (ROOT / 'src/main/java/com/hexvane/strangematter/anomaly/ThoughtwellHallucinations.java').read_text()
    # The actual engine fixture tests packet delivery. These contract checks
    # catch accidental replacement with manual client IDs or real NPC spawning.
    assert 'takeNextNetworkId()' in source and 'store.addEntity(holder,AddReason.SPAWN)' in source
    assert 'NonSerialized.get()' in source and 'Intangible.INSTANCE' in source
    assert 'Order.AFTER,EntityTrackerSystems.FIND_VISIBLE_ENTITIES_GROUP' in source
    assert 'Order.BEFORE,EntityTrackerSystems.ClearPreviouslyVisible.class' in source
    assert 'phantom.owner!=viewerRef' in source
    assert all(token not in source for token in ('new EntityUpdates', 'NPCPlugin', 'executeDamage', 'PersistentModel'))
    snapshot = ROOT / report['snapshot'] / 'Resources.zip'
    preserved = 0
    if snapshot.exists():
        with zipfile.ZipFile(snapshot) as archive:
            for name in archive.namelist():
                relative = name.removeprefix('src/main/resources/')
                if relative.startswith('Common/') and Path(relative).suffix in ('.png','.blockymodel','.blockyanim','.ogg'):
                    assert archive.read(name) == (RES / relative).read_bytes(), 'Existing Common artwork changed: ' + relative
                    preserved += 1
            haze = 'Server/Entity/Effects/StrangeMatter/SM_Cognitive_Dissonance.json'
            assert archive.read('src/main/resources/' + haze) == (RES / haze).read_bytes(), 'Blue haze or status icon changed'
    if native_zip is not None:
        native_zip.close()
    print(json.dumps({'result': 'PASS', 'nativeAnimatedCreatureModels': 3, 'maximumCreaturesPerPlayer': 1,
                      'creatureSeconds': 2.15, 'finiteDissolveParticles': 14, 'preservedCommonFiles': preserved,
                      'nativeTrackerVisibilityFilter': True, 'hazePreserved': True}))


if __name__ == '__main__':
    validate()
