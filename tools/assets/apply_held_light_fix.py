"""Repair native hand attachment boundaries and fixture light washout without regenerating art."""
from pathlib import Path
import argparse, copy, hashlib, json, math, zipfile
from content_policy import ROOT, COMMON, FAMILIES, family_light

RES = ROOT / 'src/main/resources'
REPORT = ROOT / 'tools/assets/held-light-fix.json'

def read(path): return json.loads(path.read_text(encoding='utf-8-sig'))
def write(path, value): path.write_text(json.dumps(value, indent=2) + '\n', encoding='utf8')

def attach(model, yaw):
    """A piece's outer transform is a bind pose. Put the actual grip rotation below it.

    Native Items/Tools/Pickaxe/Thorium.blockymodel has this same empty, named
    isPiece root. Its children retain their local transforms when fitted to the
    player's animated R-Attachment bone. Preserve all authored shape/UV data.
    """
    result = copy.deepcopy(model)
    assert len(result['nodes']) == 1, 'Review a model with multiple roots separately'
    art = result['nodes'][0]
    assert art['name'] != 'R-Attachment', 'Already fitted; do not apply twice'
    original_position = copy.deepcopy(art['position'])
    art['position'] = {'x': 0, 'y': 0, 'z': 0}
    angle = math.radians(yaw) / 2
    art['orientation'] = {'x': 0, 'y': round(math.sin(angle), 12), 'z': 0, 'w': round(math.cos(angle), 12)}
    # The old visible root was used as the attachment boundary. Its attempted
    # 0.4 rotations did not survive attachment (confirmed by unchanged playtest
    # pose despite the client loading the new SHA). Correct the effective held
    # identity orientation here, not by adding another yaw to that ignored root.
    result['nodes'] = [{'id': '90000', 'name': 'R-Attachment',
      'position': original_position, 'orientation': {'x': 0, 'y': 0, 'z': 0, 'w': 1},
      'shape': {'type': 'none', 'offset': {'x': 0, 'y': 0, 'z': 0}, 'stretch': {'x': 1, 'y': 1, 'z': 1},
        'settings': {'isPiece': True}, 'textureLayout': {}, 'unwrapMode': 'custom',
        'visible': True, 'doubleSided': False, 'shadingMode': 'flat'}, 'children': [art]}]
    return result

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--snapshot', required=True, type=Path)
    args = parser.parse_args(); snapshot = args.snapshot.resolve()
    manifest = read(snapshot / 'sha256.json')
    changed = []; rotations = {}
    with zipfile.ZipFile(snapshot / 'Resources.zip') as archive:
        for name, yaw in [('field_scanner', 180), ('graviton_hammer', 90)]:
            path = COMMON / f'Items/StrangeMatter/{name}.blockymodel'; key = path.relative_to(ROOT).as_posix()
            original = archive.read(key)
            assert hashlib.sha256(original).hexdigest() == manifest['files'][key]
            saved = json.loads(original); desired = attach(saved, yaw); current = read(path)
            assert current == saved or current == desired, 'Manual edit since snapshot; review before applying: ' + key
            if current != desired: write(path, desired); changed.append(key)
            rotations[name] = {'attachment': 'R-Attachment', 'geometryChild': saved['nodes'][0]['name'], 'gripYawDegrees': yaw,
                              'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
    lights = {}
    for family in FAMILIES:
        color = family_light(family, True)
        lights[family] = {'Color': color, 'Radius': 0}
        for suffix in ('Lamp', 'Lantern'):
            path = RES / f'Server/Item/Items/StrangeMatter/SM_{family.title()}_Shard_{suffix}.json'
            item = read(path); item['BlockType']['Light'] = dict(lights[family]); write(path, item)
    report = {'snapshot': snapshot.relative_to(ROOT).as_posix(), 'heldModels': rotations, 'lights': lights,
              'notes': ['All painted geometry, UV maps and textures retained', 'Item interactions and animation IDs untouched',
                        'Native radius-zero fixture default; RGB voxel brightness remains explicit',
                        'Client renderer source is not present in the shared checkout; final held/light appearance requires a client pass']}
    write(REPORT, report); print(json.dumps(report, indent=2))

if __name__ == '__main__': main()
