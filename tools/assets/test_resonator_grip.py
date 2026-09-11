"""Native hand binding and preservation of editable, multi-root resonator art."""
import copy
import json
import unittest

from fit_resonator_grip import MODEL, empty_shape, fit
from test_held_light_fix import add, geometry, mm, mv, player


class ResonatorGripTests(unittest.TestCase):
    def test_preserves_all_authored_roots_and_is_idempotent(self):
        nodes = []
        for index, point in enumerate(((2, 8, 3), (-6, 11, -5))):
            shape = empty_shape()
            shape.update(type='box', settings={'size': {'x': 6, 'y': 12, 'z': 3}},
                         offset={'x': 1, 'y': -2, 'z': 3},
                         textureLayout={'front': {'offset': {'x': 9, 'y': 16}}})
            nodes.append({'id': str(index + 1), 'name': 'Authored_' + str(index),
                          'position': dict(zip('xyz', point)),
                          'orientation': {'x': 0, 'y': 0, 'z': 0, 'w': 1}, 'shape': shape})
        original = {'nodes': nodes, 'lod': 'auto'}
        backup = copy.deepcopy(original)
        fitted = fit(original)
        self.assertEqual(original, backup)
        self.assertEqual(fitted['nodes'][0]['children'][0]['children'], nodes)
        self.assertEqual(fit(fitted), fitted)
        _, bones = geometry(player())
        hand_rotation, hand_position = bones['R-Attachment']
        actual, _ = geometry({'nodes': fitted['nodes'][0]['children']}, hand_rotation, hand_position)
        before, _ = geometry(original)
        turn = ((-1, 0, 0), (0, 1, 0), (0, 0, -1))
        expected = [add(hand_position, mv(mm(hand_rotation, turn), point)) for point in before]
        for got, want in zip(actual, expected):
            for x, y in zip(got, want):
                self.assertAlmostEqual(x, y, places=7)

    def test_current_screen_faces_same_way_as_corrected_scanner(self):
        model = json.loads(MODEL.read_text(encoding='utf-8-sig'))
        self.assertEqual(len(model['nodes']), 1)
        attachment = model['nodes'][0]
        self.assertEqual(attachment['name'], 'R-Attachment')
        self.assertTrue(attachment['shape']['settings']['isPiece'])
        self.assertEqual(attachment['shape']['type'], 'none')
        self.assertEqual(attachment['orientation'], {'x': 0, 'y': 0, 'z': 0, 'w': 1})
        _, bones = geometry({'nodes': attachment['children']})
        rotation, _ = bones['SM_Resonator_Grip']
        self.assertEqual(mv(rotation, (0, 0, 1)), (0, 0, -1))
        self.assertEqual(mv(rotation, (0, 1, 0)), (0, 1, 0))
        self.assertTrue(attachment['children'][0]['children'], 'Authored art must remain present')


if __name__ == '__main__':
    unittest.main()
