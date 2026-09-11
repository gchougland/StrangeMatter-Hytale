"""Write only the new warp bolt definitions; existing models and raster art are read only."""
from pathlib import Path
import json

ROOT = Path(__file__).resolve().parents[2]
RESOURCES = ROOT / 'src/main/resources'
PARTICLES = RESOURCES / 'Server/Particles/StrangeMatter'
FLIGHT_SECONDS = 3.0
PALETTES = {
    'Cyan': {'core': '#d8ffff', 'halo': '#22ddff', 'vortex': '#62faff', 'trail': '#28d9fa', 'light': '#3af'},
    'Purple': {'core': '#f1ddff', 'halo': '#a653ff', 'vortex': '#cf98ff', 'trail': '#ab65ff', 'light': '#93f'},
}


def fixed(value):
    return {'Min': value, 'Max': value}


def scale(value):
    return {'X': fixed(value), 'Y': fixed(value)}


def definition(sprite, color, size, *, trail=False, mote=False):
    lifetime = {'Min': .18, 'Max': .30} if mote else {'Min': .12, 'Max': .20}
    rate = 12 if mote else 20
    frames = {
        '0': {'Color': color, 'Opacity': .85, 'Scale': scale(1)},
        '30': {'Opacity': .7},
        '100': {'Opacity': 0, 'Scale': scale(.15)},
    } if trail else {
        '0': {'Color': color, 'Opacity': .9, 'Scale': scale(1)},
        '25': {'Scale': scale(1.1)},
        '50': {'Scale': scale(.95)},
        '75': {'Scale': scale(1.1)},
        '100': {'Opacity': .9, 'Scale': scale(1)},
    }
    if sprite == 'vortex':
        for key, frame in frames.items():
            frame['Rotation'] = {'Z': fixed(int(key) * 10.8)}
    if sprite == 'halo':
        frames['0']['Opacity'] = .42
        frames['100']['Opacity'] = .42
    offset = .045 if mote else .012 if trail else 0
    result = {
        'Shape': 'Sphere', 'RenderMode': 'BlendAdd',
        'LifeSpan': FLIGHT_SECONDS if trail else .04,
        'TotalParticles': fixed(int(FLIGHT_SECONDS * rate) if trail else 1),
        'MaxConcurrentParticles': 4 if mote else 5 if trail else 1,
        'ParticleLifeSpan': lifetime if trail else fixed(3.3),
        'SpawnRate': fixed(rate if trail else 1), 'SpawnBurst': not trail,
        'EmitOffset': {axis: {'Min': -offset, 'Max': offset} for axis in ('X', 'Y', 'Z')},
        'ParticleRotationInfluence': 'Billboard', 'ParticleRotateWithSpawner': False,
        'TrailSpawnerPositionMultiplier': 1 if trail else 0,
        'TrailSpawnerRotationMultiplier': 1,
        'LinearFiltering': True, 'LightInfluence': 0,
        'InitialVelocity': {'Speed': {'Min': .05, 'Max': .20} if mote else fixed(0),
                            'Yaw': {'Min': -180, 'Max': 180}, 'Pitch': {'Min': -90, 'Max': 90}},
        'Particle': {
            'Texture': f'Particles/StrangeMatter/{sprite}.png',
            'FrameSize': {'Width': 128, 'Height': 128},
            'ScaleRatioConstraint': 'OneToOne', 'UVOption': 'None',
            'InitialAnimationFrame': {'Color': '#ffffff', 'Opacity': 1,
                                      'Scale': scale(size), 'FrameIndex': fixed(0)},
            'Animation': frames,
        },
    }
    return result


def resources():
    output = {}
    for channel, palette in PALETTES.items():
        name = f'SM_Warp_Bolt_{channel}'
        layers = [
            ('Star', 'star', palette['core'], .105, False, False),
            ('Halo', 'halo', palette['halo'], .245, False, False),
            ('Vortex', 'vortex', palette['vortex'], .160, False, False),
            ('Trace', 'spark', palette['trail'], .055, True, False),
            ('Motes', 'star', palette['vortex'], .025, True, True),
        ]
        for layer, sprite, color, size, trail, mote in layers:
            output[f'Server/Particles/StrangeMatter/Spawners/{name}_{layer}.particlespawner'] = definition(
                sprite, color, size, trail=trail, mote=mote)
        for kind, selected in [('Core', ['Star', 'Halo', 'Vortex']), ('Trail', ['Trace', 'Motes'])]:
            output[f'Server/Particles/StrangeMatter/{name}_{kind}.particlesystem'] = {
                'Spawners': [{'SpawnerId': f'{name}_{layer}'} for layer in selected],
                'LifeSpan': 3.35, 'CullDistance': 96, 'BoundingRadius': 8, 'IsImportant': True,
            }
        output[f'Server/Models/StrangeMatter/{name}.json'] = {
            # Native projectile host is an ordinary box with a fully transparent atlas.
            # Colored particles provide all visible art; no new model/texture is needed.
            'Model': 'Items/Projectiles/Projectile.blockymodel',
            'Texture': 'Items/Projectiles/Projectile_default.png',
            'MinScale': 1, 'MaxScale': 1, 'EyeHeight': 0,
            'HitBox': {'Min': {axis: -.09 for axis in ('X', 'Y', 'Z')},
                       'Max': {axis: .09 for axis in ('X', 'Y', 'Z')}},
            'Light': {'Color': palette['light']},
            'Particles': [{'SystemId': f'{name}_{kind}', 'TargetEntityPart': 'Self',
                           'TargetNodeName': '', 'Scale': 1,
                           'PositionOffset': {'X': 0, 'Y': 0, 'Z': 0},
                           'DetachedFromModel': False, 'ClearParticlesOnRemove': kind == 'Core'}
                          for kind in ('Core', 'Trail')],
        }
        output[f'Server/ProjectileConfigs/StrangeMatter/{name}.json'] = {
            'Model': name, 'LaunchForce': 24,
            'SpawnOffset': {'X': 0, 'Y': 0, 'Z': 0},
            'SpawnRotationOffset': {'Yaw': 0, 'Pitch': 0, 'Roll': 0},
            # Native Interactions copies this map into EnumMap, which rejects an
            # empty decoded HashMap. The runtime impact callback replaces Miss;
            # this unused no-op avoids starting a forced remote Spawn chain.
            'Interactions': {'ProjectileMiss': {
                'Cooldown': {'Cooldown': 0},
                'Interactions': [{'Type': 'Simple', 'RunTime': 0}],
            }},
            'Physics': {'Type': 'Standard', 'Gravity': 0, 'Bounciness': 0,
                        'BounceCount': 0, 'SticksVertically': True, 'AllowRolling': False,
                        'TerminalVelocityAir': 24, 'TerminalVelocityWater': 24,
                        'HitWaterImpulseLoss': 0},
        }
    return output


def main():
    generated = resources()
    for relative, data in generated.items():
        path = RESOURCES / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')
    print(f'Wrote {len(generated)} warp bolt definitions. No Common artwork was opened for writing.')


if __name__ == '__main__':
    main()
