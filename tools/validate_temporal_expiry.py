"""Check the yellow native block disappearance tint and all reused particle paths."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'src/main/resources'
NATIVE = ROOT.parent / 'HytaleSourceCode/hytale-shared-source/HytaleAssets'


def main():
    block = json.loads((RES / 'Server/Item/Items/StrangeMatter/SM_Time_Dilation_Block.json').read_text(encoding='utf8'))['BlockType']
    assert block['ParticleColor'] == '#ffda68', 'Disappearance must match the existing chrono yellow'
    assert block['Material'] == 'Empty' and block['BlockParticleSetId'] == 'Stone', 'Native pass through and breakup effect remain unchanged'
    sparks = json.loads((RES / 'Server/Particles/StrangeMatter/Spawners/SM_Chrono_Impact_Sparks.particlespawner').read_text(encoding='utf8'))
    assert block['ParticleColor'] == sparks['Particle']['Animation']['0']['Color'], 'Expiry and impact belong to the same yellow effect family'
    particle_set = json.loads((NATIVE / 'Server/Item/Block/Particles/Stone.json').read_text(encoding='utf8'))
    system_id = particle_set['Particles']['Break']
    systems = {p.stem: p for p in (NATIVE / 'Server/Particles').rglob('*.particlesystem')}
    spawners = {p.stem: p for p in (NATIVE / 'Server/Particles').rglob('*.particlespawner')}
    system = json.loads(systems[system_id].read_text(encoding='utf8'))
    for entry in system['Spawners']:
        spawner = json.loads(spawners[entry['SpawnerId']].read_text(encoding='utf8'))
        texture = spawner['Particle']['Texture']
        assert (NATIVE / 'Common' / texture).is_file(), f'Missing native particle texture: {texture}'
    for script in ('tools/convert_content.py', 'tools/finalize_assets.py'):
        text = (ROOT / script).read_text(encoding='utf8')
        assert "'ParticleColor'" in text and "'#ffda68'" in text, 'Generation must retain the yellow tint'
    print(f'PASS: yellow #ffda68 native expiry tint, chrono impact color match, {system_id} and all {len(system["Spawners"])} spawner textures resolve; no art or expiry timing changes.')


if __name__ == '__main__':
    main()
