"""Read-only audit of the targeted art revision against its pre-edit ZIP.

This preservation audit is tied to this revision. After later intentional art edits,
keep this report as history and use validate_presentation.py for current assets.
"""
from pathlib import Path
import argparse
import hashlib
import json
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools/assets'))
from apply_asset_polish import rotate_model, canonical
from content_policy import descriptions


def validate(resources, snapshot):
    common = resources / 'Common'
    errors = []
    def check(condition, message):
        if not condition: errors.append(message)
    def read(path): return json.loads(path.read_text(encoding='utf-8-sig'))
    manifest = read(snapshot / 'sha256.json')
    shared = read(ROOT / 'tools/assets/shared-models.json')
    rotations = {'Items/StrangeMatter/field_scanner.blockymodel': 180,
                 'Items/StrangeMatter/graviton_hammer.blockymodel': 90}
    selected_icons = {'Icons/ItemsGenerated/SM_Field_Scanner.png', 'Icons/ItemsGenerated/SM_Graviton_Hammer.png'}
    counts = {'unchangedModelOrTextureFiles': 0, 'exactAliases': 0, 'rootOnlyRotations': 0, 'untouchedIcons': 0}
    with zipfile.ZipFile(snapshot / 'Resources.zip') as archive:
        for key, expected in manifest['files'].items():
            prefix = 'src/main/resources/Common/'
            if not key.startswith(prefix): continue
            relative = key[len(prefix):]
            path = common / relative
            if relative in shared['aliases']:
                old = json.loads(archive.read(key))
                target = common / shared['aliases'][relative]
                check(target.exists() and canonical(read(target)) == canonical(old), 'Distinct model or UV edit lost: ' + relative)
                check(not path.exists(), 'Obsolete duplicate model remains: ' + relative)
                counts['exactAliases'] += 1
            elif relative in rotations:
                check(read(path) == rotate_model(json.loads(archive.read(key)), rotations[relative]), 'Held rotation changed local geometry/UVs: ' + relative)
                counts['rootOnlyRotations'] += 1
            elif path.suffix == '.blockymodel' or (path.suffix == '.png' and not relative.startswith(('Icons/', 'Particles/', 'UI/'))):
                check(path.exists() and hashlib.sha256(path.read_bytes()).hexdigest() == expected, 'Unrequested source art change: ' + relative)
                counts['unchangedModelOrTextureFiles'] += 1
            elif relative.startswith('Icons/ItemsGenerated/') and relative not in selected_icons:
                check(path.exists() and hashlib.sha256(path.read_bytes()).hexdigest() == expected, 'Unrequested inventory icon rerender: ' + relative)
                counts['untouchedIcons'] += 1

        for group in shared['groups']:
            target = common / group['model']
            check(hashlib.sha256(canonical(read(target))).hexdigest() == group['semanticSha256'], 'Shared model differs from its recorded exact group: ' + group['model'])
            for item_id in group['items']:
                key = 'src/main/resources/Server/Item/Items/StrangeMatter/' + item_id + '.json'
                before = json.loads(archive.read(key))
                current = read(resources / 'Server/Item/Items/StrangeMatter' / (item_id + '.json'))
                path = current.get('Model', current.get('BlockType', {}).get('CustomModel'))
                check(path == group['model'], 'Item does not use its exact shared model: ' + item_id)
                check(before.get('Texture') == current.get('Texture'), 'Item texture changed: ' + item_id)
                check(before.get('BlockType', {}).get('CustomModelTexture') == current.get('BlockType', {}).get('CustomModelTexture'), 'Block texture changed: ' + item_id)
                check(before.get('Interactions') == current.get('Interactions'), 'Family item interaction changed: ' + item_id)

    for family in ('Gravitic', 'Chrono', 'Energetic', 'Spatial', 'Shade', 'Insight'):
        lights = [read(resources / 'Server/Item/Items/StrangeMatter' / f'SM_{family}_Shard_{suffix}.json')['BlockType']['Light'] for suffix in ('Crystal', 'Lamp', 'Lantern')]
        crystal = [int(c, 16) for c in lights[0]['Color'][1:]]
        for light in lights[1:]:
            check([int(c, 16) for c in light['Color'][1:]] == [c * 3 for c in crystal] and light['Radius'] == 15,
                  family + ' fixture hue differs from crystal or brightness is not15')
    lang = dict(line.split('=', 1) for line in (resources / 'Server/Languages/en-US/server.lang').read_text(encoding='utf8').splitlines() if '=' in line)
    for path in (resources / 'Server/Item/Items/StrangeMatter').glob('*.json'):
        item = read(path); key = item['TranslationProperties']['Description'].removeprefix('server.')
        value = lang.get(key, '')
        check(len(value) > 30 and 'A Strange Matter material or laboratory' not in value, 'Missing specific tooltip: ' + path.stem)
    dirt = read(resources / 'Server/Item/Items/StrangeMatter/SM_Anomalous_Dirt.json')
    check(dirt['BlockType']['DrawType'] == 'Cube' and dirt['BlockType']['Textures'][0]['All'] == 'BlockTextures/StrangeMatter/Anomalous_Grass_Soil.png', 'Anomalous dirt does not use existing native cube soil art')
    return {'status': 'FAIL' if errors else 'PASS', **counts, 'sharedModels': len(shared['groups']), 'tooltips': len(descriptions()), 'errors': errors}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--snapshot', type=Path)
    args = parser.parse_args()
    snapshot = args.snapshot or ROOT / json.loads((ROOT / 'tools/assets/asset-polish.json').read_text())['snapshot']
    report = validate(ROOT / 'src/main/resources', snapshot)
    (ROOT / 'tools/assets/asset-polish-validation.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))
    raise SystemExit(bool(report['errors']))


if __name__ == '__main__': main()
