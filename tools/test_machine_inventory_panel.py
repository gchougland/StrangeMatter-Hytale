"""Structural checks for the shared native inventory layout, not a client interaction emulator."""
from pathlib import Path
import re
import unittest
from validate_ui import structure
from validate_laboratory_ui import block, anchor

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / 'src/main/resources/Common/UI/Custom/StrangeMatter/MachineInventoryPanel.ui'
ROW = UI.with_name('MachineInventoryRow.ui')
SLOT = UI.with_name('MachineInventorySlot.ui')


def validate(text, row, slot):
    structure(text, UI.name)
    structure(row, ROW.name)
    structure(slot, SLOT.name)
    expected = {
        'MachineInputGrid': {'Left':10,'Top':36,'Width':242,'Height':230},
        'MachineOutputGrid': {'Left':268,'Top':36,'Width':242,'Height':230},
        'PlayerStorageGrid': {'Left':10,'Top':36,'Width':414,'Height':184},
        'PlayerHotbarGrid': {'Left':10,'Top':236,'Width':414,'Height':42},
    }
    actual=set(re.findall(r'Group\s+#(\w*Grid)\s*\{',text))
    assert actual==set(expected), 'Expose only machine input, machine output, player storage and hotbar'
    assert not re.search(r'\bItemGrid\b',text),'Slot cells belong to the reusable single slot template'
    for name,bounds in expected.items():
        body=block(text,name)
        assert anchor(body)==bounds,name+' must retain its full native inventory panel area'
        if name.startswith('Machine'):
            assert 'LayoutMode: TopScrolling;' in body and 'DefaultScrollbarStyle' in body,name+' must support recovery inventory scrolling'
        else:
            assert 'LayoutMode: Top;' in body,name+' rows must stack vertically'
    assert re.search(r'Group(?:\s+#\w+)?\s*\{',row),'Rows must accept appended cell children'
    assert anchor(row).get('Height')==46 and 'LayoutMode: Left;' in row,'One row must fit native slots plus a four pixel vertical gap'
    wrappers=re.findall(r'Group\s+#(\w+)\s*\{',slot)
    assert wrappers==['InventoryCell'],'Each cell needs one stable wrapper for its slot and displayed revision'
    wrapper=block(slot,'InventoryCell')
    assert anchor(wrapper)=={'Width':46,'Height':42},'Cell footprint must fit all nine native inventory columns'
    cells=re.findall(r'ItemGrid\s+#(\w+)\s*\{([^{}]*)\}',wrapper)
    assert len(cells)==1 and cells[0][0]=='Slot','Exactly one native slot must be nested inside each cell'
    cell=cells[0][1]
    assert anchor(cell)=={'Width':46,'Height':42},'The native slot must fit its cell without clipping'
    revisions=re.findall(r'TextField\s+#(\w+)\s*\{([^{}]*)\}',wrapper)
    assert len(revisions)==1 and revisions[0][0]=='DisplayRevision','Each cell needs one native Value field for stable event bindings'
    revision=revisions[0][1]
    assert re.search(r'Visible:\s*false\s*;',revision),'The revision field must never appear over or intercept a visible inventory slot'
    assert re.search(r'Value:\s*"0"\s*;',revision),'The revision field must have a defined initial native Value'
    assert re.search(r'SlotsPerRow:\s*1\s*;',cell),'One slot per cell supplies an unambiguous static event address'
    for property_name in ('AreItemsDraggable','RenderEmptySlots','DisplayItemQuantity'):
        assert re.search(property_name+r':\s*true\s*;',cell),property_name+' must be enabled for occupied and empty native slots'
    assert re.search(r'IsCreativeSource:\s*false\s*;',cell),'Real inventory slots must not be creative item sources'
    for property_name,value in [('SlotSize',42),('SlotIconSize',36),('SlotSpacing',4)]:
        assert re.search(property_name+rf':\s*{value}\s*[,)]',slot),property_name+' must retain the readable native slot style'
    assert 9*anchor(wrapper)['Width']==expected['PlayerStorageGrid']['Width']==expected['PlayerHotbarGrid']['Width']
    assert 4*anchor(row)['Height']<=expected['PlayerStorageGrid']['Height'],'All 36 main inventory slots must fit'
    assert 5*anchor(cell)['Width']<=expected['MachineInputGrid']['Width'],'Five machine cells must fit each row'
    assert 'Label #MachineInputLabel' in text and 'Label #MachineOutputLabel' in text
    assert 'Text: "YOUR INVENTORY";' in text and 'Text: "HOTBAR";' in text,'Both player inventory sections need visible labels'
    assert re.search(r'Group #MachineInventoryPanel\s*\{\s*Anchor:\s*\(Width:\s*984,\s*Height:\s*310\)', text)
    assert not re.search(r'\b(?:Backpack|Load|Collect|Deposit)\b', text+row+slot), 'No backpack or transfer button substitutes'
    for path in re.findall(r'SlotBackground:\s*"([^"]+)"', slot):
        native = ROOT.parent / 'HytaleSourceCode/hytale-shared-source/HytaleAssets/Common/UI/Custom/StrangeMatter' / path
        local = UI.parent / path
        assert any(candidate.exists() for p in (local, native.resolve())
                   for candidate in (p, p.with_stem(p.stem + '@2x'))), f'Native slot art does not resolve: {path}'


class MachineInventoryPanelTests(unittest.TestCase):
    def test_current_layout(self):
        validate(UI.read_text(),ROW.read_text(),SLOT.read_text())

    def test_missing_hotbar_is_rejected(self):
        text = re.sub(r'Group #PlayerHotbarGrid\s*\{[^{}]*\}', '', UI.read_text())
        with self.assertRaises(AssertionError):
            validate(text,ROW.read_text(),SLOT.read_text())

    def test_disabled_native_dragging_is_rejected(self):
        with self.assertRaises(AssertionError):
            validate(UI.read_text(),ROW.read_text(),SLOT.read_text().replace('AreItemsDraggable: true;', 'AreItemsDraggable: false;', 1))

    def test_multiple_slot_cell_is_rejected(self):
        with self.assertRaises(AssertionError):
            validate(UI.read_text(),ROW.read_text(),SLOT.read_text().replace('SlotsPerRow: 1;', 'SlotsPerRow: 2;', 1))

    def test_oversized_cell_cannot_hide_last_hotbar_slot(self):
        with self.assertRaises(AssertionError):
            validate(UI.read_text(),ROW.read_text(),SLOT.read_text().replace('Width: 46', 'Width: 48', 1))

    def test_oversized_nested_slot_is_rejected(self):
        text=re.sub(r'(ItemGrid\s+#Slot\s*\{\s*Anchor:\s*\(Width:\s*)46',r'\g<1>48',SLOT.read_text(),count=1)
        with self.assertRaises(AssertionError):
            validate(UI.read_text(),ROW.read_text(),text)

    def test_visible_revision_field_is_rejected(self):
        with self.assertRaises(AssertionError):
            validate(UI.read_text(),ROW.read_text(),SLOT.read_text().replace('Visible: false;', 'Visible: true;', 1))

    def test_missing_revision_field_is_rejected(self):
        text=re.sub(r'TextField #DisplayRevision\s*\{[^{}]*\}', '', SLOT.read_text())
        with self.assertRaises(AssertionError):
            validate(UI.read_text(),ROW.read_text(),text)

    def test_lost_main_inventory_height_is_rejected(self):
        with self.assertRaises(AssertionError):
            validate(UI.read_text().replace('Height: 184','Height: 126',1),ROW.read_text(),SLOT.read_text())


if __name__ == '__main__':
    unittest.main()
