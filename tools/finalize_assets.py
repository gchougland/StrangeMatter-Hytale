"""Idempotent integration patch after convert_content.py and asset generation.

Writes generated item JSON/hitboxes and attachment/door animation resources only.
No runtime logic or source content registry is modified here.
"""
from pathlib import Path
import json,sys,math,copy,re
import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parent/'assets'))
import build_assets as art
from content_policy import FAMILIES, family_light
ROOT=art.ROOT;RES=ROOT/'src/main/resources';COMMON=art.COMMON
ITEMS=RES/'Server/Item/Items/StrangeMatter';HIT=RES/'Server/Item/Block/Hitboxes/StrangeMatter'
VANILLA=ROOT.parent/'HytaleSourceCode/hytale-shared-source/HytaleAssets'

def read(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def write(p,d):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(d,indent=2)+'\n',encoding='utf-8')
def box(low,high):
    return {'Min':dict(zip('XYZ',[round(float(v)/32+(.5 if i!=1 else 0),6) for i,v in enumerate(low)])),
            'Max':dict(zip('XYZ',[round(float(v)/32+(.5 if i!=1 else 0),6) for i,v in enumerate(high)]))}
def bounds(model):
    pts=np.concatenate([f[0] for f in art.geometry(model)]);return pts.min(0),pts.max(0)
def ungroup(model,name):
    if len(model['nodes'])==1 and model['nodes'][0]['name']==name:
        group=model['nodes'][0];offset=np.array([group['position'][k] for k in 'xyz']);nodes=group['children']
        for n in nodes:n['position']=art.V((np.array([n['position'][k] for k in 'xyz'])+offset).tolist())
        model['nodes']=nodes
    return model
def hinge(model,name,pivot):
    m=copy.deepcopy(model)
    for n in m['nodes']:n['position']=art.V((np.array([n['position'][k] for k in 'xyz'])-np.array(pivot)).tolist())
    m['nodes']=[{'id':'9000','name':name,'position':art.V(pivot),'orientation':art.quat(),
      'shape':{'type':'none','offset':art.V((0,0,0)),'stretch':art.V((1,1,1)),
      'settings':{'isPiece':True},'textureLayout':{},'unwrapMode':'custom','visible':True,
      'doubleSided':False,'shadingMode':'standard'},'children':m['nodes']}];return m
def rotated(model,pivot,rot):
    m=copy.deepcopy(model);r=art.qmatrix(art.quat(rot));p=np.array(pivot)
    for n in m['nodes']:
        n['position']=art.V(((np.array([n['position'][k] for k in 'xyz'])-p)@r.T+p).tolist())
        n['orientation']=art.matrixquat(r@art.qmatrix(n['orientation']))
    return m
def animation(path,node,rot,close=False):
    keys=[(0,rot if close else (0,0,0)),(14,(0,0,0) if close else rot)]
    write(path,{'formatVersion':1,'duration':16,'holdLastKeyframe':True,'nodeAnimations':{node:{'position':[],
        'orientation':[{'time':t,'delta':art.quat(q),'interpolationType':'smooth'} for t,q in keys],
        'shapeStretch':[],'shapeVisible':[],'shapeUvOffset':[]}}})

