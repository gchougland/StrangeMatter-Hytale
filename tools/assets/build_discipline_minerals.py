"""Rebuild procedural mineral materials against the shipped native UV layouts.

Geometry, UVs, rock/metal materials and discipline icons remain unchanged. Source
material definitions come from the original model generators, including revised
standing lamps. Inventory icons are rendered from the resulting native assets.
"""
import argparse
import hashlib
import json
import numpy as np
from PIL import Image, ImageDraw
import build_assets as art
import render_current_icons as render
from apply_playtest_art_revision import lamp_model
from content_policy import descriptions, family_light
from discipline_palette import DISCIPLINES, MATERIALS, MINERAL_RGB

ROOT = art.ROOT
COMMON = art.COMMON
ITEMS = ROOT / 'src/main/resources/Server/Item/Items/StrangeMatter'
KINDS = ('', '_ore', '_crystal', '_lamp', '_lantern')

def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))

def nodes(values):
    for node in values:
        yield node
        yield from nodes(node.get('children', []))

def source_model(name, kind):
    if kind == '_lamp':
        return lamp_model(name.removesuffix('_shard_lamp'))
    return art.make_item(name) if not kind else art.make_block(name)

def tiles(name, kind, model):
    source = source_model(name, kind)
    materials = {n['id']: (n, source.materials[i]) for i, n in enumerate(source.nodes)}
    for node in nodes(model['nodes']):
        if node['shape']['type'] == 'none':
            continue
        original, (mat, overrides) = materials[node['id']]
        assert original['name'] == node['name'], (name, node['name'], original['name'])
        shape = node['shape']
        size = shape['settings']['size']
        for face, layout in shape['textureLayout'].items():
            material = overrides.get(face, mat)
            base = material.split(':')[1] if material.startswith('triangle:') else material
            if base not in MATERIALS:
                continue
            assert not any(layout.get('mirror', {}).values()) and layout.get('angle', 0) == 0, (name, node['name'], face)
            if shape['type'] == 'quad':
                w, h = size['x'], size['y']
            elif face in ('top', 'bottom'):
                w, h = size['x'], size['z']
            elif face in ('left', 'right'):
                w, h = size['z'], size['y']
            else:
                w, h = size['x'], size['y']
            x, y = layout['offset']['x'], layout['offset']['y']
            yield (x, y, w, h), art.paint(w, h, material, f'{name}/{int(node["id"])-1}/{face}')

def rebuild(name, kind, item, write):
    block = item.get('BlockType', {})
    model_path = COMMON / (item.get('Model') or block['CustomModel'])
    texture_path = COMMON / (item.get('Texture') or block['CustomModelTexture'][0]['Texture'])
    model = read(model_path)
    before = np.array(Image.open(texture_path).convert('RGBA'))
    atlas = Image.fromarray(before.copy())
    changed = Image.new('L', atlas.size)
    painter = ImageDraw.Draw(changed)
    count = 0
    for (x, y, w, h), tile in tiles(name, kind, model):
        assert x >= 1 and y >= 1 and x+w < atlas.width and y+h < atlas.height
        atlas.paste(tile, (x, y))
        atlas.paste(tile.crop((0, 0, w, 1)), (x, y-1))
        atlas.paste(tile.crop((0, h-1, w, h)), (x, y+h))
        atlas.paste(tile.crop((0, 0, 1, h)), (x-1, y))
        atlas.paste(tile.crop((w-1, 0, w, h)), (x+w, y))
        painter.rectangle((x, y-1, x+w-1, y+h), fill=255)
        painter.rectangle((x-1, y, x+w, y+h-1), fill=255)
        count += 1
    assert count, name
    after = np.array(atlas)
    assert np.array_equal(before[np.array(changed) == 0], after[np.array(changed) == 0]), name
    assert np.array_equal(before[:, :, 3], after[:, :, 3]), (name, 'native crystal alpha changed')
    if write:
        atlas.save(texture_path)
    return {'item': art.hid(name), 'mineralFaces': count, 'texture': texture_path.relative_to(COMMON).as_posix(),
            'geometrySha256': hashlib.sha256(model_path.read_bytes()).hexdigest()}

def apply_colors(family, item):
    block = item.get('BlockType')
    if not block:
        return
    block['ParticleColor'] = '#' + ''.join(f'{v:02x}' for v in MINERAL_RGB[family])
    if block.get('Light') is not None:
        fixture = item['Icon'].endswith(('_Lamp.png', '_Lantern.png'))
        block['Light']['Color'] = family_light(family, fixture)
        for state in block.get('State', {}).get('Definitions', {}).values():
            if state.get('Light') is not None:
                state['Light']['Color'] = block['Light']['Color']

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write', action='store_true')
    args = parser.parse_args()
    rows = []
    # Validate every native UV/material mapping before changing any output.
    for family in DISCIPLINES:
        for kind in KINDS:
            name = family + '_shard' + kind
            item = read(ITEMS / (art.hid(name) + '.json'))
            rows.append(rebuild(name, kind, item, False))
    if args.write:
        views = read(ROOT / 'tools/assets/icon_views.json')
        previews = []
        decorations = []
        for family in DISCIPLINES:
            for kind in KINDS:
                name = family + '_shard' + kind
                path = ITEMS / (art.hid(name) + '.json')
                item = read(path)
                rebuild(name, kind, item, True)
                apply_colors(family, item)
                path.write_text(json.dumps(item, indent=2) + '\n', encoding='utf-8')
                faces = render.item_faces(item)
                render.render(faces, **views.get(art.hid(name), {})).resize((64, 64), Image.Resampling.LANCZOS).save(COMMON / item['Icon'])
                if kind in ('', '_ore'):
                    previews.append((name.replace('_', ' ').title(), faces))
                else:
                    decorations.append((name.replace('_shard', '').replace('_', ' ').title(), faces))
        language = ROOT / 'src/main/resources/Server/Languages/en-US/server.lang'
        updated = descriptions()
        prefix = 'items.'
        updated_descriptions = 0
        lines = language.read_text(encoding='utf-8').splitlines()
        for i, line in enumerate(lines):
            if '=' not in line:
                continue
            key = line.split('=', 1)[0]
            if key.startswith(prefix + 'SM_') and key.endswith('.description'):
                name = key[len(prefix + 'SM_'):-len('.description')].lower()
                if name in updated and any(name.startswith(f + '_shard') for f in DISCIPLINES):
                    lines[i] = key + '=' + updated[name]
                    updated_descriptions += 1
        assert updated_descriptions == len(rows), ('Missing mineral translations', updated_descriptions, len(rows))
        language.write_text('\n'.join(lines) + '\n', encoding='utf-8')
        render.contact(previews, ROOT / 'docs/art/discipline-minerals.png', cols=4, cell=200)
        render.contact(decorations, ROOT / 'docs/art/discipline-decorations.png', cols=6, cell=200)
        (ROOT / 'tools/assets/discipline-minerals.json').write_text(json.dumps({'status': 'PASS', 'assets': rows}, indent=2) + '\n', encoding='utf-8')
    print(f'DISCIPLINE_MINERALS PASS: {len(rows)} native materials validated' + (' and rebuilt; geometry, alpha and non-mineral texels preserved' if args.write else ' (dry run)'))

if __name__ == '__main__':
    main()
