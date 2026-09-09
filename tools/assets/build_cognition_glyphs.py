"""Surgical cognition UI art. Native Groups draw every stroke; no font or bitmap."""
from pathlib import Path
import json

ROOT = Path(__file__).resolve().parents[2]
UI = ROOT / 'src/main/resources/Common/UI/Custom/StrangeMatter'


def line(points, target):
    for a, b in zip(points, points[1:]):
        steps = max(abs(b[0] - a[0]), abs(b[1] - a[1]))
        for i in range(steps + 1):
            t = i / max(1, steps)
            target.add((round(a[0] + (b[0] - a[0]) * t), round(a[1] + (b[1] - a[1]) * t)))


def glyphs():
    definitions = [
        ('Fork', [[(2, 0), (2, 10), (8, 10), (8, 6), (5, 6)], [(2, 3), (9, 3), (6, 0)]], [(2, 0), (5, 6)]),
        ('Eye', [[(5, 0), (10, 5), (5, 10), (0, 5), (5, 0)]], [(4, 4), (5, 4), (6, 4), (4, 5), (5, 5), (6, 5), (4, 6), (5, 6), (6, 6)]),
        ('Spiral', [[(10, 2), (10, 0), (0, 0), (0, 10), (10, 10), (10, 4), (4, 4), (4, 7), (7, 7)]], [(7, 7)]),
        ('Crown', [[(0, 2), (0, 9), (10, 9), (10, 2), (7, 5), (5, 1), (3, 5), (0, 2)]], [(5, 0), (5, 1), (4, 9), (5, 9), (6, 9)]),
        ('Hourglass', [[(1, 1), (9, 1), (1, 9), (9, 9), (1, 1)]], [(4, 5), (5, 5), (6, 5)]),
        ('Gate', [[(1, 10), (1, 3), (4, 0), (6, 0), (9, 3), (9, 10)]], [(4, 4), (5, 4), (6, 4), (5, 5), (5, 6), (5, 7)]),
        ('Star', [[(5, 1), (6, 4), (9, 5), (6, 6), (5, 9), (4, 6), (1, 5), (4, 4), (5, 1)]], [(0, 0), (10, 0), (0, 10), (10, 10), (5, 5)]),
        ('Comet', [[(3, 0), (6, 3), (3, 6), (0, 3), (3, 0)], [(6, 6), (10, 10)], [(7, 3), (10, 6)], [(3, 7), (6, 10)]], [(3, 3), (10, 10)]),
        ('Orbit', [[(4, 1), (2, 1), (0, 3), (0, 7), (2, 9), (4, 9)], [(6, 1), (8, 1), (10, 3), (10, 7), (8, 9), (6, 9)]], [(5, 3), (5, 7)]),
    ]
    output = []
    for name, paths, dots in definitions:
        ink = set()
        for points in paths:
            line(points, ink)
        accent = set(dots)
        ink -= accent
        rectangles = []
        for role, pixels in [('Ink', ink), ('Accent', accent)]:
            for y in range(11):
                xs = sorted(x for x, yy in pixels if yy == y)
                while xs:
                    start = end = xs.pop(0)
                    while xs and xs[0] == end + 1:
                        end = xs.pop(0)
                    rectangles.append({'x': 7 + start * 2, 'y': 3 + y * 2,
                                       'w': (end - start + 1) * 2, 'h': 2, 'role': role})
        output.append({'name': name, 'rectangles': rectangles})
    return output


def main():
    symbols = glyphs()
    templates = ['// Literal-color native vector glyphs. All strokes pass input through to the existing button.', '']
    for symbol in symbols:
        # Instances cannot declare parameters after properties. Separate literal-color
        # templates also make identical cue geometry explicit without overrides.
        for suffix, palette in [('', {'Ink': '#64dce6', 'Accent': '#ab86dc'}),
                                ('Lit', {'Ink': '#efffff', 'Accent': '#a7faff'})]:
            templates += [f"@{symbol['name']}{suffix} = Group {{", ' HitTestVisible: false;']
            for r in symbol['rectangles']:
                templates.append(f" Group {{ Anchor: (Left: {r['x']}, Top: {r['y']}, Width: {r['w']}, Height: {r['h']}); Background: {palette[r['role']]}; HitTestVisible: false; }}")
            templates += ['};', '']
    (UI / 'CognitionGlyphs.ui').write_text('\n'.join(templates), encoding='utf8')
    page = UI / 'ResearchMachine.ui'
    text = page.read_text(encoding='utf-8-sig')
    if '$G = "CognitionGlyphs.ui";' not in text:
        text = text.replace('$R = "ResearchStyle.ui";', '$R = "ResearchStyle.ui";\n$G = "CognitionGlyphs.ui";', 1)
    start = text.index('$R.@Control #Rune0')
    end = text.index('Label #CognitionReadout', start)
    controls = []
    for i, symbol in enumerate(symbols):
        x, y = 76 + (i % 3) * 42, (i // 3) * 34
        anchor = f'Anchor: (Left: {x}, Top: {y}, Width: 37, Height: 29);'
        controls += [f'$R.@Control #Rune{i} {{ Text: ""; {anchor} }}',
            f"$G.@{symbol['name']} #RuneMark{i} {{ {anchor} }}",
            f"$G.@{symbol['name']}Lit #RuneGlow{i} {{ {anchor} Background: #31526a; Visible: false; HitTestVisible: false; }}"]
    text = text[:start] + '\n'.join(controls) + '\n' + text[end:]
    text = text.replace('Watch the lit keys, then repeat their order.', 'Watch the glowing symbols, then repeat their order.')
    page.write_text(text, encoding='utf8')
    report = {'snapshot': 'build/art-preservation/20260909T192207040422Z',
              'nativeTemplate': 'StrangeMatter/CognitionGlyphs.ui', 'glyphs': symbols,
              'buttonSize': [37, 29], 'strokePixels': 2, 'normal': {'Ink': '#64dce6', 'Accent': '#ab86dc'},
              'cue': {'Ink': '#efffff', 'Accent': '#a7faff', 'Background': '#31526a'},
              'preservation': 'Same nine buttons, anchors and native Activating bindings. Only cognition visual content changes.'}
    (ROOT / 'tools/assets/cognition-glyphs.json').write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
