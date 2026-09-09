"""Validate the recipe payload contracts the server loader does not fully enforce.

Reads mod resources and either the supplied HytaleAssets directory or installed
Assets.zip. Writes a diagnostic report only; it never rewrites production assets.
"""
import argparse
import collections
import json
import os
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_ASSETS = ROOT.parent / 'HytaleSourceCode/hytale-shared-source/HytaleAssets'
DEFAULT_MC = Path('C:/Users/gchou/Documents/Projects/StrangeMatter-1.20.1/strange-matter')
# Verified source snapshot keeps distributed builds independent of the owner's MC checkout.
SOURCE_SHARDS = {'SM_' + family + '_Shard' for family in ('Gravitic', 'Chrono', 'Spatial', 'Shade', 'Insight', 'Energetic')}
SOURCE_ANY_SHARD_COSTS = {'SM_Anomaly_Resonator': 1, 'SM_Field_Scanner': 1, 'SM_Reality_Forge': 1, 'SM_Stabilized_Core': 2}


class Assets:
    def __init__(self, path):
        self.path = Path(path)
        self.archive = zipfile.ZipFile(path) if self.path.is_file() else None
        self.names = set(self.archive.namelist()) if self.archive else None

    def files(self, prefix):
        if self.archive:
            return sorted(n for n in self.names if n.startswith(prefix + '/') and n.endswith('.json'))
        return sorted(p.relative_to(self.path).as_posix() for p in (self.path / prefix).rglob('*.json'))

    def read(self, name):
        raw = self.archive.read(name) if self.archive else (self.path / name).read_bytes()
        return json.loads(raw.decode('utf-8-sig'))

    def exists(self, name):
        return name in self.names if self.archive else (self.path / name).is_file()


def hid(name):
    return 'SM_' + '_'.join(word.capitalize() for word in name.split(':')[-1].split('_'))


