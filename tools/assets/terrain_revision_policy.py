"""Narrow authorization for the two explicitly requested terrain border repairs.

Older preservation audits retain their original snapshots. This exact input and
output hash pair permits the later repair without exempting a folder or asset type.
"""
from pathlib import Path
import hashlib
import json

ROOT = Path(__file__).resolve().parents[2]
TEXTURES = frozenset('Common/BlockTextures/StrangeMatter/Anomalous_Grass_' + suffix + '.png'
                     for suffix in ('Top', 'Soil'))


def authorized_terrain_edit(relative, before, after):
    if relative not in TEXTURES:
        return False
    report = ROOT / 'tools/assets/seamless-terrain.json'
    if not report.exists():
        return False
    entry = json.loads(report.read_text())['textures'][relative]
    return (hashlib.sha256(before).hexdigest() == entry['beforeSha256']
            and hashlib.sha256(after).hexdigest() == entry['afterSha256'])
