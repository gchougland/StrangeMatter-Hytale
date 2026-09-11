"""Fit the current research tablet to Hytale's hand without rebuilding artwork.

The existing icon already shows the front and stays byte identical. A camera
entry preserves that view for future icons-only renders. Historical checks are
explicit via --audit-art and never run during normal builds.
"""
import argparse
import copy
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import zipfile
from fit_resonator_grip import empty_shape

ROOT=Path(__file__).resolve().parents[2]
RES=ROOT/'src/main/resources'
MODEL=RES/'Common/Items/StrangeMatter/research_tablet.blockymodel'
VIEWS=ROOT/'tools/assets/icon_views.json'
PRESERVED=(RES/'Common/Items/StrangeMatter/research_tablet.png',
           RES/'Common/Icons/ItemsGenerated/SM_Research_Tablet.png',
           RES/'Server/Item/Items/StrangeMatter/SM_Research_Tablet.json')

def read(path):return json.loads(path.read_text(encoding='utf-8-sig'))
def save(path,value):path.write_text(json.dumps(value,indent=2)+'\n',encoding='utf-8')

def fit(model):
    result=copy.deepcopy(model);roots=result['nodes']
    if len(roots)==1 and roots[0]['name']=='R-Attachment':
        assert roots[0]['shape']['settings'].get('isPiece'),'Unexpected native hand attachment'
        assert len(roots[0].get('children',[]))==1 and roots[0]['children'][0]['name']=='SM_Tablet_Grip','Review an already attached model separately'
        return result
    ids=set()
    def collect(nodes):
        for node in nodes:
            ids.add(str(node['id']));collect(node.get('children',[]))
    collect(roots)
    next_id=max((int(value) for value in ids if value.isdecimal()),default=0)+1
    # The named native attachment boundary binds to the animated player bone.
    # Its child keeps the actual turn when the outer bind pose is replaced.
    grip={'id':str(next_id),'name':'SM_Tablet_Grip','position':dict.fromkeys('xyz',0),
          'orientation':{'x':0,'y':1,'z':0,'w':0},'shape':empty_shape(),'children':roots}
    result['nodes']=[{'id':str(next_id+1),'name':'R-Attachment','position':dict.fromkeys('xyz',0),
                      'orientation':{'x':0,'y':0,'z':0,'w':1},'shape':empty_shape(True),'children':[grip]}]
    return result

def apply():
    current=read(MODEL);desired=fit(current)
    if current==desired:
        print('Tablet hand attachment already fitted; current art and camera preserved');return
    stamp=datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    folder=ROOT/'build/art-preservation'/('tablet-grip-'+stamp);folder.mkdir(parents=True)
    paths=(MODEL,*PRESERVED,VIEWS)
    hashes={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}
    with zipfile.ZipFile(folder/'Tablet.zip','w',zipfile.ZIP_DEFLATED) as z:
        for path in paths:z.write(path,path.relative_to(ROOT).as_posix())
    save(folder/'sha256.json',hashes)
    views=read(VIEWS);views['SM_Research_Tablet']={'yaw':215,'pitch':23}
    save(MODEL,desired);save(VIEWS,views)
    assert desired['nodes'][0]['children'][0]['children']==current['nodes']
    for path in PRESERVED:assert hashlib.sha256(path.read_bytes()).hexdigest()==hashes[str(path.relative_to(ROOT))]
    report={'snapshot':str(folder.relative_to(ROOT)),'model':str(MODEL.relative_to(ROOT)),
            'preservedAuthoredRootCount':len(current['nodes']),'heldYawDegrees':180,'iconRepainted':False,
            'textureChanged':False,'itemInteractionsChanged':False,'currentModelSha256':hashlib.sha256(MODEL.read_bytes()).hexdigest()}
    save(folder/'result.json',report);print(json.dumps(report,indent=2))

def audit(folder):
    with zipfile.ZipFile(folder/'Tablet.zip') as z:
        old=json.loads(z.read(MODEL.relative_to(ROOT).as_posix()))
        assert fit(old)==read(MODEL),'Additional model edits since this snapshot'
        for path in PRESERVED:assert z.read(path.relative_to(ROOT).as_posix())==path.read_bytes(),'Preserved asset changed: '+str(path)
    print('Explicit historical tablet audit PASS')

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--apply',action='store_true');parser.add_argument('--audit-art',type=Path);args=parser.parse_args()
    if args.apply:apply()
    elif args.audit_art:audit(args.audit_art)
    else:print('Tablet attachment already fitted' if fit(read(MODEL))==read(MODEL) else 'Tablet can be fitted; pass --apply to preserve and wrap current art')
