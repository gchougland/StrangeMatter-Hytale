"""Validate actual native warp bolt assets semantically, allowing safe manual tuning."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import struct
import zipfile

ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / 'src/main/resources'
ART_BASELINE = ROOT / 'build/art-preservation/warp-bolt-art-before.json'


def common_bytes(relative):
    for base in (RESOURCES, ROOT.parent / 'HytaleSourceCode/hytale-shared-source/HytaleAssets'):
        path = base / 'Common' / relative
        if path.exists():
            return path.read_bytes()
    archive = Path(os.environ.get('APPDATA', '')) / 'Hytale/install/release/package/game/latest/Assets.zip'
    if archive.exists():
        with zipfile.ZipFile(archive) as native:
            return native.read('Common/' + relative)
    raise FileNotFoundError(relative)


def validate(audit_art=False, art_baseline=ART_BASELINE):
    checked = set()

    def read(relative):
        checked.add(relative)
        return json.loads((RESOURCES / relative).read_text(encoding='utf-8-sig'))

    def texture_size(relative):
        texture = common_bytes(relative)
        assert texture[:8] == b'\x89PNG\r\n\x1a\n', f'Not a PNG: {relative}'
        size = struct.unpack('>II', texture[16:24])
        assert all(d >= 32 and d % 32 == 0 for d in size), f'Native atlas dimensions: {relative}'
        return size

    runtime = (ROOT / 'src/main/java/com/hexvane/strangematter/equipment/WarpProjectiles.java').read_text()
    ttl_match = re.search(r'\bLIFETIME\s*=\s*(\d+(?:\.\d+)?)', runtime)
    assert ttl_match, 'Find runtime lifetime to verify effect coverage'
    ttl = float(ttl_match.group(1))
    assert 0 < ttl <= 3, 'Presentation supports a bounded three second flight'
    per_channel = {}
    channel_lights = []
    for channel in ('Cyan', 'Purple'):
        name = f'SM_Warp_Bolt_{channel}'
        model = read(f'Server/Models/StrangeMatter/{name}.json')
        config = read(f'Server/ProjectileConfigs/StrangeMatter/{name}.json')
        assert config['Model'] == name
        interactions = config.get('Interactions', {})
        assert set(interactions) == {'ProjectileMiss'}, 'Nonempty native EnumMap without a forced remote Spawn chain'
        assert interactions['ProjectileMiss']['Interactions'] == [{'Type': 'Simple', 'RunTime': 0}], 'Unused native Miss action is a harmless no-op'
        assert 0 < config['LaunchForce'] <= 48 and config['Physics']['Gravity'] == 0, 'Bounded straight native flight'
        assert config['Physics']['SticksVertically'] and config['Physics']['BounceCount'] == 0, 'Wall callback eligibility'
        assert 0 < model['MinScale'] <= model['MaxScale'], 'Positive model scale'
        assert all(-.25 <= model['HitBox']['Min'][a] < 0 < model['HitBox']['Max'][a] <= .25 for a in ('X', 'Y', 'Z')), 'Compact collider'
        assert json.loads(common_bytes(model['Model']))['nodes'], 'Real native projectile host'
        texture_size(model['Texture'])
        channel_lights.append(model['Light'])
        total = concurrent = 0
        maximum_trail = 0
        kinds = set()
        for attachment in model['Particles']:
            kind = attachment['SystemId'].rsplit('_', 1)[1]
            assert kind in ('Core', 'Trail'), 'Explicit head or fading trail attachment'
            kinds.add(kind)
            assert not attachment['DetachedFromModel'], 'Spawner must track moving projectile'
            assert attachment['ClearParticlesOnRemove'] == (kind == 'Core'), 'Head vanishes; motes fade on impact'
            system = read(f"Server/Particles/StrangeMatter/{attachment['SystemId']}.particlesystem")
            assert ttl <= system['LifeSpan'] <= 4, 'Finite system covers actual flight'
            for entry in system['Spawners']:
                spawner = read(f"Server/Particles/StrangeMatter/Spawners/{entry['SpawnerId']}.particlespawner")
                total += spawner['TotalParticles']['Max']
                concurrent += spawner['MaxConcurrentParticles']
                assert 0 < spawner['LifeSpan'] <= 4 and 0 < spawner['TotalParticles']['Max'] <= 64, 'Established emitter budget'
                assert 0 < spawner['MaxConcurrentParticles'] <= spawner['TotalParticles']['Max']
                assert spawner['LightInfluence'] == 0 and spawner['RenderMode'] == 'BlendAdd'
                assert spawner['TrailSpawnerPositionMultiplier'] == (1 if kind == 'Trail' else 0)
                assert spawner['ParticleRotationInfluence'] == 'Billboard'
                if kind == 'Trail':
                    lifetime = spawner['ParticleLifeSpan']['Max']
                    maximum_trail = max(maximum_trail, lifetime)
                    assert 0 < lifetime <= .30 and not spawner['SpawnBurst']
                    assert spawner['LifeSpan'] >= ttl and spawner['TotalParticles']['Max'] >= spawner['SpawnRate']['Max'] * ttl, 'Trail covers complete flight'
                    assert spawner['Particle']['Animation']['100']['Opacity'] == 0
                    assert system['BoundingRadius'] >= config['LaunchForce'] * lifetime
                else:
                    assert spawner['ParticleLifeSpan']['Min'] >= ttl, 'Head stays visible through flight'
                width, height = texture_size(spawner['Particle']['Texture'])
                frame = spawner['Particle']['FrameSize']
                assert frame['Width'] > 0 and frame['Height'] > 0
                assert width % frame['Width'] == 0 and height % frame['Height'] == 0, 'Frame divides atlas'
        assert kinds == {'Core', 'Trail'}
        assert total <= 128 and concurrent <= 24, 'Bounded complete projectile effect'
        per_channel[channel] = {'totalParticleCap': total, 'concurrentParticleCap': concurrent,
                                'maximumFlightSeconds': ttl, 'maximumTrailSeconds': maximum_trail}
    assert channel_lights[0] != channel_lights[1], 'Channel colors stay distinct'
    preserved = None
    if audit_art:
        before = json.loads(Path(art_baseline).read_text(encoding='utf-8-sig'))
        for relative, digest in before['files'].items():
            assert hashlib.sha256((ROOT / relative).read_bytes()).hexdigest() == digest, f'Original artwork changed: {relative}'
        preserved = len(before['files'])
    return {'status': 'PASS', 'definitions': len(checked), 'channels': per_channel,
            'unchangedExistingBinaryArt': preserved}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--audit-art', action='store_true', help='Compare binary art with the optional local build snapshot.')
    parser.add_argument('--art-baseline', type=Path, default=ART_BASELINE, help='Local JSON hash record from the task snapshot.')
    args = parser.parse_args()
    print(json.dumps(validate(args.audit_art, args.art_baseline), indent=2))
