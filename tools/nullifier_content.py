"""Hytale additions to the source catalog. Imports never change files or authored art."""
from copy import deepcopy
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ITEM_ID = 'SM_Anomaly_Nullifier'
DESCRIPTION = ('Suppresses anomaly effects within 12 blocks. Use to turn it on or off. '
               'Needs no fuel or resonant power.')
RECIPE = {
    'id': 'anomaly_nullifier', 'source': 'hytale:anomaly_nullifier',
    'output': ITEM_ID, 'quantity': 1,
    'ingredients': {'SM_Rift_Stabilizer': 1, 'SM_Resonant_Circuit': 2},
    'shards': {name: 1 for name in ('gravitic', 'chrono', 'energetic', 'spatial', 'shade', 'insight')},
    'research': 'rift_stabilizer', 'seconds': 5.0, 'station': 'forge',
}
PAGE = {
    'title': 'Anomaly Nullifier',
    'content': ('The Anomaly Nullifier keeps a laboratory safe from nearby anomaly effects. '
                'Its field reaches 12 blocks in every direction.\n\n'
                'Place it near the area you want to protect. Use the device to turn it on or off. '
                'It needs no fuel or resonant power.\n\n'
                'Suppression leaves the anomalies intact. Turning the device off restores their effects. '
                'The glowing core shows when it is suppressing a nearby anomaly.'),
    'recipe': 'anomaly_nullifier',
}
PROMPTS = {
    'interactionHints.SM_Anomaly_Nullifier': 'Press [{key}] to turn the anomaly nullifier on or off',
    'interactionHints.SM_Stasis_Projector': 'Press [{key}] to turn the stasis projector on or off',
}
TABLET_DESCRIPTION = ('Harness energetic rifts for power, or build an Anomaly Nullifier '
                      'to suppress nearby anomaly effects without fuel.')


def item_asset():
    use = {'Interactions': [{'Type': 'SM_Use', 'Action': 'machine'}], 'RequireNewClick': True}
    return {
        'TranslationProperties': {'Name': f'server.items.{ITEM_ID}.name',
                                  'Description': f'server.items.{ITEM_ID}.description'},
        'Icon': f'Icons/ItemsGenerated/{ITEM_ID}.png', 'MaxStack': 25,
        'Categories': ['Furniture.Benches', 'SM_StrangeMatter.All'],
        'PlayerAnimationsId': 'Block', 'Quality': 'Uncommon', 'Tags': {'Type': ['StrangeMatter']},
        'BlockType': {
            'Material': 'Solid', 'DrawType': 'Model', 'Opacity': 'Transparent',
            'CustomModel': 'Blocks/StrangeMatter/anomaly_nullifier.blockymodel',
            'CustomModelTexture': [{'Texture': 'Blocks/StrangeMatter/anomaly_nullifier.png', 'Weight': 1}],
            'HitboxType': ITEM_ID, 'VariantRotation': 'NESW',
            'Gathering': {'Breaking': {'GatherType': 'Rocks'}},
            'BlockParticleSetId': 'Stone', 'ParticleColor': '#243950',
            'BlockSoundSetId': 'Stone', 'PhysicalMaterialId': 'Stone',
            'Interactions': {'Use': deepcopy(use)},
            'InteractionHint': f'server.interactionHints.{ITEM_ID}',
            'State': {'Definitions': {'Working': {
                'Looping': True,
                'CustomModelTexture': [{'Texture': 'Blocks/StrangeMatter/anomaly_nullifier_working.png', 'Weight': 1}],
                'CustomModelAnimation': 'Blocks/StrangeMatter/anomaly_nullifier_working.blockyanim',
                'CustomModelAnimationSpeed': 1, 'AmbientSoundEventId': 'SM_Nullifier_Hum_SFX',
            }}},
        },
        'Interactions': {'Primary': 'Block_Primary', 'Secondary': 'Block_Secondary', 'Use': deepcopy(use)},
    }


def hitbox_asset():
    return {'Boxes': [{'Min': {'X': 0, 'Y': 0, 'Z': 0}, 'Max': {'X': 1, 'Y': 1, 'Z': 1}}]}


def add_recipe(recipes):
    """Keep every existing source recipe and replace only this port addition, idempotently."""
    recipes[:] = [recipe for recipe in recipes if recipe['id'] != RECIPE['id']]
    recipes.append(deepcopy(RECIPE))


def add_teaching(teaching):
    pages = teaching['rift_stabilizer']
    pages[:] = [page for page in pages if page.get('recipe') != RECIPE['id']]
    pages.append(deepcopy(PAGE))


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf8')


def update_properties(path, updates):
    lines = path.read_text(encoding='utf8').splitlines() if path.exists() else []
    remaining = dict(updates)
    for index, line in enumerate(lines):
        key = line.partition('=')[0]
        if key in remaining:
            lines[index] = key + '=' + remaining.pop(key)
    lines.extend(key + '=' + value for key, value in remaining.items())
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text('\n'.join(lines) + '\n', encoding='utf8')


def apply(resources):
    """Only update this addition and its discovery text, preserving the rest of the catalog."""
    write_json(resources / f'Server/Item/Items/StrangeMatter/{ITEM_ID}.json', item_asset())
    write_json(resources / f'Server/Item/Block/Hitboxes/StrangeMatter/{ITEM_ID}.json', hitbox_asset())
    recipes_path = resources / 'Server/StrangeMatter/recipes.json'
    recipes = json.loads(recipes_path.read_text(encoding='utf8'))
    add_recipe(recipes)
    write_json(recipes_path, recipes)
    teaching_path = resources / 'Server/StrangeMatter/Research/Teaching.json'
    teaching = json.loads(teaching_path.read_text(encoding='utf8'))
    add_teaching(teaching)
    write_json(teaching_path, teaching)
    update_properties(resources / 'Server/Languages/en-US/server.lang', {
        f'items.{ITEM_ID}.name': 'Anomaly Nullifier', f'items.{ITEM_ID}.description': DESCRIPTION, **PROMPTS})
    update_properties(resources / 'Server/StrangeMatter/Research/Tablet.properties', {
        'rift_stabilizer.description': TABLET_DESCRIPTION})


if __name__ == '__main__':
    apply(ROOT / 'src/main/resources')
    print('Updated Anomaly Nullifier content without rewriting existing art or source recipes.')
