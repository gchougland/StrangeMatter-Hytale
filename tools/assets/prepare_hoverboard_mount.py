"""Align the mount artwork with native entity units without moving its collider."""
from pathlib import Path
import argparse, copy, hashlib, json

ROOT=Path(__file__).resolve().parents[2]
COMMON=ROOT/'src/main/resources/Common'
SOURCE=COMMON/'Items/StrangeMatter/hoverboard.blockymodel'
TARGET=COMMON/'Items/StrangeMatter/hoverboard_mount.blockymodel'
ENTITY_UNITS=64
ENTITY_SCALE=2
ANCHOR_Y=1.64
LEGACY_SHIFT=17.6

def translated(model,shift):
    result=copy.deepcopy(model)
    for node in result['nodes']:node['position']['y']+=shift
    return result

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--write',action='store_true');args=parser.parse_args()
    original=SOURCE.read_bytes();model=json.loads(original)
    grips=[n for n in model['nodes'] if n['name']=='foot_grip']
    tops=[n['position']['y']+n['shape']['settings']['size']['y']/2 for n in grips]
    assert len(tops)==2 and abs(tops[0]-tops[1])<1e-9,'Both original grips must share a flat riding plane'
    grip_top=tops[0]
    shift=round(ANCHOR_Y*ENTITY_UNITS/ENTITY_SCALE-grip_top,8)
    shifted=translated(model,shift)
    if TARGET.exists():
        existing=json.loads(TARGET.read_text())
        assert existing==shifted or args.write and existing==translated(model,LEGACY_SHIFT),'Existing mount variant differs; preserve and review its manual edits'
    if args.write:TARGET.write_text(json.dumps(shifted,indent=2)+'\n',encoding='utf8')
    assert TARGET.exists() and json.loads(TARGET.read_text())==shifted,'Run with --write to create the reviewed mount translation'
    restored=copy.deepcopy(shifted)
    for before,after in zip(model['nodes'],restored['nodes']):
        assert abs(after['position']['y']-before['position']['y']-shift)<1e-9
        after['position']['y']=before['position']['y']
    assert restored==model,'Translation altered hierarchy, geometry or UV data'
    assert SOURCE.read_bytes()==original,'Original item model was modified'
    report={'source':SOURCE.relative_to(COMMON).as_posix(),'variant':TARGET.relative_to(COMMON).as_posix(),
      'rootShiftYModelUnits':shift,'entityArtUnitsPerBlock':ENTITY_UNITS,'runtimeScale':ENTITY_SCALE,
      'riderAnchorY':ANCHOR_Y,'originalGripTopModelUnits':grip_top,
      'visualOriginWorldY':shift*ENTITY_SCALE/ENTITY_UNITS,
      'gripTopWorldY':(shift+grip_top)*ENTITY_SCALE/ENTITY_UNITS,
      'legacyGapWorldBlocks':ANCHOR_Y-(LEGACY_SHIFT+grip_top)*ENTITY_SCALE/ENTITY_UNITS,
      'sourceSha256':hashlib.sha256(original).hexdigest(),
      'variantSha256':hashlib.sha256(TARGET.read_bytes()).hexdigest(),'itemAndModelAssetUnchanged':True,'status':'PASS'}
    (ROOT/'tools/assets/hoverboard-mount-model.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))

if __name__=='__main__':main()
