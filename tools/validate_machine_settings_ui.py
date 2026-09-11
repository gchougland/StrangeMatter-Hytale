"""Bounds, readable controls and shared-column checks for the actual machine settings UI.

Stdlib only. Checks geometry, not the native client renderer.
"""
from pathlib import Path
from itertools import combinations
import re
from validate_laboratory_ui import block, anchor, rect, separate

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / 'src/main/resources/Common/UI/Custom/StrangeMatter'


def group(source, names, width, height, minimum=36):
    boxes = []
    for name in names:
        bounds = rect(block(source, name), width, height)
        assert bounds[3] - bounds[1] >= minimum, f'#{name} is too short'
        boxes.append(bounds)
    for a, b in combinations(boxes, 2):
        separate(a, b)
    return boxes


def validate(factory, row, tube):
    settings = block(factory, 'SettingsGroup')
    settings_rect = rect(settings, 1140, 896)
    separate(settings_rect, rect(block(factory, 'InventoryHost'), 1140, 896))
    for selector in ('Title', 'Settings', 'Close'):
        separate(settings_rect, rect(block(factory, selector), 1140, 896))
    width, height = anchor(settings)['Width'], anchor(settings)['Height']
    controls = group(settings, ['AccessName', 'Grant', 'Revoke', 'Upgrade'], width, height)
    costs = block(settings, 'UpgradeCosts')
    assert 'LayoutMode: TopScrolling;' in costs and 'ScrollbarStyle:' in costs
    assert anchor(costs)['Height'] >= 250 and anchor(costs)['Width'] >= 600
    for control in controls:
        separate(rect(costs, width, height), control)
    for name in ('UpgradeTitle', 'UpgradeHelp'):
        bounds = rect(block(settings, name), width, height)
        separate(bounds, rect(costs, width, height))
        for control in controls:
            separate(bounds, control)
    # Native list width includes padding and scrollbar. Do not let cost counts cover names.
    group(row, ['UpgradeIcon', 'UpgradeName', 'UpgradeCount', 'UpgradeMissing'], 640, 44, 16)
    for name in ('UpgradeIcon', 'UpgradeName', 'UpgradeCount', 'UpgradeMissing'):
        assert 'HitTestVisible: false;' in block(row, name)
    stock = rect(block(factory, 'Stock'), 1140, 896)
    helper = rect(block(factory, 'StockHelp'), 1140, 896)
    separate(stock, helper)
    for name in ('StockDown', 'StockUp', 'PowerMeter'):
        separate(helper, rect(block(factory, name), 1140, 896))

    top = group(tube, [f'Face{i}' for i in range(6)] + ['Close', 'Apply'], 1120, 820)
    left = block(tube, 'ConnectionPanel')
    right = block(tube, 'FilterPanel')
    panels = [rect(left, 1120, 820), rect(right, 1120, 820)]
    separate(*panels)
    for panel in panels:
        for control in top:
            separate(panel, control)
    group(left, ['Off', 'Take', 'Send', 'Section'], 340, 570)
    group(left, ['HeldIcon', 'HeldName'], 340, 570)
    group(right, ['Match', 'Filter', 'Step'] + [key + direction for key in ('Leave', 'Fill', 'Priority', 'Batch') for direction in ('Minus', 'Plus')], 712, 570)
    samples = block(right, 'SamplesGroup')
    assert anchor(samples)['Width'] == 680
    boxes = group(samples, [f'Sample{i}' for i in range(5)] + [f'Clear{i}' for i in range(5)], 680, 152)
    for i in range(5):
        sample, clear = anchor(block(samples, f'Sample{i}')), anchor(block(samples, f'Clear{i}'))
        assert sample['Left'] == clear['Left'] and sample['Width'] == clear['Width'], f'Sample {i} and Clear must share a column'
        icon = anchor(block(samples, f'Icon{i}'))
        assert icon['Left'] * 2 + icon['Width'] == sample['Width'], f'Sample {i} icon is not centered'
        for child in (f'Icon{i}', f'SampleLabel{i}', f'SamplePrompt{i}'):
            body = block(samples, child)
            rect(body, sample['Width'], sample['Height'])
            assert 'HitTestVisible: false;' in body, f'{child} intercepts sample clicks'
    resource = block(right, 'ResourceGroup')
    assert 'Visible: false;' in resource
    rect(block(resource, 'Resource'), 680, 152)
    for key in ('Leave', 'Fill', 'Priority', 'Batch'):
        group(right, [key + 'Minus', key + 'Value', key + 'Plus'], 712, 570)


def main():
    originals = [(UI / name).read_text(encoding='utf-8') for name in ('Factory.ui', 'FactoryUpgradeCost.ui', 'TubeConfig.ui')]
    validate(*originals)
    negative = [
        (0, 'Height: 404', 'Height: 430', 'settings overlap inventory'),
        (0, 'LayoutMode: TopScrolling;', 'LayoutMode: Top;', 'upgrade list cannot scroll'),
        (1, 'HitTestVisible: false;', 'HitTestVisible: true;', 'cost icon swallows clicks'),
        (2, 'Left: 40, Top: 8', 'Left: 0, Top: 8', 'sample icon drifts left'),
        (2, 'Top: 112, Width: 128, Height: 40', 'Top: 112, Width: 128, Height: 30', 'short clear control'),
        (2, 'Left: 596, Top: 376', 'Left: 390, Top: 376', 'numeric controls overlap'),
    ]
    for index, before, after, why in negative:
        changed = list(originals)
        assert before in changed[index], why
        # For the factory scrolling regression, remove all scrolling declarations.
        changed[index] = changed[index].replace(before, after) if 'scroll' in why else changed[index].replace(before, after, 1)
        try:
            validate(*changed)
        except AssertionError:
            continue
        raise AssertionError('Negative regression escaped: ' + why)
    print('PASS machine settings geometry, actual icon rows, all five ghost columns, readable controls and 6 negative regressions')


if __name__ == '__main__':
    main()
