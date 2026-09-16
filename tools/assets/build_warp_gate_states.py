"""Author short warp aperture states from existing native particle textures.

Only the six *_Open/*_Closed systems and their dedicated spawners are written.
The original gate systems, painted sprites and stationary core models are inputs.
Run with --write to regenerate; the default checks the committed assets.
"""
import argparse
import copy
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PARTICLES = ROOT / 'src/main/resources/Server/Particles/StrangeMatter'
SPAWNERS = PARTICLES / 'Spawners'
CHANNELS = ('', '_Cyan', '_Purple')
CADENCE = .2
# Native one-shot emitters normally omit their lifetime: TotalParticles ends the
# burst. A 10ms emitter can expire before the client's first 30/60Hz FX update.
# Keep it alive until the finite system ends instead of depending on frame order.
EMITTER_LIFE = 0
PARTICLE_LIFE = .25
SYSTEM_LIFE = .5


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def fixed(value):
    return {'Min': value, 'Max': value}


def scale(x, y):
    return {'X': fixed(x), 'Y': fixed(y)}


def short_pulse(source, count, opacity=None, closed_size=None):
    data = copy.deepcopy(source)
    data.update(LifeSpan=EMITTER_LIFE, TotalParticles=fixed(count), MaxConcurrentParticles=count,
                ParticleLifeSpan=fixed(PARTICLE_LIFE), SpawnRate=fixed(count), SpawnBurst=True,
                WaveDelay=fixed(0))
    original = source['Particle']['Animation']
    peak = opacity if opacity is not None else original['12']['Opacity']
    first = {'Opacity': 0, 'Color': original['0']['Color'], 'Scale': scale(1, 1)}
    last = {'Opacity': 0}
    if closed_size:
        data.pop('Attractors', None)
        data['InitialVelocity']['Speed'] = fixed(0)
        data['EmitOffset'] = {axis: fixed(0) for axis in 'XYZ'}
        data['Particle']['InitialAnimationFrame']['Scale'] = scale(closed_size[0] / 4, closed_size[1] / 4)
        data['Particle']['InitialAnimationFrame'].pop('Rotation', None)
    else:
        # Preserve angular and expansion speeds rather than replaying a whole
        # original 1.25-second animation five times faster in a short pulse.
        fraction = PARTICLE_LIFE / ((source['ParticleLifeSpan']['Min'] + source['ParticleLifeSpan']['Max']) / 2)
        if 'Rotation' in original['100']:
            last['Rotation'] = {axis: {end: value * fraction for end, value in span.items()}
                                for axis, span in original['100']['Rotation'].items()}
        if 'Scale' in original['100']:
            last['Scale'] = {axis: {end: 1 + (value - 1) * fraction for end, value in span.items()}
                             for axis, span in original['100']['Scale'].items()}
    # Adjacent 200ms pulses overlap across their short fades, including a little
    # scheduling tolerance. The half-second system is a cleanup bound, not a
    # repeating emitter: its finite total budget can only produce one burst.
    data['Particle']['Animation'] = {'0': first, '9': {'Opacity': peak}, '90': {'Opacity': peak}, '100': last}
    return data


def expected_assets():
    assets = {}
    for channel in CHANNELS:
        base = 'SM_Warp_Gate' + channel
        original_system = read(PARTICLES / (base + '.particlesystem'))
        for state in ('Open', 'Closed'):
            system = copy.deepcopy(original_system)
            system.update(LifeSpan=SYSTEM_LIFE, Spawners=[])
            for layer in original_system['Spawners']:
                original_id = layer['SpawnerId']
                source = read(SPAWNERS / (original_id + '.particlespawner'))
                if state == 'Closed':
                    if original_id == 'SM_Warp_Event_Horizon' + channel:
                        data = short_pulse(source, 1, opacity=.80, closed_size=(.18, 3.9))
                    elif original_id == 'SM_Warp_Rim' + channel:
                        data = short_pulse(source, 1, opacity=.65, closed_size=(.30, 4.15))
                    elif original_id == 'SM_Warp_Outer_Rim' + channel:
                        data = short_pulse(source, 1, opacity=.22, closed_size=(.48, 4.3))
                    else:
                        continue
                else:
                    data = short_pulse(source, 4 if original_id == 'SM_Warp_Infall' + channel else 1)
                dedicated_id = original_id + '_' + state
                assets[SPAWNERS / (dedicated_id + '.particlespawner')] = data
                system['Spawners'].append(dict(layer, SpawnerId=dedicated_id, StartDelay=0,
                                               TotalSpawners=1))
            assets[PARTICLES / (base + '_' + state + '.particlesystem')] = system
    return assets


def check_assets():
    assets = expected_assets()
    for path, expected in assets.items():
        if not path.is_file() or read(path) != expected:
            raise AssertionError(f'{path.name}: missing or stale; regenerate with build_warp_gate_states.py --write')
    return assets


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write', action='store_true')
    args = parser.parse_args()
    if args.write:
        for path, data in expected_assets().items():
            path.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')
    check_assets()
    print('WARP_GATE_STATES PASS: 6 systems, 24 spawners; open 8 / closed 3 particles per 200ms pulse; '
          'frame-safe one-shot emitters, 250ms particles and 500ms system cleanup; existing textures/models unchanged.')


if __name__ == '__main__':
    main()