def doors():
    for source,pivot,rotations,interaction in [('resonite_door',(-16,0,0),{'In':(0,-90,0),'Out':(0,90,0)},'Door'),
        # Inset pivot keeps the opened hatch, latch and pane inside one native cell.
        ('resonite_trapdoor',(0,3,13),{'Out':(90,0,0)},'Door_Horizontal')]:
        id=art.hid(source);path=COMMON/f'Blocks/StrangeMatter/{source}.blockymodel';node='SM_Hinge';m=ungroup(read(path),node)
        write(path,hinge(m,node,pivot));item=read(ITEMS/(id+'.json'));b=item['BlockType'];definitions={'DoorBlocked':{}}
        # Explicit rest pose initializes the same animation transform path on placement
        # that the native Door interaction uses after its first open/close.
        rest=f'Blocks/StrangeMatter/Animations/{source}_Closed.blockyanim'
        animation(COMMON/rest,node,(0,0,0));b['CustomModelAnimation']=rest
        for direction,rot in rotations.items():
            for close in (False,True):
                state=('CloseDoor' if close else 'OpenDoor')+direction;anim=f'Blocks/StrangeMatter/Animations/{source}_{state}.blockyanim'
                animation(COMMON/anim,node,rot,close)
                hit=id if close else id+'_Open_'+direction
                if not close:
                    opened=rotated(m,pivot,rot);write(HIT/(hit+'.json'),{'Boxes':[box(*bounds(opened))]})
                    # Reviewable static open model also helps model-editor inspection.
                    write(COMMON/f'Blocks/StrangeMatter/{source}_open_{direction.lower()}.blockymodel',opened)
                definitions[state]={'CustomModelAnimation':anim,'HitboxType':hit,'InteractionHitboxType':hit,
                   'InteractionSoundEventId':'SFX_Door_Wooden_'+('Close' if close else 'Open'),
                   'InteractionHint':'server.interactionHints.'+id+('_Open' if close else '_Close')}
                if not close:definitions[state]['SoundOcclusionOpacity']=0
        b.update({'IsDoor':True,'State':{'Definitions':definitions},'Interactions':{'Use':interaction},'InteractionHint':'server.interactionHints.'+id+'_Open'})
        item['Categories']=['Furniture.Doors'];item['Interactions']={'Primary':'Block_Primary','Secondary':'Block_Secondary'}
        if source=='resonite_trapdoor':b['MovementSettings']={'IsClimbable':True}
        write(ITEMS/(id+'.json'),item)

def hat():
    base=read(COMMON/'Items/StrangeMatter/tinfoil_hat.blockymodel');nodes=copy.deepcopy(base['nodes'])
    for n in nodes:
        pos=n['position'];pos['x']*=1.8;pos['z']*=1.8;pos['y']=pos['y']*1.2+8
        s=n['shape']['stretch'];s['x']*=1.8;s['z']*=1.8;s['y']*=1.2
    # Hytale armor matches this root name to the player rig's Head bone.
    model={'lod':'auto','nodes':[{'id':'9000','name':'Head','position':{'x':0,'y':0,'z':-2},'orientation':art.quat(),
      'shape':{'type':'none','offset':art.V((0,0,0)),'stretch':art.V((1,1,1)),'settings':{'isPiece':True},'textureLayout':{},'unwrapMode':'custom','visible':True,'doubleSided':False,'shadingMode':'standard'},'children':nodes}]}
    write(COMMON/'Items/StrangeMatter/tinfoil_hat_worn.blockymodel',model)
    p=ITEMS/'SM_Tinfoil_Hat.json';item=read(p);item['Model']='Items/StrangeMatter/tinfoil_hat_worn.blockymodel';item['PlayerAnimationsId']='Block'
    item['Armor'].update({'CosmeticsToHide':['Haircut','HeadAccessory']});item['Categories']=['Items.Armors']
    equip={'Interactions':[{'Type':'EquipItem'}]};item['Interactions']={'Primary':equip,'Secondary':equip};write(p,item)

def lights(source,b):
    if 'Light' not in b and source!='anomalous_grass':return
    family=source.split('_')[0]
    if family in FAMILIES and source.endswith(('_crystal','_lamp','_lantern')):
        fixture=source.endswith(('_lamp','_lantern'))
        b['Light']={'Color':family_light(family,fixture),'Radius':15 if fixture else 5}
        return
    colors={'chrono':'#eb5','gravitic':'#95d','shade':'#749','spatial':'#d6c','insight':'#4dc','energetic':'#5dd'}
    color=next((v for k,v in colors.items() if k in source),'#6dc')
    # ColorShort channels are emitted light levels, not an ordinary RGB tint.
    # Preserve Minecraft's crystal 5 / lamp and lantern 15 brightness distinction.
    level=5 if source.endswith('_crystal') else 15 if source.endswith(('_lamp','_lantern')) else 8
    if source=='anomalous_grass':color='#6f9';level=3
    channels=[int(c,16) for c in color[1:]];peak=max(channels)
    b['Light']={'Color':'#'+''.join(format(round(c/peak*level),'x') for c in channels),'Radius':level}

