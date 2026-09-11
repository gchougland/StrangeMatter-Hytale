"""Native tier drops. Explicit --apply changes only tier gathering and three drop definitions."""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / 'src/main/resources'
TIERS = {'SM_Flux_Furnace': (2,), 'SM_Pattern_Assembler': (2, 3)}


def configure_item(item, item_id):
    for tier in TIERS.get(item_id, ()):
        drop = f'{item_id}_Tier{tier}'
        for suffix in ('', 'Working'):
            definition = item['BlockType']['State']['Definitions'][f'Tier{tier}{suffix}']
            # No explicit ItemId: getDrops would add that on top of the drop list.
            definition['Gathering'] = {'Breaking': {'GatherType': 'Rocks', 'Quantity': 1, 'DropList': drop},
                                       'Physics': {'DropList': drop}}
    return item


def drop_documents():
    return {f'{item_id}_Tier{tier}': {'Container': {'Type': 'Single', 'Item': {
        'ItemId': item_id, 'QuantityMin': 1, 'QuantityMax': 1, 'Metadata': {'SMFactoryTier': tier}}}}
        for item_id, tiers in TIERS.items() for tier in tiers}


def apply():
    for item_id in TIERS:
        path = RES / 'Server/Item/Items/StrangeMatter' / f'{item_id}.json'
        item = configure_item(json.loads(path.read_text(encoding='utf-8-sig')), item_id)
        path.write_text(json.dumps(item, indent=2) + '\n', encoding='utf-8')
    folder = RES / 'Server/Drops/StrangeMatter'
    folder.mkdir(parents=True, exist_ok=True)
    for item_id, document in drop_documents().items():
        (folder / f'{item_id}.json').write_text(json.dumps(document, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    if args.apply:
        apply()
    else:
        parser.print_help()
