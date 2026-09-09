"""Check the bounded thought-echo asset dependencies and preserve the authored cognitive haze."""
import json
from pathlib import Path
import zipfile

ROOT=Path(__file__).resolve().parents[1]
RES=ROOT/'src/main/resources'
NATIVE=ROOT.parent/'HytaleSourceCode/hytale-shared-source/HytaleAssets/Common'
def read(path):return json.loads(path.read_text(encoding='utf-8'))
def validate():
    system=read(RES/'Server/Particles/StrangeMatter/SM_Thoughtwell_Echo.particlesystem')
    spawner=read(RES/'Server/Particles/StrangeMatter/Spawners/SM_Thoughtwell_Echo_Glyphs.particlespawner')
    assert system['Spawners']==[{'SpawnerId':'SM_Thoughtwell_Echo_Glyphs'}]
    assert 0<spawner['LifeSpan']<=.1 and spawner['ParticleLifeSpan']['Max']+spawner['LifeSpan']<=system['LifeSpan']<=1.2
    assert spawner['MaxConcurrentParticles']==3 and spawner['TotalParticles']['Max']==3 and spawner['SpawnBurst']
    assert (RES/'Common'/spawner['Particle']['Texture']).is_file()
    for name in ('Step','Chime'):
        sound=read(RES/f'Server/Audio/SoundEvents/StrangeMatter/SM_Thoughtwell_False_{name}.json')
        assert sound['MaxInstance']<=2 and sound['Volume']<=-12 and sound['MaxDistance']==12
        assert not sound.get('Looping',False)
        for layer in sound['Layers']:
            for file in layer['Files']:assert (RES/'Common'/file).is_file() or (NATIVE/file).is_file(),file
    snapshot=ROOT/'build/art-preservation/20260909T175943153203Z/Resources.zip'
    if snapshot.is_file():
        with zipfile.ZipFile(snapshot) as archive:
            for rel in ('Server/Entity/Effects/StrangeMatter/SM_Cognitive_Dissonance.json',
                        'Common/UI/StatusEffects/SM_Cognitive_Dissonance.png',
                        'Common/Particles/StrangeMatter/rune.png',
                        'Common/Sounds/StrangeMatter/energy_wave_align.ogg'):
                name=next(n for n in archive.namelist() if n.endswith(rel))
                assert archive.read(name)==(RES/rel).read_bytes(),f'Existing authored resource changed: {rel}'
    print('THOUGHTWELL_ECHO_ASSETS_PASSED: finite three-glyph pulses, quiet bounded one-shots, native dependency paths and original haze/icon/sprite/audio bytes preserved.')
if __name__=='__main__':validate()