def validate(mod, native, minecraft):
    errors = []
    def check(condition, message):
        if not condition:
            errors.append(message)

    items, resources, owned_items, recipes = {}, {}, {}, []
    for source in (native, mod):
        for name in source.files('Server/Item/Items'):
            key = Path(name).stem
            items[key] = source.read(name)
            if source is mod:
                owned_items[key] = name
        for name in source.files('Server/Item/ResourceTypes'):
            resources[Path(name).stem] = (source, name, source.read(name))

    resolved = {}
    def inherit(key, seen=()):
        if key in resolved:
            return resolved[key]
        if key in seen:
            check(False, f'Item parent cycle: {seen + (key,)}')
            return {}
        result = {}
        raw = items[key]
        if raw.get('Parent') in items:
            result.update(inherit(raw['Parent'], seen + (key,)))
        # ResourceTypes and Tags use inherited values unless explicitly replaced.
        # Bench descriptors below are read from every native tier/state as well.
        result.update(raw)
        resolved[key] = result
        return result

    membership = collections.defaultdict(set)
    tags = collections.defaultdict(set)
    benches = collections.defaultdict(set)
    def inspect_benches(block):
        if not isinstance(block, dict):
            return  # Native state aliases are strings rather than block overrides.
        bench = block.get('Bench', {})
        if bench.get('Id') and bench.get('Type'):
            categories = {c['Id'] for c in bench.get('Categories', []) if 'Id' in c}
            benches[(bench['Id'], bench['Type'])].update(categories)
        for state in block.get('State', {}).get('Definitions', {}).values():
            inspect_benches(state)

    for key in items:
        item = inherit(key)
        for resource in item.get('ResourceTypes', []):
            membership[resource['Id']].add(key)
            if key in owned_items:
                check(resource['Id'] in resources, f'{owned_items[key]}: undefined resource membership {resource["Id"]}')
        for group, values in item.get('Tags', {}).items():
            for value in values:
                tags[group + '=' + value].add(key)
            if values:
                tags[group].add(key)
        inspect_benches(item.get('BlockType', {}))

    for key, name in owned_items.items():
        recipe = items[key].get('Recipe')
        if recipe is not None:
            recipes.append((name + ':Recipe', recipe, key))
    for name in mod.files('Server/Item/Recipes'):
        recipes.append((name, mod.read(name), None))

    material_count = 0
    used_resources = set()
    def material(value, location, concrete=False):
        nonlocal material_count
        material_count += 1
        if not isinstance(value, dict):
            check(False, location + ': material must be an object')
            return
        quantity = value.get('Quantity', 1)
        check(isinstance(quantity, int) and not isinstance(quantity, bool) and quantity > 0,
              location + ': Quantity must be a positive integer')
        selectors = ('ItemId',) if concrete else ('ItemId', 'ResourceTypeId', 'ItemTag')
        check(any(value.get(k) for k in selectors), location + ': material has no resolvable selector')
        if 'ItemId' in value:
            check(value['ItemId'] in items, location + ': missing ItemId ' + str(value['ItemId']))
        if 'ResourceTypeId' in value:
            resource = value['ResourceTypeId']
            used_resources.add(resource)
            check(resource in resources, location + ': missing ResourceTypeId ' + str(resource))
            check(bool(membership[resource]), location + ': resource has no member items ' + str(resource))
        if 'ItemTag' in value:
            check(bool(tags[value['ItemTag']]), location + ': ItemTag matches no items ' + str(value['ItemTag']))

    standalone = 0
    for location, recipe, owner in recipes:
        inputs = recipe.get('Input')
        check(isinstance(inputs, list) and bool(inputs), location + ': Input must be nonempty')
        for index, value in enumerate(inputs or []):
            material(value, f'{location}.Input[{index}]')
        for index, value in enumerate(recipe.get('Output') or []):
            material(value, f'{location}.Output[{index}]', concrete=True)
        if owner is None:
            standalone += 1
            primary = recipe.get('PrimaryOutput')
            check(primary is not None, location + ': explicit PrimaryOutput required; standalone Output does not infer it')
            if primary is not None:
                material(primary, location + '.PrimaryOutput', concrete=True)
                if recipe.get('Output'):
                    check(any(o.get('ItemId') == primary.get('ItemId') for o in recipe['Output']),
                          location + ': PrimaryOutput must also appear in Output')
        else:
            # Item.processConfig always creates an embedded recipe's primary output.
            material({'ItemId': owner, 'Quantity': recipe.get('OutputQuantity', 1)}, location + '.ImplicitPrimaryOutput', concrete=True)
        for bench in recipe.get('BenchRequirement') or []:
            key = (bench.get('Id'), bench.get('Type'))
            check(key in benches, f'{location}: missing native bench {key}')
            for category in bench.get('Categories') or []:
                check(category in benches[key], f'{location}: undefined category {category} on bench {key}')

    catalog_name = 'Server/StrangeMatter/recipes.json'
    catalog = mod.read(catalog_name) if mod.exists(catalog_name) else []
    for recipe in catalog:
        location = catalog_name + ':' + recipe['id']
        material({'ItemId': recipe['output'], 'Quantity': recipe['quantity']}, location + '.output', concrete=True)
        for key, quantity in recipe.get('ingredients', {}).items():
            value = {'ResourceTypeId': key.removeprefix('resource:')} if key.startswith('resource:') else {'ItemId': key}
            material(dict(value, Quantity=quantity), location + '.ingredients.' + key)
        for family, quantity in recipe.get('shards', {}).items():
            material({'ItemId': hid(family + '_shard'), 'Quantity': quantity}, location + '.shards.' + family)

    for resource in used_resources:
        if resource not in resources:
            continue
        source, name, definition = resources[resource]
        icon = definition.get('Icon')
        check(isinstance(icon,str) and icon.startswith('Icons/ResourceTypes/') and '..' not in Path(icon).parts,
              name + ': resource icon must remain under Icons/ResourceTypes/')
        check(bool(icon) and (mod.exists('Common/' + str(icon)) or native.exists('Common/' + str(icon))),
              name + ': resource icon missing or unresolved')

    # Minecraft's tag permits any of six anomaly shards, never a seventh synthetic item.
    tag_file = minecraft / 'src/main/resources/data/strangematter/tags/items/anomaly_shards.json'
    expected_shards = SOURCE_SHARDS
    expected_costs = SOURCE_ANY_SHARD_COSTS
    if tag_file.is_file():
        expected_shards = {hid(value) for value in json.loads(tag_file.read_text())['values']}
        expected_costs = {}
        for path in (minecraft / 'src/main/resources/data/strangematter/recipes').glob('*.json'):
            source = json.loads(path.read_text())
            symbols = collections.Counter(''.join(source.get('pattern', [])))
            expected = sum(symbols[symbol] for symbol, ingredient in source.get('key', {}).items()
                           if ingredient.get('tag') == 'strangematter:anomaly_shards')
            expected += sum(ingredient.get('tag') == 'strangematter:anomaly_shards' for ingredient in source.get('ingredients', []))
            if not expected:
                continue
            result = source['result']
            output = hid(result if isinstance(result, str) else result['item'])
            expected_costs[output] = expected
    for output, expected in expected_costs.items():
        recipe = items.get(output, {}).get('Recipe', {})
        actual = 0
        for ingredient in recipe.get('Input', []):
            members = membership[ingredient['ResourceTypeId']] if 'ResourceTypeId' in ingredient else tags[ingredient['ItemTag']] if 'ItemTag' in ingredient else {ingredient.get('ItemId')}
            if members == expected_shards:
                actual += ingredient.get('Quantity', 1)
        check(actual == expected, f'{output}: source requires {expected} any anomaly shard(s), matched quantity {actual}')

    return {'status': 'FAIL' if errors else 'PASS', 'recipes': len(recipes), 'embedded': len(recipes) - standalone,
            'standalone': standalone, 'materialsChecked': material_count, 'resourceTypesUsed': sorted(used_resources),
            'auditedCatalogRecipes': len(catalog), 'sourceAnyShardRecipes': len(expected_costs), 'sourceAnyShardMembers': sorted(expected_shards),
            'sourceSemantics': 'Minecraft checkout' if tag_file.is_file() else 'Verified source snapshot',
            'nativeAssets': str(native.path), 'errors': errors,
            'checks': ['All recipe ItemId references', 'Resource type definitions, icons and members',
                       'Standalone explicit primary outputs', 'Native bench IDs, types and categories',
                       'Positive material quantities', 'Original six-shard tag semantics and recipe quantities']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--assets', type=Path, help='HytaleAssets directory or installed Assets.zip')
    parser.add_argument('--resources', type=Path, default=ROOT / 'src/main/resources')
    parser.add_argument('--minecraft', type=Path, default=DEFAULT_MC)
    parser.add_argument('--report', type=Path, default=ROOT / 'tools/assets/recipe-validation.json')
    args = parser.parse_args()
    native_path = args.assets or DEFAULT_ASSETS
    if not native_path.exists() and args.assets is None:
        native_path = Path(os.environ.get('APPDATA', '')) / 'Hytale/install/release/package/game/latest/Assets.zip'
    if not native_path.exists():
        parser.error('Hytale assets unavailable; provide --assets /path/to/Assets.zip or HytaleAssets')
    result = validate(Assets(args.resources), Assets(native_path), args.minecraft)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result, indent=2))
    raise SystemExit(bool(result['errors']))


if __name__ == '__main__':
    main()
