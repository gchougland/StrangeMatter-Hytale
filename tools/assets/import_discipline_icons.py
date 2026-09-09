"""Import the six original PNGs without decoding or changing a pixel; decorate machine labels."""
from pathlib import Path
import hashlib
import json
import re
import shutil
import struct

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT.parent.parent / 'Projects/StrangeMatter-1.20.1/strange-matter/src/main/resources/assets/strangematter/textures/ui'
UI = ROOT / 'src/main/resources/Common/UI/Custom/StrangeMatter'
TYPES = ('cognition', 'energy', 'gravity', 'shadow', 'space', 'time')


def main():
    destination = UI / 'Disciplines'
    destination.mkdir(exist_ok=True)
    entries = []
    for name in TYPES:
        original = SOURCE / (name + '_icon.png')
        target = destination / (name + '.png')
        shutil.copyfile(original, target)
        data = target.read_bytes()
        width, height = struct.unpack('>II', data[16:24])
        entries.append({'discipline': name, 'source': 'textures/ui/' + original.name,
                        'resource': str(target.relative_to(ROOT / 'src/main/resources')).replace('\\', '/'),
                        'width': width, 'height': height, 'sha256': hashlib.sha256(data).hexdigest()})
    (ROOT / 'tools/assets/discipline-icons.json').write_text(json.dumps({'copiedWithoutModification': True, 'icons': entries}, indent=2) + '\n')
    path = UI / 'ResearchMachine.ui'
    text = path.read_text()
    for name in TYPES:
        upper = name.upper()
        if '#' + upper + 'Icon' not in text:
            pattern = r'Label\s+\{ Anchor: \(Left: 10, Top: 10, Width: 290, Height: 23\); Text: "' + upper + r' /'
            icon = f'Group #{upper}Icon {{ Anchor: (Left: 10, Top: 12, Width: 18, Height: 18); Background: (TexturePath: "Disciplines/{name}.png"); HitTestVisible: false; }}\n Label {{ Anchor: (Left: 34, Top: 10, Width: 264, Height: 23); Text: "{upper} /'
            text, count = re.subn(pattern, icon, text)
            assert count == 1, upper
        if '#' + upper + 'ShutterIcon' not in text:
            start = text.index('Group #' + upper + 'Shutter')
            at = text.index('Label  { Anchor: (Left: 12, Top: 106', start)
            additions = f'Group #{upper}ShutterIcon {{ Anchor: (Left: 142, Top: 65, Width: 24, Height: 24); Background: (TexturePath: "Disciplines/{name}.png"); HitTestVisible: false; }}\nLabel {{ Anchor: (Left: 12, Top: 93, Width: 284, Height: 17); Text: "{upper}"; Style: (FontSize: 11, TextColor: #8b9caf, HorizontalAlignment: Center); HitTestVisible: false; }}\n'
            text = text[:at] + additions + text[at:].replace('Top: 106', 'Top: 115', 1)
    if '#NoteDisciplineIcon' not in text:
        old = '  ItemIcon #NoteIcon { Anchor: (Left: 18, Top: 16, Width: 64, Height: 64); ItemId: "SM_Research_Notes"; }'
        assert old in text
        text = text.replace(old, old + '\n  Group #NoteDisciplineIcon { Anchor: (Left: 18, Top: 16, Width: 64, Height: 64); Background: (TexturePath: "Disciplines/cognition.png"); Visible: false; HitTestVisible: false; }')
    old = 'Label #NoteDisciplines { Anchor: (Left: 20, Top: 212, Width: 522, Height: 41); Style: (FontSize: 15, TextColor: #78dfe9, Wrap: true); }'
    text = text.replace(old, 'Group #NoteDisciplines { Anchor: (Left: 20, Top: 212, Width: 522, Height: 41); }')
    path.write_text(text)
    print('Imported six byte-identical discipline PNGs and updated their machine labels.')


if __name__ == '__main__':
    main()
