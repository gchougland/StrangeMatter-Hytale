"""Validate current furniture UVs, references, placement and native behavior.

Uses only Python's standard library. This is a structural validator, not an art
snapshot comparison. Current models, materials and animations may be edited.
"""
import itertools
import json
import math
import os
from pathlib import Path
import struct
import zipfile

ROOT=Path(__file__).resolve().parents[1]
RES=ROOT/'src/main/resources'
NATIVE=ROOT.parent/'HytaleSourceCode/hytale-shared-source/HytaleAssets'
PIECES=('Chair','Stool','Sofa','Table','Desk','Bed','Chest','Wardrobe','Bookshelf',
        'Wall_Shelf','Cabinet','Wall_Monitor','Ceiling_Vent','Ladder','Window','Sign','Chest_Large')


def read(path):return json.loads(path.read_text(encoding='utf-8-sig'))


def png(path):
    data=path.read_bytes()[:24]
    assert data[:8]==b'\x89PNG\r\n\x1a\n' and data[12:16]==b'IHDR',f'Not a PNG: {path}'
    return struct.unpack('>II',data[16:24])


def vec(value,default=0):return tuple(value.get(k,default) for k in 'xyz')
def add(a,b):return tuple(x+y for x,y in zip(a,b))
def mul(m,v):return tuple(sum(a*b for a,b in zip(row,v)) for row in m)
def matmul(a,b):return tuple(tuple(sum(a[i][k]*b[k][j] for k in range(3)) for j in range(3)) for i in range(3))
IDENTITY=((1,0,0),(0,1,0),(0,0,1))


def matrix(q):
    x,y,z,w=(q.get(k,1 if k=='w' else 0) for k in ('x','y','z','w'))
    assert all(math.isfinite(v) for v in (x,y,z,w)), 'Nonfinite rotation'
    assert abs(x*x+y*y+z*z+w*w-1)<.005,'Rotation is not a unit quaternion'
    return ((1-2*(y*y+z*z),2*(x*y-z*w),2*(x*z+y*w)),
            (2*(x*y+z*w),1-2*(x*x+z*z),2*(y*z-x*w)),
            (2*(x*z-y*w),2*(y*z+x*w),1-2*(x*x+y*y)))


