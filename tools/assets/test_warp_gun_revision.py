"""Current Warp Gun asset contracts, with an explicit optional preservation audit.

Normal tests permit later authored geometry, UV and color edits. They need only
Python's standard library. --audit-art <snapshot folder> is a historical check
for this scoped revision and is deliberately not part of the default tests.
"""
import argparse
import copy
import hashlib
import json
import math
from pathlib import Path
import unittest
import zipfile

from verify_texture_repack import decode_png, face_bounds, nodes

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / 'src/main/resources'
COMMON = RES / 'Common'
ITEM = RES / 'Server/Item/Items/StrangeMatter/SM_Warp_Gun.json'
MODEL = COMMON / 'Items/StrangeMatter/warp_gun.blockymodel'
TEXTURE = COMMON / 'Items/StrangeMatter/warp_gun.png'
ICON = COMMON / 'Icons/ItemsGenerated/SM_Warp_Gun.png'

# Native BlockyModelBoundsParser reads the exported float quaternion directly;
# JOML Quaternionf.transform divides by its squared norm. Five decimal export
# rounding is therefore harmless and must not require rewriting the artwork.
# For unit q and four errors |e_i| <= eps, ||q||_1 <= 2 gives
# | ||q+e||^2 - 1 | <= 4*eps + 4*eps^2, plus arithmetic noise.
QUATERNION_COMPONENT_ROUNDING = 0.5e-5
QUATERNION_NORM_SQUARED_TOLERANCE = (
    4 * QUATERNION_COMPONENT_ROUNDING + 4 * QUATERNION_COMPONENT_ROUNDING ** 2 + 1e-12)


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def validate_model(model, dimensions):
    width, height = dimensions
    assert width >= height >= 32, 'Atlas must be square or wide and at least32 pixels high'
    assert all(value & (value - 1) == 0 for value in dimensions), 'Atlas dimensions must be powers of two'
    ids = set()
    faces = 0
    for node in nodes(model):
        identity = str(node['id'])
        assert identity not in ids, 'Duplicate native node ID'
        ids.add(identity)
        for field in ('position', 'orientation'):
            assert all(isinstance(value, (int, float)) and math.isfinite(value)
                       for value in node.get(field, {}).values()), 'Nonfinite node transform'
        rotation = node.get('orientation', dict(x=0, y=0, z=0, w=1))
        assert abs(sum(rotation[axis] ** 2 for axis in 'xyzw') - 1) <= QUATERNION_NORM_SQUARED_TOLERANCE, 'Nonunit node rotation'
        shape = node.get('shape', {})
        if shape.get('type', 'none') == 'none':
            continue
        assert shape['type'] in ('box', 'quad'), 'Unsupported native primitive'
        axes = 'xy' if shape['type'] == 'quad' else 'xyz'
        size = shape['settings']['size']
        assert all(isinstance(size[axis], (int, float)) and math.isfinite(size[axis])
                   and size[axis] > 0 for axis in axes), 'Invalid native geometry size'
        for field in ('offset', 'stretch'):
            assert all(isinstance(value, (int, float)) and math.isfinite(value)
                       for value in shape.get(field, {}).values()), 'Nonfinite shape transform'
        layout = shape.get('textureLayout', {})
        assert layout, 'Visible geometry needs UV faces'
        for face, uv in layout.items():
            # The independent native UV helper includes a one texel sampling
            # gutter. Edge UVs remain legal, so test the actual rectangle here.
            left, top, right, bottom = face_bounds(shape, face, uv)
            assert 0 <= left + 1 < right - 1 <= width, 'Face UV outside atlas width'
            assert 0 <= top + 1 < bottom - 1 <= height, 'Face UV outside atlas height'
            faces += 1
    assert faces > 0, 'Empty gun model'
    return faces


