"""Author only the new storage block. Existing saved art is authoritative.

--create refuses existing output; --preview renders and checks saved geometry.
Uses the established native box model / painted atlas pipeline.
"""
from pathlib import Path
import argparse, json, sys
from PIL import Image
import build_assets as art
import render_current_icons as render

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / 'src/main/resources'
COMMON = RES / 'Common'
NAME = 'resonant_energy_storage'
ID = 'SM_Resonant_Energy_Storage'
MODEL = COMMON / f'Blocks/StrangeMatter/{NAME}.blockymodel'
ITEM = RES / f'Server/Item/Items/StrangeMatter/{ID}.json'
MATERIALS = {'Ingredient_Bar_Iron': 4, 'Ingredient_Bar_Copper': 4,
             'SM_Resonite_Ingot': 6, 'SM_Resonant_Coil': 2}

def read(path): return json.loads(path.read_text(encoding='utf-8-sig'))
def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')

def create():
    for path in (MODEL, MODEL.with_suffix('.png'), ITEM):
        assert not path.exists(), f'Existing artwork must be edited deliberately: {path}'
    m = art.Model(NAME, True)
    for x in (-10.8, 10.8):
        for z in (-10.8, 10.8):
            m.box('IsolationFoot', (x, 1.2, z), (5, 2.4, 5), 'dark')
            m.box('FootShoe', (x, 2.8, z), (5.4, .65, 5.4), 'steel')
    m.box('BaseHousing', (0, 5.1, 0), (28, 3.8, 28), 'navy')
    m.box('LowerBinding', (0, 7.5, 0), (28.8, .75, 28.8), 'edge')
    # Three tall cells remain visibly separate inside an open armored frame.
    for x in (-8.4, 0, 8.4):
        m.box('CellSleeve', (x, 17.7, -1), (6.8, 17.4, 10.4), 'dark')
        m.box('CellCeramicTop', (x, 27.2, -1), (7.1, 1.1, 10.7), 'white')
        m.box('CellCeramicBase', (x, 8.5, -1), (7.1, .7, 10.7), 'white')
        for y in (11.1, 24.3):
            m.box('CopperCellBand', (x, y, -1), (7.4, .85, 11.0), 'copper')
        m.box('CellWindow', (x, 17.7, 4.43), (4.5, 10, .25), 'cyan', glow=True)
        for y in (14.3, 17.7, 21.1):
            m.box('WindowBrace', (x, y, 4.69), (5.1, .5, .18), 'navy')
    for x in (-13.15, 13.15):
        for z in (-11.3, 11.3):
            m.box('FrameColumn', (x, 17.3, z), (1.6, 18.4, 2.2), 'edge')
            m.box('ColumnRivet', (x, 25.1, z + (1.3 if z > 0 else -1.3)), (1.1, 1.1, .25), 'copper')
    m.box('Lid', (0, 29.1, 0), (28.7, 2.3, 28.7), 'navy')
    m.box('LidTrim', (0, 30.62, 0), (29.1, .55, 29.1), 'steel')
    # +Z is the front. A compact meter sits above the front connector.
    m.box('MeterHousing', (0, 22.1, 11.9), (12, 6.5, 4), 'navy')
    m.box('MeterInset', (0, 22.1, 14.12), (8.6, 4.2, .3), 'screen')
    for x in (-2.7, -.9, .9, 2.7):
        m.box('TransferMeter', (x, 22.1, 14.38), (.75, 2.4, .18), 'cyan', glow=True)
    # All six connectors use the same neutral copper surround; UI owns direction.
    for name, p, s in (
        ('FrontPort', (0, 11.2, 13.45), (8, 4, 2.5)),
        ('BackPort', (0, 17.4, -13.45), (8, 6, 2.5)),
        ('LeftPort', (-13.45, 17.4, 0), (2.5, 6, 8)),
        ('RightPort', (13.45, 17.4, 0), (2.5, 6, 8)),
        ('TopPort', (0, 31.28, 0), (7, .55, 7)),
        ('BottomPort', (0, 2.1, 0), (7, .55, 7)),
    ):
        m.box(name, p, s, 'copper')
    entry = m.save()
    model = read(MODEL)
    animation = {'formatVersion': 1, 'duration': 60, 'holdLastKeyframe': False, 'nodeAnimations': {}}
    for index, node in enumerate(model['nodes']):
        if node['name'] != 'TransferMeter': continue
        node['name'] += str(index)
        node['shape']['settings']['isStaticBox'] = False
        track = {k: [] for k in ('position', 'orientation', 'shapeStretch', 'shapeVisible', 'shapeUvOffset')}
        track['shapeStretch'] = [{'time': t, 'delta': art.V((1, v, 1)), 'interpolationType': 'smooth'}
                                for t, v in ((0, 1), (15, .5), (30, 1), (45, .7), (60, 1))]
        animation['nodeAnimations'][node['name']] = track
    write(MODEL, model)
    write(MODEL.with_name(NAME + '_working.blockyanim'), animation)
    item = read(RES / 'Server/Item/Items/StrangeMatter/SM_Resonant_Charging_Station.json')
    item['TranslationProperties'] = {'Name': f'server.items.{ID}.name', 'Description': f'server.items.{ID}.description'}
    item['Icon'] = entry['icon']
    block = item['BlockType']
    block['CustomModel'] = entry['model']
    block['CustomModelTexture'] = [{'Texture': entry['texture'], 'Weight': 1}]
    block['HitboxType'] = ID
    block['InteractionHint'] = f'server.interactionHints.{ID}'
    block['State']['Definitions']['Working'].update({
        'CustomModelAnimation': f'Blocks/StrangeMatter/{NAME}_working.blockyanim',
        'Particles': [{'SystemId': 'SM_Gadget_Charge', 'PositionOffset': {'X': 0, 'Y': .38, 'Z': .47}, 'Scale': .25}]
    })
    item['Recipe'] = {'Input': [{'ItemId': k, 'Quantity': v} for k, v in MATERIALS.items()],
                      'OutputQuantity': 1, 'TimeSeconds': 3, 'KnowledgeRequired': True,
                      'BenchRequirement': [{'Type': 'Crafting', 'Id': 'SM_Laboratory', 'Categories': ['SM_Laboratory_All']}]}
    write(ITEM, item)
    write(RES / f'Server/Item/Block/Hitboxes/StrangeMatter/{ID}.json',
          {'Boxes': [{'Min': {'X': .04, 'Y': 0, 'Z': .04}, 'Max': {'X': .96, 'Y': .99, 'Z': .96}}]})
    manifest_path = ROOT / 'tools/assets/gadget-energy-set.json'
    manifest = read(manifest_path)
    manifest['items'].append(entry)
    write(manifest_path, manifest)

