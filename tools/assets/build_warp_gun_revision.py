"""Author only the redesigned Warp Gun. Default usage never writes production art.

--replace-art snapshots current targets and replaces this gun only. --preview
renders current resources; --icon renders only the current gun's 64 pixel icon.
Pillow/numpy use the same native UV renderer as the rest of the project.
"""
from pathlib import Path
import argparse, datetime, hashlib, json, math, zipfile
import numpy as np
from PIL import Image, ImageDraw
import build_assets as art
import render_current_icons as render

ROOT=Path(__file__).resolve().parents[2]; RES=ROOT/'src/main/resources'; COMMON=RES/'Common'
FOLDER=COMMON/'Items/StrangeMatter'; MODEL=FOLDER/'warp_gun.blockymodel'; TEXTURE=FOLDER/'warp_gun.png'
ICON=COMMON/'Icons/ItemsGenerated/SM_Warp_Gun.png'; ITEM=RES/'Server/Item/Items/StrangeMatter/SM_Warp_Gun.json'
VIEWS=ROOT/'tools/assets/icon_views.json'; CATALOG=ROOT/'tools/assets/catalog.json'
REPORT=ROOT/'build/warp-gun-redesign'; PREVIEW=REPORT/'warp-gun-preview.png'

def read(path):return json.loads(path.read_text(encoding='utf-8-sig'))
def write(path,data):
    path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8')

def model_art():
    m=art.Model('warp_gun')
    # Keep the original grip centre/angle and +Z emitter direction. The receiver
    # is slim and slightly offset; its open forks cannot read as a vacuum nozzle.
    m.box('pistol_grip',(0,4,-3),(4.8,13,6),'dark',(-16,0,0),faces={'left':'rubber','right':'rubber'})
    # Retain the authored native quaternion bytes, including its export rounding.
    m.nodes[-1]['orientation']={'x':-.13917,'y':0,'z':0,'w':.99027}
    m.box('grip_heel',(0,-2.25,-1.1),(5.5,2,5.3),'edge',(-16,0,0))
    m.box('grip_spine',(0,4.8,-6.2),(2.2,10,.65),'navy',(-16,0,0))
    m.box('receiver',(1.2,14.7,-4.7),(9,7.4,16),'navy',faces={'left':'vent','right':'navy'})
    m.box('receiver_top_bevel',(1.2,19,-5.3),(8,2,14),'edge')
    m.box('rear_lock',(1.2,15,-13.3),(7,5.4,1.4),'dark')
    m.box('rear_lock_light',(1.2,16.5,-14.04),(4,.8,.2),'purple',glow=True)
    m.box('rear_copper_key',(1.2,18.5,-12.7),(3,1.3,2.2),'copper')
    # The offset battery has two narrow channel indicators, not a big cylinder.
    m.box('phase_cartridge',(6.4,15,-6.3),(2.8,6.2,8.7),'dark')
    m.box('cartridge_cyan',(7.87,16.5,-6.3),(.25,1.2,6.5),'cyan',glow=True)
    m.box('cartridge_purple',(7.87,13.7,-6.3),(.25,1.2,6.5),'purple',glow=True)
    for z in (-10.25,-2.3):m.box('cartridge_latch_'+str(z),(6.4,15,z),(3.4,7,.7),'edge')
    # A small top readout and a guarded trigger add useful detail from behind.
    m.box('phase_readout_bezel',(-.2,20.25,-4.8),(5.8,.8,6.8),'dark')
    m.box('phase_readout',(-.2,20.71,-4.8),(4.4,.15,5.4),'display',faces={'top':'display'})
    m.box('trigger',(0,8.8,1.1),(1.3,4.7,1.3),'copper',(-15,0,0))
    m.box('trigger_guard',(0,6.5,3.9),(1.6,1.4,6.2),'edge')
    m.box('trigger_guard_riser',(0,9.1,6.5),(1.6,5.2,1.4),'edge')
    # Open emitter: two plated rails enclose a real air gap for a floating core.
    m.box('fork_yoke',(0,15.5,3.1),(17,5.4,4.8),'navy')
    m.box('yoke_focus',(0,15.5,5.6),(6,3.5,.4),'dark')
    m.box('yoke_signal',(0,15.5,5.88),(3.5,1,.2),'cyan',glow=True)
    for side,channel in ((-1,'cyan'),(1,'purple')):
        m.box('prong_'+channel,(side*7.5,16,14.2),(3.2,5.2,19),'navy')
        m.box('prong_ridge_'+channel,(side*7.5,18.83,14.2),(2.2,.65,17.4),'edge')
        m.box('prong_underplate_'+channel,(side*7.5,13.03,14.2),(2.2,.55,16),'dark')
        m.box('channel_rail_'+channel,(side*5.77,16,15),(.3,2.4,15),channel,glow=True)
        m.box('prong_tip_'+channel,(side*6.8,16,25.7),(3.1,4.5,5.5),'edge',(0,-side*13,0))
        m.box('tip_lens_'+channel,(side*6.2,16,28.42),(2.1,2.9,.55),channel,(0,-side*13,0),glow=True)
        m.box('prong_copper_binding_'+channel,(side*7.5,16,7.2),(3.8,5.8,1),'copper')
        m.box('prong_side_plate_'+channel,(side*9.23,16,14),(.25,3.5,9),'dark',faces={'left':'vent','right':'vent'})
        for z in (10,18):m.box('prong_rivet_'+channel+'_'+str(z),(side*9.45,16,z),(.35,.8,.8),'steel')
    # Thin separated ring segments float between the rails; no closed barrel or
    # opaque muzzle disk. Tangential pieces deliberately leave tiny clean gaps.
    for index in range(8):
        angle=index*math.tau/8
        material='cyan' if index<4 else 'purple'
        m.box('portal_ring_'+str(index),(5.0*math.sin(angle),16+5.0*math.cos(angle),19.4),
              (3.6,.85,.85),material,(0,0,-math.degrees(angle)),glow=True)
    m.box('suspended_phase_core',(0,16,20.2),(3.4,3.4,3.4),'purple',(20,45,45),glow=True)
    m.box('core_front_facet',(0,16,22.46),(1.6,1.6,.3),'cyan',(0,0,45),glow=True)
    return m

