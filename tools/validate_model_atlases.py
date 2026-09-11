"""Validate current model atlas sizes, independently of any packing history or manifest."""
import argparse
import json
from pathlib import Path
import struct
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools/assets'))
from verify_texture_repack import texture_bindings


def validate_size(width, height, label):
    if any(not isinstance(n, int) or n < 32 or n & (n - 1) for n in (width, height)):
        raise AssertionError(label + ': model atlas dimensions must be powers of two, at least 32')
    if width < height:
        raise AssertionError(label + ': model atlas must be square or wider than it is tall')


def validate(resources):
    associations = set()
    for path in (resources / 'Server').rglob('*.json'):
        for model, texture, _ in texture_bindings(json.loads(path.read_text(encoding='utf-8-sig'))):
            if (resources / model).is_file(): associations.add((model, texture))
    if not associations: raise AssertionError('No local native model atlas associations found')
    textures = {}
    for model, texture in sorted(associations):
        if texture in textures: continue
        path = resources / texture
        if not path.is_file(): raise AssertionError('Missing model atlas: ' + texture + ' for ' + model)
        raw = path.read_bytes()
        if raw[:8] != b'\x89PNG\r\n\x1a\n': raise AssertionError('Model atlas is not a PNG: ' + texture)
        width, height = struct.unpack('>II', raw[16:24]); validate_size(width, height, texture)
        textures[texture] = [width, height]
    return {'status': 'PASS', 'atlases': len(textures), 'models': len({m for m, _ in associations}),
            'nativeModelTexturePairs': len(associations), 'powerOfTwo': True, 'squareOrWide': True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--resources', type=Path, default=ROOT / 'src/main/resources')
    args = parser.parse_args()
    print(json.dumps(validate(args.resources), indent=2))


if __name__ == '__main__': main()
