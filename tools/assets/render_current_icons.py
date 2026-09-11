"""Render icons from current resource files only; never rewrite models or textures.

Transforms follow native BlockyModelBoundsParser: rotated shape offsets also become
the origin of child nodes. Face UV mirrors and quarter-turns are applied explicitly.
"""
from pathlib import Path
import argparse, hashlib, json, math
import numpy as np
from PIL import Image, ImageDraw, ImageFont
import build_assets as art

ROOT=art.ROOT; RES=ROOT/'src/main/resources'; COMMON=RES/'Common'
NATIVE=ROOT.parent/'HytaleSourceCode/hytale-shared-source/HytaleAssets/Common'

def read(path):return json.loads(path.read_text(encoding='utf-8-sig'))
def resolve(path):
    local=COMMON/path
    return local if local.exists() else NATIVE/path
def vec(value,default=0):return np.array([value.get(k,default) for k in 'xyz'],float)

def uv_corners(layout,width,height):
    # Native blockymodel offsets are signed corner origins, not the minimum
    # of a normalized rectangle. Hypixel's Blockbench codec rotates signed
    # spans around this origin (src/blockymodel.ts, parse UV section).
    uv=np.array([[0,1],[1,1],[1,0],[0,0]],float)*[width,height]
    uv+=(np.array([width,height])/2-uv)*.00001
    mirror=layout.get('mirror',{})
    if mirror.get('x'):uv[:,0]*=-1
    if mirror.get('y'):uv[:,1]*=-1
    angle=int(round(layout.get('angle',0)))%360
    if angle%90:raise ValueError('Unsupported non-cardinal native UV angle '+str(angle))
    for _ in range(angle//90):uv=np.column_stack((-uv[:,1],uv[:,0]))
    return uv+np.array([layout['offset']['x'],layout['offset']['y']])

def model_faces(model,texture,initial_rotation=None,initial_position=None):
    faces=[]; atlas=np.asarray(texture.convert('RGBA'))
    def walk(nodes,parent_r,parent_p):
        for n in nodes:
            shape=n.get('shape',{})
            if not shape.get('visible',True):continue
            rotation=parent_r@art.qmatrix(n.get('orientation',art.quat()))
            center=parent_p+parent_r@vec(n.get('position',{}))+rotation@vec(shape.get('offset',{}))
            kind=shape.get('type');settings=shape.get('settings',{});s=settings.get('size',{})
            if kind in ('box','quad'):
                stretch=vec(shape.get('stretch',{}),1)
                if kind=='box':
                    dims=vec(s)*stretch
                    for face,vertices in art.FACE_VERTS.items():
                        if face not in shape.get('textureLayout',{}):continue
                        width,height=(s['x'],s['z']) if face in ('top','bottom') else (s['z'],s['y']) if face in ('left','right') else (s['x'],s['y'])
                        points=np.array(vertices)*dims/2@rotation.T+center
                        faces.append((points,uv_corners(shape['textureLayout'][face],width,height),atlas,shape.get('shadingMode')=='fullbright',shape.get('doubleSided',False)))
                else:
                    normal=settings.get('normal','+Z');width=s.get('x',0);height=s.get('y',0)
                    points=np.array([[-1,-1,0],[1,-1,0],[1,1,0],[-1,1,0]],float)*np.array([width*stretch[0],height*stretch[1],1])/2
                    if normal[-1]=='X':points=points[:,[2,1,0]]
                    elif normal[-1]=='Y':points=points[:,[0,2,1]]
                    if normal.startswith('-'):points=points[::-1]
                    layout=shape.get('textureLayout',{}).get('front')
                    if layout:faces.append((points@rotation.T+center,uv_corners(layout,width,height),atlas,shape.get('shadingMode')=='fullbright',shape.get('doubleSided',False)))
            walk(n.get('children',[]),rotation,center)
    walk(model['nodes'],np.eye(3) if initial_rotation is None else initial_rotation,np.zeros(3) if initial_position is None else initial_position)
    return faces

def cube_faces(block):
    tex=block['Textures'][0];faces=[]
    mapping={'top':'Up','bottom':'Down','front':'North','back':'South','right':'East','left':'West'}
    for face,vertices in art.FACE_VERTS.items():
        path=tex.get(mapping[face],tex.get('Sides',tex.get('All')))
        texture=Image.open(resolve(path)).convert('RGBA');width,height=texture.size
        points=np.array(vertices,dtype=float)*16+np.array([0,16,0])
        faces.append((points,uv_corners({'offset':{'x':0,'y':0}},width,height),np.asarray(texture),False,False))
    return faces

def item_faces(item):
    if item.get('Model'):
        return model_faces(read(resolve(item['Model'])),Image.open(resolve(item['Texture'])))
    block=item['BlockType'];faces=[]
    if block['DrawType'] in ('Cube','CubeWithModel'):faces+=cube_faces(block)
    if block.get('CustomModel') and block['DrawType'] in ('Model','CubeWithModel'):
        faces+=model_faces(read(resolve(block['CustomModel'])),Image.open(resolve(block['CustomModelTexture'][0]['Texture'])))
    return faces

def bone_transforms(model):
    result={}
    def walk(nodes,parent_r,parent_p):
        for node in nodes:
            rotation=parent_r@art.qmatrix(node.get('orientation',art.quat()))
            center=parent_p+parent_r@vec(node.get('position',{}))+rotation@vec(node.get('shape',{}).get('offset',{}))
            result[node['name']]=(rotation,center)
            walk(node.get('children',[]),rotation,center)
    walk(model['nodes'],np.eye(3),np.zeros(3))
    return result

def attachment_faces(attachment,texture,base):
    """Fit empty named attachment pieces to their matching native bone centers."""
    bones=bone_transforms(base);faces=[]
    for piece in attachment['nodes']:
        if piece.get('shape',{}).get('settings',{}).get('isPiece') and piece['name'] in bones:
            if piece['shape']['type']!='none':raise ValueError('Attachment fitting expects an empty named root piece')
            rotation,center=bones[piece['name']]
            faces+=model_faces({'nodes':piece.get('children',[])},texture,rotation,center)
        else:faces+=model_faces({'nodes':[piece]},texture)
    return faces

def render(faces,size=256,yaw=35,pitch=23):
    a,b=math.radians(yaw),math.radians(pitch)
    cam=np.array([math.sin(a)*math.cos(b),math.sin(b),math.cos(a)*math.cos(b)])
    right=np.array([math.cos(a),0,-math.sin(a)]);up=np.cross(cam,right);matrix=np.array([right,-up,cam])
    projected=np.concatenate([f[0]@matrix.T for f in faces]);low=projected[:,:2].min(0);high=projected[:,:2].max(0)
    scale=(size-16)/max(high-low);middle=(low+high)/2
    output=np.zeros((size,size,4),np.uint8);depth=np.full((size,size),-1e9)
    light=np.array([-.3,.85,.42]);light/=np.linalg.norm(light)
    # Far faces first provide correct translucent layering in addition to the z-buffer.
    for points,uv,atlas,glow,double in sorted(faces,key=lambda f:float((f[0]@cam).mean())):
        normal=np.cross(points[1]-points[0],points[2]-points[0]);length=np.linalg.norm(normal)
        if length<1e-9:continue
        normal/=length
        if np.dot(normal,cam)<=0 and not double:continue
        projected=points@matrix.T;projected[:,:2]=(projected[:,:2]-middle)*scale+size/2
        shade=1 if glow else .70+.30*max(0,np.dot(normal,light))
        for indices in ((0,1,2),(0,2,3)):
            v=projected[list(indices)];t=uv[list(indices)]
            x0=max(0,int(v[:,0].min()));x1=min(size-1,int(math.ceil(v[:,0].max())))
            y0=max(0,int(v[:,1].min()));y1=min(size-1,int(math.ceil(v[:,1].max())))
            if x1<x0 or y1<y0:continue
            yy,xx=np.mgrid[y0:y1+1,x0:x1+1]
            denominator=(v[1,1]-v[2,1])*(v[0,0]-v[2,0])+(v[2,0]-v[1,0])*(v[0,1]-v[2,1])
            if abs(denominator)<1e-9:continue
            w0=((v[1,1]-v[2,1])*(xx-v[2,0])+(v[2,0]-v[1,0])*(yy-v[2,1]))/denominator
            w1=((v[2,1]-v[0,1])*(xx-v[2,0])+(v[0,0]-v[2,0])*(yy-v[2,1]))/denominator;w2=1-w0-w1
            z=w0*v[0,2]+w1*v[1,2]+w2*v[2,2]
            mask=(w0>=-.001)&(w1>=-.001)&(w2>=-.001)&(z>depth[y0:y1+1,x0:x1+1])
            u=np.clip((w0*t[0,0]+w1*t[1,0]+w2*t[2,0]).astype(int),0,atlas.shape[1]-1)
            vv=np.clip((w0*t[0,1]+w1*t[1,1]+w2*t[2,1]).astype(int),0,atlas.shape[0]-1)
            color=atlas[vv,u].copy();mask&=color[:,:,3]>0;color[:,:,:3]=(color[:,:,:3]*shade).astype(np.uint8)
            target=output[y0:y1+1,x0:x1+1];alpha=color[:,:,3:4]/255.;background=target[:,:,3:4]/255.
            combined=alpha+background*(1-alpha)
            blended=np.concatenate(((color[:,:,:3]*alpha+target[:,:,:3]*background*(1-alpha))/np.maximum(combined,.0001),combined*255),axis=2).astype(np.uint8)
            target[mask]=blended[mask];depth[y0:y1+1,x0:x1+1][mask]=z[mask]
    return Image.fromarray(output)

def contact(entries,path,cols=3,cell=280,views=None):
    rows=math.ceil(len(entries)/cols);sheet=Image.new('RGB',(cols*cell,rows*(cell+40)),(17,25,42));draw=ImageDraw.Draw(sheet)
    font=ImageFont.truetype('C:/Windows/Fonts/consola.ttf',13)
    for index,(name,faces) in enumerate(entries):
        x=index%cols*cell;y=index//cols*(cell+40);pic=render(faces,cell-14,**(views or {}).get(name,{}))
        sheet.paste(pic,(x+7,y+3),pic);lines=['']
        for word in name.split():
            text=(lines[-1]+' '+word).strip()
            if draw.textlength(text,font=font)>cell-12:lines.append(word)
            else:lines[-1]=text
        for row,line in enumerate(lines):draw.text((x+cell/2,y+cell+4+row*14),line,font=font,fill=(201,224,226),anchor='mm')
    path.parent.mkdir(parents=True,exist_ok=True);sheet.save(path)

def hat_fit_preview():
    player=read(NATIVE/'Characters/Player_With_Face.blockymodel')
    body=model_faces(player,Image.open(NATIVE/'Characters/Player_Textures/Player_Greyscale.png'))
    item=read(RES/'Server/Item/Items/StrangeMatter/SM_Tinfoil_Hat.json')
    fitted=attachment_faces(read(resolve(item['Model'])),Image.open(resolve(item['Texture'])),player)
    head_center=bone_transforms(player)['Head'][1]
    minimum=np.concatenate([face[0] for face in fitted]).min(0)[1]
    assert minimum>=head_center[1]+14.35,'Hat intersects the native scalp plane'
    sheet=Image.new('RGBA',(1000,600),(17,25,42,255));draw=ImageDraw.Draw(sheet)
    font=ImageFont.truetype('C:/Windows/Fonts/consola.ttf',15)
    for column,yaw in enumerate((0,45)):
        sheet.alpha_composite(render(body+fitted,500,yaw=yaw,pitch=8),(column*500,30))
        draw.text((column*500+250,560),'Native player Head attachment',font=font,anchor='mm',fill=(210,232,235))
    sheet.convert('RGB').save(ROOT/'docs/art/tinfoil-player-fitting.png')

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--preview',action='store_true')
    parser.add_argument('--items',nargs='+',help='Render only these exact native item IDs; leave all other icons unchanged.')
    args=parser.parse_args()
    hashes={str(p.relative_to(COMMON)):hashlib.sha256(p.read_bytes()).hexdigest() for p in COMMON.rglob('*') if p.is_file() and p.suffix in ('.blockymodel','.blockyanim','.png') and not str(p.relative_to(COMMON)).startswith('Icons')}
    entries=[]
    views_path=ROOT/'tools/assets/icon_views.json'
    views=read(views_path) if views_path.exists() else {}
    paths=sorted((RES/'Server/Item/Items/StrangeMatter').glob('*.json'))
    if args.items:
        unknown=set(args.items)-{p.stem for p in paths}
        if unknown:parser.error('Unknown item IDs: '+', '.join(sorted(unknown)))
        paths=[p for p in paths if p.stem in args.items]
    for path in paths:
        item=read(path);faces=item_faces(item);icon=render(faces,**views.get(path.stem,{})).resize((64,64),Image.Resampling.LANCZOS)
        icon.save(COMMON/item['Icon']);entries.append((path.stem.removeprefix('SM_').replace('_',' '),faces))
    for relative,expected in hashes.items():
        assert hashlib.sha256((COMMON/relative).read_bytes()).hexdigest()==expected,'Renderer altered source '+relative
    report={'icons':len(entries),'size':[64,64],'sourceFilesVerifiedUnchanged':len(hashes),'status':'PASS'}
    if args.items:report['itemIds']=[p.stem for p in paths]
    report_name='selected-icon-validation.json' if args.items else 'current-icon-validation.json'
    (ROOT/'tools/assets'/report_name).write_text(json.dumps(report,indent=2)+'\n')
    if args.preview:
        preview_views={p.stem.removeprefix('SM_').replace('_',' '):views.get(p.stem,{}) for p in paths}
        contact(entries,ROOT/('docs/art/selected-item-icons.png' if args.items else 'docs/art/current-item-icons.png'),cols=min(6,len(entries)),cell=180,views=preview_views)
        if not args.items:hat_fit_preview()
    print(json.dumps(report))

if __name__=='__main__':main()
