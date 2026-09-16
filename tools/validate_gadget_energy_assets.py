"""Validate the saved energy set: transformed coplanar overlap, UVs, native rig and preservation.

Uses the same native transform/face parser as current icon exports. No assets are regenerated.
"""
from pathlib import Path
import io,json,math,sys,zipfile
import numpy as np
from PIL import Image
sys.path.insert(0,str(Path(__file__).resolve().parent/'assets'))
import render_current_icons as render

ROOT=Path(__file__).resolve().parents[1];RES=ROOT/'src/main/resources';COMMON=RES/'Common'
def read(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def cross2(a,b):return a[0]*b[1]-a[1]*b[0]
def clip(subject,clipper):
    # All convex faces are projected in the same orientation before clipping.
    def area(poly):return sum(cross2(poly[(i+1)%len(poly)]-poly[i],poly[(i+2)%len(poly)]-poly[(i+1)%len(poly)]) for i in range(len(poly)))
    if area(clipper)<0:clipper=clipper[::-1]
    output=list(subject)
    for a,b in zip(clipper,np.roll(clipper,-1,axis=0)):
        old=output;output=[]
        if not old:break
        def side(p):return cross2(b-a,p-a)
        previous=old[-1];before=side(previous)
        for point in old:
            after=side(point)
            if (after>=-1e-9)!=(before>=-1e-9):output.append(previous+(point-previous)*(before/(before-after)))
            if after>=-1e-9:output.append(point)
            previous,before=point,after
    return np.array(output)
def overlap_area(a,b):
    n=np.cross(a[1]-a[0],a[2]-a[0]);norm=np.linalg.norm(n)
    if norm<1e-9:return 0
    n/=norm
    if np.max(np.abs((b-a[0])@n))>1e-5:return 0
    keep=[i for i in range(3) if i!=np.argmax(abs(n))];first=a[:,keep];second=b[:,keep]
    poly=clip(first,second)
    if len(poly)<3:return 0
    return abs(sum(cross2(poly[i],poly[(i+1)%len(poly)]) for i in range(len(poly)))/2)
def walk(nodes):
    for node in nodes:
        yield node
        yield from walk(node.get('children',[]))
def inspect(model,texture):
    # Model parser applies every native parent transform (including attachment roots).
    faces=render.model_faces(model,texture)
    overlaps=[]
    for i in range(len(faces)):
        for j in range(i+1,len(faces)):
            if np.any(faces[i][0].max(0)<faces[j][0].min(0)-1e-6) or np.any(faces[j][0].max(0)<faces[i][0].min(0)-1e-6):continue
            area=overlap_area(faces[i][0],faces[j][0])
            if area>1e-5:overlaps.append({'a':i,'b':j,'area':round(float(area),5),'centerA':faces[i][0].mean(0).round(4).tolist(),'centerB':faces[j][0].mean(0).round(4).tolist()})
    for points,uv,atlas,*_ in faces:
        assert np.isfinite(points).all() and np.isfinite(uv).all()
        assert uv.min()>=0 and uv[:,0].max()<texture.width and uv[:,1].max()<texture.height,'UV escapes saved atlas'
    return faces,overlaps
def main():
    manifest=read(ROOT/'tools/assets/gadget-energy-set.json');results=[];errors=[]
    for name in ('Field_Scanner','Echo_Vacuum','Anomaly_Resonator','Warp_Gun','Chrono_Blister','Graviton_Hammer',
                 'Hoverboard','Echoform_Imprinter','Resonant_Battery_Pack','Gravitic_Manipulator','Arc_Projector'):
        item=read(RES/f'Server/Item/Items/StrangeMatter/SM_{name}.json')
        assert 'MaxDurability' not in item,'New asset must distinguish fresh stacks from old saved durability: '+name
        assert item.get('Repairable') is False and item.get('DurabilityLossOnDeath') is False and item.get('DurabilityLossOnHit')==0,'Gadget must not use native wear or repair: '+name
        assert not item.get('Tool',{}).get('DurabilityLossBlockTypes'),'Gadget must not consume native mining wear: '+name
    for entry in manifest['items']:
        model=read(COMMON/entry['model']);texture=Image.open(COMMON/entry['texture']).convert('RGBA');faces,overlaps=inspect(model,texture)
        if overlaps:errors.append(entry['id']+': coplanar face overlaps: '+json.dumps(overlaps))
        results.append({'item':entry['id'],'faces':len(faces),'coplanarOverlaps':len(overlaps),'texture':list(texture.size)})
        if entry['id']=='SM_Resonant_Battery_Pack':
            assert len(model['nodes'])==1 and model['nodes'][0]['name']=='Chest' and model['nodes'][0]['shape']['settings']['isPiece']
            root=model['nodes'][0]
            assert root['id'].isdigit() and model.get('lod')=='auto' and 'format' not in model,'Pack uses native chest armor export metadata'
            assert root['shape']['type']=='none' and root['shape']['textureLayout']=={} and root['shape']['unwrapMode']=='custom'
            assert root['shape']['visible'] and root['shape']['doubleSided'] is False and root['shape']['shadingMode']=='flat'
            pack=read(RES/'Server/Item/Items/StrangeMatter/SM_Resonant_Battery_Pack.json')
            assert pack['Armor']=={'ArmorSlot':'Chest','BaseDamageResistance':0,'CosmeticsToHide':['Cape']},'Pack hides only the cape cosmetic, preserving the shirt'
            assert pack['Tags']['Type']==['StrangeMatter','Armor']
    with zipfile.ZipFile(ROOT/manifest['preservedResourcesSnapshot']) as archive:
        modelpath='src/main/resources/Common/Blocks/StrangeMatter/resonant_burner.blockymodel';texpath='src/main/resources/Common/Blocks/StrangeMatter/resonant_burner.png'
        old=json.loads(archive.read(modelpath));current=read(ROOT/modelpath);assert current['nodes'][:-1]==old['nodes'],'Original burner model nodes were changed'
        oldtex=Image.open(io.BytesIO(archive.read(texpath))).convert('RGBA');newtex=Image.open(ROOT/texpath).convert('RGBA')
        assert np.array_equal(np.asarray(oldtex),np.asarray(newtex.crop((0,0,oldtex.width,oldtex.height)))),'Original burner atlas pixels were changed'
        dockfaces,overlaps=inspect({'nodes':[current['nodes'][-1]]},newtex)
        if overlaps:errors.append('Burner dock internal overlaps: '+json.dumps(overlaps))
        oldfaces=render.model_faces(old,newtex)
        intersect=[(i,j) for i,a in enumerate(dockfaces) for j,b in enumerate(oldfaces) if overlap_area(a[0],b[0])>1e-5]
        if intersect:errors.append('Burner appended faces coincide with existing faces: '+str(intersect))
        preserved=0
        for path in archive.namelist():
            if '/Common/' not in path or not path.endswith(('.blockymodel','.blockyanim','.png')):continue
            if path in (modelpath,texpath,'src/main/resources/Common/Icons/ItemsGenerated/SM_Resonant_Burner.png'):continue
            assert (ROOT/path).read_bytes()==archive.read(path),'Unexpected modification of existing saved artwork: '+path
            preserved+=1
    for name in ('Gravitic_Acquire','Gravitic_Launch','Gravitic_Suspension','Arc_Muzzle','Arc_Impact','Gadget_Charge','Charging_Hum'):
        sound=read(RES/f'Server/Audio/SoundEvents/StrangeMatter/SM_{name}_SFX.json');assert sound['SpatialBlend']==1 and sound['MaxDistance']<=14 and sound['MaxInstance']<=8
        for layer in sound['Layers']:
            for path in layer['Files']:
                encoded=(COMMON/path).read_bytes();header=encoded.find(b'\x01vorbis')
                assert encoded.startswith(b'OggS') and header>=0 and encoded[header+11]==1,'Sound is not mono Vorbis'
    assert read(RES/'Server/Entity/Effects/StrangeMatter/SM_Gravitic_Hold.json').get('Invulnerable',False)==False
    slow=read(RES/'Server/Entity/Effects/StrangeMatter/SM_Arc_Slow.json');assert slow['Duration']==.35 and slow['ApplicationEffects']['HorizontalSpeedMultiplier']==.65
    report={'status':'FAIL' if errors else 'PASS','items':results,'originalBurnerNodes':'exactly preserved','originalBurnerAtlasPixels':'exactly preserved',
       'existingArtFilesByteIdentical':preserved,'arcElectricity':'SM_Stabilizer_Link reused unchanged','errors':errors,'limitations':'Offline models and neutral native rig checked. Client hand alignment, animated armor poses and live VFX require client acceptance.'}
    (ROOT/'tools/assets/gadget-energy-validation.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2));return bool(errors)
if __name__=='__main__':raise SystemExit(main())
