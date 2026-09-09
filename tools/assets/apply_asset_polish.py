"""Targeted lighting, held-model rotation and exact-model consolidation.

Requires a complete resource snapshot. Existing art is never regenerated.
Repeated runs do not compound rotations; a later manual edit requires review.
"""
from pathlib import Path
import argparse
import copy
import hashlib
import json
import math
import zipfile
from content_policy import ROOT, COMMON, FAMILIES, descriptions, family_light

RES = ROOT / 'src/main/resources'
ITEMS = RES / 'Server/Item/Items/StrangeMatter'
REPORT = ROOT / 'tools/assets/asset-polish.json'
SHARED = ROOT / 'tools/assets/shared-models.json'


def read(path): return json.loads(path.read_text(encoding='utf-8-sig'))
def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n', encoding='utf8')
def digest(data): return hashlib.sha256(data).hexdigest()
def canonical(model): return json.dumps(model, sort_keys=True, separators=(',', ':')).encode()


def rotate_model(model, degrees):
    """World-axis yaw affects only root transforms; child geometry and UVs are exact."""
    model = copy.deepcopy(model)
    angle = math.radians(degrees)
    sy, cy = math.sin(angle / 2), math.cos(angle / 2)
    for node in model['nodes']:
        p = node['position']; x, z = p['x'], p['z']
        p['x'] = round(math.cos(angle) * x + math.sin(angle) * z, 12)
        p['z'] = round(-math.sin(angle) * x + math.cos(angle) * z, 12)
        q = node['orientation']; x, y, z, w = (q[k] for k in 'xyzw')
        values = (cy*x + sy*z, cy*y + sy*w, cy*z - sy*x, cy*w - sy*y)
        norm = math.sqrt(sum(v*v for v in values))
        node['orientation'] = {k: round(v / norm, 12) for k, v in zip('xyzw', values)}
    return model


