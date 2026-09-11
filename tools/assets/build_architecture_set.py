"""Targeted native roof set and architectural fixes; never runs broad art generators.

Native metal roof geometry and UVs are copied unchanged into this mod's namespace.
Only its new shared atlas is recolored. Existing door/hatch/stair atlases and the
closed door/straight stair models remain byte-identical. Run validate_architecture_set.py
afterwards. Central content catalogs, languages, recipes and icons are root-owned.
"""
from pathlib import Path
import copy, hashlib, json, math
import numpy as np
from PIL import Image
import build_assets as art
import render_current_icons as renderer

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / 'src/main/resources'
COMMON = RES / 'Common'
ITEMS = RES / 'Server/Item/Items/StrangeMatter'
HITBOXES = RES / 'Server/Item/Block/Hitboxes/StrangeMatter'
NATIVE = ROOT.parent / 'HytaleSourceCode/Assets'
BLOCKS = COMMON / 'Blocks/StrangeMatter'
ROOFS = BLOCKS / 'Roofs'
ATLAS = 'Blocks/StrangeMatter/Roofs/Resonite_Roof.png'
STATES = ('Corner_Right', 'Corner_Left', 'Inverted_Corner_Right', 'Inverted_Corner_Left')

def read(path): return json.loads(path.read_text(encoding='utf-8-sig'))
def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')
def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def vector(x,y,z): return dict(x=x,y=y,z=z)
def roof_texture():
    # Keep the native painted seams, rivets, edge wear and transparent triangular
    # masks. A luminance grade gives all 17 shapes one matching navy metal finish.
    source=NATIVE/'Common/Blocks/Structures/Roofs/Metal_Roofs/Metal_Iron.png'
    rgba=np.array(Image.open(source).convert('RGBA')); luma=rgba[:,:,:3].mean(axis=2)/255
    low=np.array([8,17,31]); high=np.array([91,117,145])
    rgba[:,:,:3]=np.clip(low+luma[:,:,None]*(high-low),0,255).astype(np.uint8)
    # Short inlaid signals follow existing corrugation seams instead of painting
    # entire sheets bright. The top rectangle and triangular UV islands share it.
    for y in (111,127,143,159,207,223,239,255,271,287,303):
        for x in range(3, min(96,rgba.shape[1]-8), 16):
            color=np.array([58,166,184] if (x//16+y//16)%3 else [111,79,155])
            for dx in range(5):
                if y<rgba.shape[0] and rgba[y,x+dx,3]>0:
                    rgba[y,x+dx,:3]=color
    ROOFS.mkdir(parents=True,exist_ok=True)
    # The native metal corner's Beam_Middle reaches UV y=357 although its
    # stock bitmap ends at 352. Extend the new atlas to cover those real UVs,
    # continuing the final painted edge instead of shipping out-of-range faces.
    padded=np.zeros((512,256,4),dtype=np.uint8)
    for x in range(256):padded[:rgba.shape[0],x]=rgba[:,x%rgba.shape[1]]
    for x in range(256):
        sx=x%rgba.shape[1]
        opaque=np.flatnonzero(rgba[:,sx,3])
        if len(opaque):padded[rgba.shape[0]:,x]=rgba[opaque[-1],sx]
    Image.fromarray(padded).save(COMMON/ATLAS)
    return source

def roofs():
    source_texture=roof_texture(); entries=[]; model_proofs={}
    names={'':'Resonite Roof','_Shallow':'Shallow Resonite Roof','_Steep':'Steep Resonite Roof','_Flat':'Flat Resonite Roof'}
    descriptions={
      '':'Navy metal roofing. Automatically joins into inner corners, outer corners and ridge caps.',
      '_Shallow':'A broad, gentle metal roof slope. Place matching roofs beside it to form corners.',
      '_Steep':'A tall metal roof slope for sharp laboratory gables. Joins into inner and outer corners.',
      '_Flat':'A low metal roof panel for flat laboratory roofs, ledges and transitions.'}
    for suffix in names:
        native_id='Metal_Iron_Roof'+suffix; item_id='SM_Resonite_Roof'+suffix
        source=read(NATIVE/f'Server/Item/Items/Metal/Iron/{native_id}.json')
        block=copy.deepcopy(source['BlockType'])
        # Parent supplies native roof support/placement/shape sizes. Explicit
        # local state textures and own drop IDs prevent inheritance leaks.
        def local_model(block):
            path=block.get('CustomModel')
            if path:
                original=NATIVE/'Common'/path
                target=ROOFS/(Path(path).stem.replace('Metal_', 'Resonite_')+'.blockymodel')
                target.write_bytes(original.read_bytes())
                block['CustomModel']=target.relative_to(COMMON).as_posix()
                model_proofs[block['CustomModel']]={'native':path,'sha256':digest(original)}
            block['CustomModelTexture']=[{'Texture':ATLAS,'Weight':1}]
        local_model(block)
        block.update(Material='Solid', DrawType='Model', Opacity='Transparent', Group='Roof',
                     ParticleColor='#263c58', TextureComputedColor='#263c58',
                     BlockSoundSetId='Metal', BlockParticleSetId='Metal', PhysicalMaterialId='Metal',
                     VariantRotation='UpDownNESW')
        block['Gathering']={'Breaking':{'GatherType':'Rocks','ItemId':item_id}}
        for state in block.get('State',{}).get('Definitions',{}).values():
            local_model(state)
            # Native shallow corners combine two adjacent roof items.
            state['Gathering']={'Breaking':{'GatherType':'Rocks','ItemId':item_id,
                                            'Quantity':2 if suffix=='_Shallow' else 1}}
        if 'ConnectedBlockRuleSet' in block:
            # Same pitch joins native metal/stone roofs as the native metal set.
            # Explicit material names keep steep and shallow shapes separate.
            block['ConnectedBlockRuleSet']['MaterialName']='Roof'+suffix
        quantity=2 if suffix in ('_Shallow','_Steep') else 4
        recipe={'Input':[{'ItemId':'SM_Resonite_Ingot','Quantity':2}],
                'OutputQuantity':quantity,
                'TimeSeconds':2,'KnowledgeRequired':True,
                'BenchRequirement':[{'Id':'SM_Laboratory','Type':'Crafting','Categories':['SM_Laboratory_Building']} ]}
        item={'Parent':native_id,'TranslationProperties':{'Name':f'server.items.{item_id}.name',
              'Description':f'server.items.{item_id}.description'},'Icon':f'Icons/ItemsGenerated/{item_id}.png',
              'IconProperties':source['IconProperties'],'MaxStack':100,'Quality':'Uncommon',
              'PlayerAnimationsId':'Block','ItemSoundSetId':'ISS_Items_Metal','Set':'SM_Resonite',
              'Categories':['Blocks.Metal','SM_StrangeMatter.All'],
              'Tags':{'Type':['StrangeMatter'],'SubType':['Roof'],'Family':['Resonite']},
              'Recipe':recipe,'ResourceTypes':[],
              'Interactions':{'Primary':'Block_Primary','Secondary':'Block_Secondary'},'BlockType':block}
        write(ITEMS/(item_id+'.json'),item)
        entries.append({'id':item_id,'name':names[suffix],'description':descriptions[suffix],
                        'model':block['CustomModel'],'texture':ATLAS,'recipe':recipe,
                        'category':'SM_Laboratory_Building','research':'resonite',
                        'nativeParent':native_id,'states':list(block.get('State',{}).get('Definitions',{}))})
    return entries,model_proofs,source_texture

def stairs():
    path=ITEMS/'SM_Resonite_Tile_Stairs.json'; item=read(path); block=item['BlockType']
    base=read(BLOCKS/'resonite_tile_stairs.blockymodel')
    native=read(NATIVE/'Server/Item/Items/Wood/Softwood/Wood_Softwood_Stairs.json')['BlockType']
    block['State']=copy.deepcopy(native['State']);block['ConnectedBlockRuleSet']=copy.deepcopy(native['ConnectedBlockRuleSet'])
    block['Supporting']=copy.deepcopy(native['Supporting'])
    block['VariantRotation']='UpDownNESW';block['PlacementSettings']={'RotationMode':'StairFacingPlayer','AllowRotationKey':True}
    for state in STATES:
        hitbox=read(NATIVE/f'Server/Item/Block/Hitboxes/Structure/Stairs/Stairs_{state}.json')
        nodes=[copy.deepcopy(base['nodes'][0])]; serial=20
        for box in hitbox['Boxes'][1:]:
            lo=np.array([box['Min'][k] for k in 'XYZ'])*32-np.array([16,0,16])
            hi=np.array([box['Max'][k] for k in 'XYZ'])*32-np.array([16,0,16])
            # Copy the upper tread atlas. Tall right/left inner wings use a
            # quarter-turn of the authored tread, preserving texel density.
            node=copy.deepcopy(base['nodes'][1]);node['id']=str(serial);serial+=1
            node['name']='upper_'+str(serial);node['position']=vector(*((lo+hi)/2).tolist())
            dims=(hi-lo).tolist()
            if dims[2]>16:
                node['orientation']=art.quat((0,90,0));dims=[dims[2],dims[1],dims[0]]
            node['shape']['settings']['size']=vector(*dims)
            nodes.append(node)
            inlay=copy.deepcopy(base['nodes'][3]);inlay['id']=str(serial);serial+=1
            inlay['name']='tread_inlay_'+str(serial);inlay['position']=vector(*((lo+hi)/2).tolist())
            inlay['position']['y']=31.55;inlay['orientation']=copy.deepcopy(node['orientation'])
            inlay['shape']['settings']['size']['x']=max(1,dims[0]-2)
            nodes.append(inlay)
        # A lower tread signal stays on the remaining exposed quarter.
        inlay=copy.deepcopy(base['nodes'][2]);inlay['id']=str(serial)
        inlay['shape']['settings']['size']['x']=14
        inlay['position']['x']=-8 if 'Right' in state else 8
        nodes.append(inlay)
        target=BLOCKS/f'resonite_tile_stairs_{state.lower()}.blockymodel'
        model=copy.deepcopy(base);model['nodes']=nodes;write(target,model)
        definition=block['State']['Definitions'][state]
        definition['CustomModel']=target.relative_to(COMMON).as_posix()
        definition['CustomModelTexture']=[{'Texture':'Blocks/StrangeMatter/resonite_tile_stairs.png','Weight':1}]
    write(path,item)

def animation(name,axis,angle,translation=(0,0,0),closing=False):
    orientation=art.quat(tuple(angle if k==axis else 0 for k in range(3)))
    start,end=(orientation,art.quat()) if closing else (art.quat(),orientation)
    first,last=(translation,(0,0,0)) if closing else ((0,0,0),translation)
    tracks={'position':[],'orientation':[{'time':0,'delta':start,'interpolationType':'smooth'},
               {'time':14,'delta':end,'interpolationType':'smooth'}],
            'shapeStretch':[],'shapeVisible':[],'shapeUvOffset':[]}
    if any(translation):tracks['position']=[{'time':0,'delta':vector(*first),'interpolationType':'smooth'},
                                         {'time':14,'delta':vector(*last),'interpolationType':'smooth'}]
    write(BLOCKS/f'Animations/{name}.blockyanim',{'formatVersion':1,'duration':16,
                                               'holdLastKeyframe':True,'nodeAnimations':{'SM_Hinge':tracks}})

def model_bounds(model,texture):
    faces=renderer.model_faces(model,Image.open(texture));points=np.concatenate([x[0] for x in faces])
    lo=points.min(0)/32+np.array([.5,0,.5]);hi=points.max(0)/32+np.array([.5,0,.5])
    def clean(v):return round(float(v),9) if abs(v-round(float(v),9))<1e-8 else float(v)
    return {'Min':{k:clean(v) for k,v in zip('XYZ',lo)},'Max':{k:clean(v) for k,v in zip('XYZ',hi)}}

def doors():
    # Preserve all closed authored geometry and UVs. The hinge side of a thick
    # door used to rotate into x<0, reserving an extra filler column in the wall.
    # Its native open pose now slides inward by the maximum ornament depth.
    item_path=ITEMS/'SM_Resonite_Door.json';item=read(item_path);model=read(BLOCKS/'resonite_door.blockymodel')
    original=model_bounds(model,BLOCKS/'resonite_door.png')
    slide=max((original['Max']['Z']-.5)*32,(.5-original['Min']['Z'])*32)
    for suffix,angle in (('In',-90),('Out',90)):
        opened=copy.deepcopy(model);hinge=opened['nodes'][0]
        hinge['orientation']=art.quat((0,angle,0));hinge['position']['x']+=slide
        target=BLOCKS/f'resonite_door_open_{suffix.lower()}.blockymodel';write(target,opened)
        collision=model_bounds(opened,BLOCKS/'resonite_door.png')
        write(HITBOXES/f'SM_Resonite_Door_Open_{suffix}.json',{'Boxes':[collision]})
        definition=item['BlockType']['State']['Definitions']['OpenDoor'+suffix]
        # Native interaction boxes are deliberately wider than physical collision.
        definition['InteractionHitboxType']='Door_Open_'+suffix+'_Interaction'
        animation('resonite_door_OpenDoor'+suffix,1,angle,(slide,0,0))
        animation('resonite_door_CloseDoor'+suffix,1,angle,(slide,0,0),True)
    write(HITBOXES/'SM_Resonite_Door.json',{'Boxes':[original]})
    write(item_path,item)
    # The latch is at +X. Rebase the pivot onto the opposite -X edge without
    # moving any closed shape, then swing +90 around Z so the latch rises.
    model=read(BLOCKS/'resonite_trapdoor.blockymodel');hinge=model['nodes'][0]
    old_center={k:hinge['position'][k]+hinge['shape']['offset'][k] for k in 'xyz'}
    hinge['position']=vector(-13,3,0)
    hinge['shape']['offset']={k:old_center[k]-hinge['position'][k] for k in 'xyz'}
    write(BLOCKS/'resonite_trapdoor.blockymodel',model)
    opened=copy.deepcopy(model);opened['nodes'][0]['orientation']=art.quat((0,0,90))
    write(BLOCKS/'resonite_trapdoor_open_out.blockymodel',opened)
    write(HITBOXES/'SM_Resonite_Trapdoor_Open_Out.json',{'Boxes':[model_bounds(opened,BLOCKS/'resonite_trapdoor.png')]})
    write(HITBOXES/'SM_Resonite_Trapdoor.json',{'Boxes':[model_bounds(model,BLOCKS/'resonite_trapdoor.png')]})
    animation('resonite_trapdoor_OpenDoorOut',2,90)
    animation('resonite_trapdoor_CloseDoorOut',2,90,closing=True)
    return {'doorInwardSlideModelUnits':slide,'hatchHinge':[-13,3,0],'hatchOpenAxis':'Z','hatchOpenDegrees':90}

def main():
    preserved=[BLOCKS/(x+'.png') for x in ('resonite_door','resonite_trapdoor','resonite_tile_stairs')]
    preserved += [BLOCKS/'resonite_door.blockymodel',BLOCKS/'resonite_tile_stairs.blockymodel']
    before={p.relative_to(COMMON).as_posix():digest(p) for p in preserved}
    entries,proofs,texture=roofs();stairs();fixes=doors()
    assert all(digest(COMMON/p)==sha for p,sha in before.items())
    write(ROOT/'tools/assets/architecture-set.json',{'items':entries,'nativeModelCopies':proofs,
      'nativeAtlas':str(texture),'preservedExistingArt':before,'targetedFixes':fixes})
    print(f'Architecture: {len(entries)} roof items, {len(proofs)} native roof shapes, four stair corners, door clearance and latch-opposite hatch hinge.')

if __name__=='__main__':main()
