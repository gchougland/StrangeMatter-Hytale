"""Check current native tier drop contracts without locking model or texture edits."""
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'src/main/resources'


def check_drop(item_id, tier, definition, documents):
    for mode in ('Breaking', 'Physics'):
        gathering = definition['Gathering'][mode]
        assert not gathering.get('ItemId'), 'An explicit item would duplicate the drop list output'
        assert not gathering.get('DropListId'), 'Native gathering uses DropList, not DropListId'
        assert gathering.get('Quantity', 1) == 1, 'A machine drops once'
        drop = documents[gathering['DropList']]['Container']
        assert drop['Type'] == 'Single', 'A machine has exactly one deterministic drop'
        item = drop['Item']
        assert item['ItemId'] == item_id
        assert item.get('QuantityMin', 1) == item.get('QuantityMax', 1) == 1
        assert item['Metadata'] == {'SMFactoryTier': tier}, 'Only the tier travels, never owner or inventory'


def validate_current():
    drops = {p.stem: json.loads(p.read_text(encoding='utf-8-sig')) for p in (RES / 'Server/Drops').rglob('*.json')}
    count = 0
    for item_id, tiers in {'SM_Flux_Furnace': (2,), 'SM_Pattern_Assembler': (2, 3)}.items():
        item = json.loads((RES / 'Server/Item/Items/StrangeMatter' / (item_id + '.json')).read_text(encoding='utf-8-sig'))
        for tier in tiers:
            for suffix in ('', 'Working'):
                check_drop(item_id, tier, item['BlockType']['State']['Definitions'][f'Tier{tier}{suffix}'], drops)
                count += 1
    return count


class FactoryPickupTests(unittest.TestCase):
    def test_current_native_assets(self):
        self.assertEqual(validate_current(), 6)

    def test_duplicate_item_rejected(self):
        definition = {'Gathering': {'Breaking': {'DropList': 'Test', 'ItemId': 'SM_Flux_Furnace'}}}
        with self.assertRaisesRegex(AssertionError, 'duplicate'):
            check_drop('SM_Flux_Furnace', 2, definition, {})

    def test_owner_payload_rejected(self):
        definition = {'Gathering': {mode: {'DropList': 'Test'} for mode in ('Breaking', 'Physics')}}
        documents = {'Test': {'Container': {'Type': 'Single', 'Item': {'ItemId': 'SM_Flux_Furnace',
                        'Metadata': {'SMFactoryTier': 2, 'Owner': 'old owner'}}}}}
        with self.assertRaisesRegex(AssertionError, 'Only the tier'):
            check_drop('SM_Flux_Furnace', 2, definition, documents)


if __name__ == '__main__':
    unittest.main()
