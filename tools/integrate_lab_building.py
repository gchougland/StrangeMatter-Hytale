"""Register the furniture and roof additions without regenerating existing artwork."""
from pathlib import Path
import json
import re

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'src/main/resources'
ITEMS = RES / 'Server/Item/Items/StrangeMatter'


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def write(path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')


def integrate():
    reports = [read(ROOT / 'tools/assets' / name) for name in ('furniture-set.json', 'architecture-set.json')]
    entries = [entry for report in reports for entry in report['items']]
    ids = [entry['id'] for entry in entries]
    assert len(ids) == len(set(ids)), 'Repeated furniture or architecture item'
    bench_path = ITEMS / 'SM_Laboratory_Bench.json'
    bench = read(bench_path)
    categories = bench['BlockType']['Bench']['Categories']
    additions = [
        {'Id': 'SM_Laboratory_Furniture', 'Icon': 'Icons/CraftingCategories/SM_Laboratory_Furniture.png', 'Name': 'server.benchCategories.sm.furniture'},
        {'Id': 'SM_Laboratory_Building', 'Icon': 'Icons/CraftingCategories/SM_Laboratory_Building.png', 'Name': 'server.benchCategories.sm.building'},
    ]
    # Bench icons have their own native asset root, even when depicting an item.
    for category, source in zip(additions, ('SM_Resonite_Chair', 'SM_Resonite_Roof')):
        target = RES / 'Common' / category['Icon']
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes((RES / 'Common/Icons/ItemsGenerated' / f'{source}.png').read_bytes())
    new_categories = {entry['Id'] for entry in additions}
    bench['BlockType']['Bench']['Categories'] = [c for c in categories if c['Id'] not in new_categories] + additions
    write(bench_path, bench)

    # These existing pieces join the roofing set in the same native crafting tab.
    for suffix in ('Tile', 'Tile_Slab', 'Tile_Stairs', 'Pillar', 'Door', 'Trapdoor'):
        path = ITEMS / f'SM_Resonite_{suffix}.json'
        item = read(path)
        for requirement in item['Recipe']['BenchRequirement']:
            if requirement['Id'] == 'SM_Laboratory':
                requirement['Categories'] = ['SM_Laboratory_Building']
        write(path, item)
    path = ITEMS / 'SM_Fancy_Resonite_Tile.json'
    item = read(path)
    for requirement in item['Recipe']['BenchRequirement']:
        if requirement['Id'] == 'SM_Laboratory':
            requirement['Categories'] = ['SM_Laboratory_Building']
    write(path, item)

    labels = {
        'benchCategories.sm.furniture': 'Furniture',
        'benchCategories.sm.building': 'Building',
        'items.SM_Laboratory_Bench.description': 'Craft your discoveries, furnish your laboratory and build a home for strange science. Choose a tab to find what you need.',
        'items.SM_Resonite_Tile_Stairs.description': 'Navy metal steps for your laboratory. They form corners when placed beside matching stairs. Can also be placed upside down.',
        'items.SM_Resonite_Trapdoor.description': 'A sturdy metal hatch for lift shafts and secret passages. Use the handle to swing it open.',
        'items.SM_Levitation_Pad.description': 'A glowing lift for your laboratory. Use to switch between rising and falling. Gently keeps you near the center as you travel up to 16 blocks. Move to the side to step out.',
    }
    recipes_path = RES / 'Server/StrangeMatter/recipes.json'
    recipes = read(recipes_path)
    for entry in entries:
        assert not re.search(r'[-\u2010-\u2015]', entry['name'] + entry['description']), 'Player text must not contain dashes'
        identity = entry['id']
        item = read(ITEMS / f'{identity}.json')
        labels[f'items.{identity}.name'] = entry['name']
        labels[f'items.{identity}.description'] = entry['description']
        if entry.get('interactionHint'):
            hint = entry['interactionHint']
            assert not re.search(r'[-\u2010-\u2015]', hint), 'Player hints must not contain dashes'
            labels[item['BlockType']['InteractionHint'].removeprefix('server.')] = hint
        # The native item recipe remains authoritative; the tablet uses this catalogue.
        if 'Recipe' not in item:
            continue
        native = item['Recipe']
        ingredients = {}
        for material in native['Input']:
            key = material.get('ItemId') or 'resource:' + material['ResourceTypeId']
            ingredients[key] = ingredients.get(key, 0) + material.get('Quantity', 1)
        recipe_id = identity.removeprefix('SM_').lower()
        record = {'id': recipe_id, 'source': f'Hytale furniture and building set/{identity}',
                  'output': identity, 'quantity': native.get('OutputQuantity', 1),
                  'ingredients': ingredients, 'shards': {}, 'research': 'resonite',
                  'seconds': native.get('TimeSeconds', 2), 'station': 'workbench'}
        recipes = [old for old in recipes if old['id'] != recipe_id]
        recipes.append(record)
    write(recipes_path, recipes)
    lang_path = RES / 'Server/Languages/en-US/server.lang'
    lines = lang_path.read_text(encoding='utf-8-sig').splitlines()
    updated = []
    for line in lines:
        key = line.split('=', 1)[0].strip()
        if key in labels:
            updated.append(key + '=' + labels.pop(key))
        else:
            updated.append(line)
    updated.extend(key + '=' + value for key, value in labels.items())
    lang_path.write_text('\n'.join(updated) + '\n', encoding='utf-8')

    teaching_path = RES / 'Server/StrangeMatter/Research/Teaching.json'
    teaching = read(teaching_path)
    pages = [
        {'title': 'A Home for Strange Science', 'content': 'Resonite can make more than machines. Open the Furniture tab at your Laboratory Bench to craft beds, seats, storage and wall fittings.\n\nSit down after a long experiment, sleep in your new bed or keep your discoveries safe in a chest. Place two matching chests side by side to make a large chest. Shelves and monitors bring the room to life.', 'recipe': 'resonite_chair'},
        {'title': 'Building Your Laboratory', 'content': 'The Building tab holds floors, stairs, doors and matching roof pieces. Place stairs beside one another to form corners. Roofs also join as you build.\n\nUse shallow roofs for a broad shelter and steep roofs for a tall laboratory. Matching flat pieces finish the top.', 'recipe': 'resonite_roof'},
    ]
    page_names = {page['title'] for page in pages}
    teaching['resonite'] = [page for page in teaching.get('resonite', []) if page['title'] not in page_names] + pages
    write(teaching_path, teaching)
    print(json.dumps({'result': 'PASS', 'registeredItems': ids, 'benchTabs': len(bench['BlockType']['Bench']['Categories'])}, indent=2))


if __name__ == '__main__':
    integrate()
