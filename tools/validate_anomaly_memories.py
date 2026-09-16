"""Validate shipped native Memories assets and translations (standard library only).

Run against source resources by default, or --pack build/libs/<mod>.jar to check
the actual distributable. Native codec/provider/UI behavior belongs to the native
integration verification; this check verifies the client resources they require.
"""
import argparse
from pathlib import Path
import struct
import zipfile
import zlib

ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / 'src/main/resources'
CATEGORY = 'SM_Anomalies'
CATEGORY_DIR = 'Common/UI/Custom/Pages/Memories/categories/'
ENTRY_NAMES = ('Gravity', 'Temporal_Bloom', 'Energetic_Rift', 'Warp_Gate', 'Echoing_Shadow', 'Thoughtwell')
ENTRY_ICONS = tuple('Common/Icons/ItemsGenerated/SM_Anomaly_' + name + '.png' for name in ENTRY_NAMES)
LOCALE_KEYS = ('memories.categories.SM_Anomalies.title',) + tuple(
    'memories.entries.SM_Anomaly_' + name.upper() + '.' + field
    for name in ENTRY_NAMES for field in ('title', 'description'))


class Resources:
    def __init__(self, path):
        self.path = path
        self.archive = zipfile.ZipFile(path) if path.is_file() else None

    def exists(self, relative):
        return relative in self.archive.namelist() if self.archive else (self.path / relative).is_file()

    def read(self, relative):
        assert self.exists(relative), 'Missing Memories resource: ' + relative
        return self.archive.read(relative) if self.archive else (self.path / relative).read_bytes()

    def close(self):
        if self.archive:
            self.archive.close()


def rgba(data, label):
    """Decode all five PNG row filters; reject invalid RGBA data before pixel QA."""
    assert data[:8] == b'\x89PNG\r\n\x1a\n', label + ': expected PNG'
    offset = 8
    compressed = bytearray()
    header = None
    ended = False
    while offset < len(data):
        assert offset + 12 <= len(data), label + ': truncated chunk'
        size = struct.unpack_from('>I', data, offset)[0]
        kind = data[offset + 4:offset + 8]
        chunk = data[offset + 8:offset + 8 + size]
        assert offset + size + 12 <= len(data), label + ': truncated chunk payload'
        crc = struct.unpack_from('>I', data, offset + 8 + size)[0]
        assert zlib.crc32(kind + chunk) & 0xffffffff == crc, label + ': invalid chunk CRC'
        offset += size + 12
        if kind == b'IHDR':
            assert header is None and size == 13, label + ': invalid PNG header'
            header = struct.unpack('>IIBBBBB', chunk)
        elif kind == b'IDAT':
            compressed.extend(chunk)
        elif kind == b'IEND':
            ended = True
            break
    assert ended and header is not None and compressed, label + ': incomplete PNG'
    width, height, depth, color, compression, filtering, interlace = header
    assert 0 < width <= 1024 and 0 < height <= 1024, label + ': unexpected icon dimensions'
    assert (depth, color, compression, filtering, interlace) == (8, 6, 0, 0, 0), label + ': expected noninterlaced 8-bit RGBA'
    stride = width * 4
    raw = zlib.decompress(compressed)
    assert len(raw) == (stride + 1) * height, label + ': invalid scanline length'
    previous = bytearray(stride)
    pixels = bytearray()
    for y in range(height):
        at = y * (stride + 1)
        mode = raw[at]
        assert mode <= 4, label + ': invalid PNG row filter'
        row = bytearray(raw[at + 1:at + 1 + stride])
        for i in range(stride):
            left, up = row[i - 4] if i >= 4 else 0, previous[i]
            corner = previous[i - 4] if i >= 4 else 0
            p = left + up - corner
            distances = (abs(p - left), abs(p - up), abs(p - corner))
            paeth = (left, up, corner)[distances.index(min(distances))]
            correction = (0, left, up, (left + up) // 2, paeth)[mode]
            row[i] = (row[i] + correction) & 255
        pixels.extend(row)
        previous = row
    return width, height, pixels


def glyph(data, label, dimensions):
    width, height, pixels = rgba(data, label)
    assert (width, height) == dimensions, f'{label}: expected {dimensions}, got {(width, height)}'
    alpha = pixels[3::4]
    visible = sum(value >= 32 for value in alpha)
    assert visible >= width * height * .01, label + ': empty or effectively invisible glyph'
    assert alpha.count(0) >= width * height * .01, label + ': missing transparent background'
    assert max(alpha) >= 128, label + ': glyph lacks visible foreground'
    return pixels


def visually_distinct(first, second, label):
    assert len(first) == len(second)
    changed = 0
    for i in range(0, len(first), 4):
        # Ignore differences in invisible RGB. Completion must differ on screen.
        delta = max(abs(first[i + c] * first[i + 3] - second[i + c] * second[i + 3]) / 255
                    for c in range(3))
        delta = max(delta, abs(first[i + 3] - second[i + 3]))
        changed += delta >= 8
    assert changed >= len(first) / 4 * .01, label + ': completion glyph is visually indistinguishable'


def validate(pack):
    category_pixels = []
    aliases = 0
    for suffix in ('', 'Complete'):
        stem = CATEGORY_DIR + CATEGORY + suffix
        category_pixels.append(glyph(pack.read(stem + '@2x.png'), stem + '@2x.png', (256, 256)))
        # Native AssetImage requests .png and supports its high-resolution @2x
        # sibling. Explicit 128px aliases are optional, but must be valid if shipped.
        if pack.exists(stem + '.png'):
            glyph(pack.read(stem + '.png'), stem + '.png', (128, 128))
            aliases += 1
    assert aliases in (0, 2), 'Ship either both native-size category aliases or neither'
    visually_distinct(*category_pixels, CATEGORY)
    entry_pixels = [glyph(pack.read(path), path, (64, 64)) for path in ENTRY_ICONS]
    for i, first in enumerate(entry_pixels):
        for j in range(i):
            visually_distinct(first, entry_pixels[j], ENTRY_NAMES[i] + '/' + ENTRY_NAMES[j])

    language = pack.read('Server/Languages/en-US/server.lang').decode('utf-8-sig')
    entries = {}
    for line in language.splitlines():
        if not line.strip() or line.lstrip().startswith('#') or '=' not in line:
            continue
        key, value = line.split('=', 1)
        key = key.strip()
        if key in LOCALE_KEYS:
            assert key not in entries, 'Duplicate Memories translation: ' + key
            entries[key] = value.strip()
    for key in LOCALE_KEYS:
        assert entries.get(key), 'Missing/empty Memories translation: ' + key
        assert not entries[key].startswith('server.'), 'Unresolved Memories translation value: ' + key
    return aliases


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pack', type=Path, default=RESOURCES, help='Resource directory or built mod JAR/ZIP')
    args = parser.parse_args()
    pack = Resources(args.pack)
    try:
        aliases = validate(pack)
    finally:
        pack.close()
    print(f'Anomaly Memories assets PASS: 2 transparent category glyphs, {aliases} base aliases, '
          f'6 distinct entry icons, {len(LOCALE_KEYS)} nonempty native translation keys; {args.pack}')


if __name__ == '__main__':
    main()
