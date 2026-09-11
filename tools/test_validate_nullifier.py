"""Focused semantic regressions. Does not mutate production assets."""
import copy, unittest
import validate_nullifier as validator

class NullifierTests(unittest.TestCase):
    def test_current_assets(self):
        report=validator.validate();self.assertEqual('PASS',report['status'])
        self.assertFalse(report['historicalArtAuditPerformed'])
        self.assertIsNone(report['historicalArtFilesChecked'])

    def test_manual_geometry_edits_are_not_historical_hash_locked(self):
        model=validator.read(validator.MODEL);model['nodes'][0]['position']['x']+=.125
        validator.validate_model(model,validator.png(validator.BASE))

    def test_bad_uv_and_missing_animation_binding_are_rejected(self):
        model=validator.read(validator.MODEL);model['nodes'][0]['shape']['textureLayout']['front']['offset']['x']=99999
        with self.assertRaisesRegex(AssertionError,'UV rectangle escapes'):
            validator.validate_model(model,validator.png(validator.BASE))
        animation=validator.read(validator.ANIMATION)
        with self.assertRaisesRegex(AssertionError,'missing model node'):
            validator.validate_animation(animation,set())

    def test_long_lived_or_excessive_particles_are_rejected(self):
        emitter=validator.read(validator.PARTICLES/'Spawners/SM_Nullifier_Motes.particlespawner')
        too_long=copy.deepcopy(emitter);too_long['LifeSpan']=10
        with self.assertRaisesRegex(AssertionError,'does not terminate'):validator.validate_spawner(too_long)
        too_many=copy.deepcopy(emitter);too_many['TotalParticles']['Max']=100
        with self.assertRaisesRegex(AssertionError,'particle budget'):validator.validate_spawner(too_many)

if __name__=='__main__':unittest.main()