def audit_art(folder):
    folder = Path(folder)
    before = read(folder / 'Common-before.json')
    allowed = {path.relative_to(COMMON).as_posix() for path in (MODEL, TEXTURE, ICON)}
    changed = []
    for name, digest in before.items():
        path = COMMON / name
        assert path.is_file(), 'Unrelated art was removed: ' + name
        if hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            changed.append(name)
    assert set(changed) <= allowed, 'Unrelated art changed: ' + str(set(changed) - allowed)
    with zipfile.ZipFile(folder / 'Targets.zip') as archive:
        assert ITEM.read_bytes() == archive.read(ITEM.relative_to(ROOT).as_posix()), 'Warp Gun mechanics changed'
        original = json.loads(archive.read(MODEL.relative_to(ROOT).as_posix()))
        before_grip = next(node for node in nodes(original) if node['name'] == 'pistol_grip')
        after_grip = next(node for node in nodes(read(MODEL)) if node['name'] == 'pistol_grip')
        for field in ('position', 'orientation'):
            assert before_grip[field] == after_grip[field], 'Existing grip direction or pivot changed'
    return {'preservationAuditPerformed': True, 'changedCommonFiles': changed,
            'unrelatedExistingCommonFilesPreserved': len(before) - len(allowed),
            'gunMechanicsAndGripPreserved': True}


def sample_quad():
    return {'nodes': [{'id': '1', 'position': dict(x=0, y=0, z=0),
                       'orientation': dict(x=0, y=0, z=0, w=1),
                       'shape': {'type': 'quad', 'settings': {'size': dict(x=8, y=4), 'normal': '-X'},
                                 'stretch': dict(x=.5, y=2, z=1),
                                 'textureLayout': {'front': {'offset': dict(x=10, y=10),
                                                               'mirror': dict(x=True, y=False), 'angle': 90}}}}]}


class WarpGunAssets(unittest.TestCase):
    def test_current_assets_are_native_valid(self):
        item = read(ITEM)
        self.assertEqual(COMMON / item['Model'], MODEL)
        self.assertEqual(COMMON / item['Texture'], TEXTURE)
        self.assertEqual(COMMON / item['Icon'], ICON)
        width, height, pixels = decode_png(TEXTURE.read_bytes())
        self.assertGreater(validate_model(read(MODEL), (width, height)), 0)
        self.assertTrue(any(pixels[3::4]), 'Texture has no visible pixels')
        icon_width, icon_height, icon_pixels = decode_png(ICON.read_bytes())
        self.assertEqual((icon_width, icon_height), (64, 64))
        self.assertTrue(any(icon_pixels[3::4]), 'Icon is empty')

    def test_native_signed_rotated_quad_is_editable(self):
        self.assertEqual(validate_model(sample_quad(), (64, 32)), 1)

    def test_real_outside_uv_is_rejected(self):
        model = sample_quad()
        model['nodes'][0]['shape']['textureLayout']['front']['offset']['x'] = 2
        with self.assertRaisesRegex(AssertionError, 'outside atlas width'):
            validate_model(model, (64, 32))

    def test_nonfinite_transform_is_rejected(self):
        model = sample_quad()
        model['nodes'][0]['position']['z'] = float('nan')
        with self.assertRaisesRegex(AssertionError, 'Nonfinite node'):
            validate_model(model, (64, 32))

    def test_five_decimal_editor_rotation_is_accepted_without_mutation(self):
        model = sample_quad()
        # The authored unit quaternion for Euler(20,45,45), rounded by the editor.
        rotation = dict(x=.004, y=.40958, z=.28679, w=.86602)
        self.assertGreater(abs(sum(value * value for value in rotation.values()) - 1), 1e-5)
        model['nodes'][0]['orientation'] = rotation
        before = copy.deepcopy(model)
        self.assertEqual(validate_model(model, (64, 32)), 1)
        self.assertEqual(model, before)

    def test_degenerate_and_materially_scaled_rotations_are_rejected(self):
        for rotation in (dict(x=0, y=0, z=0, w=0), dict(x=0, y=0, z=0, w=1.001),
                         dict(x=0, y=0, z=0, w=.999), dict(x=.5, y=.5, z=.5, w=.51)):
            with self.subTest(rotation=rotation):
                model = sample_quad()
                model['nodes'][0]['orientation'] = rotation
                with self.assertRaisesRegex(AssertionError, 'Nonunit node rotation'):
                    validate_model(model, (64, 32))

    def test_nonfinite_rotation_is_rejected(self):
        for value in (float('nan'), float('inf'), -float('inf')):
            with self.subTest(value=value):
                model = sample_quad()
                model['nodes'][0]['orientation']['w'] = value
                with self.assertRaisesRegex(AssertionError, 'Nonfinite node transform'):
                    validate_model(model, (64, 32))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--audit-art', type=Path)
    args, remaining = parser.parse_known_args()
    if args.audit_art:
        print(json.dumps(audit_art(args.audit_art), indent=2))
    unittest.main(argv=[__file__, *remaining])
