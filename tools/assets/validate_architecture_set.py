"""UV, native-rule, preservation and moving architecture endpoint regressions."""
from pathlib import Path
import argparse, copy, hashlib, itertools, json, math, struct

# Normal Gradle validation deliberately requires only Python's standard library.
# The optional contact-sheet renderer imports Pillow and NumPy inside preview().
ROOT=Path(__file__).resolve().parents[2]
COMMON=ROOT/'src/main/resources/Common'
ITEMS=ROOT/'src/main/resources/Server/Item/Items/StrangeMatter'
HITBOXES=ROOT/'src/main/resources/Server/Item/Block/Hitboxes/StrangeMatter'
BLOCKS=COMMON/'Blocks/StrangeMatter'
NATIVE=ROOT.parent/'HytaleSourceCode/Assets'
ATLAS='Blocks/StrangeMatter/Roofs/Resonite_Roof.png'
STATES=('Corner_Right','Corner_Left','Inverted_Corner_Right','Inverted_Corner_Left')
IDENTITY=((1,0,0),(0,1,0),(0,0,1))
def read(path):return json.loads(path.read_text(encoding='utf-8-sig'))
def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def require(test,message):
    if not test: raise AssertionError(message)
def png_size(path):
    with path.open('rb') as file:header=file.read(24)
    require(len(header)==24 and header[:8]==b'\x89PNG\r\n\x1a\n' and header[12:16]==b'IHDR','Expected PNG header: '+str(path))
    return struct.unpack('>II',header[16:24])
def vec(value,default=0):return tuple(value.get(k,default) for k in 'xyz')
def add(a,b):return tuple(x+y for x,y in zip(a,b))
def rotate(matrix,point):return tuple(sum(matrix[i][j]*point[j] for j in range(3)) for i in range(3))
def multiply(a,b):return tuple(tuple(sum(a[i][k]*b[k][j] for k in range(3)) for j in range(3)) for i in range(3))
def qmatrix(q):
    x,y,z,w=(q[k] for k in ('x','y','z','w'))
    return ((1-2*(y*y+z*z),2*(x*y-z*w),2*(x*z+y*w)),
            (2*(x*y+z*w),1-2*(x*x+z*z),2*(y*z-x*w)),
            (2*(x*z-y*w),2*(y*z+x*w),1-2*(x*x+y*y)))
def transformed_nodes(model):
    # Matches native BlockyModelBoundsParser: the rotated shape offset becomes
    # the origin of children, rather than an unrelated drawing-only offset.
    def walk(values,parent_rotation,parent_position):
        for node in values:
            shape=node.get('shape',{})
            if not shape.get('visible',True):continue
            rotation=multiply(parent_rotation,qmatrix(node.get('orientation',dict(x=0,y=0,z=0,w=1))))
            center=add(add(parent_position,rotate(parent_rotation,vec(node.get('position',{})))),rotate(rotation,vec(shape.get('offset',{}))))
            yield node,rotation,center
            yield from walk(node.get('children',[]),rotation,center)
    return walk(model['nodes'],IDENTITY,(0,0,0))
def model_bounds(model):
    points=[]
    for node,rotation,center in transformed_nodes(model):
        shape=node.get('shape',{});kind=shape.get('type');size=shape.get('settings',{}).get('size',{})
        if kind not in ('box','quad') or not shape.get('textureLayout'):continue
        stretch=vec(shape.get('stretch',{}),1)
        if kind=='box':
            dims=tuple(s*t for s,t in zip(vec(size),stretch))
            local=[tuple(sign[i]*dims[i]/2 for i in range(3)) for sign in itertools.product((-1,1),repeat=3)]
        else:
            normal=shape.get('settings',{}).get('normal','+Z')[-1]
            local=[(x*size['x']*stretch[0]/2,y*size['y']*stretch[1]/2,0) for x,y in itertools.product((-1,1),repeat=2)]
            if normal=='X':local=[(p[2],p[1],p[0]) for p in local]
            elif normal=='Y':local=[(p[0],p[2],p[1]) for p in local]
        points.extend(add(rotate(rotation,p),center) for p in local)
    require(bool(points),'Model has no visible geometry')
    return {side:{axis:operation(p[i] for p in points)/32+(.5 if i!=1 else 0) for i,axis in enumerate('XYZ')}
            for side,operation in (('Min',min),('Max',max))}
def nodes(model):
    def walk(values):
        for n in values:
            yield n
            yield from walk(n.get('children',[]))
    return walk(model['nodes'])