def replace_paths(value, aliases):
    if isinstance(value, str): return aliases.get(value, value)
    if isinstance(value, list): return [replace_paths(v, aliases) for v in value]
    if isinstance(value, dict): return {k: replace_paths(v, aliases) for k, v in value.items()}
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--snapshot', type=Path, required=True)
    args = parser.parse_args()
    snapshot = args.snapshot.resolve()
    manifest = read(snapshot / 'sha256.json')
    assert (snapshot / 'Resources.zip').exists(), 'A full resource snapshot is required'
    previous = read(REPORT) if REPORT.exists() else {}
    report = {'snapshot': str(snapshot.relative_to(ROOT)), 'rotations': {}, 'lightColors': {}, 'descriptions': 0}
    with zipfile.ZipFile(snapshot / 'Resources.zip') as archive:
        for name, degrees in [('field_scanner', 180), ('graviton_hammer', 90)]:
            relative = f'Items/StrangeMatter/{name}.blockymodel'
            path = COMMON / relative
            key = path.relative_to(ROOT).as_posix()
            saved = archive.read(key)
            assert digest(saved) == manifest['files'][key], 'Snapshot integrity failure'
            old = json.loads(saved)
            desired = rotate_model(old, degrees)
            current = read(path)
            if current != desired:
                assert current == old, f'{relative} changed since this snapshot; review the edit before applying a rotation'
                write(path, desired)
            report['rotations'][relative] = {'degreesY': degrees, 'beforeSha256': digest(saved), 'afterSha256': digest(path.read_bytes())}

    aliases = read(SHARED).get('aliases', {}) if SHARED.exists() else {}
    groups = []
    for suffix in ('shard', 'shard_crystal', 'shard_lamp', 'shard_lantern'):
        buckets = {}
        for family in FAMILIES:
            item_id = 'SM_' + '_'.join(word.title() for word in (family + '_' + suffix).split('_'))
            item = read(ITEMS / (item_id + '.json'))
            path = item.get('Model', item.get('BlockType', {}).get('CustomModel'))
            key = digest(canonical(read(COMMON / path)))
            buckets.setdefault(key, []).append((item_id, path))
        for key, members in buckets.items():
            if len(members) < 2:
                continue  # A unique manual variant must never be folded into another model.
            folder = 'Items' if suffix == 'shard' else 'Blocks'
            target = f'{folder}/StrangeMatter/Shared/{suffix}_{key[:12]}.blockymodel'
            data = (COMMON / members[0][1]).read_bytes()
            if (COMMON / target).exists():
                assert canonical(read(COMMON / target)) == canonical(json.loads(data)), 'Shared model hash collision or manual edit'
            else:
                (COMMON / target).parent.mkdir(parents=True, exist_ok=True)
                (COMMON / target).write_bytes(data)
            for item_id, source in members:
                if source != target:
                    aliases[source] = target
            groups.append({'model': target, 'semanticSha256': key, 'items': [member[0] for member in members]})

    # Update runtime and tooling references before removing exact duplicate resources.
    for path in sorted(RES.rglob('*.json')) + [ROOT / 'tools/assets/catalog.json']:
        old = read(path); new = replace_paths(old, aliases)
        if old != new:
            assert path.name != 'SM_Graviton_Hammer.json', 'Hammer item definition belongs to runtime integration'
            write(path, new)
    for source, target in aliases.items():
        path = COMMON / source
        if path.exists() and source != target:
            assert canonical(read(path)) == canonical(read(COMMON / target)), 'Refusing to remove a distinct manual model'
            path.unlink()
    write(SHARED, {'comparison': 'Exact parsed JSON, including geometry, UVs, names, attachments and LOD settings', 'aliases': aliases, 'groups': groups})

    for family in FAMILIES:
        crystal_id = 'SM_' + family.title() + '_Shard_Crystal'
        crystal = read(ITEMS / (crystal_id + '.json'))['BlockType']['Light']
        channels = [int(c, 16) for c in crystal['Color'][1:]]
        assert max(channels) == 5, 'Review nonstandard crystal brightness before matching its fixtures'
        color = '#' + ''.join(format(c * 3, 'x') for c in channels)
        for suffix in ('Lamp', 'Lantern'):
            path = ITEMS / (crystal_id.removesuffix('Crystal') + suffix + '.json')
            item = read(path); item['BlockType']['Light'] = {'Color': color, 'Radius': 15}; write(path, item)
        report['lightColors'][family] = {'crystal': crystal['Color'], 'lampAndLantern': color}

    # The terrain system uses an explicit subsoil item, sharing the saved grass soil bitmap.
    dirt_path = ITEMS / 'SM_Anomalous_Dirt.json'
    if not dirt_path.exists():
        write(dirt_path, {
          'Parent': 'Template_Soil',
          'TranslationProperties': {'Name': 'server.items.SM_Anomalous_Dirt.name', 'Description': 'server.items.SM_Anomalous_Dirt.description'},
          'Icon': 'Icons/ItemsGenerated/SM_Anomalous_Dirt.png', 'MaxStack': 100,
          'Categories': ['Blocks.Soils', 'SM_StrangeMatter.All'], 'PlayerAnimationsId': 'Block', 'Quality': 'Uncommon',
          'Tags': {'Type': ['StrangeMatter', 'Soil'], 'Family': ['Dirt']},
          'BlockType': {'Material': 'Solid', 'DrawType': 'Cube', 'Opacity': 'Solid', 'Group': 'Dirt',
            'HitboxType': 'SM_Anomalous_Dirt', 'VariantRotation': 'None',
            'Gathering': {'Breaking': {'GatherType': 'Soils', 'ItemId': 'SM_Anomalous_Dirt'}},
            'BlockParticleSetId': 'Dirt', 'BlockSoundSetId': 'Dirt', 'PhysicalMaterialId': 'Dirt',
            'ParticleColor': '#497872', 'Textures': [{'All': 'BlockTextures/StrangeMatter/Anomalous_Grass_Soil.png', 'Weight': 1}],
            'TransitionTexture': 'BlockTextures/Transition_Soil_Dirt.png'},
          'Interactions': {'Primary': 'Block_Primary', 'Secondary': 'Block_Secondary'}, 'ItemSoundSetId': 'ISS_Blocks_Soft'})
        write(RES / 'Server/Item/Block/Hitboxes/StrangeMatter/SM_Anomalous_Dirt.json',
              {'Boxes': [{'Min': {'X': 0, 'Y': 0, 'Z': 0}, 'Max': {'X': 1, 'Y': 1, 'Z': 1}}]})
    text = (RES / 'Server/Languages/en-US/server.lang').read_text(encoding='utf-8-sig')
    if 'items.SM_Anomalous_Dirt.name=' not in text:
        text += 'items.SM_Anomalous_Dirt.name=Anomalous Dirt\nitems.SM_Anomalous_Dirt.description=\n'
    values = {'items.SM_' + '_'.join(w.title() for w in name.split('_')) + '.description': value for name, value in descriptions().items()}
    lines = []
    for line in text.splitlines():
        key = line.partition('=')[0].strip()
        if key in values:
            line = key + '=' + values.pop(key); report['descriptions'] += 1
        lines.append(line)
    assert not values, 'Missing translation entries: ' + str(list(values))
    (RES / 'Server/Languages/en-US/server.lang').write_text('\n'.join(lines) + '\n', encoding='utf8')
    report['sharedModels'] = len(groups); report['aliases'] = len(aliases); report['status'] = 'PASS'
    write(REPORT, report)
    print(json.dumps(report, indent=2))


if __name__ == '__main__': main()
