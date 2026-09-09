"""Regression cases for client-rejected art sizes and incomplete native content."""
import json
from pathlib import Path
from unittest.mock import patch
import unittest

import validate_presentation as presentation

RESOURCES = presentation.ROOT / 'src/main/resources'


class PresentationValidationTest(unittest.TestCase):
    def assert_rejected(self, report, diagnostic):
        self.assertEqual('FAIL', report['status'])
        self.assertTrue(any(diagnostic in error for error in report['errors']), report['errors'])

    def override_json(self, filename, change):
        original = Path.read_text

        def read(path, *args, **kwargs):
            text = original(path, *args, **kwargs)
            if path.name == filename:
                data = json.loads(text)
                change(data)
                return json.dumps(data)
            return text

        return patch.object(Path, 'read_text', read)

    def test_current_assets_accept_native_particle_sizes(self):
        report = presentation.validate(RESOURCES)
        self.assertEqual([], report['errors'])
        self.assertGreater(report['particleSprites'], 0)
        self.assertEqual(64, report['conduitMasks'])

    def test_reject_previous_16_pixel_shard_atlas(self):
        original = presentation.png_size
        with patch.object(presentation, 'png_size', side_effect=lambda p: (256, 16) if p.name == 'gravitic_shard.png' else original(p)):
            self.assert_rejected(presentation.validate(RESOURCES), 'atlas 256x16')

    def test_reject_previous_128_pixel_inventory_icon(self):
        original = presentation.png_size
        with patch.object(presentation, 'png_size', side_effect=lambda p: (128, 128) if p.name == 'SM_Field_Scanner.png' else original(p)):
            self.assert_rejected(presentation.validate(RESOURCES), 'icon 128x128')

    def test_reject_missing_connection_state(self):
        with self.override_json('SM_Resonant_Conduit.json', lambda d: d['BlockType']['State']['Definitions'].pop('Connection63')):
            self.assert_rejected(presentation.validate(RESOURCES), 'Incomplete conduit masks')

    def test_reject_workbench_recipe_regression(self):
        def move(data):
            data['Recipe']['BenchRequirement'][0]['Id'] = 'Workbench'
        with self.override_json('SM_Field_Scanner.json', move):
            self.assert_rejected(presentation.validate(RESOURCES), 'recipe remains at the wrong station')

    def test_reject_missing_creative_membership(self):
        with self.override_json('SM_Field_Scanner.json', lambda d: d['Categories'].remove('SM_StrangeMatter.All')):
            self.assert_rejected(presentation.validate(RESOURCES), 'missing creative category')

    def test_reject_previous_childless_creative_tab(self):
        with self.override_json('SM_StrangeMatter.json', lambda d: d.pop('Children')):
            self.assert_rejected(presentation.validate(RESOURCES), 'Creative root requires selectable child categories')

    def test_reject_unknown_recipe_gate_regression(self):
        with self.override_json('SM_Field_Scanner.json', lambda d: d['Recipe'].pop('KnowledgeRequired')):
            self.assert_rejected(presentation.validate(RESOURCES), 'crafting knowledge gate')

    def test_reject_missing_native_creature_atlas(self):
        with self.override_json('SM_Thoughtwell_Phantom_Wolf.json', lambda d: d.update(Texture='NPC/Missing/Thoughtwell.png')):
            self.assert_rejected(presentation.validate(RESOURCES), 'NPC')


if __name__ == '__main__':
    unittest.main()
