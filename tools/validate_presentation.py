"""Check client-facing art constraints without Pillow, a renderer, or a running game."""
import argparse
import json
import re
import struct
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def png_size(path):
    header = path.read_bytes()[:24]
    if header[:8] != b'\x89PNG\r\n\x1a\n' or header[12:16] != b'IHDR':
        raise ValueError('not a PNG with an IHDR header')
    return struct.unpack('>II', header[16:24])


def validate(resources):
    common = resources / 'Common'
    errors = []
    icons = set()
    textures = set()
    particle_textures = set()
    items = {}

    def check(condition, message):
        if not condition:
            errors.append(message)

    def walk(value):
        if isinstance(value, dict):
            yield value
            for child in value.values():
                yield from walk(child)
        elif isinstance(value, list):
            for child in value:
                yield from walk(child)

    for path in (resources / 'Server/Item/Items/StrangeMatter').glob('*.json'):
        item = json.loads(path.read_text(encoding='utf-8-sig'))
        items[path.stem] = item
        icons.add(common / item['Icon'])
        check('SM_StrangeMatter.All' in item.get('Categories', []), f'{path.stem}: missing creative category')
        check(item.get('MaxStack', 1) == 1 or item['MaxStack'] % 5 == 0,
              path.stem + ': stackable quantities must be multiples of five')
        for obj in walk(item):
            if 'Model' in obj and 'Texture' in obj:
                textures.add(common / obj['Texture'])
            for entry in obj.get('CustomModelTexture', []):
                textures.add(common / entry['Texture'])
    # Include native display-entity and newly added core textures as well as items.
    for path in (resources / 'Server/Models/StrangeMatter').glob('*.json'):
        model = json.loads(path.read_text(encoding='utf-8-sig'))
        if 'Texture' in model:
            textures.add(common / model['Texture'])
    icons.update((common / 'Icons/ItemsGenerated').glob('SM_*.png'))
    spawners = {path.stem: path for path in (resources / 'Server/Particles').rglob('SM_*.particlespawner')}
    frames = []
    for path in spawners.values():
        particle = json.loads(path.read_text())['Particle']
        sprite = common / particle['Texture']
        particle_textures.add(sprite)
        if particle.get('FrameSize'):
            frames.append((path.stem, sprite, particle['FrameSize']))
    for path in (resources / 'Server/Particles').rglob('SM_*.particlesystem'):
        for spawner in json.loads(path.read_text())['Spawners']:
            check(spawner['SpawnerId'] in spawners, f'{path.stem}: missing spawner {spawner["SpawnerId"]}')
    for path in sorted(icons | textures | particle_textures):
        try:
            width, height = png_size(path)
            if path in icons:
                check((width, height) == (64, 64), f'{path.name}: icon {width}x{height}; must be64x64')
            if path in textures:
                check(min(width, height) >= 32 and width % 32 == height % 32 == 0,
                      f'{path.name}: atlas {width}x{height}; native model textures require32-pixel multiples and minimum32')
            if path in particle_textures:
                # Particle sprites are independent textures, not inventory icons or
                # model atlases. Native FrameSize can divide a sprite sheet into cells.
                check(width > 0 and height > 0, f'{path.name}: empty particle sprite')
        except (ValueError, OSError) as exception:
            errors.append(f'{path}: {exception}')
    for spawner, sprite, frame in frames:
        if sprite.exists():
            width, height = png_size(sprite)
            check(0 < frame['Width'] <= width and 0 < frame['Height'] <= height,
                  spawner + ': frame lies outside sprite; native client would ignore it')
    for kind in ('Door', 'Trapdoor'):
        block = items[f'SM_Resonite_{kind}']['BlockType']
        model = json.loads((common / block['CustomModel']).read_text())
        # A model editor may merge the first door face into the hinge; both a native
        # box and an empty group are valid, provided the animated node remains.
        nodes = [obj for obj in walk(model) if isinstance(obj, dict) and 'shape' in obj]
        check(any(n.get('name') == 'SM_Hinge' and n['shape'].get('type') in ('box','none') for n in nodes),
              f'{kind}: named native hinge missing')
        check(block.get('CustomModelAnimation', '').endswith('_Closed.blockyanim'), f'{kind}: missing closed placement pose')
    grass = items['SM_Anomalous_Grass']['BlockType']
    check(grass['DrawType'] == 'Cube' and not grass.get('Interactions') and not grass.get('InteractionHint'),
          'Anomalous grass must use a native cube with no till action or prompt')
    for item_id, item in items.items():
        if item_id.endswith('_Ore'):
            block = item['BlockType']
            check(block['DrawType'] == 'CubeWithModel' and block['Textures'][0]['All'] == 'BlockTextures/Rock_Stone.png',
                  item_id + ': ore must be embedded in ordinary native stone')
    language = dict(line.split('=', 1) for line in (resources / 'Server/Languages/en-US/server.lang').read_text(encoding='utf-8-sig').splitlines() if '=' in line)
    for item_id, item in items.items():
        block = item.get('BlockType', {})
        if block.get('Interactions', {}).get('Use') or block.get('Bench'):
            check(bool(block.get('InteractionHint')), item_id + ': interactive block has no prompt')
        for obj in walk(block):
            if obj.get('InteractionHint'):
                key = obj['InteractionHint'].removeprefix('server.')
                check(key.startswith('interactionHints.SM_') and '[{key}]' in language.get(key, ''),
                      item_id + ': prompt must name its action and include the native key binding placeholder')
    bench = items['SM_Laboratory_Bench']
    check(bench['BlockType']['Bench']['Id'] == 'SM_Laboratory', 'Laboratory bench has incorrect native bench ID')
    check(bench['Recipe']['BenchRequirement'][0]['Id'] == 'Workbench', 'Laboratory bench must be craftable at vanilla Workbench')
    category_path = resources / 'Server/Item/Category/CreativeLibrary/SM_StrangeMatter.json'
    check(category_path.exists(), 'Strange Matter creative category asset missing')
    if category_path.exists():
        category = json.loads(category_path.read_text())
        check(category.get('Name') == 'server.ui.itemcategory.SM_StrangeMatter', 'Creative category translation mismatch')
        check((common / category['Icon']).exists(), 'Creative category icon missing')
        check(bool(category.get('Children')), 'Creative root requires selectable child categories, matching native tabs')
        check(any(child.get('Id') == 'All' for child in category.get('Children', [])), 'Creative All child missing')
        for child in category.get('Children', []):
            check(bool(child.get('Name')) and (common / child.get('Icon','missing')).is_file(), 'Creative child name/icon unresolved')
    categories = {cat['Id'] for cat in bench['BlockType']['Bench']['Categories']}
    for category in bench['BlockType']['Bench']['Categories']:
        check((common / category['Icon']).exists(), 'Crafting category icon missing')
    recipes = [(item_id, item['Recipe']) for item_id, item in items.items() if 'Recipe' in item]
    recipes += [(p.stem, json.loads(p.read_text())) for p in (resources / 'Server/Item/Recipes/StrangeMatter').glob('*.json')]
    for recipe_id, recipe in recipes:
        crafting = any(requirement['Type'] == 'Crafting' for requirement in recipe.get('BenchRequirement', []))
        check(recipe.get('KnowledgeRequired', False) == crafting,
              recipe_id + ': crafting knowledge gate must be enabled only for native Crafting recipes')
        for requirement in recipe.get('BenchRequirement', []):
            if recipe_id == 'SM_Laboratory_Bench':
                continue
            check(requirement['Id'] in ('SM_Laboratory', 'Furnace'), recipe_id + ': recipe remains at the wrong station')
            if requirement['Id'] == 'SM_Laboratory':
                check(set(requirement.get('Categories', [])) <= categories, recipe_id + ': undefined laboratory category')
                if recipe_id.endswith(('_Shard','_Crystal','_Lamp','_Lantern')):
                    check(requirement.get('Categories') == ['SM_Laboratory_Shards'], recipe_id + ': missing shard/lighting crafting tab')
    conduit = items['SM_Resonant_Conduit']['BlockType']
    states = conduit['State']['Definitions']
    check(conduit['VariantRotation'] == 'None', 'Conduit masks must use world axes')
    check(set(states) == {f'Connection{mask:02d}' for mask in range(64)}, 'Incomplete conduit masks')
    for mask in range(64):
        state = states.get(f'Connection{mask:02d}')
        if state is None:
            continue
        path = common / state['CustomModel']
        check(path.exists(), f'Conduit{mask:02d}: model missing')
        if path.exists():
            nodes = json.loads(path.read_text())['nodes']
            arms = {int(re.match(r'arm(\d+)', n['name'])[1]) for n in nodes if n['name'].startswith('arm')}
            check(arms == {1 << bit for bit in range(6) if mask & (1 << bit)}, f'Conduit{mask:02d}: incorrect rendered arms')
        hitboxes = list((resources / 'Server/Item/Block/Hitboxes').rglob(state['HitboxType'] + '.json'))
        check(len(hitboxes) == 1, f'Conduit{mask:02d}: collision asset unresolved or duplicate')
        if len(hitboxes) == 1:
            boxes = json.loads(hitboxes[0].read_text())['Boxes']
            check(len(boxes) == 1 + mask.bit_count(), f'Conduit{mask:02d}: collision arm count mismatch')
    return {'status': 'FAIL' if errors else 'PASS', 'items': len(items), 'icons': len(icons),
            'modelAtlases': len(textures), 'particleSprites': len(particle_textures),
            'particleSpawners': len(spawners), 'conduitMasks': len(states), 'nativeRecipes': len(recipes), 'errors': errors}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--resources', type=Path, default=ROOT / 'src/main/resources')
    args = parser.parse_args()
    result = validate(args.resources)
    print(json.dumps(result, indent=2))
    raise SystemExit(bool(result['errors']))


if __name__ == '__main__':
    main()