def source_parity(source,item):
    b=item['BlockType']
    if source.endswith('_ore') or source in ('anomalous_grass','resonite_block','resonite_tile','fancy_resonite_tile'):
        b['Opacity']='Solid'
    if source.endswith('_crystal'):
        b['VariantRotation']='DoublePipe'
        b['PlacementSettings']={'RotationMode':'Default','AllowRotationKey':True}
        b['BlockParticleSetId']='Crystal';b['BlockSoundSetId']='Crystal'
        b['Gathering']={'Breaking':{'GatherType':'Rocks','ItemId':item_id(source)}}
    if source=='resonite_pillar':
        b['VariantRotation']='Pipe';b['PlacementSettings']={'AllowRotationKey':True}
    if source=='resonite_tile_stairs':
        b['VariantRotation']='UpDownNESW';b['PlacementSettings']={'RotationMode':'StairFacingPlayer','AllowRotationKey':True}
    if source=='resonite_tile_slab':
        # Native Half_Block changes the placed half to its doubled Block state,
        # consuming one held slab only in adventure mode.
        b['VariantRotation']='DoublePipe'
        item['Interactions']['Secondary']={'Interactions':[{'Parent':'Half_Block','Matchers':[{
          'Block':{'Id':item_id(source),'State':'default'},'Face':'Up','StaticFace':False}]}],
          'Cooldown':{'Id':'BlockInteraction','Cooldown':.3,'ClickBypass':True},
          'Settings':{'Creative':{'AllowSkipChainOnClick':True}}}
        full=read(ITEMS/'SM_Resonite_Tile.json')['BlockType']
        b['State']={'Definitions':{'Block':{'CustomModel':full['CustomModel'],'CustomModelTexture':full['CustomModelTexture'],
          'HitboxType':'SM_Resonite_Tile','InteractionHitboxType':'SM_Resonite_Tile','Gathering':{'Breaking':{
          'GatherType':'Rocks','DropList':{'Container':{'Type':'Single','Item':{'ItemId':item_id(source),'QuantityMin':2,'QuantityMax':2}}}}}}}}
    if source.endswith('_ore'):
        b.update({'DrawType':'CubeWithModel','Textures':[{'All':'BlockTextures/Rock_Stone.png','Weight':1}],
          'Group':'Stone','BlockParticleSetId':'Ore','BlockSoundSetId':'Ore','VariantRotation':'None'})
        path=COMMON/b['CustomModel'];model=read(path)
        model['nodes']=[n for n in model['nodes'] if n['name']!='host_rock'];write(path,model)
        drop='SM_Raw_Resonite' if source=='resonite_ore' else item_id(source.removesuffix('_ore'))
        b['Gathering']={'Breaking':{'GatherType':'Rocks','Quality':3,'DropList':{'Container':{
          'Type':'Single','Item':{'ItemId':drop,'QuantityMin':1,'QuantityMax':2}}}}}
    if source=='resonite_block':b['Gathering']['Breaking']['Quality']=3
    if source=='anomalous_grass':
        item['Categories']=['Blocks.Soils'];item['Tags']['Type']=['StrangeMatter','Soil'];item['ItemSoundSetId']='ISS_Blocks_Soft'
        b.update({'Group':'Grass','BlockParticleSetId':'Grass_Earth','ParticleColor':'#497872',
          'BlockSoundSetId':'Grass','PhysicalMaterialId':'Foliage','Gathering':{'Breaking':{'GatherType':'Soils','ItemId':'Soil_Dirt'}},
          'RandomTickProcedure':{'Type':'SM_Anomalous_Grass'},'DrawType':'Cube','VariantRotation':'None',
          'Textures':[{'Up':'BlockTextures/StrangeMatter/Anomalous_Grass_Top.png',
            'Sides':'BlockTextures/StrangeMatter/Anomalous_Grass_Side.png',
            'Down':'BlockTextures/StrangeMatter/Anomalous_Grass_Soil.png','Weight':1}],
          'TransitionTexture':'BlockTextures/Transition_Soil_Grass_GS.png',
          'TransitionToGroups':['Stone','Dirt','Dirt_Dark','Sand','Gravel','Wood','Cobble']})
        b.pop('Interactions',None);b.pop('InteractionHint',None)
        b.pop('CustomModel',None);b.pop('CustomModelTexture',None)
        for label,mat in [('Top','grass_top'),('Side','grass_side'),('Soil','soil')]:
            path=COMMON/f'BlockTextures/StrangeMatter/Anomalous_Grass_{label}.png';path.parent.mkdir(parents=True,exist_ok=True)
            art.paint(32,32,mat,'anomalous_grass/0/'+{'Top':'top','Side':'front','Soil':'bottom'}[label]).save(path)
    if source=='time_dilation_block':
        b.update({'Opacity':'Solid','VariantRotation':'None','Light':{'Color':'#a72','Radius':10}})


