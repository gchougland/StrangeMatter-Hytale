"""Validate acquisition, guide discovery and passive conduit interaction content. Standard library only."""
from copy import deepcopy
import json
from pathlib import Path
import re
from nullifier_content import ITEM_ID, RECIPE, PAGE, DESCRIPTION, add_recipe, add_teaching, item_asset, hitbox_asset

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'src/main/resources'


def read(path):
    return json.loads((RES / path).read_text(encoding='utf8'))


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def passive_conduit(value):
    if isinstance(value, dict):
        require(not value.get('InteractionHint'), 'Conduit has no use prompt')
        require('Use' not in value.get('Interactions', {}), 'Conduit has no Use interaction')
        for nested in value.values():
            passive_conduit(nested)
    elif isinstance(value, list):
        for nested in value:
            passive_conduit(nested)


def main():
    catalog = read('Server/StrangeMatter/recipes.json')
    forge = {r['id']: r for r in catalog if r['station'] == 'forge'}
    original = {'chrono_blister', 'containment_capsule', 'echo_vacuum', 'echoform_imprinter',
                'graviton_hammer', 'hoverboard', 'levitation_pad', 'resonance_condenser',
                'rift_stabilizer', 'stasis_projector', 'warp_gun'}
    require(set(forge) == original | {'anomaly_nullifier', 'resonant_separator', 'flux_furnace', 'pattern_assembler'}, 'Original forge recipes, nullifier and three powered machines')
    recipe = forge['anomaly_nullifier']
    require(recipe == RECIPE and recipe['research'] == 'rift_stabilizer', 'Generator keeps the actual researched recipe')
    costs = dict(recipe['ingredients'])
    for name, amount in recipe['shards'].items():
        key = 'SM_' + name.title() + '_Shard'
        costs[key] = costs.get(key, 0) + amount
    require(len(recipe['shards']) == 6 and all(n == 1 for n in recipe['shards'].values()), 'Suppression device uses every anomaly discipline')
    require(all(n > 0 for n in costs.values()), 'No zero or negative recipe input')
    require(all((RES / f'Server/Item/Items/StrangeMatter/{item}.json').is_file() for item in costs), 'Every ingredient is a real obtainable item')
    ui = (RES / 'Common/UI/Custom/StrangeMatter/RealityForge.ui').read_text(encoding='utf8')
    rows = set(re.findall(r'Group #Material(\d+)\s*\{', ui))
    require(len(costs) <= len(rows), 'Every material has a visible forge row')
    item = read(f'Server/Item/Items/StrangeMatter/{ITEM_ID}.json')
    require(item == item_asset() and read(f'Server/Item/Block/Hitboxes/StrangeMatter/{ITEM_ID}.json') == hitbox_asset(), 'New content rebuilds identically')
    require('Recipe' not in item, 'Custom forge research cannot be bypassed with a native workbench recipe')
    require('SM_StrangeMatter.All' in item['Categories'], 'Nullifier remains available in the mod creative category')
    require(item['Interactions']['Use']['RequireNewClick'] and item['BlockType']['Interactions']['Use']['RequireNewClick'], 'One click requests one device toggle')
    require(item['Interactions']['Primary'] == 'Block_Primary' and item['Interactions']['Secondary'] == 'Block_Secondary', 'Native block placement remains intact')
    teaching = read('Server/StrangeMatter/Research/Teaching.json')
    require(PAGE in teaching['rift_stabilizer'] and len(teaching) == 30, 'Existing discovery exposes the nullifier beside the automation discoveries')
    require(sum(p.get('recipe') == 'anomaly_nullifier' for p in teaching['rift_stabilizer']) == 1, 'Guide contains the new recipe exactly once')
    original_catalog, original_teaching = deepcopy(catalog), deepcopy(teaching)
    add_recipe(catalog); add_recipe(catalog); add_teaching(teaching); add_teaching(teaching)
    require(catalog == original_catalog and teaching == original_teaching, 'Repeated targeted generation preserves every existing recipe and teaching page')
    bridge = (ROOT / 'src/main/java/com/hexvane/strangematter/research/ResearchRecipeBridge.java').read_text(encoding='utf8')
    require('Map.entry("SM_Anomaly_Nullifier", "rift_stabilizer")' in bridge, 'Native research bridge maps the output to its existing discovery')
    lang = dict(line.split('=', 1) for line in (RES / 'Server/Languages/en-US/server.lang').read_text(encoding='utf8').splitlines() if '=' in line and not line.startswith('#'))
    require(lang[f'items.{ITEM_ID}.description'] == DESCRIPTION and '12 blocks' in DESCRIPTION, 'Tooltip states real range and no ongoing fuel requirement')
    require('on or off' in lang['interactionHints.SM_Stasis_Projector'], 'Stasis prompt describes the direct toggle')
    require('interactionHints.SM_Resonant_Conduit' not in lang, 'Passive conduit has no localized Use hint')
    conduit = read('Server/Item/Items/StrangeMatter/SM_Resonant_Conduit.json')
    passive_conduit(conduit)
    require(conduit['BlockType'].get('InteractionHint') == '', 'Explicit empty hint prevents native generic fallback')
    require(len(conduit['BlockType']['State']['Definitions']) == 64, 'All conduit connection shapes remain available')
    require(conduit['Interactions'] == {'Primary': 'Block_Primary', 'Secondary': 'Block_Secondary'}, 'Passive conduit preserves native placement')
    for broken in ({'Interactions': {'Use': {}}}, {'State': {'Definitions': {'Connection01': {'InteractionHint': 'stale'}}}}):
        try:
            passive_conduit(broken)
        except AssertionError:
            pass
        else:
            raise AssertionError('Conduit regression probe must fail for a hidden interaction or hint')
    print('PASS: fifteen gated forge recipes, all six shard inputs, eight visible costs, idempotent preserved catalog and guide, direct device prompts and all 64 passive conduit states.')


if __name__ == '__main__':
    main()
