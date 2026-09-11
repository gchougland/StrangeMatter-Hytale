"""Correct the current Imprinter hand attachment without rebuilding its artwork.

Run with --apply for a targeted snapshot and an idempotent grip wrapper. The
existing texture, icon and item definition are preserved. Historical comparisons
are explicit via --audit-art and never constrain later manual art edits.
"""
import argparse
import copy
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import zipfile
from fit_resonator_grip import empty_shape

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / 'src/main/resources'
MODEL = RES / 'Common/Items/StrangeMatter/echoform_imprinter.blockymodel'
VIEWS = ROOT / 'tools/assets/icon_views.json'
PRESERVED = (RES / 'Common/Items/StrangeMatter/echoform_imprinter.png',
             RES / 'Common/Icons/ItemsGenerated/SM_Echoform_Imprinter.png',
             RES / 'Server/Item/Items/StrangeMatter/SM_Echoform_Imprinter.json')


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def save(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')


def fit(model):
    result = copy.deepcopy(model)
    roots = result['nodes']
    if len(roots) == 1 and roots[0]['name'] == 'R-Attachment':
        assert roots[0]['shape']['settings'].get('isPiece'), 'Unexpected hand attachment'
        assert len(roots[0].get('children', [])) == 1 and roots[0]['children'][0]['name'] == 'SM_Imprinter_Grip', 'Review an already attached model separately'
        return result
    ids = set()
    def collect(nodes):
        for node in nodes:
            ids.add(str(node['id']))
            collect(node.get('children', []))
    collect(roots)
    next_id = max((int(value) for value in ids if value.isdecimal()), default=0) + 1
    # As in the native Thorium pickaxe, the named isPiece root matches the player
    # hand bone. Keep the turn below that bind boundary so attaching cannot erase it.
    # +Z is the authored instrument front; a Y half turn reverses it without tilt.
    grip = {'id': str(next_id), 'name': 'SM_Imprinter_Grip',
            'position': dict.fromkeys('xyz', 0), 'orientation': {'x': 0, 'y': 1, 'z': 0, 'w': 0},
            'shape': empty_shape(), 'children': roots}
    result['nodes'] = [{'id': str(next_id + 1), 'name': 'R-Attachment',
                        'position': dict.fromkeys('xyz', 0), 'orientation': {'x': 0, 'y': 0, 'z': 0, 'w': 1},
                        'shape': empty_shape(True), 'children': [grip]}]
    return result


def apply():
    current = read(MODEL)
    desired = fit(current)
    if current == desired:
        print('Imprinter hand attachment already fitted; current art and camera preserved')
        return
    stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    folder = ROOT / 'build/art-preservation' / ('imprinter-grip-' + stamp)
    folder.mkdir(parents=True)
    paths = (MODEL, *PRESERVED, VIEWS)
    hashes = {path.relative_to(ROOT).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest() for path in paths}
    common_hashes = {path.relative_to(RES / 'Common').as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
                     for path in (RES / 'Common').rglob('*') if path.is_file()}
    with zipfile.ZipFile(folder / 'Imprinter.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
        for path in paths:
            archive.write(path, path.relative_to(ROOT).as_posix())
    save(folder / 'sha256.json', hashes)
    save(folder / 'common-before.json', common_hashes)
    views = read(VIEWS)
    previous_view = views.get('SM_Echoform_Imprinter', {})
    views['SM_Echoform_Imprinter'] = {**previous_view, 'yaw': (previous_view.get('yaw', 35) + 180) % 360}
    save(MODEL, desired)
    save(VIEWS, views)
    assert desired['nodes'][0]['children'][0]['children'] == current['nodes']
    for path in PRESERVED:
        assert hashlib.sha256(path.read_bytes()).hexdigest() == hashes[path.relative_to(ROOT).as_posix()]
    changed = [name for name, digest in common_hashes.items()
               if hashlib.sha256((RES / 'Common' / name).read_bytes()).hexdigest() != digest]
    assert changed == [MODEL.relative_to(RES / 'Common').as_posix()], changed
    report = {'snapshot': folder.relative_to(ROOT).as_posix(), 'heldYawDegrees': 180,
              'authoredRootCountPreserved': len(current['nodes']), 'unchangedOtherCommonFiles': len(common_hashes) - 1,
              'changedCommonFiles': changed, 'textureChanged': False, 'iconChanged': False,
              'itemDefinitionChanged': False, 'futureIconCamera': views['SM_Echoform_Imprinter']}
    save(folder / 'result.json', report)
    print(json.dumps(report, indent=2))


def audit(folder):
    with zipfile.ZipFile(folder / 'Imprinter.zip') as archive:
        original = json.loads(archive.read(MODEL.relative_to(ROOT).as_posix()))
        assert fit(original) == read(MODEL), 'Additional model edits since this snapshot'
        for path in PRESERVED:
            assert archive.read(path.relative_to(ROOT).as_posix()) == path.read_bytes(), str(path)
    print('Explicit historical Imprinter artwork audit PASS')


def preview(folder):
    """Render current faces and the supplied player bind pose without changing icons."""
    from PIL import Image, ImageDraw, ImageFont
    import render_current_icons as renderer
    with zipfile.ZipFile(folder / 'Imprinter.zip') as archive:
        original = json.loads(archive.read(MODEL.relative_to(ROOT).as_posix()))
    current = read(MODEL)
    texture = Image.open(PRESERVED[0])
    native = read(renderer.NATIVE / 'Characters/Player_With_Face.blockymodel')
    body = renderer.model_faces(native, Image.open(renderer.NATIVE / 'Characters/Player_Textures/Player_Greyscale.png'))
    before = fit(original)
    before['nodes'][0]['children'][0]['orientation'] = {'x': 0, 'y': 0, 'z': 0, 'w': 1}
    entries = [
        ('Authored instrument front', renderer.model_faces(original, texture), 35, 23),
        ('Corrected grip with matching front camera', renderer.model_faces(current, texture), 215, 23),
        ('Native hand reference before the turn', body + renderer.attachment_faces(before, texture, native), 90, 5),
        ('Native hand reference after the turn', body + renderer.attachment_faces(current, texture, native), 90, 5),
    ]
    size = 420
    sheet = Image.new('RGB', (size * 2, 72 + (size + 36) * 2), '#11192a')
    draw = ImageDraw.Draw(sheet)
    font = ImageFont.truetype('C:/Windows/Fonts/segoeui.ttf', 16)
    draw.text((20, 12), 'Echoform Imprinter hand orientation', fill='#c8eef0', font=font)
    draw.text((20, 39), 'Static attachment reference. Not a native client screenshot.', fill='#93a8bf', font=font)
    for index, (label, faces, yaw, pitch) in enumerate(entries):
        x, y = index % 2 * size, 72 + index // 2 * (size + 36)
        picture = renderer.render(faces, size, yaw=yaw, pitch=pitch)
        sheet.paste(picture, (x, y), picture)
        draw.text((x + size // 2, y + size + 8), label, fill='#c8dce8', font=font, anchor='mt')
    path = folder / 'imprinter-grip-preview.png'
    sheet.save(path)
    print(path)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--audit-art', type=Path)
    parser.add_argument('--preview', type=Path, help='Snapshot folder for a static comparison; requires Pillow and numpy')
    args = parser.parse_args()
    if args.apply:
        apply()
    elif args.audit_art:
        audit(args.audit_art)
    elif args.preview:
        preview(args.preview)
    else:
        print('Imprinter attachment already fitted' if fit(read(MODEL)) == read(MODEL)
              else 'Imprinter can be fitted; pass --apply to preserve and wrap current art')