def conduit_states():
    path=COMMON/'Blocks/StrangeMatter/resonant_conduit_full.blockymodel';full=read(path)
    item=read(ITEMS/'SM_Resonant_Conduit.json');b=item['BlockType'];definitions={}
    for mask in range(64):
        nodes=[n for n in full['nodes'] if n['name'].startswith('hub_') or mask & int(re.match(r'arm(\d+)',n['name'])[1])]
        model={**full,'nodes':nodes};name=f'resonant_conduit_connection{mask:02d}'
        modelpath=f'Blocks/StrangeMatter/{name}.blockymodel';write(COMMON/modelpath,model)
        hit=f'SM_Resonant_Conduit_Connection{mask:02d}';boxes=[box((-5.5,10.5,-5.5),(5.5,21.5,5.5))]
        for bit,(axis,sign) in enumerate(((0,1),(0,-1),(1,1),(1,-1),(2,1),(2,-1))):
            if not mask & (1<<bit):continue
            low=[-4.5,11.5,-4.5];high=[4.5,20.5,4.5];center=[0,16,0][axis]
            low[axis]=center+(5 if sign>0 else -16);high[axis]=center+(16 if sign>0 else -5)
            boxes.append(box(low,high))
        write(HIT/(hit+'.json'),{'Boxes':boxes})
        definitions[f'Connection{mask:02d}']={'CustomModel':modelpath,'HitboxType':hit,'InteractionHitboxType':hit}
    b.update({'CustomModel':definitions['Connection00']['CustomModel'],'HitboxType':'SM_Resonant_Conduit_Connection00',
      'InteractionHitboxType':'SM_Resonant_Conduit_Connection00','VariantRotation':'None','State':{'Definitions':definitions}})
    write(ITEMS/'SM_Resonant_Conduit.json',item)
    write(HIT/'SM_Resonant_Conduit.json',read(HIT/'SM_Resonant_Conduit_Connection00.json'))
    review=[{'sourceId':f'Conduit mask {m:02d}','model':definitions[f'Connection{m:02d}']['CustomModel'],
      'texture':'Blocks/StrangeMatter/resonant_conduit.png'} for m in (0,1,3,5,21,63)]
    art.contact(review,ROOT/'docs/art/conduit-connections.png',cols=3,cell=250)
    art.render(review[2],256).resize((64,64),art.Image.Resampling.LANCZOS).save(COMMON/item['Icon'])


def creative_library():
    for p in ITEMS.glob('*.json'):
        item=read(p);item['Categories']=list(dict.fromkeys([*item.get('Categories',[]),'SM_StrangeMatter']))
        write(p,item)
    icon=(COMMON/'Icons/ItemsGenerated/SM_Laboratory_Bench.png').read_bytes()
    for path in ('Icons/ItemCategories/SM_StrangeMatter.png','Icons/CraftingCategories/SM_Laboratory.png'):
        p=COMMON/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(icon)
    write(RES/'Server/Item/Category/CreativeLibrary/SM_StrangeMatter.json',{
      'Name':'server.ui.itemcategory.SM_StrangeMatter','Icon':'Icons/ItemCategories/SM_StrangeMatter.png','Order':5})
    for color in ('Cyan','Purple'):
        model=read(RES/'Server/Models/StrangeMatter/SM_Warp_Gate_Core.json')
        model['Model']=f'Items/StrangeMatter/anomaly_warp_gate_{color.lower()}.blockymodel'
        model['Texture']=f'Items/StrangeMatter/anomaly_warp_gate_{color.lower()}.png'
        write(RES/f'Server/Models/StrangeMatter/SM_Warp_Gate_{color}_Core.json',model)

