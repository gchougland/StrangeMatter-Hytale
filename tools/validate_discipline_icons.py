"""Exact original discipline PNG and native UI integration audit. Standard library only."""
from pathlib import Path
import hashlib
import json
import struct
from validate_laboratory_ui import block, anchor, rect

ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / 'src/main/resources'
UI = RESOURCES / 'Common/UI/Custom/StrangeMatter'
ORIGINAL = ROOT.parent.parent / 'Projects/StrangeMatter-1.20.1/strange-matter/src/main/resources/assets/strangematter'
TYPES = {'cognition', 'energy', 'gravity', 'shadow', 'space', 'time'}


def validate_bytes(entry, data):
    assert data.startswith(b'\x89PNG\r\n\x1a\n'), entry['discipline']
    assert hashlib.sha256(data).hexdigest() == entry['sha256'], 'Original icon bytes changed: ' + entry['discipline']
    assert struct.unpack('>II', data[16:24]) == (entry['width'], entry['height']) == (256, 256), 'Original dimensions changed'


def main():
    manifest = json.loads((ROOT / 'tools/assets/discipline-icons.json').read_text())
    assert manifest['copiedWithoutModification'] is True
    assert {entry['discipline'] for entry in manifest['icons']} == TYPES and len(manifest['icons']) == 6
    checked_source = 0
    for entry in manifest['icons']:
        data = (RESOURCES / entry['resource']).read_bytes()
        validate_bytes(entry, data)
        source = ORIGINAL / entry['source']
        if source.exists():
            assert source.read_bytes() == data, 'Shipped texture differs from Minecraft original'
            checked_source += 1
        bad = bytearray(data)
        bad[-8] ^= 1
        try:
            validate_bytes(entry, bytes(bad))
        except AssertionError:
            pass
        else:
            raise AssertionError('Pixel asset mutation was not rejected')

    machine = (UI / 'ResearchMachine.ui').read_text()
    for name in TYPES:
        upper = name.upper()
        for selector in [upper + 'Icon', upper + 'ShutterIcon']:
            body = block(machine, selector)
            assert f'TexturePath: "Disciplines/{name}.png"' in body, selector
            assert 'HitTestVisible: false;' in body
            rect(body, 310, 234)
        assert 'Text: "' + upper + ' /' in machine and f'Text: "{upper}"' in block(machine, upper + 'Shutter'), 'Icons must accompany their labels'
    assert 'Group #NoteDisciplines' in machine
    chip = (UI / 'ResearchDisciplineChip.ui').read_text()
    assert 'Group #DisciplineIcon' in chip and 'Label #DisciplineLabel' in chip
    for selector in ['DisciplineIcon', 'DisciplineLabel']:
        rect(block(chip, selector), 138, 20)
    note = anchor(block(machine, 'NoteDisciplines'))
    assert 3 * 174 <= note['Width'] and 21 + 20 <= note['Height'], 'Six note disciplines must fit'
    tablet = (UI / 'ResearchTablet.ui').read_text()
    assert 6 * 19 <= anchor(block(tablet, 'CostRows'))['Height']
    cost = (UI / 'ResearchDisciplineCost.ui').read_text()
    assert 'Group #CostIcon' in cost and 'Label #CostLabel' in cost
    for selector in ['CostIcon', 'CostLabel']:
        rect(block(cost, selector), 328, 19)
    node = (UI / 'ResearchTreeNode.ui').read_text()
    assert 'Group #NodeDisciplineIcon' in node and 'ItemIcon #NodeIcon' in node, 'Discipline symbols cannot replace equipment item icons'
    assert 'TextTooltipStyle:' in node, 'Node tooltips must remain'
    assert 'Background: #07171e;' in block(node, 'NodeCaption') and 'HitTestVisible: false;' in block(node, 'NodeCaption')
    assert 'Default: (Background: #000000(0))' in node, 'Only the title is backed; the node surface remains transparent'
    for name in ['ResearchPoint.ui', 'ResearchDisciplineCost.ui', 'ResearchDisciplineChip.ui', 'ResearchTreeNode.ui', 'ResearchTablet.ui', 'ResearchNoteRow.ui']:
        for entry in TYPES:
            relative = 'Disciplines/' + entry + '.png'
            if relative in (UI / name).read_text():
                assert (UI / relative).is_file(), (name, relative)
    report = {'status': 'PASS', 'originalIcons': 6, 'byteIdenticalSourceComparisons': checked_source,
              'mutatedAssetsRejected': 6, 'machineHeaders': 6, 'machineShutters': 6,
              'uses': ['Tablet observation balances', 'Research costs', 'Anomaly discipline nodes and details', 'Machine headers and shutters', 'Note rows and active instruments', 'Field guide legend', 'Typed scanner HUD helper'],
              'preservation': 'Original PNG bytes, dimensions, colors and transparency preserved. Equipment and material ItemIcons remain.'}
    (ROOT / 'tools/assets/discipline-icon-validation.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
