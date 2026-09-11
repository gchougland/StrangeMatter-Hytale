"""Synthetic signed UV and failure regressions; never edits project resources."""
import copy
import json
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile
import zlib
import verify_texture_repack as verifier


def png(width, height, rgba):
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)
    rows = b''.join(b'\0' + rgba[y * width * 4:(y + 1) * width * 4] for y in range(height))
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(rows)) + chunk(b'IEND', b''))


class RepackVerificationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name); self.resources = self.root / 'resources'
        (self.resources / 'Common').mkdir(parents=True)
        self.baseline = self.root / 'before.zip'; self.manifest = self.root / 'plan.json'

    def fixture(self, angle=0, mx=False, my=False):
        # Deliberately touches and crosses the original atlas edge under mirrors
        # and rotation: the copy must retain clamped samples and their alpha.
        uv = {'offset': {'x': 0, 'y': 0}, 'angle': angle, 'mirror': {'x': mx, 'y': my}}
        self.model = {'metadata': {'authored': True}, 'nodes': [{'id': '1', 'name': 'Face',
                      'position': {'x': 3, 'y': 7, 'z': -2}, 'shape': {'type': 'quad',
                      'stretch': {'x': 1.5, 'y': -1, 'z': 1},
                      'settings': {'size': {'x': 4.25, 'y': 3.5}, 'normal': '+Z'},
                      'textureLayout': {'front': uv}}}]}
        self.current_model = copy.deepcopy(self.model)
        self.current_model['nodes'][0]['shape']['textureLayout']['front']['offset'] = {'x': 12, 'y': 12}
        self.source_pixels = bytes(v for y in range(64) for x in range(64)
                                   for v in ((x * 19) % 256, (y * 29) % 256, x ^ y, (x + y) % 256))
        self.packed_pixels = bytearray()
        for y in range(32):
            for x in range(32):
                old_x, old_y = max(0, x - 12), max(0, y - 12)
                index = (old_y * 64 + old_x) * 4
                self.packed_pixels.extend(self.source_pixels[index:index + 4])
        self.plan = {'groups': [{'models': ['model.blockymodel'], 'textures': ['atlas.png'],
                     'beforeSizes': {'atlas.png': [64, 64]}, 'afterSize': [32, 32]}]}
        self.original = {'Common/model.blockymodel': json.dumps(self.model).encode(),
                         'Common/atlas.png': png(64, 64, self.source_pixels),
                         'Common/untouched.png': png(64, 64, self.source_pixels)}
        self.save()

    def save(self):
        with zipfile.ZipFile(self.baseline, 'w') as archive:
            for key, value in self.original.items(): archive.writestr(key, value)
        self.manifest.write_text(json.dumps(self.plan), encoding='utf8')
        (self.resources / 'Common/model.blockymodel').write_text(json.dumps(self.current_model), encoding='utf8')
        (self.resources / 'Common/atlas.png').write_bytes(png(32, 32, self.packed_pixels))
        (self.resources / 'Common/untouched.png').write_bytes(self.original['Common/untouched.png'])

    def verify(self):
        return verifier.verify(self.baseline, self.manifest, self.resources)

    def test_signed_rotations_fractional_size_and_clamped_rgba_are_preserved(self):
        for angle in (0, 90, 180, 270):
            for mx in (False, True):
                for my in (False, True):
                    with self.subTest(angle=angle, mx=mx, my=my):
                        self.fixture(angle, mx, my)
                        result = self.verify()
                        self.assertEqual(result['faces'], 1)
                        self.assertEqual(result['unlistedCommonFilesByteIdentical'], 1)

    def test_palette_png_depths_and_partial_transparency(self):
        def chunk(kind, data):
            return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)
        for depth in (1, 2, 4, 8):
            count = 1 << depth
            colors = [bytes((i, (i * 3) & 255, (i * 7) & 255)) for i in range(count)]
            # The last palette entry omits tRNS and must default to fully opaque.
            alpha = bytes((i * 23) & 255 for i in range(count - 1))
            indexes = list(range(count)); packed = bytearray((count * depth + 7) // 8)
            for x, index in enumerate(indexes): packed[x * depth // 8] |= index << (8 - depth - x * depth % 8)
            raw = (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', count, 1, depth, 3, 0, 0, 0))
                   + chunk(b'PLTE', b''.join(colors)) + chunk(b'tRNS', alpha)
                   + chunk(b'IDAT', zlib.compress(b'\0' + packed)) + chunk(b'IEND', b''))
            expected = b''.join(colors[i] + bytes((alpha[i] if i < len(alpha) else 255,)) for i in indexes)
            self.assertEqual(verifier.decode_png(raw), (count, 1, expected))

    def test_reject_changed_filtering_margin_even_outside_face(self):
        self.fixture()
        index = (11 * 32 + 11) * 4
        self.packed_pixels[index] ^= 1
        self.save()
        with self.assertRaisesRegex(AssertionError, 'filtering margin'): self.verify()

    def test_reject_transparent_rgb_changes(self):
        self.fixture()
        index = (12 * 32 + 12) * 4
        self.assertEqual(self.packed_pixels[index + 3], 0)
        self.packed_pixels[index] ^= 1
        self.save()
        with self.assertRaisesRegex(AssertionError, 'filtering margin'): self.verify()

    def test_reject_geometry_or_orientation_changes(self):
        for edit in ('geometry', 'uv'):
            self.fixture()
            if edit == 'geometry': self.current_model['nodes'][0]['position']['y'] += .01
            else: self.current_model['nodes'][0]['shape']['textureLayout']['front']['mirror']['x'] = True
            self.save()
            with self.assertRaisesRegex(AssertionError, 'beyond UV translation'): self.verify()

    def test_reject_fractional_translation(self):
        self.fixture(); self.current_model['nodes'][0]['shape']['textureLayout']['front']['offset']['x'] += .5
        self.save()
        with self.assertRaisesRegex(AssertionError, 'sampling phase'): self.verify()

    def test_each_working_texture_variant_is_checked(self):
        self.fixture()
        self.original['Common/working.png'] = png(64, 64, self.source_pixels)
        self.plan['groups'][0]['textures'].append('working.png')
        self.plan['groups'][0]['beforeSizes']['working.png'] = [64, 64]
        self.save(); (self.resources / 'Common/working.png').write_bytes(png(32, 32, self.packed_pixels))
        self.assertEqual(self.verify()['faceTextureVariants'], 2)
        wrong = bytearray(self.packed_pixels); wrong[(13 * 32 + 13) * 4 + 3] ^= 1
        (self.resources / 'Common/working.png').write_bytes(png(32, 32, wrong))
        with self.assertRaisesRegex(AssertionError, 'working.png'): self.verify()

    def test_declared_identical_texture_alias_is_allowed(self):
        self.fixture()
        self.original['Common/copy.png'] = self.original['Common/atlas.png']
        self.plan['groups'][0]['textures'].append('copy.png')
        self.plan['groups'][0]['beforeSizes']['copy.png'] = [64, 64]
        self.plan['textureAliases'] = {'copy.png': 'atlas.png'}
        self.save()
        self.assertEqual(self.verify()['aliases'], 1)

    def test_reject_unlisted_art_change_or_addition(self):
        self.fixture()
        path = self.resources / 'Common/untouched.png'; path.write_bytes(png(32, 32, self.packed_pixels))
        with self.assertRaisesRegex(AssertionError, 'Unlisted Common'): self.verify()
        self.save(); (self.resources / 'Common/unrequested.png').write_bytes(path.read_bytes())
        with self.assertRaisesRegex(AssertionError, 'Unlisted Common resource added'): self.verify()

    def test_reject_omitted_shared_model_consumer(self):
        self.fixture()
        extra = {'Model': 'unlisted.blockymodel', 'Texture': 'atlas.png'}
        self.original['Server/Models/Shared.json'] = json.dumps(extra).encode()
        self.save()
        with self.assertRaisesRegex(AssertionError, 'omitted model consumer'): self.verify()

    def test_inherited_working_texture_binding_is_checked(self):
        self.fixture()
        item = {'BlockType': {'CustomModel': 'model.blockymodel', 'State': {'Definitions': {
                'Working': {'CustomModelTexture': [{'Texture': 'atlas.png'}]}}}}}
        self.original['Server/Item.json'] = json.dumps(item).encode()
        self.save()
        (self.resources / 'Server').mkdir(exist_ok=True)
        (self.resources / 'Server/Item.json').write_text(json.dumps(item), encoding='utf8')
        self.assertEqual(self.verify()['nativeModelTextureBindingsChecked'], 1)

    def test_reject_non_power_of_two_output(self):
        self.fixture(); self.plan['groups'][0]['afterSize'] = [96, 32]
        self.save()
        with self.assertRaisesRegex(AssertionError, 'powers of two'): self.verify()

    def test_reject_omitted_state_model_inheriting_parent_texture(self):
        self.fixture()
        item = {'BlockType': {'CustomModel': 'model.blockymodel',
                'CustomModelTexture': [{'Texture': 'atlas.png'}], 'State': {'Definitions': {
                'Connection01': {'CustomModel': 'unlisted_state.blockymodel'}}}}}
        self.original['Server/Item.json'] = json.dumps(item).encode()
        self.save(); (self.resources / 'Server').mkdir(exist_ok=True)
        (self.resources / 'Server/Item.json').write_text(json.dumps(item), encoding='utf8')
        with self.assertRaisesRegex(AssertionError, 'omitted model consumer'): self.verify()

    def test_reject_tall_output(self):
        self.fixture(); self.plan['groups'][0]['afterSize'] = [32, 64]
        self.save()
        with self.assertRaisesRegex(AssertionError, 'square or wider'): self.verify()


if __name__ == '__main__':
    unittest.main()