def preview():
    sys.path.insert(0, str(ROOT / 'tools'))
    from validate_gadget_energy_assets import inspect
    model = read(MODEL)
    texture = Image.open(MODEL.with_suffix('.png')).convert('RGBA')
    faces, overlaps = inspect(model, texture)
    assert not overlaps, overlaps
    render.render(faces, 256, yaw=35, pitch=22).resize((64, 64), Image.Resampling.LANCZOS).save(COMMON / read(ITEM)['Icon'])
    render.contact([('Resonant Energy Storage - front', faces), ('Resonant Energy Storage - rear', faces)],
                   ROOT / 'docs/art/resonant-energy-storage.png', cols=2, cell=440,
                   views={'Resonant Energy Storage - front': {'yaw': 35, 'pitch': 22},
                          'Resonant Energy Storage - rear': {'yaw': 215, 'pitch': 25}})
    print(json.dumps({'faces': len(faces), 'coplanarOverlaps': len(overlaps), 'texture': list(texture.size)}))

def content():
    recipe_path = RES / 'Server/StrangeMatter/recipes.json'
    recipes = read(recipe_path)
    recipe = {'id': NAME, 'source': 'StrangeMatter energy storage', 'output': ID, 'quantity': 1,
              'ingredients': MATERIALS, 'shards': {}, 'research': 'resonant_energy', 'seconds': 3,
              'station': 'workbench'}
    recipes = [r for r in recipes if r['id'] != NAME] + [recipe]
    write(recipe_path, recipes)
    teaching_path = RES / 'Server/StrangeMatter/Research/Teaching.json'
    teaching = read(teaching_path)
    page = {'title': 'Store a reserve', 'recipe': NAME,
            'content': 'Resonant Energy Storage buffers up to 60,000 RE and starts empty. It accepts spare generation and supplies machines later, up to 400 RE per second. Craft it at the Laboratory Bench.\n\nUse the block to configure each of its six faces as Input, Output or Disabled. The front is an output by default; the other five faces are inputs. These faces follow the block when it is rotated. Route conduits from your generator to an input, and from an output to your machines.\n\nStorage does not generate power. Pack Up preserves its charge and face settings when moving it.'}
    teaching['resonant_energy'] = [p for p in teaching['resonant_energy'] if p.get('recipe') != NAME]
    teaching['resonant_energy'].insert(-1, page)
    write(teaching_path, teaching)
    lang_path = RES / 'Server/Languages/en-US/server.lang'
    lines = lang_path.read_text(encoding='utf-8').splitlines()
    values = {f'items.{ID}.name': 'Resonant Energy Storage',
              f'items.{ID}.description': 'Store up to 60,000 RE and transfer up to 400 RE/s. Configure each face as Input, Output or Disabled. Starts empty; Pack Up preserves the charge and settings.',
              f'interactionHints.{ID}': 'Press [{key}] to configure energy storage'}
    lines = [line for line in lines if line.split('=', 1)[0] not in values]
    lines.extend(k + '=' + v for k, v in values.items())
    lang_path.write_text('\n'.join(lines) + '\n', encoding='utf-8')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--create', action='store_true')
    parser.add_argument('--preview', action='store_true')
    parser.add_argument('--content', action='store_true')
    args = parser.parse_args()
    if args.create: create()
    if args.create or args.preview: preview()
    if args.content: content()
