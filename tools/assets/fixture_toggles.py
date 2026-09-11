"""Surgical native light switches. Existing models, textures and palettes are read only."""
import argparse
import copy
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / 'src/main/resources'
FAMILIES = ('Gravitic', 'Chrono', 'Energetic', 'Spatial', 'Shade', 'Insight')
IDS = tuple(f'SM_{family}_Shard_{kind}' for family in FAMILIES for kind in ('Lamp', 'Lantern')) + ('SM_Lab_Lamp',)
CHANGES = {'default': 'Off', 'On': 'Off', 'Off': 'On'}

def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))

def write(path, value):
    encoded = json.dumps(value, indent=2) + '\n'
    if path.exists() and path.read_text(encoding='utf-8-sig') == encoded:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(encoded, encoding='utf-8')

def unlit(model):
    result = copy.deepcopy(model)
    def walk(nodes):
        for node in nodes:
            if node.get('shape', {}).get('shadingMode') == 'fullbright':
                node['shape']['shadingMode'] = 'standard'
            walk(node.get('children', []))
    walk(result['nodes'])
    return result

def apply(item_id, item, resources=RES, write_models=True):
    if item_id not in IDS:
        return False
    block = item['BlockType']
    if item_id == 'SM_Lab_Lamp':
        # Native flood light refuses to propagate out of optical Solid blocks.
        # This does not change the solid material, collider, geometry or texture.
        block['Opacity'] = 'Transparent'
    source = Path(block['CustomModel'])
    off = source.with_name(source.stem + '_SwitchedOff.blockymodel').as_posix()
    if write_models:
        write(resources / 'Common' / off, unlit(read(resources / 'Common' / source)))
    # The installed native BlockType codec derives usability from its Use
    # interaction; IsUsable is a packet field, not a supported asset flag.
    flags = block.get('Flags')
    if isinstance(flags, dict):
        flags.pop('IsUsable', None)
        if not flags:
            block.pop('Flags')
    block['InteractionHint'] = 'server.interactionHints.turnoff'
    block.setdefault('Interactions', {})['Use'] = {'Interactions': [{'Type': 'ChangeState', 'Changes': dict(CHANGES)}]}
    definitions = block.setdefault('State', {}).setdefault('Definitions', {})
    definitions['On'] = {
        'CustomModel': source.as_posix(),
        'Light': copy.deepcopy(block['Light']),
        'InteractionHint': 'server.interactionHints.turnoff',
        'InteractionSoundEventId': 'SFX_Torch_Ignite',
    }
    definitions['Off'] = {
        'CustomModel': off,
        'InteractionHint': 'server.interactionHints.turnon',
        'Light': None,
        'Particles': None,
        'AmbientSoundEventId': None,
        'InteractionSoundEventId': 'SFX_Torch_Off',
    }
    return True

def apply_all(resources=RES):
    for item_id in IDS:
        path = resources / 'Server/Item/Items/StrangeMatter' / (item_id + '.json')
        if path.exists():
            item = read(path)
            apply(item_id, item, resources)
            write(path, item)

def validate(resources=RES):
    models = set()
    for item_id in IDS:
        item = read(resources / 'Server/Item/Items/StrangeMatter' / (item_id + '.json'))
        block = item['BlockType']
        if item_id == 'SM_Lab_Lamp':
            assert block['Opacity'] == 'Transparent', 'Ceiling light must propagate beyond its own source cell'
        assert block['Light'] is not None, (item_id, 'New placements and held fixtures start lit')
        assert block['Interactions']['Use'] == {'Interactions': [{'Type': 'ChangeState', 'Changes': CHANGES}]}, item_id
        assert block['InteractionHint'] == 'server.interactionHints.turnoff', item_id
        assert 'IsUsable' not in block.get('Flags', {}), (item_id, 'Usability comes from native Use')
        on, off = (block['State']['Definitions'][key] for key in ('On', 'Off'))
        assert on['Light'] == block['Light'] and on['CustomModel'] == block['CustomModel'], item_id
        assert off['Light'] is None and off['Particles'] is None and off['AmbientSoundEventId'] is None, item_id
        assert off['InteractionHint'] == 'server.interactionHints.turnon', item_id
        assert on['InteractionSoundEventId'] == 'SFX_Torch_Ignite' and off['InteractionSoundEventId'] == 'SFX_Torch_Off', item_id
        assert 'HitboxType' not in off and 'CustomModelTexture' not in off, (item_id, 'Switch retains collider and painted palette')
        assert read(resources / 'Common' / off['CustomModel']) == unlit(read(resources / 'Common' / block['CustomModel'])), (item_id, 'Only emissive shading may differ')
        assert item['Interactions']['Primary'] == 'Block_Primary' and item['Interactions']['Secondary'] == 'Block_Secondary', (item_id, 'Native held placement remains intact')
        models.add(off['CustomModel'])
    print(f'FIXTURE_TOGGLES PASS: {len(IDS)} native switches, {len(models)} geometry and UV identical unlit models, original palettes and held placement')

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--write', action='store_true', help='Apply only these thirteen fixture switches and their unlit model copies')
    args = parser.parse_args()
    if args.write:
        apply_all()
    validate()
