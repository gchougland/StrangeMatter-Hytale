"""Original Strange Matter art: native box/quad models, UV-painted atlases and icons.

Run with Python 3, Pillow and numpy. No Minecraft bitmaps or geometry are copied.
Model units are Hytale's 32 units per world block. Every face is uniquely packed
with a one-texel gutter and validated against its atlas. The CPU renderer reads
the exported model, rather than a separate idealized version of the geometry.
"""
from pathlib import Path
import argparse, json, math, random, hashlib, itertools
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = Path(__file__).resolve().parents[2]
COMMON = ROOT / 'src/main/resources/Common'
OUT = ROOT / 'tools/assets'
ART = ROOT / 'docs/art'
V = lambda p: dict(zip(('x','y','z'),p))
P = {'navy':(30,43,69),'edge':(61,81,109),'dark':(13,23,41),
     'steel':(112,142,162),'copper':(173,107,69),'cyan':(72,231,235),
     'purple':(151,88,237),'amber':(250,187,83),'white':(212,233,228),
     'screen':(13,43,56),'stone':(72,79,97),'paper':(208,204,174),
     'grass':(78,132,76),'soil':(103,77,54),'pink':(221,104,207)}
FAMILY = {'gravitic':'purple','gravity':'purple','chrono':'amber','temporal':'amber',
          'energetic':'cyan','spatial':'pink','warp':'pink','shade':'purple',
          'echoing':'purple','insight':'cyan','thoughtwell':'cyan','resonite':'cyan'}

def hid(name): return 'SM_' + '_'.join(s.capitalize() for s in name.split('_'))
def family(name): return next((v for k,v in FAMILY.items() if k in name),'cyan')
def quat(e=(0,0,0)):
    x,y,z=[math.radians(a)/2 for a in e]; cx,cy,cz=math.cos(x),math.cos(y),math.cos(z); sx,sy,sz=math.sin(x),math.sin(y),math.sin(z)
    return {'x':sx*cy*cz-cx*sy*sz,'y':cx*sy*cz+sx*cy*sz,'z':cx*cy*sz-sx*sy*cz,'w':cx*cy*cz+sx*sy*sz}
def qmatrix(q):
    x,y,z,w=[q[k] for k in ('x','y','z','w')]
    return np.array([[1-2*(y*y+z*z),2*(x*y-z*w),2*(x*z+y*w)],[2*(x*y+z*w),1-2*(x*x+z*z),2*(y*z-x*w)],[2*(x*z-y*w),2*(y*z+x*w),1-2*(x*x+y*y)]])
def matrixquat(m):
    # Largest eigenvector gives a stable unit quaternion even at 180 degrees.
    K=np.array([[m[0,0]-m[1,1]-m[2,2],m[1,0]+m[0,1],m[2,0]+m[0,2],m[2,1]-m[1,2]],
      [m[1,0]+m[0,1],m[1,1]-m[0,0]-m[2,2],m[2,1]+m[1,2],m[0,2]-m[2,0]],
      [m[2,0]+m[0,2],m[2,1]+m[1,2],m[2,2]-m[0,0]-m[1,1],m[1,0]-m[0,1]],
      [m[2,1]-m[1,2],m[0,2]-m[2,0],m[1,0]-m[0,1],m[0,0]+m[1,1]+m[2,2]]])/3
    _,vec=np.linalg.eigh(K);q=vec[:,-1];return dict(zip(('x','y','z','w'),q.tolist()))