def model_geometry(model,atlas):
    ids=set();names=set();points=[];faces=0
    def check_uv(uv,width,height):
        nonlocal faces
        angle=uv.get('angle',0);assert math.isfinite(angle) and angle%90==0,'UV angle must be a quarter turn'
        x,y=uv['offset']['x'],uv['offset']['y']
        assert all(math.isfinite(v) for v in (x,y,width,height)),'Nonfinite UV rectangle'
        mirror=uv.get('mirror',{})
        corners=[]
        for u,v in itertools.product((0,width),(0,height)):
            if mirror.get('x'):u=-u
            if mirror.get('y'):v=-v
            for _ in range(int(angle)%360//90):u,v=-v,u
            corners.append((x+u,y+v))
        assert all(0<=u<=atlas[0] and 0<=v<=atlas[1] for u,v in corners),'Face UV rectangle escapes current atlas'
        faces+=1

    def walk(nodes,parent_rotation,parent_position):
        for node in nodes:
            assert node['id'] not in ids,'Duplicate native node ID';ids.add(node['id']);names.add(node['name'])
            shape=node.get('shape',{})
            # Native hidden nodes hide their descendants as well.
            if not shape.get('visible',True):continue
            rotation=matmul(parent_rotation,matrix(node.get('orientation',{})))
            center=add(add(parent_position,mul(parent_rotation,vec(node.get('position',{})))),mul(rotation,vec(shape.get('offset',{}))))
            assert all(math.isfinite(v) for v in center),'Nonfinite model position'
            if shape.get('type')=='box':
                size=vec(shape['settings']['size']);stretch=vec(shape.get('stretch',{}),1)
                assert all(v>0 and math.isfinite(v) for v in size),'Invalid box dimensions'
                assert all(v!=0 and math.isfinite(v) for v in stretch),'Invalid box stretch'
                for signs in itertools.product((-1,1),repeat=3):
                    corner=tuple(s*d*t/2 for s,d,t in zip(signs,size,stretch));points.append(add(center,mul(rotation,corner)))
                for face,uv in shape.get('textureLayout',{}).items():
                    assert face in ('front','back','left','right','top','bottom'),'Unknown native face'
                    width,height=(size[0],size[2]) if face in ('top','bottom') else (size[2],size[1]) if face in ('left','right') else (size[0],size[1])
                    check_uv(uv,width,height)
                assert shape.get('textureLayout'),'Visible box has no faces'
            elif shape.get('type')=='quad':
                settings=shape['settings'];size=settings['size'];width,height=size['x'],size['y']
                stretch=vec(shape.get('stretch',{}),1)
                assert all(v>0 and math.isfinite(v) for v in (width,height)),'Invalid quad dimensions'
                assert all(math.isfinite(v) for v in stretch) and stretch[0]!=0 and stretch[1]!=0,'Invalid quad stretch'
                normal=settings.get('normal','+Z');assert normal in ('+X','-X','+Y','-Y','+Z','-Z'),'Unknown quad normal'
                # Native quads store two dimensional size.x/size.y regardless of
                # their normal. Furniture.blockymodel (-Y) and Stalactite_Lime_Small
                # (+X) are supplied examples. Match the current icon renderer:
                # apply the planar stretch first, then select the normal axis.
                corners=[(x*width*stretch[0]/2,y*height*stretch[1]/2,0) for x,y in ((-1,-1),(1,-1),(1,1),(-1,1))]
                if normal[-1]=='X':corners=[(z,y,x) for x,y,z in corners]
                elif normal[-1]=='Y':corners=[(x,z,y) for x,y,z in corners]
                if normal.startswith('-'):corners.reverse()
                points.extend(add(center,mul(rotation,corner)) for corner in corners)
                uv=shape.get('textureLayout',{}).get('front');assert uv is not None,'Visible quad has no front face'
                check_uv(uv,width,height)
            elif shape.get('type') not in (None,'none'):
                raise AssertionError('Unknown native furniture primitive: '+str(shape.get('type')))
            walk(node.get('children',[]),rotation,center)
    walk(model['nodes'],IDENTITY,(0,0,0))
    assert points and faces,'Empty furniture model'
    return names,points,faces


def native_item_ids():
    local={p.stem for p in (RES/'Server/Item/Items').rglob('*.json')}
    if NATIVE.exists():return local|{p.stem for p in (NATIVE/'Server/Item/Items').rglob('*.json')}
    installed=Path(os.environ.get('APPDATA',''))/'Hytale/install/release/package/game/latest/Assets.zip'
    if installed.exists():
        with zipfile.ZipFile(installed) as archive:
            return local|{Path(name).stem for name in archive.namelist() if name.startswith('Server/Item/Items/') and name.endswith('.json')}
    raise AssertionError('Native Hytale assets are required to resolve recipe item references')


def validate():
    all_ids=native_item_ids();face_count=0;texture_paths=set();animation_paths=set();storage={};seats={}
    for piece in PIECES:
        id='SM_Resonite_'+piece;item=read(RES/'Server/Item/Items/StrangeMatter'/(id+'.json'));block=item['BlockType']
        assert 'SM_StrangeMatter.All' in item['Categories'] and any(c.startswith('Furniture.') for c in item['Categories']),id+' creative categories'
        assert item['MaxStack']>0 and item['MaxStack']%5==0,id+' stack size'
        assert png(RES/'Common'/item['Icon'])==(64,64),id+' inventory icon must be 64 square'
        textures=block['CustomModelTexture'];assert textures and all(t['Weight']>0 for t in textures),id+' weighted atlas'
        model=read(RES/'Common'/block['CustomModel'])
        for texture in textures:
            path=RES/'Common'/texture['Texture'];dimensions=png(path);texture_paths.add(path)
            assert all(v>=32 and v%32==0 for v in dimensions),id+' native atlas dimensions'
            names,points,faces=model_geometry(model,dimensions);face_count+=faces
        boxes=read(RES/'Server/Item/Block/Hitboxes/StrangeMatter'/(block['HitboxType']+'.json'))['Boxes']
        assert boxes,id+' collider required'
        for box in boxes:
            assert all(math.isfinite(box[end][axis]) for end in ('Min','Max') for axis in 'XYZ'),id+' nonfinite collider'
            assert all(box['Min'][a]<box['Max'][a] for a in 'XYZ'),id+' inverted collider'
        minimum=tuple(min(box['Min'][axis] for box in boxes) for axis in 'XYZ')
        maximum=tuple(max(box['Max'][axis] for box in boxes) for axis in 'XYZ')
        for point in points:
            p=(point[0]/32+.5,point[1]/32,point[2]/32+.5)
            assert all(minimum[i]-.035<=p[i]<=maximum[i]+.035 for i in range(3)),id+' has substantial art outside its placement envelope'
        # Decorations may overhang a few pixels within the same footprint, but
        # must not accidentally reserve a third horizontal or vertical cell.
        assert minimum[0]>=(-1 if piece=='Chest_Large' else 0) and minimum[1]>=0 and minimum[2]>=0,id+' negative filler extent'
        assert maximum[0]<=2 and maximum[1]<=2 and maximum[2]<=2,id+' excessive filler extent'
        recipe=item.get('Recipe')
        if piece=='Chest_Large':
            assert item.get('Variant') is True and recipe is None,id+' joined chest must not duplicate a crafting recipe'
            drop=block['Gathering']['Breaking']['DropList']['Container']['Item']
            assert drop=={'ItemId':'SM_Resonite_Chest','QuantityMin':2,'QuantityMax':2},id+' return both chest blocks'
        else:
            assert recipe['KnowledgeRequired'] is True,id+' research gate'
            assert recipe['BenchRequirement']==[{'Type':'Crafting','Id':'SM_Laboratory','Categories':['SM_Laboratory_Furniture']}],id+' furniture tab'
        for ingredient in (recipe or {}).get('Input',[]):
            assert ingredient.get('Quantity',1)>0,id+' ingredient quantity'
            if 'ItemId' in ingredient:assert ingredient['ItemId'] in all_ids,id+' missing recipe item '+ingredient['ItemId']
            else:assert ingredient.get('ResourceTypeId')=='Wood_Planks',id+' unexpected unresolved recipe resource'
        assert item['Interactions']=={'Primary':'Block_Primary','Secondary':'Block_Secondary'},id+' native block placement'
        if piece in ('Chair','Stool','Sofa'):
            assert block['Interactions']['Use']=='Block_Seat',id+' native seat interaction'
            seats[id]=len(block['Seats']);assert seats[id]==(2 if piece=='Sofa' else 1),id+' seat count'
            for mount in block['Seats']:
                p=tuple(mount['Offset'][k]+.5 for k in 'XYZ')
                assert all(minimum[i]<=p[i]<=maximum[i] for i in range(3)),id+' seat outside furniture'
        if piece=='Bed':
            assert len(block['Beds'])==1 and block['Interactions']['Use']['Interactions'][0]['Type']=='Bed',id+' native sleep'
            assert 'RespawnBlock' in block['BlockEntity']['Components'] and block['Interactions']['Primary']=='Check_Can_Break_Respawn',id+' respawn safety'
        if piece in ('Chest','Chest_Large','Cabinet','Wardrobe'):
            assert block['Interactions']['Use']=='Open_Container',id+' native container interaction'
            storage[id]=block['BlockEntity']['Components']['ItemContainerBlock']['Capacity']
            assert 18<=storage[id]<=54 and storage[id]%9==0,id+' native storage grid'
            initial_path=RES/'Common'/block['CustomModelAnimation'];animation_paths.add(initial_path)
            initial=read(initial_path)
            assert initial['holdLastKeyframe'] and initial['duration']==1,id+' explicit resting pose'
            for target,channels in initial['nodeAnimations'].items():
                node=next(n for n in all_nodes(model['nodes']) if n['name']==target)
                assert node['shape']['type']=='none' and node['shape']['visible'] is True,id+' native visible hinge group'
                assert channels['orientation']==[{'time':0,'delta':{'x':0,'y':0,'z':0,'w':1},'interpolationType':'linear'}],id+' initial hinge must be closed'
                assert channels['shapeVisible'][0]['delta'] is True,id+' initial hinge visibility'
            for state in ('OpenWindow','CloseWindow'):
                path=RES/'Common'/block['State']['Definitions'][state]['CustomModelAnimation'];data=read(path);animation_paths.add(path)
                assert data['holdLastKeyframe'] and 0<data['duration']<=60,id+' finite container animation'
                assert data['nodeAnimations'],id+' empty animation'
                for target,channels in data['nodeAnimations'].items():
                    assert target in names,id+' missing animated node '+target
                    for channel,frames in channels.items():
                        for frame in frames:
                            assert 0<=frame['time']<=data['duration'],id+' frame time'
                            if channel=='orientation':matrix(frame['delta'])
            assert block['InteractionHint']=='server.interactionHints.'+id,id+' specific container hint'
        if piece=='Ladder':assert block['MovementSettings']['IsClimbable'] is True and block['PlacementSettings']['RotationMode']=='BlockNormal',id+' native climb placement'
        if piece=='Ceiling_Vent':assert 'Up' in block['Support'],id+' ceiling support'
        if piece=='Window':assert block['VariantRotation']=='Wall' and block['Opacity']=='Transparent',id+' window placement'
        if piece=='Sign':assert 'BlockEntity' not in block and 'Interactions' not in block,id+' unsupported native editable sign'
        if piece=='Table':assert maximum[0]>1 and maximum[2]>1 and minimum[0]>=0 and minimum[2]>=0,id+' two by two placement footprint'
        if piece=='Chest':assert block['ConnectedBlockRuleSet']=={'Type':'CustomTemplate','TemplateShapeAssetId':'ChestConnectedBlockTemplate',
            'TemplateShapeBlockPatterns':{'Default':'SM_Resonite_Chest','Double':'SM_Resonite_Chest_Large'}},id+' native double chest connection'
    return {'status':'PASS','pieces':len(PIECES),'faces':face_count,'atlases':len(texture_paths),'animations':len(animation_paths),
            'storage':storage,'seats':seats,'historicalArtworkCompared':False}


def all_nodes(nodes):
    for node in nodes:
        yield node
        yield from all_nodes(node.get('children',[]))

if __name__=='__main__':print(json.dumps(validate(),indent=2))
