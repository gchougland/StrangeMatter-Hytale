"""Power of two atlas policy regressions without resource edits."""
import json
from pathlib import Path
import struct
import tempfile
import unittest
from validate_model_atlases import validate_size, validate


class AtlasPolicyTests(unittest.TestCase):
    def test_current_model_atlases(self):
        validate(Path(__file__).resolve().parents[1] / 'src/main/resources')

    def test_square_and_wide_native_sizes_are_allowed(self):
        for width, height in ((32, 32), (64, 32), (256, 128), (512, 512), (1024, 256)):
            validate_size(width, height, 'fixture')

    def test_non_power_of_two_and_tall_sizes_are_rejected(self):
        for size in ((16, 16), (96, 32), (256, 96), (128, 256)):
            with self.subTest(size=size), self.assertRaises(AssertionError):
                validate_size(*size, 'fixture')

    def test_native_state_atlases_checked_without_touching_ui_or_particles(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); (root / 'Common').mkdir(); (root / 'Server').mkdir()
            (root / 'Common/model.blockymodel').write_text('{"nodes":[]}', encoding='utf8')
            def header(width, height): return b'\x89PNG\r\n\x1a\n' + b'\0\0\0\rIHDR' + struct.pack('>II', width, height)
            (root / 'Common/idle.png').write_bytes(header(64, 32))
            (root / 'Common/working.png').write_bytes(header(64, 32))
            (root / 'Common/ui.png').write_bytes(header(90, 400))
            item = {'BlockType': {'CustomModel': 'model.blockymodel',
                    'CustomModelTexture': [{'Texture': 'idle.png'}], 'State': {'Definitions': {
                        'Working': {'CustomModelTexture': [{'Texture': 'working.png'}]}}}}}
            (root / 'Server/item.json').write_text(json.dumps(item), encoding='utf8')
            self.assertEqual(validate(root)['atlases'], 2)
            (root / 'Common/working.png').write_bytes(header(32, 64))
            with self.assertRaisesRegex(AssertionError, 'square or wider'):
                validate(root)


if __name__ == '__main__': unittest.main()
