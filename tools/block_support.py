"""Explicit native support for the full cube Resonite building models only.

Hytale infers supporting faces for solid Cube draw types, never for Model.
This targeted patch changes item placement metadata and preserves all artwork.
"""
from pathlib import Path
import argparse, copy, datetime, hashlib, json, zipfile

ROOT=Path(__file__).resolve().parents[1];RES=ROOT/'src/main/resources'
FULL=('resonite_block','resonite_tile','fancy_resonite_tile','resonite_pillar')
TARGETS=(*FULL,'resonite_tile_slab')

def full_faces():return {'BlockSides':[{'FaceType':'Full'}]}

def apply(source,block):
    if source in FULL:block['Supporting']=full_faces()
    elif source=='resonite_tile_slab':
        doubled=block.get('State',{}).get('Definitions',{}).get('Block')
        if doubled is not None:doubled['Supporting']=full_faces()

def item_path(source):
    return RES/'Server/Item/Items/StrangeMatter'/('SM_'+'_'.join(w.capitalize() for w in source.split('_'))+'.json')

def read(path):return json.loads(path.read_text(encoding='utf-8-sig'))

def patch_files():
    common=RES/'Common';before={p.relative_to(common).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in common.rglob('*') if p.is_file()}
    stamp=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    folder=ROOT/'build/art-preservation'/('wall-support-'+stamp);folder.mkdir(parents=True)
    (folder/'Common-before.json').write_text(json.dumps(before,indent=2)+'\n')
    changed=[]
    with zipfile.ZipFile(folder/'Items-before.zip','w',zipfile.ZIP_DEFLATED) as archive:
        for source in TARGETS:
            path=item_path(source);original=read(path);updated=copy.deepcopy(original);apply(source,updated['BlockType'])
            archive.write(path,path.relative_to(RES).as_posix())
            if updated!=original:path.write_text(json.dumps(updated,indent=2)+'\n',encoding='utf-8');changed.append(path.name)
    assert all((common/name).exists() and hashlib.sha256((common/name).read_bytes()).hexdigest()==digest for name,digest in before.items()),'Unrelated artwork changed'
    report={'changedItems':changed,'commonFilesPreserved':len(before),'snapshot':str(folder)}
    (folder/'result.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--apply',action='store_true');args=parser.parse_args()
    if args.apply:patch_files()
    else:parser.print_help()
