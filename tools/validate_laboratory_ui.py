"""Focused layout regression checks. Uses stdlib; does not emulate the native client."""
from pathlib import Path
import importlib.util
import json
import re

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / 'src/main/resources/Common/UI/Custom/StrangeMatter'


def block(text, selector):
    match = re.search(r'#' + re.escape(selector) + r'\s*\{', text)
    assert match, f'Missing #{selector}'
    depth, quoted, escaped = 1, False, False
    for end in range(match.end(), len(text)):
        char = text[end]
        if quoted:
            if escaped:
                escaped = False
            elif char == '\\':
                escaped = True
            elif char == '"':
                quoted = False
        elif char == '"':
            quoted = True
        elif char == '{':
            depth += 1
        elif char == '}':
            depth -= 1
            if depth == 0:
                return text[match.end():end]
    raise AssertionError(f'Unclosed #{selector}')


def anchor(body):
    match = re.search(r'Anchor:\s*\(([^)]+)\)', body)
    assert match, 'Expected an explicit anchor'
    return {key: int(value) for key, value in re.findall(r'(\w+):\s*(-?\d+)', match[1])}


def rect(body, width, height):
    a = anchor(body)
    w = a.get('Width', width - a.get('Left', 0) - a.get('Right', 0))
    h = a.get('Height', height - a.get('Top', 0) - a.get('Bottom', 0))
    x = a.get('Left', width - a.get('Right', 0) - w)
    y = a.get('Top', height - a.get('Bottom', 0) - h)
    assert 0 <= x and 0 <= y and w > 0 and h > 0, (x, y, w, h)
    assert x + w <= width and y + h <= height, ('Clipped anchor', a, width, height)
    return x, y, x + w, y + h


def separate(a, b):
    assert a[2] <= b[0] or b[2] <= a[0] or a[3] <= b[1] or b[3] <= a[1], ('Overlapping controls', a, b)


def validate(files):
    forge, research = files['RealityForge.ui'], files['ResearchMachine.ui']
    for source, selector in ((forge, 'Recipes'), (research, 'Notes')):
        scrolling = block(source, selector)
        assert 'LayoutMode: TopScrolling;' in scrolling, f'#{selector} must scroll'
        assert 'ScrollbarStyle: $C.@DefaultScrollbarStyle;' in scrolling, f'#{selector} needs native scrollbar'
        assert anchor(scrolling)['Height'] >= 360, f'#{selector} lost its usable list height'

    for filename, button, icon, name, state, accent, width in (
        ('ForgeRecipeRow.ui', 'SelectRecipe', 'RecipeIcon', 'RecipeName', 'RecipeState', 'RecipeAccent', 238),
        ('ResearchNoteRow.ui', 'SelectNote', 'NoteRowIcon', 'NoteName', 'NoteState', 'NoteAccent', 308),
    ):
        row = files[filename]
        assert f'Button #{button}' in row and f'ItemIcon #{icon}' in row, filename
        row_height = anchor(row)['Height']
        assert 65 <= row_height <= 90, filename
        for child in (icon, name, state, accent):
            assert 'HitTestVisible: false;' in block(row, child), f'{filename}: #{child} intercepts the button'
            rect(block(row, child), width, row_height)
        assert 'Wrap: true' in block(row, name), f'{filename}: long names cannot wrap'
        separate(rect(block(row, icon), width, row_height), rect(block(row, name), width, row_height))
        separate(rect(block(row, name), width, row_height), rect(block(row, state), width, row_height))

    # Main controls occupy independent rectangles; the recipe title also leaves room for its counter.
    controls = ['Recipes', 'Recipe', 'RecipeIndex', 'Research', 'Requirements', 'Message', 'Toggle', 'Craft', 'Collect', 'Close']
    bounds = [rect(block(forge, selector), 1140, 754) for selector in controls]
    for i, first in enumerate(bounds):
        for other in bounds[i + 1:]:
            separate(first, other)
    for i in range(8):
        material = block(forge, f'Material{i}')
        rect(material, 346, 330)
        children = [rect(block(material, f'{family}{i}'), 346, 37) for family in ('MaterialIcon', 'MaterialName', 'MaterialCount')]
        for a, b in zip(children, children[1:]):
            separate(a, b)
    assert 'ItemIcon #ForgePreview' in forge and 'Group #ForgeScan' in forge
    assert all(f'Group #ForgeMote{i}' in forge for i in range(8)), 'Crafting animation missing'

    picker = block(research, 'NotePicker')
    a = anchor(picker)
    assert a['Left'] == 0 and a['Top'] <= 92 and a['Width'] >= 960 and a['Top'] + a['Height'] >= 572, 'Picker must cover all six instruments'
    assert re.search(r'Background:\s*#[0-9a-fA-F]{6};', picker), 'Picker needs an opaque background'
    separate(rect(block(picker, 'Notes'), 960, 508), rect(block(picker, 'NoteDetails'), 960, 508))
    detail = block(picker, 'NoteDetails')
    controls = ['NoteIcon', 'NoteTitle', 'NoteDescription', 'NoteDisciplines', 'NotePrerequisites', 'InsertNote']
    bounds = [rect(block(detail, selector), 562, 379) for selector in controls]
    for i, first in enumerate(bounds):
        for other in bounds[i + 1:]:
            separate(first, other)
    assert '#InsertNote' not in files['ResearchNoteRow.ui'], 'Insertion must follow a reviewed selection'
    for discipline in ('COGNITION', 'ENERGY', 'GRAVITY', 'SHADOW', 'SPACE', 'TIME'):
        shutter = anchor(block(research, discipline + 'Shutter'))
        assert shutter == {'Left': 0, 'Top': 0, 'Right': 0, 'Bottom': 0}, f'{discipline} shutter must cover its whole panel'


