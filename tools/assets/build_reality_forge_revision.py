"""Rebuild only the explicitly requested Reality Forge art and Working presentation.

Uses original geometry, individual face UV islands with gutters, and deterministic
painted textures. No other model, texture, language, recipe or item is regenerated.
Rendering is optional and does not replace production item icons.
"""
from pathlib import Path
import argparse, copy, hashlib, json, math, random
import numpy as np
from PIL import Image, ImageDraw
import build_assets as art
import render_current_icons as render

ROOT=Path(__file__).resolve().parents[2];RES=ROOT/'src/main/resources';COMMON=RES/'Common'
FOLDER=COMMON/'Blocks/StrangeMatter';NAME='reality_forge';ITEM=RES/'Server/Item/Items/StrangeMatter/SM_Reality_Forge.json'
PAINT=art.paint
def read(path):return json.loads(path.read_text(encoding='utf-8-sig'))
def write(path,data):path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8')

def paint(w,h,mat,seed,hot=False):
    rng=random.Random(seed)
    if mat=='firebrick':
        image=Image.new('RGBA',(w,h));pixels=image.load()
        for y in range(h):
            for x in range(w):
                mortar=y%7==0 or (x+(4 if y//7%2 else 0))%11==0
                base=(31,35,46) if mortar else (83,80,91)
                light=8*math.sin(y*.23)+rng.choice((-3,0,0,2,4));pixels[x,y]=tuple(max(0,int(v+light)) for v in base)+(255,)
        draw=ImageDraw.Draw(image)
        for y in range(1,h,7):draw.line((1,y,w-2,y),fill=(114,104,108,255))
        return image
    if mat in ('molten','embers'):
        image=Image.new('RGBA',(w,h));pixels=image.load()
        for y in range(h):
            for x in range(w):
                wave=(math.sin(x*.75+y*.33)+math.cos(y*.77-x*.28))*.25+.5
                if mat=='molten':
                    a,b=((38,99,123),(126,223,228)) if hot else ((24,54,83),(52,151,169))
                else:a,b=((104,35,22),(255,189,91)) if hot else ((32,25,34),(108,54,39))
                pixels[x,y]=tuple(round(a[i]*(1-wave)+b[i]*wave) for i in range(3))+(255,)
        draw=ImageDraw.Draw(image)
        if mat=='molten' and w>=5 and h>=5:
            draw.arc((1,1,w-2,h-2),20,290,fill=(226,252,241,255) if hot else (117,186,194,255),width=1)
            draw.arc((w*.23,h*.23,w*.75,h*.75),170,480,fill=(171,108,236,255),width=1)
        return image
    if mat=='anvil_face':
        image=PAINT(w,h,'steel',seed);draw=ImageDraw.Draw(image)
        for i in range(max(2,w*h//28)):
            x=rng.randrange(w);y=rng.randrange(h);draw.line((x,y,min(w-1,x+3),max(0,y-1)),fill=(154,174,180,255))
        if w>9 and h>5:draw.rectangle((2,2,4,4),fill=(15,25,40,255))
        return image
    return PAINT(w,h,mat,seed)

def build():
    m=art.Model(NAME,True)
    # Massive low hearth base, rather than a full-height cabinet.
    for x in (-12,12):
        for z in (-11,11):m.box('cast_foot_'+str(x)+'_'+str(z),(x,1.5,z),(6,3,6),'dark')
    m.box('forge_foundation',(0,4,0),(30,5,28),'navy')
    m.box('foundation_bevel',(0,7,0),(31,2,29),'edge')
    m.box('ash_drawer',(0,5.5,14.55),(16,3,.8),'dark',faces={'front':'embers'},glow=True)
    for x in (-6,-3,0,3,6):m.box('ash_grate_'+str(x),(x,5.5,15.04),(.65,3.1,.3),'dark')
    m.box('ash_drawer_handle',(0,5.5,15.15),(6,1,.6),'copper')
    # A truly open, deep fire chamber lined with individual painted fire bricks.
    m.box('hearth_floor',(0,10,-1),(25,4,24),'firebrick')
    m.box('hearth_back',(0,19,-11),(24,18,4),'firebrick')
    for side in (-1,1):
        x=side*11.6
        m.box('hearth_pier_'+str(side),(x,18,-1),(4,18,22),'navy',faces={'front':'firebrick','back':'navy'})
        m.box('pier_inner_lining_'+str(side),(side*9.35,18,-2),(1,15,18),'firebrick')
        m.box('arch_shoulder_'+str(side),(side*8.3,27,0),(9,5,21),'navy',(0,0,-side*24),faces={'front':'firebrick'})
        m.box('arch_copper_binding_'+str(side),(side*8.3,27,10.8),(9,1.2,.7),'copper',(0,0,-side*24))
        for y in (13,18,23):
            m.box('induction_winding_'+str(side)+'_'+str(y),(side*11.65,y,-2),(5,1.4,21),'copper')
            m.box('conductor_'+str(side)+'_'+str(y),(side*14.23,y,-2),(.45,.6,14),'cyan',glow=True)
    m.box('arch_keystone',(0,29,1),(11,4,19.3),'edge',faces={'front':'firebrick'})
    m.box('arch_keystone_badge',(0,29,10.85),(3,2,.6),'purple',glow=True)
    # Coals and a suspended-looking crucible inside the actual opening.
    m.box('ember_bed',(0,12.15,-2),(16,.7,15),'embers',glow=True)
    for index,(x,z) in enumerate(((-7,-6),(6,-7),(-6,1),(7,1),(-4,5),(4,5))):
        m.box('charcoal_'+str(index),(x,12.8,z),(3,1.6,3),'dark',(0,index*31,0))
    m.box('crucible_foot',(0,13,-3),(9,1.5,9),'dark')
    for side in (-1,1):
        m.box('crucible_wall_x_'+str(side),(side*5,15,-3),(2,4,11),'dark')
        m.box('crucible_wall_z_'+str(side),(0,15,-3+side*5),(9,4,2),'dark')
        m.box('crucible_copper_x_'+str(side),(side*5,17,-3),(2.6,1.2,11.6),'copper')
        m.box('crucible_copper_z_'+str(side),(0,17,-3+side*5),(9,1.2,2.6),'copper')
    m.box('molten_reality',(0,16.8,-3),(8,.45,8),'molten',glow=True)
    # The front anvil projects out of the hearth. Its horn, narrowed waist and
    # scarred striking face make the machine recognizable even when inactive.
    m.box('anvil_pedestal',(-1,10.5,10),(14,4,9),'dark')
    m.box('anvil_waist',(-1,13,10),(8,4,6),'edge')
    m.box('anvil_shoulders',(-1,15,10),(16,3,8),'steel')
    m.box('anvil_striking_face',(-1,16.6,10),(17,1,9),'anvil_face')
    m.box('anvil_horn_root',(-11,15.9,10),(4,2.4,6),'steel')
    m.box('anvil_horn_tip',(-13.5,16.3,10),(2,1.2,3),'steel')
    m.box('anvil_heel',(9.5,15.6,10),(4,2.4,7),'steel')
    m.box('anvil_cyan_edge',(-1,16,14.6),(13,.65,.5),'cyan',glow=True)
    # Compact automatic hammer underneath the hood, aligned over the anvil.
    m.box('hammer_guide',(0,27.9,8),(8,6,6),'navy')
    m.box('hammer_ram',(0,24.3,8),(3,7,3),'copper')
    m.box('hammer_head',(0,21.3,8),(7,3,6),'edge')
    m.box('hammer_die',(0,19.65,8),(5,.7,4.5),'purple',glow=True)
    # Stepped extraction hood and hollow chimney stay inside the existing height.
    m.box('fume_hood_base',(0,31,-3),(28,3,19),'navy')
    m.box('hood_step',(0,33,-5),(24,2,15),'edge')
    m.box('hood_collar',(-5,34.5,-7),(15,2,12),'copper')
    m.box('chimney_back',(-5,38.5,-12),(11,6,2),'dark')
    m.box('chimney_front',(-5,38.5,-3),(11,6,2),'navy',faces={'front':'vent'})
    for x in (-10,0):m.box('chimney_side_'+str(x),(x,38.5,-7.5),(2,6,7),'navy')
    m.box('chimney_throat',(-5,35.8,-7.5),(8,.5,6),'dark')
    for z in (-12.5,-2.5):m.box('chimney_cap_z_'+str(z),(-5,42.25,z),(15,2.5,2),'edge')
    for x in (-11.5,1.5):m.box('chimney_cap_x_'+str(x),(x,42.25,-7.5),(2,2.5,8),'edge')
    # The back remains authored: bolted access panel, baffles and coolant pipe.
    m.box('rear_insulation',(0,21,-13.5),(25,18,1.5),'navy')
    m.box('rear_cleanout',(0,18,-14.28),(15,9,.25),'vent')
    m.box('rear_cleanout_latch',(0,18,-14.4),(4,1,.2),'steel')
    for side in (-1,1):
        m.box('coolant_riser_'+str(side),(side*13.8,24,-9),(1.6,18,2),'dark')
        for y in (17,24,30):m.box('coolant_ferrule_'+str(side)+'_'+str(y),(side*13.8,y,-9),(2.5,1.2,3),'copper')
    m.box('pressure_gauge_bezel',(13.9,25,7),(6,6,2.3),'dark',(0,90,0))
    m.box('pressure_gauge',(15.15,25,7),(4,4,.4),'gauge',(0,90,0))
    m.box('control_toggle',(12.5,20,12),(2,3,2),'copper',(0,0,-15))
    m.box('control_signal',(12.5,22.5,12),(1.5,1,1.5),'purple',glow=True)
    for side in (-1,1):
        for x,y,z in ((13,5,14.15),(12,10,10.2),(9,28,10.7)):
            m.box('front_rivet_'+str(side)+'_'+str(y),(side*x,y,z),(1.1,1.1,.7),'steel')

    art.paint=lambda w,h,mat,seed:paint(w,h,mat,seed,False)
    try:entry=m.save()
    finally:art.paint=PAINT
    # A separate working atlas brightens only the real heat sources. Texture
    # addressing and all front/back UV islands are identical between states.
    working=Image.open(FOLDER/(NAME+'.png')).convert('RGBA')
    for index,node in enumerate(m.nodes):
        mat,overrides=m.materials[index]
        size=node['shape']['settings']['size'];x,y,z=(size[k] for k in 'xyz')
        for face,w,h in [('front',x,y),('back',x,y),('left',z,y),('right',z,y),('top',x,z),('bottom',x,z)]:
            face_mat=overrides.get(face,mat)
            if face_mat not in ('molten','embers'):continue
            tile=paint(w,h,face_mat,f'{NAME}/{index}/{face}',True);uv=node['shape']['textureLayout'][face]['offset'];px,py=uv['x'],uv['y']
            working.paste(tile,(px,py));working.paste(tile.crop((0,0,w,1)),(px,py-1));working.paste(tile.crop((0,h-1,w,h)),(px,py+h))
            working.paste(tile.crop((0,0,1,h)),(px-1,py));working.paste(tile.crop((w-1,0,w,h)),(px+w,py))
    working.save(FOLDER/(NAME+'_working.png'))
    animate(m)
    item=read(ITEM);state=item['BlockType']['State']['Definitions']['Working']
    state['CustomModelTexture']=[{'Texture':'Blocks/StrangeMatter/reality_forge_working.png','Weight':1}]
    # Working texture and physical hammer loop follow the existing state. No
    # new continuously running emitter or ambient loop is needed.
    write(ITEM,item)
    entry.update({'workingTexture':'Blocks/StrangeMatter/reality_forge_working.png','front':'+Z',
                  'animatedNodes':['hammer_ram','hammer_head','hammer_die','molten_reality','ember_bed'],
                  'nativeReference':'Blocks/Benches/Furnace.blockymodel and Furnace2.blockymodel: open lined hearth, extraction hood and chimney silhouette',
                  'footprintPreserved':True})
    write(ROOT/'tools/assets/reality-forge-revision.json',entry)
    print('Reality Forge:',len(m.nodes),'nodes,',entry['atlas'],'atlas. Only forge art/Working texture selection written.')

def animate(model):
    tracks={}
    for name in ('hammer_ram','hammer_head','hammer_die'):
        positions=[(0,0),(14,2),(29,2),(40,-2.2),(45,-2.2),(56,0),(90,0)]
        tracks[name]={'position':[{'time':t,'delta':art.V((0,y,0)),'interpolationType':'smooth'} for t,y in positions],
                      'orientation':[],'shapeStretch':[],'shapeVisible':[],'shapeUvOffset':[]}
    for name,axis in (('molten_reality','y'),('ember_bed','y')):
        tracks[name]={'position':[],'orientation':[],'shapeStretch':[
          {'time':t,'delta':{'x':1,'y':value,'z':1},'interpolationType':'smooth'} for t,value in ((0,1),(22,1.15),(40,1.02),(55,.9),(74,1.12),(90,1))],
          'shapeVisible':[],'shapeUvOffset':[]}
    write(FOLDER/(NAME+'_working.blockyanim'),{'formatVersion':1,'duration':90,'holdLastKeyframe':False,'nodeAnimations':tracks})

def previews():
    model=read(FOLDER/(NAME+'.blockymodel'));panels=[]
    for label,yaw,pitch,working in [('Front',0,12,False),('Front right',35,23,False),('Back left',215,23,False),('Left side',-90,12,False),('Top',35,52,False),('Working hearth',20,18,True)]:
        texture=Image.open(FOLDER/(NAME+('_working' if working else '')+'.png'))
        panels.append((label,render.render(render.model_faces(model,texture),480,yaw,pitch)))
    canvas=Image.new('RGBA',(1440,1040),(14,21,33,255));draw=ImageDraw.Draw(canvas)
    for index,(label,image) in enumerate(panels):
        x=index%3*480;y=index//3*520;canvas.alpha_composite(image,(x,y));draw.text((x+18,y+487),label,fill=(223,236,242))
    target=ROOT/'docs/art/reality-forge-revision.png';canvas.save(target);print(target)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--preview',action='store_true');args=parser.parse_args()
    build()
    if args.preview:previews()
