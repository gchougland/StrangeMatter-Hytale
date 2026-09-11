"""Fit the current authored resonator to the native hand without rebuilding its art."""
import argparse
import copy
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MODEL = ROOT / 'src/main/resources/Common/Items/StrangeMatter/anomaly_resonator.blockymodel'


def empty_shape(piece=False):
    return {'type': 'none', 'offset': dict.fromkeys('xyz', 0),
            'stretch': dict.fromkeys('xyz', 1), 'settings': {'isPiece': piece},
            'textureLayout': {}, 'unwrapMode': 'custom', 'visible': True,
            'doubleSided': False, 'shadingMode': 'flat'}


def fit(model):
    result = copy.deepcopy(model)
    roots = result['nodes']
    if len(roots) == 1 and roots[0]['name'] == 'R-Attachment':
        assert roots[0]['shape']['settings'].get('isPiece'), 'Unexpected hand attachment'
        assert roots[0]['children'][0]['name'] == 'SM_Resonator_Grip', 'Review an already attached model separately'
        return result
    ids = set()
    def collect(nodes):
        for node in nodes:
            ids.add(str(node['id']))
            collect(node.get('children', []))
    collect(roots)
    next_id = max((int(value) for value in ids if value.isdecimal()), default=0) + 1
    # Native held items fit the outer piece to R-Attachment, replacing its bind
    # transform. The grip rotation therefore belongs beneath that boundary.
    # Keep every existing root and its local transform intact under this group.
    grip = {'id': str(next_id), 'name': 'SM_Resonator_Grip',
            'position': dict.fromkeys('xyz', 0),
            'orientation': {'x': 0, 'y': 1, 'z': 0, 'w': 0},
            'shape': empty_shape(), 'children': roots}
    result['nodes'] = [{'id': str(next_id + 1), 'name': 'R-Attachment',
                        'position': dict.fromkeys('xyz', 0),
                        'orientation': {'x': 0, 'y': 0, 'z': 0, 'w': 1},
                        'shape': empty_shape(True), 'children': [grip]}]
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    current = json.loads(MODEL.read_text(encoding='utf-8-sig'))
    desired = fit(current)
    if args.apply and desired != current:
        MODEL.write_text(json.dumps(desired, indent=2) + '\n', encoding='utf-8')
    print('Resonator grip already fitted' if current == desired else
          'Fitted current resonator with all authored nodes unchanged' if args.apply else
          'Resonator grip can be fitted; pass --apply to write')