def item_id(source):return art.hid(source)

def validate():
    missing=[];count=0
    # Hytale resolves hitbox IDs by filename across the asset type, regardless of folder.
    hitboxes={p.stem:p for p in HIT.parent.rglob('*.json')}
    def names(nodes):
        result=set()
        for n in nodes:result.add(n['name']);result.update(names(n.get('children',[])))
        return result
    for p in ITEMS.glob('*.json'):
        item=read(p);count+=1;b=item.get('BlockType',{})
        for key in ('Model','Texture','Icon'):
            if key in item and not(COMMON/item[key]).exists():missing.append((p.name,key,item[key]))
        assert art.Image.open(COMMON/item['Icon']).size==(64,64),(p.name,'item icon must be exactly 64x64')
        assert 'SM_StrangeMatter' in item.get('Categories',[]),(p.name,'missing Strange Matter creative tab')
        states=[b]+list(b.get('State',{}).get('Definitions',{}).values())
        for state in states:
            for key in ('CustomModel','CustomModelAnimation'):
                if key in state and not(COMMON/state[key]).exists():missing.append((p.name,key,state[key]))
            if 'CustomModelAnimation' in state:
                model=read(COMMON/state.get('CustomModel',b['CustomModel']));anim=read(COMMON/state['CustomModelAnimation'])
                assert set(anim['nodeAnimations'])<=names(model['nodes']),(p.name,'animation targets missing')
                for tracks in anim['nodeAnimations'].values():
                    for keyframes in tracks.values():
                        times=[key['time'] for key in keyframes];assert times==sorted(times);assert all(0<=t<=anim['duration'] for t in times)
                    for k in tracks.get('orientation',[]):assert abs(sum(v*v for v in k['delta'].values())-1)<1e-6
            for t in state.get('CustomModelTexture',[]):
                if not(COMMON/t['Texture']).exists():missing.append((p.name,'CustomModelTexture',t['Texture']))
                else:
                    w,h=art.Image.open(COMMON/t['Texture']).size
                    assert min(w,h)>=32 and w%32==h%32==0,(p.name,'model texture dimensions must be 32-pixel multiples')
            for texture in state.get('Textures',[]):
                for face,path in texture.items():
                    if face!='Weight' and not(COMMON/path).exists() and not(VANILLA/'Common'/path).exists():missing.append((p.name,face,path))
            for key in ('HitboxType','InteractionHitboxType'):
                if state.get(key,'').startswith('SM_') and state[key] not in hitboxes:missing.append((p.name,key,state[key]))
            if 'Light' in state:
                assert re.fullmatch('#[0-9a-fA-F]{3}',state['Light']['Color']);assert 0<=state['Light'].get('Radius',0)<=15
    spawners={p.stem:p for p in (RES/'Server/Particles').rglob('*.particlespawner')}
    for p in (RES/'Server/Particles').rglob('*.particlesystem'):
        for ref in read(p)['Spawners']:
            if ref['SpawnerId'] not in spawners:missing.append((p.name,'SpawnerId',ref['SpawnerId']))
    for p in spawners.values():
        d=read(p);assert d['LifeSpan']>0;assert d['Particle']['InitialAnimationFrame']['Opacity']>0
        if not(COMMON/d['Particle']['Texture']).exists():missing.append((p.name,'Texture',d['Particle']['Texture']))
    for p in hitboxes.values():
        for b in read(p)['Boxes']:assert all(b['Min'][k]<b['Max'][k] for k in 'XYZ'),p
    iron=read(VANILLA/'Server/Item/Items/Tool/Pickaxe/Tool_Pickaxe_Iron.json')
    iron_quality=next(s['Quality'] for s in iron['Tool']['Specs'] if s['GatherType']=='Rocks')
    for p in ITEMS.glob('*_Ore.json'):
        gathering=read(p)['BlockType']['Gathering']['Breaking'];drop=gathering['DropList']['Container']['Item']
        assert gathering['Quality']==iron_quality and (drop['QuantityMin'],drop['QuantityMax'])==(1,2),p
    full_sources=['anomalous_grass','resonite_block','resonite_tile','fancy_resonite_tile']
    full_ids=[art.hid(s) for s in full_sources]+[p.stem for p in ITEMS.glob('*_Ore.json')]
    for id in full_ids:assert read(hitboxes[id])['Boxes']==[box((-16,0,-16),(16,32,16))],(id,'decorations cannot reserve filler cells')
    for b in read(hitboxes['SM_Resonite_Trapdoor_Open_Out'])['Boxes']:
        assert all(0<=b['Min'][k]<b['Max'][k]<=1 for k in 'XYZ'),'open hatch must stay within its native cell'
    for p in ITEMS.glob('*_Crystal.json'):
        b=read(p)['BlockType'];assert b['VariantRotation']=='DoublePipe';assert max(int(c,16) for c in b['Light']['Color'][1:])==5
    for p in list(ITEMS.glob('*_Lamp.json'))+list(ITEMS.glob('*_Lantern.json')):
        if p.stem=='SM_Lab_Lamp':continue
        b=read(p)['BlockType'];assert max(int(c,16) for c in b['Light']['Color'][1:])==15
    assert read(ITEMS/'SM_Resonite_Pillar.json')['BlockType']['VariantRotation']=='Pipe'
    assert read(ITEMS/'SM_Resonite_Tile_Slab.json')['BlockType']['State']['Definitions']['Block']['HitboxType']=='SM_Resonite_Tile'
    grass=read(ITEMS/'SM_Anomalous_Grass.json')['BlockType']
    assert grass['DrawType']=='Cube' and not grass.get('Interactions') and not grass.get('InteractionHint')
    for p in ITEMS.glob('*_Ore.json'):
        b=read(p)['BlockType'];assert b['DrawType']=='CubeWithModel' and b['Textures'][0]['All']=='BlockTextures/Rock_Stone.png'
        assert all(n['name']!='host_rock' for n in read(COMMON/b['CustomModel'])['nodes'])
    for p in ITEMS.glob('*_Crystal.json'):
        assert all(n['name']!='rock_root' for n in read(COMMON/read(p)['BlockType']['CustomModel'])['nodes'])
    for source in ('resonite_door','resonite_trapdoor'):
        b=read(ITEMS/(item_id(source)+'.json'))['BlockType'];model=read(COMMON/b['CustomModel'])
        assert model['nodes'][0]['shape']['type']=='none' and b['CustomModelAnimation'].endswith('_Closed.blockyanim')
        rest=read(COMMON/b['CustomModelAnimation'])['nodeAnimations']['SM_Hinge']['orientation']
        assert all(k['delta']==art.quat() for k in rest)
    conduit=read(ITEMS/'SM_Resonant_Conduit.json')['BlockType']
    assert conduit['VariantRotation']=='None' and len(conduit['State']['Definitions'])==64
    for mask in range(64):
        state=conduit['State']['Definitions'][f'Connection{mask:02d}'];nodes=read(COMMON/state['CustomModel'])['nodes']
        arms={int(re.match(r'arm(\d+)',n['name'])[1]) for n in nodes if n['name'].startswith('arm')}
        assert arms=={1<<bit for bit in range(6) if mask & (1<<bit)},mask
    bench=read(ITEMS/'SM_Laboratory_Bench.json')
    assert bench['BlockType']['Bench']['Id']=='SM_Laboratory'
    assert bench['Recipe']['BenchRequirement'][0]['Id']=='Workbench'
    for p in ITEMS.glob('*.json'):
        item=read(p)
        if p.stem=='SM_Laboratory_Bench':continue
        assert all(r['Id']!='Workbench' for r in item.get('Recipe',{}).get('BenchRequirement',[])),p
    assert not missing,missing
    report={'status':'PASS','items':count,'hitboxAssets':len(hitboxes),'spawners':len(spawners),
      'checks':['Item model/texture/icon references','All item icons exactly64x64','Model texture dimensions at least32x32 in 32-pixel multiples','Door animation target nodes, time bounds and quaternions','Explicit native shape:none hinge and closed placement pose','Door state-hitbox references','Particle graph and texture references','Nonzero initial opacity','Finite emitters','3-digit ColorLight and bounded radius','Positive hitbox volumes','Model bounds use x/32+.5, y/32, z/32+.5','Full-cube building collision excludes ornamental overhang','Open hatch stays within one native cell','Ore drop ranges and native iron quality','Native CubeWithModel ores with ordinary stone host','Native grass Cube without till interactions','Pedestal-free crystal blooms','Six-face crystals / three-axis pillars / double slabs','Crystal 5 vs lamp 15 emitted brightness','64 conduit masks contain exactly their active arms','All registered items in Strange Matter creative tab','Only laboratory bench craftable in vanilla Workbench']}
    write(ROOT/'tools/assets/integration-validation.json',report);print(json.dumps(report))

