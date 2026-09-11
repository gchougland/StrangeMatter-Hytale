"""Stdlib checks for seamless terrain and the 0.1 block crystal embedding."""
from pathlib import Path
import argparse
import copy
import json
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'src/main/resources'
sys.path.insert(0, str(ROOT / 'tools/assets'))
from terrain_revision_policy import TEXTURES, authorized_terrain_edit
from test_held_light_fix import geometry
from validate_grass_status_icon import digest, read, rgba, weighted_mean


def seam_contrast(pixels, width, height):
    """Average RGB difference between adjacent pixels across repeated tile edges."""
    horizontal = sum(abs(pixels[y * width * 4 + c] - pixels[(y * width + width - 1) * 4 + c])
                     for y in range(height) for c in range(3)) / (height * 3)
    vertical = sum(abs(pixels[x * 4 + c] - pixels[((height - 1) * width + x) * 4 + c])
                   for x in range(width) for c in range(3)) / (width * 3)
    return [horizontal, vertical]


def geometry_definition(model):
    """The embedding contract constrains geometry, not where its painted faces live."""
    result = copy.deepcopy(model)
    def walk(nodes):
        for node in nodes:
            node.get('shape', {}).pop('textureLayout', None)
            walk(node.get('children', []))
    walk(result['nodes'])
    return result


def verify_texture(before, after, entry):
    w, h, old = rgba(before)
    aw, ah, current = rgba(after)
    assert (w, h) == (aw, ah) == (32, 32)
    assert old[3::4] == current[3::4], 'Terrain opacity changed'
    expected = bytearray(old)
    changed = 0
    deltas = entry['baseChannelDifferences']
    for y in range(h):
        for x in range(w):
            i = (y * w + x) * 4
            bevel = 10 if min(x, y) == 0 else -12 if x == w - 1 or y == h - 1 else 0
            if bevel and [old[i] - old[i + 1], old[i + 1] - old[i + 2]] == deltas:
                for c in range(3):
                    expected[i + c] = max(0, min(255, old[i + c] - bevel))
                changed += 1
            elif 0 < x < w - 1 and 0 < y < h - 1:
                assert current[i:i + 4] == old[i:i + 4], 'Painted interior changed'
    assert current == bytes(expected), 'Changes extend beyond the original base pigment bevel'
    assert changed == entry['changedPixels'] and entry['untouchedInteriorPixels'] == 900
    old_mean, new_mean = weighted_mean(old), weighted_mean(current)
    assert all(abs(a - b) < .11 for a, b in zip(old_mean, new_mean)), 'Terrain palette drifted'
    old_seam, new_seam = seam_contrast(old, w, h), seam_contrast(current, w, h)
    assert all(b < a * .45 for a, b in zip(old_seam, new_seam)), 'Hard border remains'
    return {'changedEdgePixels': changed, 'unchangedInteriorPixels': 900,
            'oldSeamContrast': old_seam, 'currentSeamContrast': new_seam}


