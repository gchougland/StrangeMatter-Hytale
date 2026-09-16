"""Bounds the visible tail and particle work of personal warp cooldown states."""
import unittest
from build_warp_gate_states import (ROOT, PARTICLES, SPAWNERS, CHANNELS, CADENCE,
                                    SYSTEM_LIFE, read, check_assets)


class WarpGateStates(unittest.TestCase):
    def test_generated_assets_match_current_original_inputs(self):
        self.assertEqual(30, len(check_assets()))

    def test_short_finite_lifetimes_and_particle_budgets(self):
        for channel in CHANNELS:
            for state, layers, count in (('Open', 5, 8), ('Closed', 3, 3)):
                system = read(PARTICLES / ('SM_Warp_Gate' + channel + '_' + state + '.particlesystem'))
                self.assertEqual(layers, len(system['Spawners']))
                self.assertGreater(system['LifeSpan'], CADENCE)
                self.assertLessEqual(system['LifeSpan'], .5)
                particles = 0
                for layer in system['Spawners']:
                    emitter = read(SPAWNERS / (layer['SpawnerId'] + '.particlespawner'))
                    self.assertTrue(emitter['SpawnBurst'])
                    self.assertEqual(0, emitter['LifeSpan'], 'One-shot emitters must survive their first client FX update')
                    self.assertEqual({'Min': 0, 'Max': 0}, emitter['WaveDelay'])
                    self.assertEqual(0, layer['StartDelay'])
                    self.assertEqual(1, layer['TotalSpawners'])
                    self.assertGreater(emitter['ParticleLifeSpan']['Min'], CADENCE)
                    self.assertLessEqual(emitter['ParticleLifeSpan']['Max'], .25)
                    self.assertEqual(emitter['TotalParticles'], emitter['SpawnRate'])
                    self.assertEqual(emitter['TotalParticles']['Max'], emitter['MaxConcurrentParticles'])
                    self.assertEqual(0, emitter['Particle']['Animation']['100']['Opacity'])
                    self.assertTrue((ROOT / 'src/main/resources/Common' / emitter['Particle']['Texture']).is_file())
                    particles += emitter['TotalParticles']['Max']
                self.assertEqual(count, particles)

    def test_first_client_update_has_time_to_emit_and_render(self):
        # This is a conservative scheduling contract, not a renderer simulation.
        # The former 10ms lifetime was already elapsed on the FIRST normal 60Hz
        # update. Allow even a 100ms initial FX update plus one whole particle life.
        for path, asset in check_assets().items():
            if path.suffix != '.particlesystem':
                continue
            for layer in asset['Spawners']:
                emitter = read(SPAWNERS / (layer['SpawnerId'] + '.particlespawner'))
                for fps in (10, 20, 30, 60, 120, 240):
                    first_update = 1 / fps
                    self.assertTrue(emitter['LifeSpan'] == 0 or emitter['LifeSpan'] > first_update)
                    self.assertGreaterEqual(asset['LifeSpan'], first_update + emitter['ParticleLifeSpan']['Max'])

    def test_closed_gate_is_a_stationary_seam_without_open_motion(self):
        for channel in CHANNELS:
            system = read(PARTICLES / ('SM_Warp_Gate' + channel + '_Closed.particlesystem'))
            for layer in system['Spawners']:
                self.assertNotIn('Vortex', layer['SpawnerId'])
                self.assertNotIn('Infall', layer['SpawnerId'])
                emitter = read(SPAWNERS / (layer['SpawnerId'] + '.particlespawner'))
                self.assertNotIn('Attractors', emitter)
                self.assertEqual(0, emitter['InitialVelocity']['Speed']['Max'])
                shape = emitter['Particle']['InitialAnimationFrame']['Scale']
                self.assertLess(shape['X']['Max'] / shape['Y']['Min'], .12)
                for frame in emitter['Particle']['Animation'].values():
                    self.assertNotIn('Rotation', frame)

    def test_open_state_preserves_textures_colors_and_aperture_proportions(self):
        for channel in CHANNELS:
            base = read(PARTICLES / ('SM_Warp_Gate' + channel + '.particlesystem'))
            current = read(PARTICLES / ('SM_Warp_Gate' + channel + '_Open.particlesystem'))
            for original, pulse in zip(base['Spawners'], current['Spawners'], strict=True):
                before = read(SPAWNERS / (original['SpawnerId'] + '.particlespawner'))
                after = read(SPAWNERS / (pulse['SpawnerId'] + '.particlespawner'))
                for key in ('Texture', 'FrameSize', 'InitialAnimationFrame'):
                    self.assertEqual(before['Particle'][key], after['Particle'][key])
                self.assertEqual(before['Particle']['Animation']['0']['Color'], after['Particle']['Animation']['0']['Color'])
                self.assertEqual(before['RenderMode'], after['RenderMode'])
                self.assertEqual(before['EmitOffset'], after['EmitOffset'])


if __name__ == '__main__':
    unittest.main()