def uv_bounds(shape,face,layout):
    """Native UV origin is the signed, rotated face corner, not its minimum.

    Hytale Blockbench export/import applies mirrors before cardinal rotation.
    Stretch changes geometry only; quad UV dimensions remain size.x/size.y for
    every normal axis. Return actual boundaries without a sampling gutter.
    """
    size=shape['settings']['size']
    if shape['type']=='quad' or face in ('front','back'):w,h=size['x'],size['y']
    elif face in ('left','right'):w,h=size['z'],size['y']
    elif face in ('top','bottom'):w,h=size['x'],size['z']
    else:raise AssertionError('Unknown box UV face: '+face)
    offset=layout['offset'];x,y=offset['x'],offset['y'];angle=layout.get('angle',0)
    require(all(isinstance(v,(int,float)) and math.isfinite(v) for v in (w,h,x,y,angle)),'Nonfinite UV rectangle')
    require(w>=0 and h>=0 and angle%90==0,'Native UVs need nonnegative face sizes and quarter turns')
    mirror=layout.get('mirror',{});corners=[]
    for u,v in ((0,0),(w,0),(w,h),(0,h)):
        if mirror.get('x'):u=-u
        if mirror.get('y'):v=-v
        for _ in range(int(angle)%360//90):u,v=-v,u
        corners.append((x+u,y+v))
    return min(p[0] for p in corners),min(p[1] for p in corners),max(p[0] for p in corners),max(p[1] for p in corners)

def check_model_uvs(model,width,height,label='<model>'):
    count=0
    for node in nodes(model):
        shape=node.get('shape',{});kind=shape.get('type')
        if kind not in ('box','quad'):continue
        for face,layout in shape.get('textureLayout',{}).items():
            left,top,right,bottom=uv_bounds(shape,face,layout)
            require(left>=-.001 and top>=-.001 and right<=width+.001 and bottom<=height+.001,f'UV out of atlas: {label} {node["name"]} {face} bounds ({left},{top},{right},{bottom}) vs {width}x{height}')
            count+=1
    return count

def uvcheck(path,texture):
    return check_model_uvs(read(COMMON/path),*png_size(COMMON/texture),str(path))
def animated(base,clip):
    model=copy.deepcopy(base)
    for node in nodes(model):
        tracks=clip['nodeAnimations'].get(node['name'])
        if not tracks:continue
        if tracks['orientation']:node['orientation']=tracks['orientation'][-1]['delta']
        if tracks['position']:
            for axis in 'xyz':node['position'][axis]+=tracks['position'][-1]['delta'][axis]
    return model
def bounds_equal(a,b):return all(abs(a[side][axis]-b[side][axis])<1e-7 for side in ('Min','Max') for axis in 'XYZ')
def validate(preservation=False):
    catalog=read(ROOT/'tools/assets/architecture-set.json');checks=0
    if preservation:
        for path,sha in catalog['preservedExistingArt'].items():require(digest(COMMON/path)==sha,'Existing authored art changed: '+path)
    for path,proof in catalog['nativeModelCopies'].items():
        if preservation:require(digest(COMMON/path)==proof['sha256'],'Roof topology/UV changed from native shape: '+path)
        checks+=uvcheck(path,ATLAS)
    for entry in catalog['items']:
        item=read(ITEMS/(entry['id']+'.json'));block=item['BlockType']
        require(item['Recipe']['OutputQuantity'] in (2,4) and 'Output' not in item['Recipe'],'Native implicit recipe primary output')
        require(block['VariantRotation']=='UpDownNESW','Roof inverted placement missing')
        if not entry['id'].endswith('_Flat'):
            rule=block['ConnectedBlockRuleSet'];require(rule['Type']=='Roof','Native roof joining required')
            require(all(x in rule['Regular'] for x in (*STATES,'Straight')),'Roof corner mapping incomplete')
        if entry['id']=='SM_Resonite_Roof':require('Topper' in block['ConnectedBlockRuleSet'],'Ridge topper missing')
        if entry['id'].endswith('_Shallow'):require(block['ConnectedBlockRuleSet']['Width']==2,'Wide corner rule changed')
    stairs=read(ITEMS/'SM_Resonite_Tile_Stairs.json')['BlockType'];require(stairs['ConnectedBlockRuleSet']['Type']=='Stair','Native stair joining required')
    for state in STATES:
        definition=stairs['State']['Definitions'][state]
        checks+=uvcheck(definition['CustomModel'],definition['CustomModelTexture'][0]['Texture'])
        model=read(COMMON/definition['CustomModel']);bounds=model_bounds(model)
        require(all(-1e-8<=bounds['Min'][a]<=bounds['Max'][a]<=1+(1/64 if a=='Y' else 1e-8) for a in 'XYZ'),'Corner mesh extends beyond the authored stair ornament allowance')
        # The solid boxes must exactly match native inner/outer quarter geometry.
        actual=[]
        for node in model['nodes']:
            if 'inlay' in node['name']:continue
            actual.append(model_bounds({'nodes':[node]}))
        expected=read(NATIVE/f'Server/Item/Block/Hitboxes/Structure/Stairs/Stairs_{state}.json')['Boxes']
        require(len(actual)==len(expected) and all(bounds_equal(a,b) for a,b in zip(actual,expected)),'Stair mesh no longer matches native collision '+state)
    for name in ('resonite_door','resonite_trapdoor'):
        base=read(BLOCKS/(name+'.blockymodel'));texture=BLOCKS/(name+'.png')
        for direction in ('In','Out') if name=='resonite_door' else ('Out',):
            clip=read(BLOCKS/f'Animations/{name}_OpenDoor{direction}.blockyanim')
            pose=animated(base,clip);box=model_bounds(pose)
            expected=read(HITBOXES/f'SM_{"Resonite_Door" if name=="resonite_door" else "Resonite_Trapdoor"}_Open_{direction}.json')['Boxes'][0]
            require(bounds_equal(box,expected),'Open animation and actual collider differ')
            require(box['Min']['X']>=-1e-8 and box['Max']['X']<=1+1e-8,'Open door reserves a filler in neighboring side wall')
            close=read(BLOCKS/f'Animations/{name}_CloseDoor{direction}.blockyanim')
            require(bounds_equal(model_bounds(animated(base,close)),model_bounds(base)),'Closing does not return to authored pose')
            for tracks in clip['nodeAnimations'].values():
                for values in tracks.values():
                    for key in values:require(type(key['time']) is int,'Client animation time requires Int32')
    hatch=read(BLOCKS/'resonite_trapdoor.blockymodel');hinge=hatch['nodes'][0]
    require(hinge['position']['x']==-13 and hinge['position']['z']==0,'Hatch hinge is not opposite the +X latch')
    pose=animated(hatch,read(BLOCKS/'Animations/resonite_trapdoor_OpenDoorOut.blockyanim'))
    transforms={n['name']:(rotation,center) for n,rotation,center in transformed_nodes(pose)}
    latch=next(v for k,v in transforms.items() if k.startswith('latch'))[1]
    require(latch[1]>24,'Hatch handle must rise toward the top when opened')
    print(f'ARCHITECTURE_VALIDATION_PASSED: 4 roof items, 17 roof geometries, {checks} UV faces, four exact corner colliders, recipe quantities, door wall clearance and animated hatch hinge. Historical preservation audit: {preservation}.')

def preview():
    from PIL import Image, ImageDraw
    import render_current_icons as renderer
    catalog=read(ROOT/'tools/assets/architecture-set.json');panels=[]
    for entry in catalog['items']:
        item=read(ITEMS/(entry['id']+'.json'));block=item['BlockType']
        panels.append((entry['name'],block['CustomModel'],ATLAS))
        if entry['id']=='SM_Resonite_Roof':
            for state,definition in block['State']['Definitions'].items():panels.append((state,definition['CustomModel'],ATLAS))
    for state in STATES:panels.append(('Stair '+state,'Blocks/StrangeMatter/resonite_tile_stairs_'+state.lower()+'.blockymodel','Blocks/StrangeMatter/resonite_tile_stairs.png'))
    for name,label in [('resonite_door','Door closed'),('resonite_door_open_in','Door inward'),('resonite_door_open_out','Door outward'),('resonite_trapdoor','Hatch closed'),('resonite_trapdoor_open_out','Hatch open')]:
        tex='resonite_trapdoor' if 'trapdoor' in name else 'resonite_door';panels.append((label,f'Blocks/StrangeMatter/{name}.blockymodel',f'Blocks/StrangeMatter/{tex}.png'))
    columns=5;cell=240;rows=math.ceil(len(panels)/columns)
    sheet=Image.new('RGBA',(columns*cell,rows*(cell+30)),(15,22,36,255));draw=ImageDraw.Draw(sheet)
    for index,(label,model,texture) in enumerate(panels):
        image=renderer.render(renderer.model_faces(read(COMMON/model),Image.open(COMMON/texture)),cell-12)
        x=(index%columns)*cell;y=(index//columns)*(cell+30)
        sheet.alpha_composite(image,(x+6,y));draw.text((x+8,y+cell),label.replace('_',' '),fill=(219,231,244))
    target=ROOT/'docs/art/architecture-set.png';target.parent.mkdir(parents=True,exist_ok=True);sheet.save(target);print(target)
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--preview',action='store_true');parser.add_argument('--preservation',action='store_true');args=parser.parse_args()
    validate(args.preservation)
    if args.preview:preview()
