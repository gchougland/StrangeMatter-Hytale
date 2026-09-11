"""One time, targeted repair of the current furniture. Never rebuild its atlas.

Run --apply once against the recorded 0.8.7 baseline. --preview reads both the
baseline and current resources and writes only review images. This is not a
normal build step; future manual furniture edits remain authoritative.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import zipfile
from PIL import Image, ImageDraw, ImageFont
import numpy as np
import render_current_icons as render

ROOT=render.ROOT; RES=render.RES; COMMON=render.COMMON
FOLDER=Path('Blocks/StrangeMatter/Furniture')
REPORT=ROOT/'build/art-preservation/furniture-0.8.7-repair.json'
IDENTITY={'x':0,'y':0,'z':0,'w':1}

def save(path,value):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(value,indent=2)+'\n',encoding='utf-8')

def walk(nodes):
    for node in nodes:
        yield node
        yield from walk(node.get('children',[]))

def span(shape,face):
    s=shape['settings']['size']
    return (s['x'],s['z']) if face in ('top','bottom') else (s['z'],s['y']) if face in ('left','right') else (s['x'],s['y'])

def repair_origins(model):
    changed=0
    for node in walk(model['nodes']):
        shape=node.get('shape',{})
        if shape.get('type')!='box':continue
        for face,uv in shape['textureLayout'].items():
            # The old authoring helper put mirrored back origins 8..15 pixels
            # into their material tile. Keep all other, possibly hand edited,
            # UV layouts untouched. This recovers that helper's intended region.
            if face=='back' and uv.get('angle',0)==0 and uv.get('mirror',{}).get('x') and 8<=uv['offset']['x']%128<=15:
                uv['offset']['x']+=span(shape,face)[0];changed+=1
    return changed

def visible_groups(model):
    targets=[]
    for node in walk(model['nodes']):
        if node['name'] not in ('Lid','Door_Left','Door_Right'):continue
        targets.append(node['name'])
        if 'shape' not in node:
            node['shape']={'type':'none','offset':{'x':0,'y':0,'z':0},'stretch':{'x':1,'y':1,'z':1},
                           'settings':{'isPiece':False},'textureLayout':{},'unwrapMode':'custom',
                           'visible':True,'doubleSided':False,'shadingMode':'standard'}
        node['shape']['visible']=True
    return targets

def repair_rails(model,large=False):
    # Rail and side wall used to share a half pixel wide coplanar top surface.
    # Keep the same outside silhouette and meet the wall at its outside edge.
    for node in walk(model['nodes']):
        if node['name']!='Corner_Rail':continue
        centre=-16 if large else 0;factor=2 if large else 1
        side=1 if node['position']['x']>centre else -1
        node['position']['x']=centre+side*14.75*factor
        node['shape']['stretch']['x']=.5

def closed_animation(targets):
    return {'formatVersion':1,'duration':1,'holdLastKeyframe':True,'nodeAnimations':{
        name:{'position':[],'orientation':[{'time':0,'delta':IDENTITY.copy(),'interpolationType':'linear'}],
              'shapeStretch':[],'shapeVisible':[{'time':0,'delta':True,'interpolationType':'linear'}],'shapeUvOffset':[]}
        for name in targets}}

def resize_axis(model,axis,factor,shift):
    for node in model['nodes']:node['position'][axis]=node['position'][axis]*factor+shift
    def children(nodes):
        for node in nodes:
            shape=node.get('shape',{})
            # All current table and chest shapes are axis aligned. Refuse a
            # silent distortion if a later manual edit adds angled geometry.
            assert all(abs(node.get('orientation',IDENTITY).get(k,0))<1e-8 for k in 'xyz'),'Angled geometry needs manual resize'
            if shape:
                shape.get('offset',{})[axis]=shape.get('offset',{}).get(axis,0)*factor
            if shape.get('type')=='box':
                old={face:span(shape,face) for face in shape['textureLayout']}
                shape['settings']['size'][axis]*=factor
                for face,uv in shape['textureLayout'].items():
                    assert uv.get('angle',0)==0,'Rotated manual UV needs manual resize'
                    new=span(shape,face)
                    for i,key in enumerate(('x','y')):
                        if uv.get('mirror',{}).get(key):uv['offset'][key]+=new[i]-old[face][i]
            for child in node.get('children',[]):child['position'][axis]*=factor
            children(node.get('children',[]))
    children(model['nodes'])

def apply():
    if REPORT.exists():raise SystemExit('Repair already applied. Current art is preserved. Use --preview to review it.')
    marker=ROOT/'build/revision-0.8.7-art-baseline.txt'
    baseline=Path(marker.read_text().strip())/'Resources.zip'
    assert baseline.exists(),'Preservation snapshot required before repairing current art'
    changes={};atlas=COMMON/FOLDER/'resonite_furniture.png';atlas_hash=hashlib.sha256(atlas.read_bytes()).hexdigest()
    for path in sorted((COMMON/FOLDER).glob('*.blockymodel')):
        model=render.read(path);before=copy.deepcopy(model)
        count=repair_origins(model)
        targets=visible_groups(model)
        if path.stem in ('chest','cabinet','wardrobe'):repair_rails(model)
        if path.stem=='table':resize_axis(model,'z',2,16)
        if model!=before:save(path,model)
        changes[path.name]={'correctedBackFaces':count,'explicitVisibleHinges':targets}
        if targets:
            animation=FOLDER/'Animations'/(path.stem+'_closed.blockyanim')
            save(COMMON/animation,closed_animation(targets))
            itempath=RES/'Server/Item/Items/StrangeMatter'/('SM_Resonite_'+path.stem.title()+'.json')
            item=render.read(itempath);item['BlockType']['CustomModelAnimation']=animation.as_posix();save(itempath,item)
    boxpath=RES/'Server/Item/Block/Hitboxes/StrangeMatter/SM_Resonite_Table.json'
    boxes=render.read(boxpath)
    for box in boxes['Boxes']:
        box['Min']['Z']=max(0,box['Min']['Z']*2);box['Max']['Z']=min(2,box['Max']['Z']*2)
    save(boxpath,boxes)
    smallpath=RES/'Server/Item/Items/StrangeMatter/SM_Resonite_Chest.json';small=render.read(smallpath)
    small['BlockType']['ConnectedBlockRuleSet']={'Type':'CustomTemplate','TemplateShapeAssetId':'ChestConnectedBlockTemplate',
        'TemplateShapeBlockPatterns':{'Default':'SM_Resonite_Chest','Double':'SM_Resonite_Chest_Large'}}
    save(smallpath,small)
    large=copy.deepcopy(small);large.pop('Recipe',None);large['Variant']=True
    large['TranslationProperties']={key:'server.items.SM_Resonite_Chest_Large.'+key.lower() for key in ('Name','Description')}
    large['Icon']='Icons/ItemsGenerated/SM_Resonite_Chest_Large.png'
    block=large['BlockType'];block.pop('ConnectedBlockRuleSet',None)
    block['CustomModel']=(FOLDER/'chest_large.blockymodel').as_posix();block['HitboxType']='SM_Resonite_Chest_Large'
    block['InteractionHint']='server.interactionHints.SM_Resonite_Chest_Large'
    block['BlockEntity']['Components']['ItemContainerBlock']['Capacity']=36
    block['Gathering']['Breaking']['DropList']={'Container':{'Type':'Single','Item':{'ItemId':'SM_Resonite_Chest','QuantityMin':2,'QuantityMax':2}}}
    model=render.read(COMMON/FOLDER/'chest.blockymodel');resize_axis(model,'x',2,-16)
    save(COMMON/FOLDER/'chest_large.blockymodel',model)
    save(RES/'Server/Item/Items/StrangeMatter/SM_Resonite_Chest_Large.json',large)
    box=render.read(RES/'Server/Item/Block/Hitboxes/StrangeMatter/SM_Resonite_Chest.json')
    for b in box['Boxes']:
        b['Min']['X']=b['Min']['X']*2-1;b['Max']['X']=b['Max']['X']*2-1
    save(RES/'Server/Item/Block/Hitboxes/StrangeMatter/SM_Resonite_Chest_Large.json',box)
    # Only the new item needs an initial icon. Root regenerates the selected
    # existing furniture icons together after all art contributors freeze.
    icon=render.render(render.item_faces(large),256).resize((64,64),Image.Resampling.LANCZOS)
    icon.save(COMMON/large['Icon'])
    meta=render.read(ROOT/'tools/assets/furniture-set.json')
    for entry in meta['items']:
        if entry['id']=='SM_Resonite_Chest':entry['description']='Store 18 stacks of supplies. Place two matching chests side by side to combine them into one large chest.'
        if entry['id']=='SM_Resonite_Table':entry['description']='A large work table with a soft surface and reinforced legs. Occupies a square of four blocks.'
        current=render.read(COMMON/entry['model']);points=np.concatenate([f[0] for f in render.model_faces(current,Image.open(atlas))])
        entry['boundsModelUnits']={'min':points.min(0).round(5).tolist(),'max':points.max(0).round(5).tolist()}
    entry=copy.deepcopy(next(e for e in meta['items'] if e['id']=='SM_Resonite_Chest'))
    entry.update(id='SM_Resonite_Chest_Large',name='Large Resonite Chest',description='Store 36 stacks of supplies in a joined chest. Breaking it returns two regular chests and its contents.',
                 model=block['CustomModel'],icon=large['Icon'],itemFile='Server/Item/Items/StrangeMatter/SM_Resonite_Chest_Large.json',
                 recipe=None,capacity=36,hitbox='SM_Resonite_Chest_Large',variant=True,interactionHint='Press [{key}] to open the large chest')
    points=np.concatenate([f[0] for f in render.item_faces(large)])
    entry['boundsModelUnits']={'min':points.min(0).round(5).tolist(),'max':points.max(0).round(5).tolist()}
    meta['items'].append(entry);meta['interactionHints']['interactionHints.SM_Resonite_Chest_Large']=entry['interactionHint']
    save(ROOT/'tools/assets/furniture-set.json',meta)
    assert hashlib.sha256(atlas.read_bytes()).hexdigest()==atlas_hash,'Painted atlas changed unexpectedly'
    save(REPORT,{'baseline':str(baseline),'atlasSha256':atlas_hash,'changes':changes,'newVariant':'SM_Resonite_Chest_Large','tableFootprint':[2,2]})
    print(json.dumps({'report':str(REPORT),'modelsRepaired':len(changes),'atlasChanged':False}))

def preview():
    from io import BytesIO
    baseline=Path((ROOT/'build/revision-0.8.7-art-baseline.txt').read_text().strip())/'Resources.zip'
    rows=[('window',175,12),('bed',170,25),('table',35,40),('chest',35,20),('cabinet',35,15),('wardrobe',35,15)]
    sheet=Image.new('RGB',(1000,len(rows)*330),(17,25,42));draw=ImageDraw.Draw(sheet)
    font=ImageFont.truetype('C:/Windows/Fonts/consola.ttf',16)
    with zipfile.ZipFile(baseline) as z:
        names=z.namelist();prefix=next(n for n in names if n.endswith('Common/'+FOLDER.as_posix()+'/window.blockymodel')).split('Common/')[0]
        texture=Image.open(BytesIO(z.read(prefix+'Common/'+FOLDER.as_posix()+'/resonite_furniture.png')))
        for row,(piece,yaw,pitch) in enumerate(rows):
            relative='Common/'+FOLDER.as_posix()+'/'+piece+'.blockymodel'
            models=[json.loads(z.read(prefix+relative)),render.read(RES/relative)]
            for col,model in enumerate(models):
                pic=render.render(render.model_faces(model,texture),310,yaw=yaw,pitch=pitch)
                sheet.paste(pic,(col*500+95,row*330),pic)
                draw.text((col*500+250,row*330+315),piece+' '+('before' if col==0 else 'after'),font=font,anchor='mm',fill=(215,237,240))
    path=ROOT/'docs/art/furniture-0.8.7-before-after.png';sheet.save(path)
    large=render.read(RES/'Server/Item/Items/StrangeMatter/SM_Resonite_Chest_Large.json')
    model=render.read(COMMON/large['BlockType']['CustomModel']);opened=copy.deepcopy(model)
    animation=render.read(COMMON/FOLDER/'Animations/chest_open.blockyanim')
    for node in walk(opened['nodes']):
        if node['name']=='Lid':node['orientation']=animation['nodeAnimations']['Lid']['orientation'][-1]['delta']
    render.contact([('Large chest closed',render.item_faces(large)),('Large chest open',render.model_faces(opened,Image.open(COMMON/FOLDER/'resonite_furniture.png')))],ROOT/'docs/art/resonite-chest-large.png',cols=2,cell=420)
    print(path)

def audit_art():
    """Explicit historical audit only. Never called by the build validator."""
    baseline=Path((ROOT/'build/revision-0.8.7-art-baseline.txt').read_text().strip())/'Resources.zip'
    checked=[];unchanged=[]
    with zipfile.ZipFile(baseline) as z:
        for name in z.namelist():
            marker='Common/'+FOLDER.as_posix()+'/'
            if marker not in name:continue
            relative=name[name.index(marker):];path=RES/relative;old=z.read(name)
            if path.suffix=='.blockymodel':
                expected=json.loads(old);repair_origins(expected);visible_groups(expected)
                if path.stem in ('chest','cabinet','wardrobe'):repair_rails(expected)
                if path.stem=='table':resize_axis(expected,'z',2,16)
                assert expected==render.read(path),'Unrequested furniture field change: '+relative
                checked.append(relative)
            else:
                assert old==path.read_bytes(),'Existing furniture texture or animation changed: '+relative
                unchanged.append(relative)
    result={'status':'PASS','historicalAuditRequested':True,'baseline':str(baseline),
            'modelsWithOnlyRequestedFieldChanges':len(checked),'unchangedExistingTextureAndAnimations':len(unchanged),
            'checkedModels':checked,'unchangedFiles':unchanged}
    save(ROOT/'build/art-preservation/furniture-0.8.7-preservation.json',result)
    print(json.dumps(result,indent=2))

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--apply',action='store_true');parser.add_argument('--preview',action='store_true');parser.add_argument('--audit-art',action='store_true',help='Explicitly compare this revision with its historical snapshot');args=parser.parse_args()
    if args.apply:apply()
    if args.preview:preview()
    if args.audit_art:audit_art()
    if not args.apply and not args.preview and not args.audit_art:parser.print_help()
