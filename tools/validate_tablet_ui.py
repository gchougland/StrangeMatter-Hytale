"""Native tablet markup and bounds regression checks. Python standard library only."""
from pathlib import Path
import json
import re
from validate_laboratory_ui import block, anchor, rect, separate

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / 'src/main/resources/Common/UI/Custom/StrangeMatter'


def validate(tablet, node, point):
    assert 'TopScrolling' in block(tablet, 'GraphScroll'), 'Extended catalog nodes must remain reachable'
    assert 'DefaultScrollbarStyle' in block(tablet, 'GraphScroll'), 'Native scrollbar style must resolve'
    assert anchor(block(tablet, 'Graph'))['Width'] == 728
    assert anchor(block(tablet, 'GraphScroll'))['Height'] >= 478
    assert 'TextTooltipStyle: $C.@DefaultTextTooltipStyle;' in block(node, 'SelectNode'), 'Native node tooltip style missing'
    assert 'Button #SelectNode' in node and 'ItemIcon #NodeIcon' in node
    assert 'Default: (Background: #000000(0))' in node, 'Transparent button surface must preserve the circuit connection up to its icon'
    children = ['NodeOutline', 'NodeFace', 'NodeIcon', 'NodeDisciplineIcon', 'NodeLamp', 'NodeCaption', 'NodeName']
    for selector in children:
        body = block(node, selector)
        assert 'HitTestVisible: false;' in body, 'Node children must leave input to their button'
        rect(body, 128, 68)
    separate(rect(block(node, 'NodeIcon'), 128, 68), rect(block(node, 'NodeName'), 128, 68))
    caption=rect(block(node,'NodeCaption'),128,68);name=rect(block(node,'NodeName'),128,68)
    assert caption[0]<=name[0] and caption[1]<=name[1] and caption[2]>=name[2] and caption[3]>=name[3], 'Caption backing must cover the name without hiding the node icon'
    separate(caption,rect(block(node,'NodeIcon'),128,68))
    for selector in ['PointIcon', 'PointName', 'PointCount']:
        rect(block(point, selector), 116, 43)
    assert (anchor(point)['Width'] + anchor(point)['Right']) * 6 <= anchor(block(tablet, 'Points'))['Width'], 'Six point chips must fit'
    details = block(tablet, 'Details')
    controls = ['DetailIcon', 'NodeTitle', 'NodeStatus', 'Description', 'Prerequisites', 'Costs', 'Purchase', 'Message']
    boxes = [rect(block(details, control), 364, 542) for control in controls]
    for i, first in enumerate(boxes):
        for other in boxes[i + 1:]:
            separate(first, other)
    assert anchor(block(details, 'Costs'))['Height'] >= 6 * 17, 'All six possible discipline costs need room'
    for selector in ['Scanned', 'Points', 'GeneralTab', 'ForgeTab', 'Journal', 'Close', 'Details']:
        rect(block(tablet, selector), 1232, 776)
    for group, selector in [('GeneralTab', 'General'), ('ForgeTab', 'Forge')]:
        container = anchor(block(tablet, group))
        rect(block(tablet, selector), container['Width'], container['Height'])


def main():
    text = [(UI / name).read_text() for name in ['ResearchTablet.ui', 'ResearchTreeNode.ui', 'ResearchPoint.ui']]
    validate(*text)
    mutations = [(0, 'Width: 714, Height: 44', 'Width: 600, Height: 44'),
                 (0, 'Top: 278, Width: 328, Height: 114', 'Top: 278, Width: 328, Height: 70'),
                 (1, 'HitTestVisible: false;', 'HitTestVisible: true;'),
                 (1, 'TextTooltipStyle: $C.@DefaultTextTooltipStyle;', ''),
                 (1, 'Default: (Background: #000000(0))', 'Default: (Background: #091e26)')]
    for i, old, new in mutations:
        broken = text.copy()
        assert old in broken[i]
        broken[i] = broken[i].replace(old, new, 1)
        try:
            validate(*broken)
        except AssertionError:
            continue
        raise AssertionError('Failed to reject broken tablet layout: ' + new)
    report = {'status': 'PASS', 'templates': len(text), 'rejectedRegressions': len(mutations),
              'checks': ['Native icon buttons and tooltips', 'Category tab and point chip bounds', 'Details and six discipline costs fit', 'Catalog extension scrolling', 'Node child hit testing'],
              'geometryVerification': 'ResearchTreeLayoutVerification executes the production router against all 30 nodes and custom catalog cases.',
              'limitation': 'Static markup and geometry checks. Does not run the native client renderer.'}
    (ROOT / 'tools/assets/tablet-ui-validation.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