def main():
    names = ['RealityForge.ui', 'ForgeRecipeRow.ui', 'ResearchMachine.ui', 'ResearchNoteRow.ui']
    files = {name: (UI / name).read_text(encoding='utf8') for name in names}
    validate(files)
    spec = importlib.util.spec_from_file_location('forge_layout', ROOT / 'tools/assets/build_forge_ui.py')
    generator = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(generator)
    assert files['RealityForge.ui'] == generator.render(), 'RealityForge.ui and its generator differ'
    mutations = [
        ('RealityForge.ui', 'LayoutMode: TopScrolling;', 'LayoutMode: Top;'),
        ('ForgeRecipeRow.ui', 'HitTestVisible: false;', 'HitTestVisible: true;'),
        ('ResearchMachine.ui', 'Width: 960, Height: 508', 'Width: 960, Height: 300'),
        ('ResearchNoteRow.ui', 'Top: 52, Right: 10', 'Top: 35, Right: 10'),
        ('RealityForge.ui', 'Left: 235, Top: 2, Width: 103', 'Left: 220, Top: 2, Width: 103'),
    ]
    for filename, before, after in mutations:
        broken = dict(files)
        assert before in broken[filename]
        broken[filename] = broken[filename].replace(before, after, 1)
        try:
            validate(broken)
        except AssertionError:
            continue
        raise AssertionError(f'Negative regression was not rejected: {filename}: {after}')
    result = {'status': 'PASS', 'templates': len(names), 'rejectedRegressions': len(mutations),
              'checks': ['Native scrolling and stable selectable row templates', 'Item icons and wrapped names preserve button hit testing',
                         'Control bounds and text regions do not overlap', 'Eight ingredient rows fit', 'Full research picker and six full shutters',
                         'Crafting animation selectors remain present', 'Forge generator matches shipped layout'],
              'limitation': 'Static layout verification. Native client parsing and visual playtesting are separate checks.'}
    (ROOT / 'tools/assets/laboratory-ui-validation.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf8')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
