"""Pack imagegen masters at the native memory-category @2x resolution.

Only resampling/PNG encoding occurs here; glyph design and completion treatment
are authored in the generated masters. Their original RGBA alpha is preserved.
"""
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'tools/assets/source/anomaly-memories'
TARGET = ROOT / 'src/main/resources/Common/UI/Custom/Pages/Memories/categories'


def main():
    TARGET.mkdir(parents=True, exist_ok=True)
    for source, name in (
        ('anomalies-regular.png', 'SM_Anomalies@2x.png'),
        ('anomalies-complete.png', 'SM_AnomaliesComplete@2x.png'),
    ):
        with Image.open(SOURCE / source) as image:
            assert image.mode == 'RGBA', (source, 'Generated transparency required')
            minimum, maximum = image.getchannel('A').getextrema()
            assert minimum == 0 and maximum >= 250, source
            image.resize((256, 256), Image.Resampling.LANCZOS).save(TARGET / name)
        print('Exported', TARGET / name)


if __name__ == '__main__':
    main()
