"""Validate authored work animations, exact model binding, native state assets and audio bounds."""
from pathlib import Path
import argparse, collections, json, math, zipfile
from assets.build_machine_work_effects import TARGETS, geometry, nodes

ROOT=Path(__file__).resolve().parents[1]
RES=ROOT/'src/main/resources'
def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--audit-preservation',action='store_true',help='Compare against the original local work-animation snapshot; intentional later art edits may differ')
    args=parser.parse_args()
    # Historical artwork is an optional audit. Current authored geometry remains
    # editable; normal validation checks actual animation bindings and behavior.
    snapshot=ROOT/'build/art-preservation/20260909T152632807879Z/Resources.zip'
    if args.audit_preservation and not snapshot.exists():
        parser.error('Historical art snapshot is unavailable: '+str(snapshot))
    archive=zipfile.ZipFile(snapshot) if args.audit_preservation else None
    report=[];total=0
    for model_name,item_id in TARGETS.items():
        model_path='Common/Blocks/StrangeMatter/'+model_name+'.blockymodel'
        model=json.loads((RES/model_path).read_text());counts=collections.Counter(n['name'] for n in nodes(model))
        item=json.loads((RES/'Server/Item/Items/StrangeMatter'/f'{item_id}.json').read_text())
        base=item['BlockType'];state=base['State']['Definitions']['Working']
        assert state['Looping'] is True and state['CustomModelAnimationSpeed']==1,item_id
        assert 'AmbientSoundEventId' not in base and 'CustomModelAnimation' not in base,item_id
        assert ('AmbientSoundEventId' in state)==(item_id=='SM_Resonance_Condenser'),item_id
        animation=json.loads((RES/'Common'/state['CustomModelAnimation']).read_text())
        assert animation['formatVersion']==1 and animation['duration']==90 and animation['holdLastKeyframe'] is False
        for name,tracks in animation['nodeAnimations'].items():
            assert counts[name]==1,(item_id,'ambiguous/missing target',name)
            assert any(tracks.values()),(item_id,name,'empty animation')
            for channel,keys in tracks.items():
                if not keys:continue
                assert channel in ('position','orientation','shapeStretch','shapeVisible','shapeUvOffset')
                assert keys[0]['time']==0 and keys[-1]['time']==animation['duration'],(item_id,name,'incomplete loop')
                assert keys[0]['delta']==keys[-1]['delta'],(item_id,name,'loop discontinuity')
                assert all(a['time']<b['time'] for a,b in zip(keys,keys[1:])),(item_id,name,'nonmonotonic keys')
                for key in keys:
                    assert key['interpolationType']=='smooth'
                    assert all(math.isfinite(v) for v in key['delta'].values())
                    if channel=='position':assert max(abs(v) for v in key['delta'].values())<=3,'Work motion must remain restrained'
            total+=1
        if archive:
            archived=next((p for p in archive.namelist() if p.replace('\\','/').endswith(model_path)),None)
            assert archived is not None,model_path
            assert geometry(json.loads(archive.read(archived)))==geometry(model),(item_id,'manual geometry/UV changed')
        report.append({'machine':item_id,'animatedNodes':len(animation['nodeAnimations']),'manualGeometryAndUV':'preserved' if archive else 'historical comparison not requested'})
    sound=json.loads((RES/'Server/Audio/SoundEvents/StrangeMatter/SM_Condenser_Working_Hum.json').read_text())
    assert sound['SpatialBlend']==1 and sound['MaxDistance']==7 and sound['MaxInstance']==3 and sound['Volume']==-18
    assert sound['Layers'][0]['Looping'] is True and len(sound['Layers'])==1
    ogg=RES/'Common'/sound['Layers'][0]['Files'][0]
    assert ogg.read_bytes().startswith(b'OggS') and ogg.stat().st_size>1000,'Real encoded loop must exist'
    if archive:archive.close()
    result={'status':'PASS','animations':len(report),'animatedNodes':total,'machines':report,
            'checks':['Unique target-node bindings','Finite ordered keys','Seamless closed loops','Restrained motion','Native work-only states','Quiet positional bounded ambient OGG'],
            'historicalArtAudit':args.audit_preservation}
    (ROOT/'tools/assets/machine-work-validation.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
if __name__=='__main__':main()