class Model:
    def __init__(self,name,block=False): self.name=name; self.block=block; self.nodes=[]; self.materials=[]
    def box(self,name,p,s,mat='navy',rot=(0,0,0),faces=None,glow=False):
        assert all(v>0 for v in s), (self.name,name,s)
        # Texture dimensions are integers; stretch preserves intentionally fractional silhouettes.
        dims=[max(1,math.ceil(a)) for a in s]
        self.nodes.append({'id':str(len(self.nodes)+1),'name':name,'position':V(p),'orientation':quat(rot),
           'shape':{'type':'box','offset':V((0,0,0)),'stretch':V([a/b for a,b in zip(s,dims)]),
           'settings':{'isPiece':False,'size':V(dims),'isStaticBox':True},'textureLayout':{},
           'unwrapMode':'custom','visible':True,'doubleSided':False,'shadingMode':'fullbright' if glow else 'standard'}})
        self.materials.append((mat,faces or {})); return self
    def triangle(self,name,verts,mat):
        v0,v1,v2=np.array(verts,dtype=float);x=v1-v0;w=np.linalg.norm(x);x/=w
        projected=np.dot(v2-v0,x);y=v2-v0-x*projected;h=np.linalg.norm(y);y/=h;z=np.cross(x,y)
        pos=(v0+v1)/2+y*h/2;tw,th=max(2,math.ceil(w*2)),max(2,math.ceil(h*2))
        self.nodes.append({'id':str(len(self.nodes)+1),'name':name,'position':V(pos.tolist()),'orientation':matrixquat(np.column_stack((x,y,z))),
          'shape':{'type':'quad','offset':V((0,0,0)),'stretch':V((w/tw,h/th,1)),
          'settings':{'size':{'x':tw,'y':th},'normal':'+Z'},'textureLayout':{},'unwrapMode':'custom','visible':True,'doubleSided':True,'shadingMode':'standard'}})
        self.materials.append((f'triangle:{mat}:{projected/w}',{}));return self
    def ico(self,name,p,r,mat='purple'):
        t=(1+math.sqrt(5))/2;verts=np.array([(0,s,t*q) for s in (-1,1) for q in (-1,1)]+[(s,t*q,0) for s in (-1,1) for q in (-1,1)]+[(t*q,0,s) for s in (-1,1) for q in (-1,1)])
        verts=verts/np.linalg.norm(verts[0])*r;idx=0
        for a,b,c in itertools.combinations(range(12),3):
            va,vb,vc=verts[[a,b,c]];normal=np.cross(vb-va,vc-va);sign=(verts-va)@normal
            if not(np.all(sign>=-1e-6) or np.all(sign<=1e-6)):continue
            if np.dot(normal,va)<0:vb,vc=vc,vb
            self.triangle(name+str(idx),np.array([va,vb,vc])+np.array(p),mat);idx+=1
        assert idx==20;return self
    def ring(self,name,p,r,t=2,depth=3,mat='edge',axis='z',count=12):
        for i in range(count):
            a=2*math.pi*i/count; length=2*r*math.tan(math.pi/count)+0.4
            if axis=='y': pos=(p[0]+r*math.sin(a),p[1],p[2]+r*math.cos(a)); size=(length,depth,t); rot=(0,math.degrees(a),0)
            else: pos=(p[0]+r*math.sin(a),p[1]+r*math.cos(a),p[2]); size=(length,t,depth); rot=(0,0,-math.degrees(a))
            self.box(name+str(i),pos,size,mat,rot,glow=mat in ('cyan','purple','amber','pink'))
        return self
    def crystal(self,name,p,h,mat='purple',rot=(0,0,0),w=None):
        w=w or h*.34
        self.box(name+'_shaft',p,(w,h*.62,w),mat,rot)
        transform=qmatrix(quat(rot));origin=np.array(p)
        for sign in (-1,1):
            ring=[(-w/2,sign*h*.31,-w/2),(w/2,sign*h*.31,-w/2),(w/2,sign*h*.31,w/2),(-w/2,sign*h*.31,w/2)]
            for i in range(4):
                verts=np.array([ring[i],ring[(i+1)%4],(0,sign*h*.5,0)])@transform.T+origin
                self.triangle(name+'_point'+str(sign)+'_'+str(i),verts,mat)
        return self
    def feet(self,w=25,d=23):
        for x in (-w/2,w/2):
            for z in (-d/2,d/2): self.box('isolation_foot',(x,2,z),(5,4,5),'dark').box('foot_cap',(x,4,z),(5,1,5),'steel')
    def base(self,w=28,h=12,d=26):
        self.feet(w-5,d-5); self.box('lower_bevel',(0,5,0),(w-2,2,d-2),'edge'); self.box('armoured_plinth',(0,5+h/2,0),(w,h,d),'navy'); self.box('upper_rim',(0,5+h,0),(w+1,2,d+1),'edge')
        for x in (-w/2+2,w/2-2): self.box('corner_binding',(x,5+h/2,d/2+.3),(2,h-2,1),'steel')
        self.box('recessed_service_panel',(0,5+h/2,d/2+.5),(w-10,max(1,h-3),1),'vent')
        return 5+h
    def control(self,p,w=14,h=10,mat='screen'):
        self.box('instrument_bezel',p,(w+3,h+3,3),'dark'); self.box('instrument_glass',(p[0],p[1],p[2]+1.6),(w,h,0.6),mat)
        self.box('screen_brow',(p[0],p[1]+h/2+1.5,p[2]+1),(w+4,1,3),'steel')
    def bolts(self,xs,y,z):
        for x in xs:self.box('rivet',(x,y,z),(1.2,1.2,1),'steel')
    def save(self):
        if self.name=='warp_gun':
            from build_warp_gun_revision import save_generated
            return save_generated(self)
        tiles=[]
        for ni,n in enumerate(self.nodes):
            s=n['shape']['settings']['size']; x,y,z=[s.get(k,0) for k in ('x','y','z')]
            if n['shape']['type']=='quad':
                mat,override=self.materials[ni];tiles.append((ni,'front',x,y,mat));continue
            for face,w,h in [('front',x,y),('back',x,y),('left',z,y),('right',z,y),('top',x,z),('bottom',x,z)]:
                mat,override=self.materials[ni]; tiles.append((ni,face,w,h,override.get(face,mat)))
        width=512 if sum((t[2]+2)*(t[3]+2) for t in tiles)>45000 else 256
        atlas=Image.new('RGBA',(width,2048)); px=1; py=1; row=0
        for ni,face,w,h,mat in sorted(tiles,key=lambda t:-t[3]):
            if px+w+1>width: px=1; py+=row+2; row=0
            tile=paint(w,h,mat,f'{self.name}/{ni}/{face}')
            atlas.paste(tile,(px,py)); # extrude all edges into padding, preventing mip bleed
            atlas.paste(tile.crop((0,0,w,1)),(px,py-1));atlas.paste(tile.crop((0,h-1,w,h)),(px,py+h))
            atlas.paste(tile.crop((0,0,1,h)),(px-1,py));atlas.paste(tile.crop((w-1,0,w,h)),(px+w,py))
            self.nodes[ni]['shape']['textureLayout'][face]={'offset':{'x':px,'y':py},'mirror':{'x':False,'y':False},'angle':0}
            px+=w+2; row=max(row,h)
        height=max(32,2**math.ceil(math.log2(py+row+1))); atlas=atlas.crop((0,0,width,height))
        folder=COMMON/('Blocks' if self.block else 'Items')/'StrangeMatter';folder.mkdir(parents=True,exist_ok=True)
        model={'nodes':self.nodes,'format':'prop'}
        if self.name=='echoform_imprinter':
            from fit_imprinter_grip import fit
            model=fit(model)
        (folder/(self.name+'.blockymodel')).write_text(json.dumps(model,indent=2)+'\n')
        atlas.save(folder/(self.name+'.png'))
        mesh=geometry(model); pts=np.concatenate([f[0] for f in mesh]); low=pts.min(0); high=pts.max(0)
        return {'sourceId':self.name,'id':hid(self.name),'block':self.block,'model':str((folder/(self.name+'.blockymodel')).relative_to(COMMON)).replace('\\','/'),
          'texture':str((folder/(self.name+'.png')).relative_to(COMMON)).replace('\\','/'),'icon':'Icons/ItemsGenerated/'+hid(self.name)+'.png','nodes':len(self.nodes),
          'boundsModelUnits':{'min':low.round(3).tolist(),'max':high.round(3).tolist()},'atlas':[width,height],
          **({'previewHostNode':next(n for n in self.nodes if n['name']=='host_rock')} if self.name.endswith('_ore') else {})}