def main():
    # ResourceType icons have a stricter root than item icons in Hytale's codec.
    resource_icon=COMMON/'Icons/ResourceTypes/SM_Anomaly_Shards.png'
    resource_icon.parent.mkdir(parents=True,exist_ok=True)
    resource_icon.write_bytes((COMMON/'Icons/ItemsGenerated/SM_Gravitic_Shard.png').read_bytes())
    catalog=read(ROOT/'tools/assets/catalog.json')
    for e in catalog['items']:
        p=ITEMS/(e['id']+'.json')
        if not p.exists():continue
        item=read(p)
        if e['block']:
            low=e['boundsModelUnits']['min'];high=e['boundsModelUnits']['max'];boxes=[box(low,high)]
            if e['sourceId'].endswith('_ore') or e['sourceId'] in ('anomalous_grass','resonite_block','resonite_tile','fancy_resonite_tile'):
                # Decorative mineral faces, turf sprouts and tile inlays must not reserve
                # adjacent native filler cells. These source blocks have full-cube collision.
                boxes=[box((-16,0,-16),(16,32,16))]
            if e['sourceId']=='resonite_tile_stairs':boxes=[box((-16,0,-16),(16,16,16)),box((-16,16,-16),(16,32,0))]
            if e['sourceId']=='resonant_conduit':boxes=[box((-16,11.5,-4.5),(16,20.5,4.5)),box((-4.5,0,-4.5),(4.5,32,4.5)),box((-4.5,11.5,-16),(4.5,20.5,16))]
            write(HIT/(e['id']+'.json'),{'Boxes':boxes});b=item['BlockType'];b['HitboxType']=e['id'];lights(e['sourceId'],b);source_parity(e['sourceId'],item)
        write(p,item)
    import build_lab_details
    build_lab_details.main()
    doors();hat();conduit_states();creative_library();validate()
    previews=[]
    for source in ('resonite_door','resonite_trapdoor'):
        for suffix,label in [('', ' closed'),('_open_out',' open')]:
            previews.append({'sourceId':source+label,'model':f'Blocks/StrangeMatter/{source}{suffix}.blockymodel','texture':f'Blocks/StrangeMatter/{source}.png'})
    previews.append({'sourceId':'tinfoil hat attachment','model':'Items/StrangeMatter/tinfoil_hat_worn.blockymodel','texture':'Items/StrangeMatter/tinfoil_hat.png'})
    previews.append(next(e for e in catalog['items'] if e['sourceId']=='resonant_conduit'))
    art.contact(previews,ROOT/'docs/art/integration.png',cols=3,cell=280)
    selected=('laboratory_bench','anomalous_grass','gravitic_shard_ore','gravitic_shard_crystal','stasis_projector','time_dilation_block')
    art.contact([next(e for e in catalog['items'] if e['sourceId']==name) for name in selected],
      ROOT/'docs/art/playtest-revisions.png',cols=3,cell=320)

if __name__=='__main__':main()