def paint(width,height,material):
    base='dark' if material in ('display','rubber') else material
    tile=art.paint(width,height,base,f'warp_revision/{material}/{width}/{height}')
    d=ImageDraw.Draw(tile)
    if material=='rubber':
        for y in range(2,height-1,3):d.line((1,y,max(1,width-2),y),fill=(50,65,81,255))
    elif material=='display' and width>=3 and height>=3:
        d.line((1,1,width-2,1),fill=(71,231,235,255))
        d.line((1,height-2,max(1,width//2),height-2),fill=(169,113,242,255))
        if height>=5:d.point((width-2,height//2),fill=(211,242,241,255))
    return tile

def packed(model):
    """Deduplicate matching painted face tiles and pack explicit positive native UVs."""
    tiles={}; assignments=[]
    for node,(material,overrides) in zip(model.nodes,model.materials):
        size=node['shape']['settings']['size'];x,y,z=(size[k] for k in 'xyz')
        for face,width,height in [('front',x,y),('back',x,y),('left',z,y),('right',z,y),('top',x,z),('bottom',x,z)]:
            key=(overrides.get(face,material),width,height)
            if key not in tiles:tiles[key]=paint(width,height,key[0])
            assignments.append((node,face,key))
    candidates=[]
    ordered=sorted(tiles,key=lambda key:(-key[2],-key[1],key[0]))
    for width in (64,128,256):
        cursor_x=cursor_y=row=0; positions={}
        for key in ordered:
            w,h=key[1]+2,key[2]+2
            if w>width:break
            if cursor_x+w>width:cursor_x=0;cursor_y+=row;row=0
            positions[key]=(cursor_x+1,cursor_y+1);cursor_x+=w;row=max(row,h)
        else:
            height=max(32,2**math.ceil(math.log2(max(1,cursor_y+row))))
            if height<=width:candidates.append((width*height,width,height,positions))
    if not candidates:raise ValueError('Gun atlas no longer fits the compact 256 pixel width budget')
    _,width,height,positions=min(candidates,key=lambda c:(c[0],c[1]))
    atlas=Image.new('RGBA',(width,height))
    for key,tile in tiles.items():
        x,y=positions[key];w,h=tile.size;atlas.paste(tile,(x,y))
        atlas.paste(tile.crop((0,0,w,1)),(x,y-1));atlas.paste(tile.crop((0,h-1,w,h)),(x,y+h))
        atlas.paste(tile.crop((0,0,1,h)),(x-1,y));atlas.paste(tile.crop((w-1,0,w,h)),(x+w,y))
        for dx,dy,tx,ty in ((-1,-1,0,0),(w,-1,w-1,0),(-1,h,0,h-1),(w,h,w-1,h-1)):
            atlas.putpixel((x+dx,y+dy),tile.getpixel((tx,ty)))
    for node,face,key in assignments:
        x,y=positions[key]
        node['shape']['textureLayout'][face]={'offset':{'x':x,'y':y},'mirror':{'x':False,'y':False},'angle':0}
    return {'nodes':model.nodes,'format':'prop','lod':'auto'},atlas,len(tiles),len(assignments)

def save_generated(model):
    document,atlas,tile_count,face_count=packed(model)
    write(MODEL,document);atlas.save(TEXTURE,optimize=True)
    faces=render.model_faces(document,atlas);vertices=np.concatenate([face[0] for face in faces])
    return {'sourceId':'warp_gun','id':'SM_Warp_Gun','block':False,'model':MODEL.relative_to(COMMON).as_posix(),
            'texture':TEXTURE.relative_to(COMMON).as_posix(),'icon':ICON.relative_to(COMMON).as_posix(),
            'nodes':len(model.nodes),'boundsModelUnits':{'min':vertices.min(0).round(3).tolist(),'max':vertices.max(0).round(3).tolist()},
            'atlas':list(atlas.size),'uniqueFaceTiles':tile_count,'texturedFaces':face_count}

def snapshot():
    folder=ROOT/'build/art-preservation'/('warp-gun-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ'))
    folder.mkdir(parents=True)
    paths=[MODEL,TEXTURE,ICON,ITEM,VIEWS,CATALOG,FOLDER/'echo_vacuum.blockymodel',FOLDER/'echo_vacuum.png',COMMON/'Icons/ItemsGenerated/SM_Echo_Vacuum.png']
    with zipfile.ZipFile(folder/'Targets.zip','w',zipfile.ZIP_DEFLATED) as archive:
        for path in paths:archive.write(path,path.relative_to(ROOT).as_posix())
    before={path.relative_to(COMMON).as_posix():hashlib.sha256(path.read_bytes()).hexdigest() for path in COMMON.rglob('*') if path.is_file()}
    write(folder/'Common-before.json',before)
    return folder,before

def icon():
    views=read(VIEWS).get('SM_Warp_Gun',{})
    picture=render.render(render.item_faces(read(ITEM)),size=192,yaw=views.get('yaw',35),pitch=views.get('pitch',23))
    picture.resize((64,64),Image.Resampling.LANCZOS).save(ICON,optimize=True)

def preview():
    item=read(ITEM);current=render.item_faces(item)
    entries=[('Warp Gun front',current),('Warp Gun reverse',current),('Open portal emitter',current),
             ('Echo Vacuum unchanged',render.item_faces(read(RES/'Server/Item/Items/StrangeMatter/SM_Echo_Vacuum.json')))]
    render.contact(entries,PREVIEW,cols=2,cell=420,views={'Warp Gun reverse':{'yaw':215},'Open portal emitter':{'yaw':0,'pitch':12}})
    print(PREVIEW)
    history=REPORT/'revision.json'
    if history.is_file():
        prior=ROOT/read(history)['snapshot']/'Targets.zip'
        if prior.is_file():
            import io
            with zipfile.ZipFile(prior) as archive:
                old_model=json.loads(archive.read(MODEL.relative_to(ROOT).as_posix()))
                old_texture=Image.open(io.BytesIO(archive.read(TEXTURE.relative_to(ROOT).as_posix()))).convert('RGBA')
            render.contact([('Previous Warp Gun',render.model_faces(old_model,old_texture)),('Redesigned Warp Gun',current)],
                           REPORT/'warp-gun-before-after.png',cols=2,cell=420)

def apply():
    folder,before=snapshot();old_dimensions=Image.open(TEXTURE).size;old_bytes=TEXTURE.stat().st_size
    entry=save_generated(model_art())
    views=read(VIEWS);views['SM_Warp_Gun']={'yaw':35,'pitch':23};write(VIEWS,views)
    catalog=read(CATALOG)
    entries=catalog['entries'] if isinstance(catalog,dict) and 'entries' in catalog else catalog
    if isinstance(entries,dict):entries=entries['items']
    for index,value in enumerate(entries):
        if value.get('id')=='SM_Warp_Gun':entries[index]={**value,**entry};break
    else:raise ValueError('Warp Gun catalog entry is missing')
    write(CATALOG,catalog);icon()
    allowed={path.relative_to(COMMON).as_posix() for path in (MODEL,TEXTURE,ICON)}
    changed=[name for name,digest in before.items() if hashlib.sha256((COMMON/name).read_bytes()).hexdigest()!=digest]
    assert set(changed)<=allowed,changed
    with zipfile.ZipFile(folder/'Targets.zip') as archive:
        assert archive.read(ITEM.relative_to(ROOT).as_posix())==ITEM.read_bytes(),'Gun mechanics changed'
    report={**entry,'snapshot':folder.relative_to(ROOT).as_posix(),'oldAtlas':list(old_dimensions),'oldTextureBytes':old_bytes,
            'newTextureBytes':TEXTURE.stat().st_size,'changedCommonFiles':changed,'unchangedOtherCommonFiles':len(before)-3,
            'heldAimAxis':'+Z','gripPivot':[0,4,-3],'gripPitchDegrees':-16,'echoVacuumPreserved':True}
    write(REPORT/'revision.json',report);print(json.dumps(report,indent=2));preview()

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--replace-art',action='store_true');parser.add_argument('--preview',action='store_true');parser.add_argument('--icon',action='store_true')
    args=parser.parse_args()
    if args.replace_art:apply()
    elif args.icon:icon()
    else:preview()