def paint(w,h,mat,seed):
    if mat=='native_stone':
        native=ROOT.parent/'HytaleSourceCode/hytale-shared-source/HytaleAssets/Common/BlockTextures/Rock_Stone.png'
        return Image.open(native).convert('RGBA').resize((w,h),Image.Resampling.NEAREST)
    if mat in ('grass_top','grass_side'):
        im=paint(w,h,'grass' if mat=='grass_top' else 'soil',seed);d=ImageDraw.Draw(im);rng=random.Random(seed)
        if mat=='grass_side':
            fringe=[(0,0),(w,0),(w,h*.27)]
            fringe += [(x,int(h*(.17+.10*rng.random()))) for x in range(w,-1,-2)]
            d.polygon(fringe,fill=P['grass']+(255,))
        for i in range(max(7,w*h//38)):
            x=rng.randrange(w);y=rng.randrange(h if mat=='grass_top' else max(1,h//5))
            d.line((x,y,x+2,y-2),fill=(110,157,88,255))
            if i%13==0:d.point((x,y),fill=(91,186,155,255))
        return im
    if mat=='temporal_field':
        im=Image.new('RGBA',(w,h),(174,108,29,255));d=ImageDraw.Draw(im)
        for y in range(h):
            t=.45+.55*math.sin((y/max(1,h-1)) * math.pi)
            d.line((0,y,w-1,y),fill=(int(181+55*t),int(113+73*t),int(27+58*t),255))
        d.rectangle((0,0,w-1,h-1),outline=(255,219,122,255),width=1)
        # Interlocking hexagonal field cells and concentric temporal ripples.
        for y in range(-4,h+8,10):
            for x in range(-4,w+8,12):
                xx=x+(6 if (y//10)%2 else 0)
                d.line([(xx-5,y),(xx-2,y-4),(xx+2,y-4),(xx+5,y),(xx+2,y+4),(xx-2,y+4),(xx-5,y)],fill=(250,199,92,255))
        d.ellipse((w*.21,h*.21,w*.79,h*.79),outline=(255,230,154,255))
        d.line((w*.5,h*.32,w*.5,h*.52,w*.64,h*.61),fill=(255,238,174,255),width=1)
        return im
    if mat.startswith('triangle:'):
        _,under,apex=mat.split(':');im=paint(w,h,under,seed);mask=Image.new('L',(w,h));md=ImageDraw.Draw(mask)
        tip=float(apex)*(w-1);md.polygon([(0,h-1),(w-1,h-1),(tip,0)],fill=255)
        im.putalpha(mask);d=ImageDraw.Draw(im);color=tuple(min(255,c+24) for c in P[under])+(255,)
        d.line([(0,h-1),(tip,0),(w-1,h-1)],fill=color,width=1);return im
    rng=random.Random(seed); base=P.get(mat,P['navy']); im=Image.new('RGBA',(w,h),base+(255,)); d=ImageDraw.Draw(im)
    # Broader hand-painted bands with subtle shared-palette variation, no photo noise.
    for y in range(h):
        for x in range(w):
            noise=rng.choice([-3,-2,0,0,0,2,3]); bevel=10 if min(x,y)<1 else -12 if x==w-1 or y==h-1 else 0
            brush=3*math.sin(x*.43+y*.12)+2*math.sin(y*.32)
            im.putpixel((x,y),tuple(max(0,min(255,int(c+noise+bevel+brush))) for c in base)+(255,))
    def col(k):return P[k]+(255,)
    if mat in ('navy','edge','steel','copper') and w>=9 and h>=8:
        d.rectangle((1,1,w-2,h-2),outline=tuple(max(0,c-10) for c in base))
        d.line((2,2,w-4,2),fill=tuple(min(255,c+17) for c in base))
        # restrained worn chips, heavier at exposed corners
        for x,y in ((2,2),(w-3,2),(2,h-3),(w-3,h-3)):
            d.point((x,y),fill=col('steel'))
    if mat=='vent':
        d.rectangle((0,0,w-1,h-1),fill=col('dark'))
        for y in range(2,h-1,3):d.line((2,y,w-3,y),fill=col('edge'));d.line((2,y+1,w-3,y+1),fill=col('navy'))
    if mat=='screen':
        d.rectangle((0,0,w-1,h-1),fill=col('screen'))
        for x in range(2,w,4): d.line((x,1,x,h-2),fill=(18,60,70))
        for y in range(2,h,4):d.line((1,y,w-2,y),fill=(18,60,70))
        wave=[(x,max(1,min(h-2,round(h*.5+math.sin(x*.9)*h*.23)))) for x in range(1,w-1)]
        if len(wave)>1:d.line(wave,fill=col('cyan'),width=1)
        d.line((1,1,min(w-2,4),1),fill=col('amber'))
    if mat=='gauge' and w>=4 and h>=4:
        d.rectangle((0,0,w-1,h-1),fill=col('dark')); d.ellipse((1,1,w-2,h-2),fill=col('paper'),outline=col('steel'))
        d.line((w//2,h//2,w-3,2),fill=col('copper'),width=1)
    if mat=='hazard':
        d.rectangle((0,0,w-1,h-1),fill=col('amber'))
        for x in range(-h,w,6):d.line((x,0,x+h,h),fill=col('dark'),width=3)
    if mat=='circuit':
        d.rectangle((0,0,w-1,h-1),fill=(21,61,60))
        for i in range(5):
            x=rng.randrange(max(1,w)); y=rng.randrange(max(1,h)); d.line((x,y,x,min(h-1,y+5),min(w-1,x+4),min(h-1,y+5)),fill=col('copper'))
        d.rectangle((w//3,h//3,w*2//3,h*2//3),fill=col('dark'),outline=col('steel'))
    if mat in ('cyan','purple','amber','pink'):
        for y in range(h):
            light=max(0,1-abs(y-h*.25)/max(1,h))
            d.line((1,y,max(1,w-2),y),fill=tuple(min(255,int(c*.65+light*c*.35)) for c in base))
        if w>=3:d.line((1,1,1,h-2),fill=tuple(min(255,c+60) for c in base))
        if w>=7 and h>=9:d.line((2,h-3,w-3,2),fill=tuple(min(255,c+30) for c in base))
    if mat in ('stone','soil','grass'):
        for i in range(max(2,w*h//40)):
            x=rng.randrange(w);y=rng.randrange(h);sz=rng.randrange(2,6);c=tuple(max(0,min(255,n+rng.randrange(-14,15))) for n in base)
            d.polygon([(x,y),(x+sz,y-1),(x+sz+2,y+2),(x+1,y+3)],fill=c)
    if mat=='paper' and w>=5:
        for y in range(3,h-2,3):d.line((2,y,w-rng.randrange(2,max(3,w//3)),y),fill=col('edge'))
    return im

def make_machine(name):
    m=Model(name,True)
    if name=='research_machine':
        m.base(h=12); m.box('sloped_console',(0,20,6),(27,5,16),'navy',(-12,0,0));m.control((0,30,-2),17,12)
        m.box('monitor_support',(0,24,-7),(6,16,5),'edge');m.box('keyboard',(0,23,10),(17,1,7),'circuit',(-12,0,0))
        for x in (-10,10):m.box('input_dial',(x,23,12),(3,2,3),'steel')
        m.box('scope_pedestal',(-10,24,-7),(7,10,7),'dark');m.box('scope_coil',(-10,31,-7),(6,3,6),'copper');m.crystal('sample',(-10,36,-7),7,'purple',w=3)
        m.box('aerial',(11,34,-9),(1,23,1),'steel');m.box('aerial_tip',(11,46,-9),(2,2,2),'cyan',glow=True)
        m.box('paper_tray',(-8,11,14),(7,2,5),'paper');m.box('lab_serial',(8,12,13.6),(5,3,1),'hazard')
    elif name=='resonant_burner':
        m.base(h=14);m.box('firebox',(0,21,0),(23,15,20),'navy');m.ring('furnace_mouth',(0,22,11),7,2,2,'copper',count=8)
        m.box('plasma_window',(0,22,11),(10,9,1),'amber',glow=True)
        for x in (-3,0,3):m.box('heat_grille',(x,22,12),(1,10,1),'dark')
        m.box('hot_plate',(0,30,0),(26,3,23),'steel');m.box('flue',(9,34,-7),(5,18,5),'dark')
        for y in (28,32,36,40):m.box('flue_fins',(9,y,-7),(8,1,8),'edge')
        m.box('fuel_hopper',(-11,27,-4),(9,10,11),'copper');m.box('fuel_mouth',(-11,32,-4),(7,1,9),'dark')
    elif name=='resonance_condenser':
        m.base(h=9)
        for x in (-9,9):
            m.box('capacitor_foot',(x,17,0),(9,4,13),'steel');m.box('capacitor_vessel',(x,27,0),(8,18,10),'navy');m.box('capacitor_window',(x,27,5.4),(4,13,1),'cyan',glow=True)
            for y in (20,26,32,36):m.box('induction_winding',(x,y,0),(10,2,12),'copper')
            m.box('ceramic_terminal',(x,39,0),(4,5,4),'white')
        m.box('bus_bridge',(0,42,0),(22,2,3),'purple',glow=True);m.control((0,20,10),7,5,'gauge')
    elif name=='reality_forge':
        m.base(w=30,h=10,d=28);m.box('anvil_waist',(0,20,0),(14,10,14),'dark');m.box('anvil_crown',(0,26,0),(27,5,18),'steel')
        m.box('striking_face',(0,29,0),(22,1,14),'purple',glow=True)
        for x in (-13,13):
            m.box('forge_pylon',(x,27,-6),(5,26,8),'navy');m.box('pylon_conductor',(x,29,-1.5),(2,19,1),'cyan',glow=True)
        m.box('upper_yoke',(0,41,-6),(30,5,9),'edge');m.box('quantum_press',(0,34,-4),(8,10,7),'copper');m.box('press_tip',(0,28,-4),(5,3,5),'purple',glow=True)
        m.box('quench_vent',(0,11,15),(19,5,1),'hazard');m.control((10,17,12),5,4)
    elif name=='rift_stabilizer':
        m.base(h=8);m.box('ring_foot',(0,19,0),(10,9,8),'steel');m.ring('containment_arch',(0,31,0),13,3,6,'navy');m.ring('inner_conductor',(0,31,3.2),10.5,1,1,'purple')
        for x,y in ((-13,31),(13,31),(0,44)):
            m.box('field_clamp',(x,y,0),(6,6,9),'edge');m.box('clamp_light',(x,y,5),(3,3,1),'cyan',glow=True)
        m.crystal('suspended_seed',(0,31,0),8,'pink',w=4)
    elif name=='paradoxical_energy_cell':
        m.base(h=7);m.box('battery_spine',(0,25,0),(17,22,17),'dark')
        for x in (-9,9):
            for z in (-9,9):m.box('corner_rail',(x,25,z),(4,25,4),'edge')
        for z in (-9.2,9.2):m.box('charge_column',(0,25,z),(9,18,1),'purple',glow=True)
        for y in (16,22,28,34):m.ring('containment_band',(0,y,0),10,1.5,2,'copper',axis='y',count=8)
        m.box('battery_head',(0,39,0),(24,4,24),'navy');m.box('positive_terminal',(-6,43,0),(4,4,4),'cyan',glow=True);m.box('negative_terminal',(6,43,0),(4,4,4),'steel')
    elif name=='stasis_projector':
        m.box('isolation_base',(0,1,0),(27,2,27),'dark')
        m.box('bevelled_pad',(0,3,0),(30,3,30),'navy')
        m.box('lens_bed',(0,4.65,0),(22,.3,22),'dark')
        m.ring('containment_coil',(0,5,0),10,1.5,1,'copper',axis='y',count=12)
        m.ring('focus_aperture',(0,5.3,0),6,1,1,'cyan',axis='y',count=12)
        m.box('upward_emitter',(0,5.3,0),(6,.6,6),'cyan',glow=True)
        for x in (-12,12):
            for z in (-12,12):
                m.box('corner_guard',(x,4.6,z),(4,2.8,4),'edge')
                m.box('status_light',(x,6,z),(2,.2,2),'purple',glow=True)
    elif name=='time_dilation_block':
        m.box('golden_temporal_forcefield',(0,16,0),(32,32,32),'temporal_field',glow=True)
    elif name=='levitation_pad':
        m.base(h=3);m.box('levitation_plate',(0,10,0),(28,3,28),'dark');m.ring('levitation_rune',(0,12,0),10,1.5,1,'purple',axis='y')
        for x,z in ((-11,-11),(-11,11),(11,-11),(11,11)):
            m.box('corner_coil',(x,10,z),(5,5,5),'copper');m.box('pad_beacon',(x,13,z),(3,1,3),'cyan',glow=True)
    elif name=='resonant_conduit':
        m.box('hub_body',(0,16,0),(11,11,11),'navy')
        for axis,sign in ((0,1),(0,-1),(1,1),(1,-1),(2,1),(2,-1)):
            bit=1 << (axis*2+(sign<0));p=[0,16,0];p[axis]+=sign*10.5
            size=[6,6,6];size[axis]=10;m.box('arm'+str(bit)+'_conductor',p,size,'dark')
            p[axis]=([0,16,0][axis])+sign*14.5;size=[9,9,9];size[axis]=3;m.box('arm'+str(bit)+'_coupling',p,size,'steel')
            p[axis]=([0,16,0][axis])+sign*12.4;size=[6.6,6.6,6.6];size[axis]=1;m.box('arm'+str(bit)+'_energy_band',p,size,'cyan',glow=True)
        for z in (-5.6,5.6):m.box('hub_indicator',(0,16,z),(4,4,.2),'purple',glow=True)
    elif name=='laboratory_bench':
        for x in (-12,12):
            for z in (-11,11):
                m.box('steel_foot',(x,1.5,z),(5,3,5),'dark');m.box('navy_leg',(x,12,z),(4,21,4),'navy')
                m.box('leg_binding',(x,6,z),(4.8,2,4.8),'copper')
        m.box('lower_supply_shelf',(0,7,0),(26,2,24),'edge')
        m.box('worktop_edge',(0,23,0),(32,4,30),'edge');m.box('ceramic_worktop',(0,25.4,0),(30,.8,28),'white')
        m.box('left_drawer',(-7,19,10),(12,4,4),'navy');m.box('drawer_handle',(-7,19,12.4),(5,1,1),'copper')
        m.box('power_pack',(8,13,-4),(9,10,12),'navy');m.box('power_indicator',(8,13,2.3),(5,5,.5),'cyan',glow=True)
        m.box('rear_instrument_rail',(0,31,-12),(30,2,3),'navy')
        for x in (-13,13):m.box('rail_support',(x,29,-12),(2,10,2),'steel')
        m.control((-6,32,-10),10,6,'gauge')
        m.box('experiment_mat',(-5,26,4),(17,.5,14),'dark');m.box('circuit_under_test',(-5,26.5,4),(9,.5,8),'circuit')
        m.box('tube_socket',(9,27,-6),(6,2,6),'copper');m.box('sample_vial',(9,31,-6),(4,7,4),'cyan',glow=True)
        m.box('vial_stop',(9,35,-6),(5,1,5),'edge')
        m.box('tool_cup',(10,28,7),(5,5,5),'navy')
        for x,rot in ((8,-10),(11,12)):m.box('probe_handle',(x,33,7),(1.5,9,1.5),'copper',(0,0,rot))
    return m

MACHINES=['research_machine','resonant_burner','resonance_condenser','reality_forge','rift_stabilizer','paradoxical_energy_cell','stasis_projector','time_dilation_block','levitation_pad','resonant_conduit','laboratory_bench']

def make_block(name):
    if name in MACHINES:return make_machine(name)
    m=Model(name,True); c=family(name)
    if name.endswith('_ore'):
        # The icon uses this exact native stone texture. Finalizer removes the preview
        # host from the model: CubeWithModel supplies Hytale's own ordinary stone cube.
        m.box('host_rock',(0,16,0),(32,32,32),'native_stone')
        for axis in range(3):
            for sign in (-1,1):
                for i,(a,b,size) in enumerate(((-7,-5,8),(5,6,9),(8,-9,5))):
                    p=[a,b+16,0];coords=[a,b];others=[k for k in range(3) if k!=axis]
                    p=[0,16,0];p[axis]+=sign*14.6
                    for k,v in zip(others,coords):p[k]+=v
                    s=[size,size*.78,size*.85];s[axis]=4;rot=[0,0,0];rot[axis]=(23,-17,37)[i]*sign
                    m.box('embedded_mineral',p,s,c,rot)
                    edge=p.copy();edge[axis]+=sign*2.02;t=[max(1,v*.5) for v in s];t[axis]=.1
                    m.box('mineral_facet',edge,t,'white' if i==2 else c,rot)
    elif name.endswith('_crystal'):
        m.crystal('central_bloom',(0,13,0),26,c,(0,12,0),w=7)
        for i in range(6):
            a=math.tau*i/6;h=17+(i%3)*2
            # Each crystal grows outward from the shared center; no pedestal.
            direction=np.array([math.sin(a)*.64,.77,math.cos(a)*.64]);direction/=np.linalg.norm(direction)
            y=direction;x=np.cross(y,[0,0,1]);x/=np.linalg.norm(x);z=np.cross(x,y)
            start=len(m.nodes);m.crystal('bloom_spike'+str(i),(0,0,0),h,c,w=4.5)
            transform=np.column_stack((x,y,z));origin=direction*h*.5+np.array([0,2,0])
            for n in m.nodes[start:]:
                n['position']=V((transform@np.array(list(n['position'].values()))+origin).tolist())
                n['orientation']=matrixquat(transform@qmatrix(n['orientation']))
    elif name.endswith('_lantern') or name.endswith('_lamp'):
        lantern=name.endswith('_lantern');m.box('plinth',(0,2,0),(16,4,16),'edge');m.box('plinth_lip',(0,5,0),(19,2,19),'copper')
        m.crystal('illuminant',(0,14,0),14,c,w=7)
        for x in (-7,7):
            for z in (-7,7):m.box('cage_post',(x,15,z),(2,20,2),'navy')
        m.box('cap',(0,25,0),(18,3,18),'edge');m.box('cap_bevel',(0,27,0),(13,2,13),'copper')
        if lantern:
            m.ring('handle',(0,31,0),4,1.4,2,'steel',count=8)
        else:m.box('base_socket',(0,0.5,0),(22,1,22),'dark')
    elif name=='anomalous_grass':
        m.box('living_grass_cube',(0,16,0),(32,32,32),'grass_side',faces={'top':'grass_top','bottom':'soil'})
    elif name=='resonite_tile_stairs':
        m.box('lower_step',(0,8,0),(32,16,32),'navy');m.box('upper_step',(0,24,-8),(32,16,16),'navy');m.box('edge_inlay',(0,16,8),(30,1,1),'cyan');m.box('edge_inlay',(0,32,-8),(30,1,1),'cyan')
    elif name in ('resonite_door','resonite_trapdoor'):
        if name=='resonite_door':
            m.box('door',(0,32,0),(32,64,4),'navy');m.box('vision_panel',(0,43,2.2),(16,23,.6),'screen');m.box('lower_panel',(0,15,2.2),(23,16,.6),'vent');m.box('handle',(10,28,3),(2,7,3),'copper')
            for x in (-14,14):m.box('edge_frame',(x,32,0),(3,64,6),'edge')
            m.box('warning',(0,29,2.2),(18,3,1),'hazard')
        else:
            m.box('hatch',(0,2,0),(32,4,32),'navy');m.box('observation_window',(0,4.3,0),(21,1,21),'screen');m.box('latch',(10,5,0),(3,2,8),'copper')
    elif name=='resonite_pillar':
        m.box('shaft',(0,16,0),(24,32,24),'navy')
        for y in (2,30):m.box('capital',(0,y,0),(32,4,32),'edge')
        for x in (-12,12):m.box('energy_flute',(x,16,0),(1,25,3),'cyan')
        for z in (-12,12):m.box('energy_flute',(0,16,z),(3,25,1),'cyan')
    elif name=='anomaly_spawner_marker':
        m.box('marker',(0,1,0),(16,2,16),'dark');m.ring('marker_rune',(0,2.1,0),6,1,1,'purple',axis='y')
    else:
        h=16 if name.endswith('slab') else 32
        m.box('resonite_masonry',(0,h/2,0),(32,h,32),'navy')
        for x in (-15,15):m.box('inlay',(x,h+.2,0),(1,.5,30),'edge')
        for z in (-15,15):m.box('inlay',(0,h+.2,z),(30,.5,1),'edge')
        if name=='fancy_resonite_tile':m.ring('ornamental_seal',(0,h+.6,0),10,1.5,1,'cyan',axis='y',count=8)
        elif name=='resonite_block':m.box('resonant_seam',(0,16,16.3),(30,2,.7),'cyan')
    return m

def make_item(name):
    if name=='warp_gun':
        from build_warp_gun_revision import model_art
        return model_art()
    m=Model(name);c=family(name)
    if name.endswith('_shard') or name in ('raw_resonite','resonite_nugget'):
        m.crystal('shard',(0,10,0),18 if name!='resonite_nugget' else 8,c,(0,10,-12),w=7)
        if name=='raw_resonite':m.box('rough_host',(-3,5,1),(8,7,7),'stone',(0,12,20))
    elif name=='resonite_ingot':
        m.box('ingot_base',(0,4,0),(18,6,9),'steel');m.box('ingot_crown',(0,8,0),(15,3,7),'cyan');m.box('assay_stamp',(0,9.6,0),(5,.3,3),'dark')
    elif name in ('research_tablet','research_notes','resonant_circuit'):
        mat='paper' if name=='research_notes' else 'circuit' if name=='resonant_circuit' else 'screen'
        m.box('case',(0,12,0),(17,23,3),'navy' if mat!='paper' else 'edge');m.box('face',(0,13,1.7),(13,18,1),mat);m.box('seal',(0,3,2),(3,2,1),'purple')
    elif name.startswith('containment_capsule'):
        m.box('capsule_cage',(0,10,0),(9,14,9),'dark');m.crystal('sample',(0,10,0),9,c,w=5)
        for y in (3,17):m.box('capsule_cap',(0,y,0),(12,4,12),'navy');m.box('cap_rim',(0,y+2.3,0),(11,1,11),'steel')
        for x in (-5,5):m.box('containment_rail',(x,10,0),(1,11,3),'copper')
        m.box('classification_light',(0,10,4.8),(4,8,.5),c,glow=True);m.box('release_latch',(0,20,0),(5,2,5),'edge')
    elif name=='graviton_hammer':
        m.box('grip',(0,10,0),(4,24,4),'dark');m.box('pommel',(0,-2,0),(6,3,6),'steel');m.box('power_handle',(0,18,0),(2,9,5),'purple',glow=True)
        m.box('hammer_head',(0,29,0),(24,14,13),'navy');m.box('head_core',(0,29,0),(12,16,15),'copper');m.box('core_window',(0,29,8),(8,9,1),'purple',glow=True)
        for x in (-14,14):m.box('striking_cap',(x,29,0),(5,17,17),'steel');m.box('cap_inlay',(x,29,9),(3,11,1),'cyan')
    elif name=='hoverboard':
        m.box('deck',(0,6,0),(13,3,37),'navy');m.box('nose',(0,8,20),(11,3,9),'edge',(-18,0,0));m.box('tail',(0,8,-20),(11,3,9),'edge',(18,0,0))
        for z in (-11,11):m.box('foot_grip',(0,8,z),(10,1,9),'vent');m.ring('lift_coil',(0,3,z),6,1.6,2,'purple',axis='y',count=8)
        for x in (-7,7):m.box('edge_light',(x,6,0),(1,2,29),'cyan',glow=True)
    elif name=='tinfoil_hat':
        m.box('foil_brim',(0,3,0),(22,2,22),'steel');m.box('folded_crown',(0,7,0),(16,7,16),'steel');m.box('folded_peak',(0,13,0),(10,7,10),'white',(0,45,0));m.box('tip',(0,18,0),(4,5,4),'steel',(0,45,0))
    elif name=='resonant_coil':
        m.box('bobbin',(0,9,0),(7,17,7),'dark')
        for y in (1,5,9,13,17):m.ring('winding',(0,y,0),6,2,2,'copper',axis='y',count=8)
        m.box('lead',(7,20,0),(2,7,2),'cyan')
    elif name=='stabilized_core':
        m.crystal('core',(0,12,0),15,'purple',w=7);m.ring('outer_cage',(0,12,0),11,1.5,2,'copper',count=8);m.ring('cross_cage',(0,12,0),11,1.5,2,'steel',axis='y',count=8)
    elif name=='chrono_blister':
        m.box('grenade',(0,10,0),(10,14,10),'navy');m.ring('chronal_band',(0,10,0),7,1.5,3,'amber',axis='y',count=8);m.box('detonator',(0,19,0),(5,5,5),'steel');m.box('safety_lever',(3,15,0),(2,13,3),'copper',(0,0,10))
    elif name=='anomaly_resonator':
        m.box('receiver',(0,10,0),(14,17,7),'navy');m.control((0,12,4),9,8,'gauge');m.box('antenna',(-5,25,0),(1,16,1),'steel');m.box('tuner',(6,20,0),(4,3,4),'copper')
        m.ring('receiver_dish',(0,22,0),5,1,2,'purple',axis='y',count=8)
    elif name=='field_scanner':
        m.box('scanner_grip',(0,4,0),(5,12,6),'dark',(-12,0,0));m.box('scanner_body',(0,16,0),(15,13,8),'navy');m.control((0,16,4),11,8)
        m.box('sensor_bar',(0,24,-1),(18,3,6),'edge');m.box('sensor_glow',(0,24,3),(13,1,1),'cyan',glow=True)
        for x in (-9,9):m.box('sensor_prong',(x,27,0),(2,8,3),'copper',(0,0,-x))
    elif name in ('warp_gun','echo_vacuum','echoform_imprinter'):
        m.box('pistol_grip',(0,4,-3),(6,14,7),'dark',(-16,0,0));m.box('receiver',(0,15,0),(12,10,20),'navy')
        if name=='echoform_imprinter':
            m.control((0,16,11),8,6);m.box('capture_frame',(0,23,2),(16,2,13),'copper')
            for x in (-7,7):m.box('capture_fork',(x,26,5),(2,8,2),'purple',glow=True)
        else:
            m.box('barrel',(0,16,15),(8,7,14),'steel');m.ring('emitter',(0,16,23),7 if name=='echo_vacuum' else 5,1.5,4,'copper',count=8)
            m.box('emitter_core',(0,16,25),(7,7,1),'purple' if name=='warp_gun' else 'cyan',glow=True)
            m.box('side_cell',(7,15,-1),(4,10,12),'purple',glow=True)
            for z in (-5,0,5):m.box('cell_clamp',(7,15,z),(5,12,2),'edge')
            if name=='echo_vacuum':m.box('reservoir',(0,18,-14),(14,16,10),'navy');m.box('reservoir_window',(0,18,-19.5),(8,11,1),'cyan')
    else:raise ValueError('No bespoke item design: '+name)
    return m

def make_core(kind):
    m=Model('anomaly_'+kind);c=family(kind)
    if kind in ('gravity','echoing_shadow'):
        m.ico('faceted_core',(0,0,0),13,'purple' if kind=='gravity' else 'dark')
        for i in range(5):a=i*math.tau/5;m.box('floating_debris',(math.cos(a)*22,math.sin(a*2)*8,math.sin(a)*22),(5,5,5),'stone',(25,i*23,34))
    elif kind=='temporal_bloom':
        for i in range(6):a=i*math.tau/6;m.crystal('time_petal'+str(i),(math.sin(a)*13,math.cos(a)*7,math.cos(a)*13),19,'amber',(0,i*60,i*12-30),w=6)
        m.crystal('chronal_heart',(0,2,0),25,'purple',w=8)
    elif kind=='thoughtwell':
        for i in range(5):a=i*math.tau/5;m.box('rune_boulder',(math.sin(a)*20,math.sin(a*2)*7,math.cos(a)*20),(12,13,11),'stone',(15,i*35,20));m.box('rune',(math.sin(a)*20,math.sin(a*2)*7+7,math.cos(a)*20),(5,1,4),'cyan',glow=True)
        m.crystal('insight',(0,0,0),13,'cyan',w=5)
    elif kind.startswith('warp_gate'):m.ring('event_horizon',(0,0,0),29,2,2,'cyan' if kind.endswith('_cyan') else 'purple',count=20)
    elif kind=='energetic_rift':
        for i in range(7):m.box('lightning_fracture',((-1 if i%2 else 1)*3,i*5-15,0),(3,8,3),'cyan',(0,0,35 if i%2 else -35),glow=True)
    return m

# Face winding is only used for CPU preview; UVs come from exported textureLayout.
FACE_VERTS={'front':[(-1,-1,1),(1,-1,1),(1,1,1),(-1,1,1)],'back':[(1,-1,-1),(-1,-1,-1),(-1,1,-1),(1,1,-1)],'right':[(1,-1,1),(1,-1,-1),(1,1,-1),(1,1,1)],'left':[(-1,-1,-1),(-1,-1,1),(-1,1,1),(-1,1,-1)],'top':[(-1,1,1),(1,1,1),(1,1,-1),(-1,1,-1)],'bottom':[(-1,-1,-1),(1,-1,-1),(1,-1,1),(-1,-1,1)]}
def geometry(model):
    faces=[]
    def flatten(nodes,parent_r=np.eye(3),parent_p=np.zeros(3)):
        for n in nodes:
            r=parent_r@qmatrix(n.get('orientation',quat()));p=parent_r@np.array([n.get('position',{}).get(k,0) for k in 'xyz'])+parent_p
            if n.get('shape',{}).get('type') in ('box','quad'):yield n,r,p
            yield from flatten(n.get('children',[]),r,p)
    for n,r,pos in flatten(model['nodes']):
        sh=n['shape'];size=np.array([sh['settings']['size'].get(k,0)*sh['stretch'][k] for k in ('x','y','z')]);dims=sh['settings']['size'];pos=pos+r@np.array([sh.get('offset',{}).get(k,0) for k in 'xyz'])
        if sh['type']=='quad':
            v=np.array([[-1,-1,0],[1,-1,0],[1,1,0],[-1,1,0]]);vertices=(v*size/2)@r.T+pos;off=sh['textureLayout']['front']['offset'];w,h=dims['x'],dims['y']
            uv=np.array([[off['x'],off['y']+h-.01],[off['x']+w-.01,off['y']+h-.01],[off['x']+w-.01,off['y']],[off['x'],off['y']]])
            faces.append((vertices,uv,sh['shadingMode']=='fullbright'));faces.append((vertices[::-1],uv[::-1],sh['shadingMode']=='fullbright'));continue
        for fn,v in FACE_VERTS.items():
            vertices=(np.array(v)*size/2)@r.T+pos
            w,h= (dims['x'],dims['z']) if fn in ('top','bottom') else (dims['z'],dims['y']) if fn in ('left','right') else (dims['x'],dims['y'])
            off=sh['textureLayout'][fn]['offset'];uv=np.array([[off['x'],off['y']+h-.01],[off['x']+w-.01,off['y']+h-.01],[off['x']+w-.01,off['y']],[off['x'],off['y']]])
            faces.append((vertices,uv,sh['shadingMode']=='fullbright'))
    return faces

def render(entry,size=320,yaw=35,pitch=23):
    model=json.loads((COMMON/entry['model']).read_text());atlas=np.asarray(Image.open(COMMON/entry['texture']).convert('RGBA'))
    # CubeWithModel's native stone host is supplied by the engine, outside its
    # custom-model JSON. Compose that same textured cube for post-finalize previews.
    if entry.get('previewHostNode') and not any(n['name']=='host_rock' for n in model['nodes']):
        model['nodes'].append(entry['previewHostNode'])
    faces=geometry(model)
    a,b=math.radians(yaw),math.radians(pitch);cam=np.array([math.sin(a)*math.cos(b),math.sin(b),math.cos(a)*math.cos(b)]);right=np.array([math.cos(a),0,-math.sin(a)]);up=np.cross(cam,right)
    mat=np.array([right,-up,cam]);pts=np.concatenate([f[0]@mat.T for f in faces]);lo=pts[:,:2].min(0);hi=pts[:,:2].max(0);scale=(size-24)/max(hi-lo);center=(lo+hi)/2
    out=np.zeros((size,size,4),dtype=np.uint8);depth=np.full((size,size),-1e9);light=np.array([-.3,.85,.42]);light/=np.linalg.norm(light)
    for verts,uv,glow in faces:
        normal=np.cross(verts[1]-verts[0],verts[2]-verts[0]);normal/=np.linalg.norm(normal)
        if np.dot(normal,cam)<=0:continue
        vv=verts@mat.T; vv[:,:2]=(vv[:,:2]-center)*scale+size/2
        shade=1.0 if glow else .65+.35*max(0,np.dot(normal,light))
        for ix in ((0,1,2),(0,2,3)):
            v=vv[list(ix)];t=uv[list(ix)];x0=max(0,int(v[:,0].min()));x1=min(size-1,int(math.ceil(v[:,0].max())));y0=max(0,int(v[:,1].min()));y1=min(size-1,int(math.ceil(v[:,1].max())))
            if x1<x0 or y1<y0:continue
            yy,xx=np.mgrid[y0:y1+1,x0:x1+1];den=(v[1,1]-v[2,1])*(v[0,0]-v[2,0])+(v[2,0]-v[1,0])*(v[0,1]-v[2,1])
            if abs(den)<1e-6:continue
            w0=((v[1,1]-v[2,1])*(xx-v[2,0])+(v[2,0]-v[1,0])*(yy-v[2,1]))/den;w1=((v[2,1]-v[0,1])*(xx-v[2,0])+(v[0,0]-v[2,0])*(yy-v[2,1]))/den;w2=1-w0-w1
            z=w0*v[0,2]+w1*v[1,2]+w2*v[2,2];mask=(w0>=-.001)&(w1>=-.001)&(w2>=-.001)&(z>depth[y0:y1+1,x0:x1+1]);u=np.clip((w0*t[0,0]+w1*t[1,0]+w2*t[2,0]).astype(int),0,atlas.shape[1]-1);tv=np.clip((w0*t[0,1]+w1*t[1,1]+w2*t[2,1]).astype(int),0,atlas.shape[0]-1)
            color=atlas[tv,u].copy();mask&=color[:,:,3]>0;color[:,:,:3]=(color[:,:,:3]*shade).astype(np.uint8);out[y0:y1+1,x0:x1+1][mask]=color[mask];depth[y0:y1+1,x0:x1+1][mask]=z[mask]
    return Image.fromarray(out)

def contact(entries,path,cols=5,cell=250):
    rows=math.ceil(len(entries)/cols);im=Image.new('RGB',(cols*cell,rows*(cell+36)),(17,25,42));d=ImageDraw.Draw(im)
    font=ImageFont.truetype('C:/Windows/Fonts/consola.ttf',13)
    for i,e in enumerate(entries):
        x=(i%cols)*cell;y=(i//cols)*(cell+36);d.rounded_rectangle((x+6,y+6,x+cell-6,y+cell+28),radius=10,fill=(23,34,53),outline=(52,71,91));pic=render(e,cell-18);im.paste(pic,(x+9,y+8),pic)
        label=e['sourceId'].replace('_',' ').title()
        words=label.split();lines=['']
        for word in words:
            trial=(lines[-1]+' '+word).strip()
            if d.textlength(trial,font=font)>cell-18:lines.append(word)
            else:lines[-1]=trial
        for li,line in enumerate(lines):d.text((x+cell/2,y+cell+2+li*13),line,font=font,fill=(190,218,224),anchor='mt')
    path.parent.mkdir(parents=True,exist_ok=True);im.save(path)

def validate(entries):
    total=0
    def walk(nodes):
        for n in nodes:
            yield n
            yield from walk(n.get('children',[]))
    for e in entries:
        model=json.loads((COMMON/e['model']).read_text());w,h=Image.open(COMMON/e['texture']).size
        assert w>=32 and h>=32 and w%32==0 and h%32==0,(e['id'],'native model textures require 32-pixel multiples')
        ids=set()
        for n in walk(model['nodes']):
            assert n['id'] not in ids;ids.add(n['id']);assert abs(sum(v*v for v in n['orientation'].values())-1)<1e-5
            if n.get('shape',{}).get('type') not in ('box','quad'):continue
            s=n['shape']['settings']['size'];assert all(v>0 for v in s.values())
            assert all(v>0 for v in n['shape']['stretch'].values())
            for face,layout in n['shape']['textureLayout'].items():
                fw,fh=(s['x'],s['z']) if face in ('top','bottom') else (s['z'],s['y']) if face in ('left','right') else (s['x'],s['y']);x,y=layout['offset'].values();assert x>=0 and y>=0 and x+fw<=w and y+fh<=h,(e['id'],face)
                total+=1
        assert Image.open(COMMON/e['icon']).size==(64,64),(e['id'],'item icons must be 64x64')
    return {'models':len(entries),'facesChecked':total,'status':'PASS','checks':['All source registrations covered','Positive dimensions','Normalized quaternion orientations','Per-face UV bounds','Model/texture/icon path resolution','Model atlases at least32x32 in 32-pixel multiples','All item icons exactly64x64']}

def main():
    inv=json.loads((OUT/'source-inventory.json').read_text(encoding='utf-8-sig'));entries=[]
    for name in sorted(set(inv['items']+inv['blocks']+['laboratory_bench'])):
        m=make_block(name) if name in inv['blocks'] or name=='laboratory_bench' else make_item(name);entries.append(m.save())
        if name=='resonant_conduit':
            path=COMMON/entries[-1]['model'];path.with_name('resonant_conduit_full.blockymodel').write_bytes(path.read_bytes())
    cores=[make_core(k).save() for k in ('gravity','energetic_rift','temporal_bloom','echoing_shadow','thoughtwell','warp_gate','warp_gate_cyan','warp_gate_purple')];entries+=cores
    (COMMON/'Icons/ItemsGenerated').mkdir(parents=True,exist_ok=True)
    for e in entries:
        pic=render(e,256);pic.resize((64,64),Image.Resampling.LANCZOS).save(COMMON/e['icon'])
    catalog={'unitsPerBlock':32,'items':[e for e in entries if not e['sourceId'].startswith('anomaly_') or e['sourceId'] in inv['items'] or e['sourceId']=='anomaly_spawner_marker'],'anomalyCores':cores}
    (OUT/'catalog.json').write_text(json.dumps(catalog,indent=2)+'\n')
    report=validate(entries);(OUT/'validation.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report))
    contact([e for e in entries if e['sourceId'] in MACHINES],ART/'machines.png')
    contact([e for e in entries if not e['block'] and e not in cores],ART/'equipment-materials.png',cols=6,cell=196)
    contact([e for e in entries if e['block'] and e['sourceId'] not in MACHINES],ART/'building-materials.png',cols=6,cell=196)
    contact(cores,ART/'anomaly-cores.png',cols=3,cell=320)
    selected=[e for e in entries if e['sourceId'] in ('research_machine','reality_forge','resonance_condenser','rift_stabilizer')]
    sheet=Image.new('RGB',(1280,len(selected)*320),(17,25,42))
    for row,e in enumerate(selected):
        for col,yaw in enumerate((35,125,215,305)):
            pic=render(e,320,yaw=yaw);sheet.paste(pic,(col*320,row*320),pic)
    sheet.save(ART/'machine-turnarounds.png')

if __name__=='__main__':main()
