import unittest
from validate_shared_ui import validate, png_size, UI


class SharedUiTests(unittest.TestCase):
    def test_current_shared_ui_and_categories(self):
        report=validate()
        self.assertFalse(report['historicalArtAudited'])
        self.assertGreaterEqual(report['buttonPatches'],12)

    def test_patch_assets_are_native_scalable_dimensions(self):
        for path in (UI/'Buttons').glob('*.png'):
            width,height=png_size(path)
            self.assertGreaterEqual(width,32)
            self.assertGreaterEqual(height,32)
            self.assertGreaterEqual(width,height)


if __name__=='__main__':unittest.main()
