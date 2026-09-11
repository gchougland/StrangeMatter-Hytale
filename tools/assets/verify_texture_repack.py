"""Independent lossless model atlas verification against an immutable resource ZIP.

No packing implementation is imported. Geometry and all UV fields except integer
offset translations must remain exact. RGBA sampling includes a one texel border
around each native signed UV rectangle, with the original atlas edge clamped.
"""
import argparse
import copy
import json
import math
from pathlib import Path, PurePosixPath
import struct
import zipfile
import zlib

ROOT = Path(__file__).resolve().parents[2]


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def common_path(value):
    value = str(value).replace('\\', '/').removeprefix('src/main/resources/').removeprefix('Common/')
    path = PurePosixPath(value)
    require(not path.is_absolute() and path.parts and all(p not in ('..', '') for p in path.parts), 'Unsafe Common path: ' + value)
    return 'Common/' + path.as_posix()


def decode_png(raw):
    """Decode native 8-bit RGB/RGBA/gray and 1/2/4/8-bit palette PNGs without Pillow."""
    require(raw[:8] == b'\x89PNG\r\n\x1a\n', 'Not a PNG')
    index = 8; image = bytearray(); palette = None; transparency = b''; header = None
    while index < len(raw):
        length = struct.unpack_from('>I', raw, index)[0]
        kind = raw[index + 4:index + 8]; chunk = raw[index + 8:index + 8 + length]
        crc = struct.unpack_from('>I', raw, index + 8 + length)[0]
        require(zlib.crc32(kind + chunk) & 0xffffffff == crc, 'PNG CRC mismatch')
        if kind == b'IHDR': header = struct.unpack('>IIBBBBB', chunk)
        elif kind == b'IDAT': image.extend(chunk)
        elif kind == b'PLTE': palette = [chunk[i:i + 3] for i in range(0, len(chunk), 3)]
        elif kind == b'tRNS': transparency = chunk
        elif kind == b'IEND': break
        index += length + 12
    require(header is not None, 'PNG header absent')
    width, height, depth, color, compression, filtering, interlace = header
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}.get(color)
    require(width > 0 and height > 0 and channels and compression == filtering == interlace == 0, 'Unsupported PNG header')
    require(depth == 8 or color in (0, 3) and depth in (1, 2, 4), 'Unsupported PNG bit depth')
    stride = (width * channels * depth + 7) // 8; bpp = max(1, (channels * depth + 7) // 8)
    data = zlib.decompress(image)
    require(len(data) == height * (stride + 1), 'PNG scanline size mismatch')
    prior = bytearray(stride); pixels = bytearray(); offset = 0
    for _ in range(height):
        method = data[offset]; row = bytearray(data[offset + 1:offset + 1 + stride]); offset += stride + 1
        require(0 <= method <= 4, 'Unknown PNG scanline filter')
        for x in range(stride):
            left = row[x - bpp] if x >= bpp else 0; above = prior[x]; corner = prior[x - bpp] if x >= bpp else 0
            if method == 1: predictor = left
            elif method == 2: predictor = above
            elif method == 3: predictor = (left + above) // 2
            elif method == 4:
                estimate = left + above - corner
                distances = (abs(estimate - left), abs(estimate - above), abs(estimate - corner))
                predictor = (left, above, corner)[distances.index(min(distances))]
            else: predictor = 0
            row[x] = (row[x] + predictor) & 255
        for x in range(width):
            if color in (0, 3):
                sample = (row[x * depth // 8] >> (8 - depth - x * depth % 8)) & ((1 << depth) - 1)
                if color == 3:
                    require(palette is not None and sample < len(palette), 'PNG palette index is invalid')
                    rgb = palette[sample]; alpha = transparency[sample] if sample < len(transparency) else 255
                else:
                    shade = sample * 255 // ((1 << depth) - 1); rgb = bytes((shade, shade, shade))
                    alpha = 0 if len(transparency) == 2 and sample == struct.unpack('>H', transparency)[0] else 255
            else:
                values = row[x * channels:(x + 1) * channels]
                if color == 6: rgb, alpha = values[:3], values[3]
                elif color == 4: rgb, alpha = bytes((values[0],) * 3), values[1]
                else:
                    rgb = values
                    alpha = 0 if len(transparency) == 6 and tuple(rgb) == struct.unpack('>HHH', transparency) else 255
            pixels.extend(rgb); pixels.append(alpha)
        prior = row
    return width, height, bytes(pixels)


def nodes(model):
    def visit(entries):
        for node in entries:
            yield node
            yield from visit(node.get('children', []))
    yield from visit(model.get('nodes', []))


def without_offset_values(model):
    result = copy.deepcopy(model)
    for node in nodes(result):
        for uv in node.get('shape', {}).get('textureLayout', {}).values():
            require(isinstance(uv.get('offset'), dict), 'Face UV offset must exist')
            for axis in ('x', 'y'):
                require(axis in uv['offset'], 'UV offset must include both axes')
                uv['offset'][axis] = 0
    return result


def face_bounds(shape, face, uv):
    size = shape.get('settings', {}).get('size', {})
    if shape.get('type') == 'quad': width, height = size['x'], size['y']
    elif face in ('front', 'back'): width, height = size['x'], size['y']
    elif face in ('left', 'right'): width, height = size['z'], size['y']
    elif face in ('top', 'bottom'): width, height = size['x'], size['z']
    else: raise AssertionError('Unknown box face: ' + face)
    origin = uv['offset']; angle = uv.get('angle', 0); mirror = uv.get('mirror', {})
    require(all(isinstance(v, (int, float)) and math.isfinite(v) for v in (width, height, angle, origin['x'], origin['y'])), 'Nonfinite UV rectangle')
    require(width >= 0 and height >= 0 and angle % 90 == 0, 'Native rectangle needs nonnegative size and quarter turn UVs')
    turns = int(angle) % 360 // 90; corners = []
    for u, v in ((0, 0), (width, 0), (width, height), (0, height)):
        if mirror.get('x'): u = -u
        if mirror.get('y'): v = -v
        for _ in range(turns): u, v = -v, u
        corners.append((origin['x'] + u, origin['y'] + v))
    # Integer translation preserves subpixel sampling phase. Round only tiny
    # arithmetic noise at an exact boundary, never an actual fractional span.
    def clean(value): return round(value) if abs(value - round(value)) < 1e-9 else value
    return (math.floor(clean(min(p[0] for p in corners))) - 1,
            math.floor(clean(min(p[1] for p in corners))) - 1,
            math.ceil(clean(max(p[0] for p in corners))) + 1,
            math.ceil(clean(max(p[1] for p in corners))) + 1)


def patch_rgba(image, bounds):
    width, height, pixels = image; left, top, right, bottom = bounds; result = bytearray()
    for y in range(top, bottom):
        source_y = max(0, min(height - 1, y))
        for x in range(left, right):
            source_x = max(0, min(width - 1, x)); start = (source_y * width + source_x) * 4
            result.extend(pixels[start:start + 4])
    return bytes(result)


def texture_bindings(document):
    """Read native Model/Texture and inherited block Working texture associations."""
    emitted = set()
    def walk(value, model=None, location=(), inherited_textures=()):
        if isinstance(value, dict):
            local = value.get('CustomModel', value.get('Model', model))
            if isinstance(local, str) and local.endswith('.blockymodel'): model = common_path(local)
            textures = inherited_textures
            if isinstance(value.get('Texture'), str) and value['Texture'].endswith('.png'):
                textures = ((common_path(value['Texture']), location + ('Texture',)),)
            elif isinstance(value.get('CustomModelTexture'), list):
                textures = tuple((common_path(entry['Texture']), location + ('CustomModelTexture', index, 'Texture'))
                                 for index, entry in enumerate(value['CustomModelTexture'])
                                 if isinstance(entry.get('Texture'), str) and entry['Texture'].endswith('.png'))
            if model:
                for texture, source_location in textures:
                    binding = (model, texture, source_location)
                    if binding not in emitted:
                        emitted.add(binding); yield binding
            for key, nested in value.items():
                yield from walk(nested, model, location + (key,), textures)
        elif isinstance(value, list):
            for index, nested in enumerate(value):
                yield from walk(nested, model, location + (index,), inherited_textures)
    yield from walk(document)


def verify(baseline, manifest, resources):
    plan = json.loads(Path(manifest).read_text(encoding='utf8'))
    aliases = {common_path(k): common_path(v) for k, v in plan.get('textureAliases', {}).items()}
    def target(path):
        seen = set()
        while path in aliases:
            require(path not in seen, 'Texture alias cycle'); seen.add(path); path = aliases[path]
        return path
    with zipfile.ZipFile(baseline) as archive:
        original = {}; server_documents = {}
        for name in archive.namelist():
            relative = name.replace('\\', '/').removeprefix('src/main/resources/')
            if relative.startswith('Common/') and not name.endswith('/'):
                require(relative not in original, 'Duplicate baseline resource: ' + relative)
                original[relative] = archive.read(name)
            elif relative.startswith('Server/') and relative.endswith('.json'):
                server_documents[relative] = json.loads(archive.read(name))
    def current(path): return (resources / path).read_bytes()
    before_images = {}; after_images = {}
    def before_png(path):
        if path not in before_images: before_images[path] = decode_png(original[path])
        return before_images[path]
    def after_png(path):
        if path not in after_images: after_images[path] = decode_png(current(path))
        return after_images[path]
    allowed = set(); listed_textures = set(); listed_models = set(); checked = set()
    faces = 0; face_variants = 0; texels = 0; model_count = 0; memberships = {}
    for group_index, group in enumerate(plan['groups']):
        textures = [common_path(p) for p in group['textures']]
        models = [common_path(p) for p in group['models']]
        require(textures and models and len(set(textures)) == len(textures) and len(set(models)) == len(models), 'Empty or duplicate packing group members')
        dimensions = tuple(group['afterSize'])
        require(len(dimensions) == 2 and all(isinstance(n, int) and n >= 32 and n % 32 == 0 for n in dimensions), 'Packed atlas violates native dimension rules')
        require(all(n & (n - 1) == 0 for n in dimensions), 'Packed atlas dimensions must be powers of two')
        require(dimensions[0] >= dimensions[1], 'Packed atlas must be square or wider than it is tall')
        expected_before = {common_path(p): tuple(size) for p, size in group['beforeSizes'].items()}
        require(set(expected_before) == set(textures), 'All texture variants need recorded source dimensions')
        for texture in textures:
            require(texture in original, 'Missing baseline texture: ' + texture)
            require(before_png(texture)[:2] == expected_before[texture], 'Incorrect source dimensions: ' + texture)
            require(after_png(target(texture))[:2] == dimensions, 'Incorrect packed dimensions: ' + texture)
            allowed.update((texture, target(texture))); listed_textures.add(texture)
            memberships.setdefault(texture, set()).add(group_index)
        for path in models:
            require(path not in listed_models, 'A shared model appears in multiple groups: ' + path)
            listed_models.add(path); allowed.add(path); model_count += 1
            memberships.setdefault(path, set()).add(group_index)
            before = json.loads(original[path]); after = json.loads(current(path))
            require(without_offset_values(before) == without_offset_values(after), 'Model changed beyond UV translation: ' + path)
            for old_node, new_node in zip(nodes(before), nodes(after)):
                shape = old_node.get('shape', {})
                if shape.get('type') not in ('box', 'quad'): continue
                for face, old_uv in shape.get('textureLayout', {}).items():
                    new_uv = new_node['shape']['textureLayout'][face]; label = path + '/' + old_node.get('name', '?') + '/' + face
                    for axis in ('x', 'y'):
                        delta = new_uv['offset'][axis] - old_uv['offset'][axis]
                        require(math.isfinite(delta) and abs(delta - round(delta)) < 1e-8, 'UV fractional sampling phase changed: ' + label)
                    a = face_bounds(shape, face, old_uv); b = face_bounds(shape, face, new_uv)
                    require(a[2] - a[0] == b[2] - b[0] and a[3] - a[1] == b[3] - b[1], 'Face sampling dimensions changed: ' + label)
                    faces += 1
                    for texture in textures:
                        face_variants += 1; key = (texture, target(texture), a, b)
                        if key in checked: continue
                        checked.add(key)
                        old_patch = patch_rgba(before_png(texture), a); new_patch = patch_rgba(after_png(target(texture)), b)
                        require(old_patch == new_patch, 'Face pixels or one pixel filtering margin changed: ' + label + ' [' + texture + ']')
                        texels += len(old_patch) // 4
    for source in aliases:
        require(source in original and source.endswith('.png') and target(source).endswith('.png'), 'Alias must name existing PNG art')
        require(not (resources / source).exists() or source == target(source), 'Deduplicated alias source was not removed: ' + source)
        if source not in listed_textures:
            require(before_png(source) == after_png(target(source)), 'Unpacked texture alias changed dimensions or RGBA pixels: ' + source)
        allowed.update((source, target(source)))
    associations = 0
    for path, document in server_documents.items():
        updated = None
        for model, texture, location in texture_bindings(document):
            if texture not in listed_textures: continue
            require(model in listed_models and memberships[model] & memberships[texture],
                    'Repacked texture has an omitted model consumer: ' + model + ' [' + texture + ']')
            if updated is None: updated = json.loads(current(path))
            value = updated
            for part in location: value = value[part]
            require(common_path(value) == target(texture), 'Native texture reference does not follow its declared alias: ' + path)
            associations += 1
    unchanged = 0
    for path, raw in original.items():
        if path in allowed: continue
        require((resources / path).is_file() and current(path) == raw, 'Unlisted Common resource changed or disappeared: ' + path)
        unchanged += 1
    for path in (resources / 'Common').rglob('*'):
        if path.is_file():
            relative = path.relative_to(resources).as_posix()
            require(relative in original or relative in allowed, 'Unlisted Common resource added: ' + relative)
    return {'status': 'PASS', 'groups': len(plan['groups']), 'models': model_count,
            'sourceTextures': len(listed_textures), 'aliases': len(aliases), 'faces': faces,
            'faceTextureVariants': face_variants, 'uniquePaddedPatches': len(checked),
            'rgbaTexelsCompared': texels, 'unlistedCommonFilesByteIdentical': unchanged,
            'nativeModelTextureBindingsChecked': associations,
            'geometryAndNonOffsetUvFieldsExact': True, 'filteringMarginTexels': 1}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', '--before', required=True, type=Path)
    parser.add_argument('--manifest', required=True, type=Path)
    parser.add_argument('--resources', type=Path, default=ROOT / 'src/main/resources')
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    report = verify(args.baseline, args.manifest, args.resources)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + '\n', encoding='utf8')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
