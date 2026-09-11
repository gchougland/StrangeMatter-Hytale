"""Surgical cognition UI art. Native Groups draw every stroke; no font or bitmap."""
from pathlib import Path
import json

ROOT = Path(__file__).resolve().parents[2]
UI = ROOT / 'src/main/resources/Common/UI/Custom/StrangeMatter'


def glyphs():
    # Deliberate orthogonal strokes keep the small symbols crisp. Each shape
    # has generous gaps and a distinct silhouette; no sampled diagonal pixels.
    definitions = [
        ('Fork', [(1,1,3,9),(9,1,3,20),(17,1,3,9),(1,8,19,3)], [(9,17,3,4)]),
        ('Eye', [(5,3,12,2),(3,5,4,2),(15,5,4,2),(1,7,3,8),(18,7,3,8),
                 (3,15,4,2),(15,15,4,2),(5,17,12,2)], [(8,8,6,6)]),
        ('Spiral', [(1,1,20,2),(19,1,2,20),(1,19,20,2),(1,6,2,15),
                    (1,6,14,2),(13,6,2,9),(7,13,8,2)], [(7,10,2,5)]),
        ('Crown', [(1,5,3,15),(9,1,4,19),(18,5,3,15),(1,17,20,3)], [(9,1,4,4)]),
        ('Hourglass', [(1,1,20,3),(1,18,20,3),(3,4,3,4),(16,4,3,4),
                       (6,7,3,3),(13,7,3,3),(6,12,3,3),(13,12,3,3),
                       (3,14,3,4),(16,14,3,4)], [(8,9,6,4)]),
        ('Gate', [(2,2,18,3),(2,5,3,16),(17,5,3,16)], [(8,9,6,3),(10,12,2,7)]),
        ('Star', [(9,1,4,20),(1,9,20,4),(6,6,10,10)], [(9,9,4,4)]),
        ('Comet', [(1,1,11,3),(1,4,3,8),(4,9,8,3),(9,4,3,5),
                   (14,9,7,2),(14,14,7,2),(9,14,2,7),(14,18,2,3)], [(5,5,3,3)]),
        ('Orbit', [(1,5,3,12),(3,2,5,3),(3,17,5,3),
                   (18,5,3,12),(14,2,5,3),(14,17,5,3)], [(9,5,4,4),(9,13,4,4)]),
    ]
    result = []
    for name, strokes, accents in definitions:
        # Merge equal spans vertically when that reduces native UI nodes.
        # Retain authored strokes where intersecting spans would fragment them.
        pixels = {}
        for role, rectangles in [('Ink', strokes), ('Accent', accents)]:
            for x,y,w,h in rectangles:
                for yy in range(y,y+h):
                    for xx in range(x,x+w): pixels[xx,yy] = role
        rectangles = []
        for role in ('Ink', 'Accent'):
            active = {}
            for y in range(23):
                spans = []
                xs = sorted(x for (x,yy),value in pixels.items() if yy == y and value == role)
                while xs:
                    start = end = xs.pop(0)
                    while xs and xs[0] == end+1: end = xs.pop(0)
                    spans.append((start,end-start+1))
                for span in list(active):
                    if span not in spans: rectangles.append(active.pop(span))
                for x,w in spans:
                    if (x,w) in active: active[x,w]['h'] += 1
                    else: active[x,w] = {'x':7+x, 'y':3+y, 'w':w, 'h':1, 'role':role}
        authored = [{'x':7+x, 'y':3+y, 'w':w, 'h':h, 'role':role}
                    for role, parts in [('Ink',strokes),('Accent',accents)] for x,y,w,h in parts]
        if len(authored) < len(rectangles): rectangles = authored
        result.append({'name':name, 'rectangles':rectangles})
    return result


def main(write_controls=True):
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
    if write_controls:
        page.write_text(text, encoding='utf8')
    report = {'snapshot': 'build/art-preservation/20260909T192207040422Z',
              'nativeTemplate': 'StrangeMatter/CognitionGlyphs.ui', 'glyphs': symbols,
              'buttonSize': [37, 29], 'strokePixels': 2, 'normal': {'Ink': '#64dce6', 'Accent': '#ab86dc'},
              'cue': {'Ink': '#efffff', 'Accent': '#a7faff', 'Background': '#31526a'},
              'preservation': 'Same nine buttons, anchors and native Activating bindings. Only cognition visual content changes.'}
    (ROOT / 'tools/assets/cognition-glyphs.json').write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--templates-only', action='store_true', help='Leave ResearchMachine.ui untouched while updating the reusable glyph templates')
    main(not parser.parse_args().templates_only)
