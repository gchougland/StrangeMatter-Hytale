"""Read actual before/current assets into JSON for the local Canvas QA renderer."""
from pathlib import Path
import base64
import json
import zipfile
from preview_energetic_revision import mesh

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / 'src/main/resources'


def image(data):
    return 'data:image/png;base64,' + base64.b64encode(data).decode()


report = json.loads((ROOT / 'tools/assets/seamless-terrain.json').read_text())
with zipfile.ZipFile(ROOT / report['snapshot'] / 'Resources.zip') as archive:
    prefix = 'src/main/resources/'
    data = {'terrain': {}, 'models': {}, 'images': {}}
    for name in ('Top', 'Soil'):
        rel = f'Common/BlockTextures/StrangeMatter/Anomalous_Grass_{name}.png'
        data['terrain'][name] = {'before': image(archive.read(prefix + rel)), 'current': image((RES / rel).read_bytes())}
    rel = report['crystal']['path']
    for key, content in [('before', archive.read(prefix + rel)), ('current', (RES / rel).read_bytes())]:
        data['models'][key] = mesh(json.loads(content), 'crystal')
    data['images']['crystal'] = image((RES / 'Common/Blocks/StrangeMatter/gravitic_shard_crystal.png').read_bytes())
    data['images']['grass'] = data['terrain']['Top']['current']
    print(json.dumps(data))
