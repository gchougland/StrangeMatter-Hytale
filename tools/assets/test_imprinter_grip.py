"""Focused stdlib checks for the Imprinter grip and editable authored contents."""
import copy
import math
import unittest
from fit_imprinter_grip import MODEL, VIEWS, empty_shape, fit, read
from test_held_light_fix import add, geometry, mm, mv, player


class ImprinterGripTests(unittest.TestCase):
    def test_wrapper_preserves_authored_nodes_and_turns_below_native_hand(self):
        shape = empty_shape()
        shape.update(type='box', offset={'x': 2, 'y': -1, 'z': 3},
                     settings={'size': {'x': 7, 'y': 11, 'z': 2}},
                     textureLayout={'front': {'offset': {'x': 34, 'y': 28}, 'mirror': {'x': True}, 'angle': 90}})
        original = {'format': 'prop', 'lod': 'auto', 'nodes': [
            {'id': '17', 'name': 'Authored_Instrument', 'position': {'x': 3, 'y': 8, 'z': 5},
             'orientation': {'x': 0, 'y': 0, 'z': 0, 'w': 1}, 'shape': shape}]}
        before = copy.deepcopy(original)
        fitted = fit(original)
        self.assertEqual(original, before)
        self.assertEqual(fitted['nodes'][0]['children'][0]['children'], original['nodes'])
        self.assertEqual({k: v for k, v in fitted.items() if k != 'nodes'}, {'format': 'prop', 'lod': 'auto'})
        self.assertEqual(fit(fitted), fitted)
        _, bones = geometry(player())
        rotation, position = bones['R-Attachment']
        actual, _ = geometry({'nodes': fitted['nodes'][0]['children']}, rotation, position)
        plain, _ = geometry(original)
        turn = ((-1, 0, 0), (0, 1, 0), (0, 0, -1))
        expected = [add(position, mv(mm(rotation, turn), point)) for point in plain]
        self.assertEqual(len(actual), len(expected))
        for got, wanted in zip(actual, expected):
            for a, b in zip(got, wanted):
                self.assertAlmostEqual(a, b, places=7)

    def test_current_hand_boundary_retains_upright_reversed_front(self):
        model = read(MODEL)
        self.assertEqual(len(model['nodes']), 1)
        root = model['nodes'][0]
        self.assertEqual(root['name'], 'R-Attachment')
        self.assertEqual(root['shape']['type'], 'none')
        self.assertTrue(root['shape']['settings']['isPiece'])
        self.assertEqual(root['orientation'], {'x': 0, 'y': 0, 'z': 0, 'w': 1})
        grip = root['children'][0]
        self.assertEqual(grip['name'], 'SM_Imprinter_Grip')
        self.assertTrue(grip['children'])
        _, bones = geometry({'nodes': [grip]})
        rotation, _ = bones[grip['name']]
        self.assertEqual(mv(rotation, (0, 0, 1)), (0, 0, -1))
        self.assertEqual(mv(rotation, (0, 1, 0)), (0, 1, 0))

    def test_future_icon_stays_on_instrument_front(self):
        yaw = math.radians(read(VIEWS)['SM_Echoform_Imprinter']['yaw'])
        _, bones = geometry(read(MODEL))
        normal = mv(bones['SM_Imprinter_Grip'][0], (0, 0, 1))
        camera = (math.sin(yaw), 0, math.cos(yaw))
        self.assertGreater(sum(a * b for a, b in zip(normal, camera)), .5)


if __name__ == '__main__':
    unittest.main()
