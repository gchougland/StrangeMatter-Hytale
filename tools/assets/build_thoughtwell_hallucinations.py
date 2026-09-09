"""Create only private hallucination model registrations and finite vanish particles.

Uses existing native model rigs, animations, textures and existing painted mod
sprites. Does not modify or generate any Common image or model file.
"""
from pathlib import Path
import json

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / 'src/main/resources'
NATIVE = ROOT.parent / 'HytaleSourceCode/hytale-shared-source/HytaleAssets'


def write(relative, data):
    path = RES / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + '\n', encoding='utf8')


def span(value):
    return {'Min': value, 'Max': value}


def main():
    models = []
    for suffix, source in [('Wolf', 'Beast/Wolf_Black'), ('Spectre', 'Void/Spectre_Void'), ('Crawler', 'Void/Crawler_Void')]:
        original = json.loads((NATIVE / f'Server/Models/{source}.json').read_text())
        model = {key: original[key] for key in ('Model', 'Texture', 'HitBox', 'EyeHeight')}
        model['AnimationSets'] = {}
        for key in ('Idle', 'Run'):
            clip = original['AnimationSets'][key]['Animations'][0]['Animation']
            model['AnimationSets'][key] = {'Animations': [{'Animation': clip, 'Speed': .9, 'Looping': True}]}
        if suffix == 'Spectre':
            model['Particles'] = [particle for particle in original.get('Particles', [])
                                  if particle.get('TargetNodeName') == 'Chest']
        name = 'SM_Thoughtwell_Phantom_' + suffix
        write(f'Server/Models/StrangeMatter/{name}.json', model)
        models.append({'id': name, 'nativeSource': source, 'model': model['Model'], 'texture': model['Texture']})
    system = 'SM_Thoughtwell_Phantom_Vanish'
    spawner = {'Shape': 'Sphere', 'RenderMode': 'BlendLinear', 'LifeSpan': .06,
        'TotalParticles': span(14), 'MaxConcurrentParticles': 14,
        'ParticleLifeSpan': {'Min': .32, 'Max': .58}, 'SpawnRate': span(14), 'SpawnBurst': True,
        'EmitOffset': {'X': {'Min': -.36, 'Max': .36}, 'Y': {'Min': -.35, 'Max': .5}, 'Z': {'Min': -.25, 'Max': .25}},
        'ParticleRotationInfluence': 'Billboard', 'ParticleRotateWithSpawner': False,
        'LinearFiltering': True, 'LightInfluence': 0,
        'InitialVelocity': {'Speed': {'Min': .3, 'Max': .8}, 'Yaw': {'Min': -180, 'Max': 180}, 'Pitch': {'Min': -15, 'Max': 70}},
        'Particle': {'Texture': 'Particles/StrangeMatter/smoke.png', 'FrameSize': {'Width': 128, 'Height': 128},
            'ScaleRatioConstraint': 'None', 'UVOption': 'None',
            'InitialAnimationFrame': {'Color': '#6aabd0', 'Opacity': 1, 'FrameIndex': span(0),
                                      'Scale': {'X': span(.22), 'Y': span(.3)}},
            'Animation': {'0': {'Opacity': .55}, '35': {'Opacity': .38, 'Color': '#7161ac'},
                          '100': {'Opacity': 0, 'Scale': {'X': span(1.5), 'Y': span(1.7)}}}}}
    write(f'Server/Particles/StrangeMatter/Spawners/{system}.particlespawner', spawner)
    write(f'Server/Particles/StrangeMatter/{system}.particlesystem',
          {'Spawners': [{'SpawnerId': system}], 'LifeSpan': .68, 'CullDistance': 20, 'BoundingRadius': 2, 'IsImportant': True})
    (ROOT / 'tools/assets/thoughtwell-hallucinations.json').write_text(json.dumps({
        'snapshot': 'build/art-preservation/20260909T184345920591Z', 'models': models,
        'nativeModelCount': 3, 'maximumPhantomsPerPlayer': 1, 'lifetimeSeconds': 2.15,
        'vanishParticles': 14, 'vanishMaximumLifetimeSeconds': .68,
        'commonAssetsChanged': 0, 'registration': 'ThoughtwellHallucinations.register(IComponentRegistry<EntityStore>)',
        'privacy': 'Owner component filtered after all native visible collectors and before ClearPreviouslyVisible. Native entity IDs, spawn, transforms, animations and removal packets.'}, indent=2) + '\n')


if __name__ == '__main__':
    main()
