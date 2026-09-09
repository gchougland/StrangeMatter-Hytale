"""Check every converted laboratory cell and its rotated native collision footprint."""
import json,math,itertools
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
NATIVE=Path(r'C:/Users/gchou/Documents/HytaleModding/HytaleSourceCode/hytale-shared-source/HytaleAssets')
items={p.stem:p for p in (NATIVE/'Server/Item/Items').rglob('*.json')}
items.update({p.stem:p for p in (ROOT/'src/main/resources/Server/Item/Items').rglob('*.json')})
hitboxes={p.stem:p for p in (NATIVE/'Server/Item/Block/Hitboxes').rglob('*.json')}
hitboxes.update({p.stem:p for p in (ROOT/'src/main/resources/Server/Item/Block/Hitboxes').rglob('*.json')})
def block_type(name):
    state=None
    if name.startswith('*'):name,state=name[1:].split('_State_Definitions_',1)
    item=json.loads(items[name].read_text(encoding='utf-8-sig'))
    parent=block_type(item['Parent']) if 'Parent' in item and item['Parent'] in items else {}
    parent.update(item.get('BlockType',{}))
    if state:parent.update(parent['State']['Definitions'][state])
    return parent
def footprint(name,rotation):
    block=block_type(name);hitbox=block.get('HitboxType','Full')
    boxes=json.loads(hitboxes[hitbox].read_text(encoding='utf-8-sig'))['Boxes'] if hitbox in hitboxes else [{'Min':dict(X=0,Y=0,Z=0),'Max':dict(X=1,Y=1,Z=1)}]
    points=[]
    for box in boxes:
        for x,y,z in itertools.product(*[(box['Min'][axis],box['Max'][axis]) for axis in 'XYZ']):
            for _ in range(rotation//16):x,y=y,1-x
            for _ in range(rotation%16//4):y,z=z,1-y
            for _ in range(rotation%4):x,z=z,1-x
            points.append((x,y,z))
    bounds=[(math.floor(min(p[a] for p in points)+1e-7),math.ceil(max(p[a] for p in points)-1e-7)) for a in range(3)]
    return list(itertools.product(*[range(low,high) for low,high in bounds]))
prefab=json.loads((ROOT/'src/main/resources/Server/Prefabs/StrangeMatter/Anomaly_Scientist_Lab.prefab.json').read_text())
cells={(b['x'],b['y'],b['z']):b for b in prefab['blocks']}
fillers={};errors=[]
for pos,block in cells.items():
    if block['name']=='Empty' or block.get('filler',0):continue
    assert block['name'].lstrip('*').split('_State_Definitions_')[0] in items,block['name']
    for delta in footprint(block['name'],block.get('rotation',0)):
        if delta==(0,0,0):continue
        target=tuple(a+b for a,b in zip(pos,delta))
        if not all(0<=v<limit for v,limit in zip(target,(9,8,8))):errors.append(f'{pos} {block["name"]} protrudes out to {target}')
        prior=cells.get(target)
        if prior and prior['name'] not in ('Empty',block['name']):errors.append(f'{pos} {block["name"]} overlaps {target} {prior["name"]}')
        if target in fillers and fillers[target]['name']!=block['name']:errors.append(f'Conflicting native fillers at{target}')
        fillers[target]={'x':target[0],'y':target[1],'z':target[2],'name':block['name'],'rotation':block.get('rotation',0),
                         'filler':(delta[0]&31)|((delta[2]&31)<<5)|((delta[1]&31)<<10)}
assert not errors,'\n'.join(errors)
source=json.loads((ROOT/'docs/worldgen/scientist-lab-source.json').read_text())
assert source['size']==[9,8,8]
assert sum(b['name']=='SM_Research_Machine' and not b.get('filler',0) for b in cells.values())==1
assert sum(b['name']=='SM_Stasis_Projector' and not b.get('filler',0) for b in cells.values())==1
assert sum(b['name']=='SM_Lab_Cyan_Glass' for b in cells.values())==20
assert sum(b['name']=='SM_Lab_Lamp' for b in cells.values())==6
print(f'PASS: laboratory {len(cells)} source cells, {len(fillers)} native filler cells, all block IDs and rotated collision footprints valid.')

def write_fillers():
    """Native BlockSelection paste disables automatic filler placement, so the prefab must carry these cells."""
    cells.update(fillers);prefab['blocks']=list(cells.values())
    (ROOT/'src/main/resources/Server/Prefabs/StrangeMatter/Anomaly_Scientist_Lab.prefab.json').write_text(json.dumps(prefab,indent=2)+'\n',encoding='utf8')

if __name__=='__main__':
    import sys
    if '--write-fillers' in sys.argv:write_fillers()