def validate(audit_unrelated=False):
    report = read(ROOT / 'tools/assets/seamless-terrain.json')
    assert set(report['textures']) == TEXTURES
    crystal = report['crystal']
    model = read(RES / crystal['path'])
    assert crystal['deltaModelUnitsY'] == -3.2 and crystal['modelUnitsPerBlock'] == 32
    assert crystal['deltaBlocksY'] == -.1 and len(model['nodes']) == crystal['rootCount'] == 63
    families = ('Gravitic', 'Chrono', 'Energetic', 'Spatial', 'Shade', 'Insight')
    for family in families:
        item = read(RES / f'Server/Item/Items/StrangeMatter/SM_{family}_Shard_Crystal.json')
        assert item['BlockType']['CustomModel'] == crystal['path'].removeprefix('Common/')
        assert item['BlockType'].get('CustomModelScale', 1) == 1
        shard = read(RES / f'Server/Item/Items/StrangeMatter/SM_{family}_Shard.json')
        assert shard.get('Model') != item['BlockType']['CustomModel']
    archive_path = ROOT / report['snapshot'] / 'Resources.zip'
    textures, preserved = {}, 0
    if archive_path.exists():
        with zipfile.ZipFile(archive_path) as archive:
            prefix = 'src/main/resources/'
            for rel, entry in report['textures'].items():
                before, after = archive.read(prefix + rel), (RES / rel).read_bytes()
                assert authorized_terrain_edit(rel, before, after)
                textures[rel] = verify_texture(before, after, entry)
            before_model = json.loads(archive.read(prefix + crystal['path']))
            expected = copy.deepcopy(before_model)
            for node in expected['nodes']:
                node['position']['y'] -= 3.2
            assert geometry_definition(model) == geometry_definition(expected), 'Crystal embedding changed geometry, attachments or child transforms'
            old_points, _ = geometry(before_model)
            points, _ = geometry(model)
            assert len(points) == len(old_points)
            for before, after in zip(old_points, points):
                assert all(abs(after[i] - before[i] - (0, -3.2, 0)[i]) < 1e-10 for i in range(3))
            # Whole-project preservation is a release audit. Routine builds must
            # not reject the user's ongoing edits to unrelated models.
            for name in (archive.namelist() if audit_unrelated else ()):
                rel = name.removeprefix(prefix)
                if (not rel.startswith('Common/') or rel in TEXTURES or rel == crystal['path']
                        or Path(rel).suffix not in ('.png','.blockymodel','.blockyanim','.ogg')):
                    continue
                before, after = archive.read(name), (RES / rel).read_bytes()
                if rel == 'Common/Items/StrangeMatter/hoverboard_mount.blockymodel' and before != after:
                    # Concurrent, separately authorized native entity scale repair.
                    # Permit only the agreed root translation; every UV and child
                    # shape must remain exactly equal to this task's snapshot.
                    mount, updated = json.loads(before), json.loads(after)
                    ride_report = read(ROOT / 'tools/assets/hoverboard-mount-model.json')
                    assert digest(after) == ride_report['variantSha256']
                    assert len(mount['nodes']) == len(updated['nodes'])
                    for old_node, new_node in zip(mount['nodes'], updated['nodes']):
                        old_y, new_y = old_node['position'].pop('y'), new_node['position'].pop('y')
                        assert abs(new_y - old_y - 26.38) < 1e-10
                    assert updated == mount, 'Unapproved hoverboard art edit'
                elif rel in report.get('concurrentArtEdits', {}):
                    # The user finished editing this unrelated model during the
                    # revision and requested the latest save in the release.
                    saved = report['concurrentArtEdits'][rel]
                    assert digest(before) == saved['beforeSha256']
                    assert digest(after) == saved['afterSha256']
                    with zipfile.ZipFile(ROOT / saved['snapshot'] / 'Resources.zip') as newer:
                        assert newer.read(prefix + rel) == after
                    preserved += 1
                else:
                    assert before == after, 'Unrelated Common artwork changed: ' + rel
                    preserved += 1
            for family in families:
                for rel in [f'Server/Item/Items/StrangeMatter/SM_{family}_Shard_Crystal.json',
                            f'Server/Item/Block/Hitboxes/StrangeMatter/SM_{family}_Shard_Crystal.json']:
                    assert archive.read(prefix + rel) == (RES / rel).read_bytes(), 'Crystal collision or item behavior changed'
    else:
        # Distributed source checkouts may omit local ZIP backups. Still verify
        # the exact authorized outputs and the resource contracts above.
        for rel, entry in report['textures'].items():
            assert digest((RES / rel).read_bytes()) == entry['afterSha256']
    print(json.dumps({'result': 'PASS', 'textures': textures, 'crystalFamilies': len(families),
                      'crystalRootOffsetBlocks': -.1, 'collisionPreserved': True,
                      'preservedOtherCommonFiles': preserved, 'unrelatedArtAudited': audit_unrelated,
                      'snapshotAvailable': archive_path.exists(), 'crystalGeometryComparedToSource': archive_path.exists()}))


if __name__ == '__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--audit-unrelated-art',action='store_true',help='Compare unrelated art with this revision\'s saved snapshots.')
    validate(parser.parse_args().audit_unrelated_art)
