"""Create only the new power and automation artwork.

--create refuses to overwrite any existing generated model. --preview and --icons
read current saved art. --reset-new-set explicitly replaces this set only after
archiving it. Requires Pillow/numpy; optional ffmpeg creates original mono audio.
No existing Strange Matter model, texture, icon or central content file is edited.
"""
from pathlib import Path
import argparse, copy, ctypes, datetime, hashlib, json, math, random, subprocess, sys, wave, zipfile
import numpy as np
from PIL import Image, ImageDraw
import build_assets as art
import render_current_icons as renderer
import factory_pickup_assets

ROOT=Path(__file__).resolve().parents[2]; RES=ROOT/'src/main/resources'; COMMON=RES/'Common'
FOLDER='Blocks/StrangeMatter/Automation'; ITEMFOLDER='Items/StrangeMatter/Concentrates'
ITEMS=RES/'Server/Item/Items/StrangeMatter'; HITBOXES=RES/'Server/Item/Block/Hitboxes/StrangeMatter'
PARTICLES=RES/'Server/Particles/StrangeMatter'; REPORT=ROOT/'tools/assets/automation-set.json'
COLORS={'navy':(31,44,68),'dark':(13,23,38),'edge':(66,88,115),'steel':(126,153,169),
 'copper':(172,103,65),'ceramic':(201,215,210),'cyan':(68,224,233),'purple':(156,99,227),
 'amber':(249,176,67),'screen':(14,43,59),'vent':(24,33,48),'warning':(212,161,61),
 'deck':(37,66,80),'rubber':(21,25,35),'trace':(77,142,160),'polished':(160,190,199)}
METALS={'Copper':(190,111,67),'Iron':(173,186,194),'Gold':(237,182,58),'Thorium':(95,195,139),
 'Cobalt':(79,115,213),'Adamantite':(188,73,81),'Mithril':(106,210,216),'Silver':(203,216,230),
 'Onyxium':(105,80,150),'Prisma':(224,140,208),'Resonite':(74,211,226)}
MACHINES=[('Resonant_Separator',1,1.5),('Flux_Furnace',2,1),('Pattern_Assembler',3,1.5)]

def read(path):return json.loads(path.read_text(encoding='utf-8-sig'))
def write(path,data):
    path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8')
def span(v):return {'Min':v,'Max':v}

def paint_tile(name,base,size=64,working=False):
    rng=random.Random('SM automation '+name); im=Image.new('RGBA',(size,size));pixels=im.load()
    glow=name in ('cyan','purple','amber')
    for y in range(size):
        for x in range(size):
            value=3*math.sin(x*.23+y*.05)+2*math.sin(y*.31)+rng.choice((-2,-1,0,0,1,2))
            factor=(.97 if working else .48) if glow else 1
            pixels[x,y]=tuple(max(0,min(255,round((v+value)*factor))) for v in base)+(255,)
    d=ImageDraw.Draw(im)
    if name in ('navy','dark','edge','steel','copper','ceramic','polished'):
        for _ in range(size//2):
            x,y=rng.randrange(size),rng.randrange(size);delta=rng.choice((-8,-4,6,10))
            d.line((x,y,min(size-1,x+rng.randrange(2,9)),y),fill=tuple(max(0,min(255,c+delta)) for c in base))
    if name=='vent':
        for y in range(3,size,5):
            d.line((0,y,size,y),fill=(70,91,113));d.line((0,y+1,size,y+1),fill=(9,15,25),width=2)
    if name=='warning':
        for x in range(-size,size*2,12):d.polygon([(x,0),(x+5,0),(x+5-size,size),(x-size,size)],fill=(29,35,45))
    if name=='screen':
        for x in range(0,size,6):d.line((x,0,x,size),fill=(20,64,77))
        for y in range(0,size,6):d.line((0,y,size,y),fill=(20,64,77))
        d.line([(x,3+round(math.sin(x*.85))) for x in range(2,size-2)],fill=(81,233,232) if working else (62,129,142))
        d.line([(x,10+round(4*math.sin(x*.7))) for x in range(2,size-2)],fill=(81,233,232) if working else (62,129,142))
        d.line([(x,24+round(2*math.sin(x*.4))) for x in range(2,size-2)],fill=(181,131,239) if working else (86,75,120))
        for x in range(3,size-5,9):d.rectangle((x,34,x+4,36),fill=(139,183,185))
    if name=='deck':
        for x in range(4,size,8):d.line((x,0,x,size),fill=(48,83,96))
        for y in range(4,size,8):d.line((0,y,size,y),fill=(48,83,96))
        d.rectangle((8,8,size-9,size-9),outline=(79,127,140))
    if glow:
        for y in range(3,size,8):d.line((0,y,size,y),fill=tuple(min(255,round(c*(1.04 if working else .6))) for c in base))
    return im

def atlas(colors=COLORS,size=64,columns=8,working=False):
    rows=math.ceil(len(colors)/columns);im=Image.new('RGBA',(columns*size,rows*size))
    for i,(name,color) in enumerate(colors.items()):im.paste(paint_tile(name,color,size,working),((i%columns)*size,(i//columns)*size))
    return im

class Model:
    def __init__(self,name,materials=None,tile=64,columns=8):
        self.name=name;self.nodes=[];self.serial=0;self.materials=list(materials or COLORS);self.tile=tile;self.columns=columns
    def group(self,name,position=(0,0,0),rotation=(0,0,0),parent=None):
        self.serial+=1;n={'id':str(self.serial),'name':name,'position':art.V(position),'orientation':art.quat(rotation),
          'shape':{'type':'none','offset':art.V((0,0,0)),'stretch':art.V((1,1,1)),'settings':{'isPiece':False},'visible':True},'children':[]}
        (self.nodes if parent is None else parent['children']).append(n);return n
    def box(self,name,pos,size,material='navy',rot=(0,0,0),parent=None,faces=None,glow=False):
        self.serial+=1;dims=[max(1,math.ceil(x)) for x in size];layout={}
        for face in ('front','back','left','right','top','bottom'):
            mat=(faces or {}).get(face,material);idx=self.materials.index(mat)
            w,h=(dims[0],dims[2]) if face in ('top','bottom') else (dims[2],dims[1]) if face in ('left','right') else (dims[0],dims[1])
            assert w<=self.tile-2 and h<=self.tile-2,(name,size,face)
            # Reuse painted swatches with correct signed mirror origin and >=1px gutters.
            ox=(idx%self.columns)*self.tile+2;oy=(idx//self.columns)*self.tile+2
            mirror=face=='back';layout[face]={'offset':{'x':ox+(w if mirror else 0),'y':oy},'mirror':{'x':mirror,'y':False},'angle':0}
        n={'id':str(self.serial),'name':name,'position':art.V(pos),'orientation':art.quat(rot),
          'shape':{'type':'box','offset':art.V((0,0,0)),'stretch':art.V([x/d for x,d in zip(size,dims)]),
            'settings':{'size':art.V(dims),'isPiece':False,'isStaticBox':parent is None},'textureLayout':layout,
            'unwrapMode':'custom','visible':True,'doubleSided':False,'shadingMode':'fullbright' if glow else 'standard'}}
        (self.nodes if parent is None else parent['children']).append(n);return n
    def ring(self,name,pos,radius,material='copper',axis='z',count=12,parent=None,thickness=1.5,depth=2):
        for i in range(count):
            a=i*math.tau/count;length=2*radius*math.tan(math.pi/count)-.24
            if axis=='z':p=(pos[0]+radius*math.sin(a),pos[1]+radius*math.cos(a),pos[2]);s=(length,thickness,depth);r=(0,0,-math.degrees(a))
            else:p=(pos[0]+radius*math.sin(a),pos[1],pos[2]+radius*math.cos(a));s=(length,depth,thickness);r=(0,math.degrees(a),0)
            self.box(name+str(i),p,s,material,r,parent,glow=material in ('cyan','purple','amber'))
    def base(self,width=28,depth=28,height=9,center=0):
        for x in (-width/2+3,width/2-3):
            for z in (-depth/2+3,depth/2-3):
                self.box('IsolationFoot',(center+x,1.4,z),(4.5,2.8,4.5),'rubber')
                self.box('FootCollar',(center+x,3.0,z),(4.8,.6,4.8),'steel')
        self.box('ArmouredPlinth',(center,4+height/2,0),(width,height,depth),'navy')
        self.box('BaseLowerLip',(center,4,0),(width+.6,.8,depth+.6),'edge')
        self.box('DeckRim',(center,4+height,0),(width+.6,.8,depth+.6),'steel')
        self.box('DeckInset',(center,4.6+height,0),(width-4,.3,depth-4),'deck')
        for sign in (-1,1):
            self.box('SideServiceVent',(center+sign*(width/2+.18),7,0),(.3,3.5,depth-10),'vent')
            self.box('CornerPlate',(center+sign*(width/2-1.4),4+height/2,depth/2+.15),(1.6,height-2,.4),'edge')
        self.box('PowerSocket',(center,6.7,-depth/2-.5),(6,4,1),'dark')
        self.box('PowerSocketContact',(center,6.7,-depth/2-1.06),(3.5,2,.25),'copper')
    def panel(self,pos,width=12,height=7,parent=None):
        x,y,z=pos;self.box('InstrumentBezel',(x,y,z),(width+2,height+2,1.5),'dark',parent=parent)
        self.box('InstrumentScreen',(x,y,z+.85),(width,height,.3),'screen',parent=parent,glow=True)
        self.box('InstrumentLip',(x,y+height/2+1,z+.55),(width+2,.6,1),'steel',parent=parent)
    def data(self):return {'formatVersion':1,'nodes':self.nodes}

def separator():
    m=Model('resonant_separator');m.base(height=8)
    # Two small trays have real floors and raised sides, not overlapping top planes.
    for x in (-6,6):
        m.box('DividedTrayFloor',(x,13.4,7),(10,.6,10),'dark')
        for dx in (-5,5):m.box('TraySide',(x+dx,14.3,7),(.6,1.3,10),'steel')
        m.box('TrayFront',(x,14.3,12),(10,1.3,.6),'edge')
    for side in (-1,1):
        m.box('ResonancePillar',(side*11.7,26,-3),(4.5,25,6),'navy')
        m.box('PillarEdge',(side*14.1,26,-3),(.7,22,4),'steel')
        m.box('CeramicShoulder',(side*10.8,33,-3),(4,8,6.4),'ceramic')
        for y in (19,22,25):m.box('InductionBand',(side*11.7,y,-3),(5.2,1.1,6.7),'copper')
        jaw=m.group('Separator_Jaw_'+('Left' if side<0 else 'Right'),(side*7.8,29,-1))
        m.box('JawCarriage',(0,0,0),(4.8,9,7),'edge',parent=jaw)
        m.box('TuningFace',(-side*2.6,0,.2),(.8,6,5.3),'cyan',parent=jaw,glow=True)
        for z in (-2,2):m.box('TuningProng',(-side*3.6,0,z),(1.4,3.2,1.2),'ceramic',parent=jaw)
    m.box('UpperBridge',(0,42,-3),(27,3,7),'navy')
    m.box('BridgeWarning',(0,43.6,-2.4),(20,.5,5),'warning')
    m.box('SamplePedestal',(0,18,-2),(5,5,5),'edge')
    m.box('SampleFocus',(0,21,-2),(4.5,1,4.5),'cyan',rot=(0,45,0),glow=True)
    rotor=m.group('Separator_Ring',(0,30,0))
    m.ring('SuspensionRing',(0,0,0),8,'edge',parent=rotor,thickness=1.3,depth=1.5)
    m.ring('VioletTrace',(0,0,1.0),7.7,'purple',parent=rotor,thickness=.65,depth=.5)
    sample=m.group('Separator_Sample',(0,29,-1))
    m.box('SuspendedSample',(0,0,0),(4,5,4),'ceramic',(15,30,20),parent=sample)
    m.box('SuspendedVein',(1,1,1.8),(1.4,3,.6),'cyan',(0,10,-20),parent=sample,glow=True)
    m.panel((-6,8.3,14.2),8,3)
    m.box('FrontSwitch',(6.5,8.2,14.8),(2.2,2.2,1),'purple',glow=True)
    m.box('FlowArrow',(10,8.2,14.65),(1.3,1.3,.5),'cyan',(0,0,45),glow=True)
    return m

def furnace(tier=1):
    m=Model('flux_furnace'+('' if tier==1 else '_tier2'));m.base(height=6)
    m.box('InsulatedChamber',(0,19,-2),(23,16,20),'navy')
    for side in (-1,1):
        m.box('CeramicCheek',(side*10.5,20,0),(2.5,15,17),'ceramic')
        m.box('ChamberCorner',(side*12.1,18,-8),(1.1,13,1.2),'steel')
    # Visible crucible recess, surrounded by radial copper windings.
    m.box('ChamberRecess',(0,20,8.15),(16,12,.5),'dark')
    m.box('HotCrucible',(0,18,8.6),(11,6,.35),'amber',glow=True)
    for x in (-4,-2,0,2,4):m.box('CrucibleLip',(x,16,9.1),(1.25,1.4,.55),'copper')
    m.ring('InductionCoil',(0,20.4,9),9.2,'copper',count=12,thickness=2.2,depth=2.5)
    m.ring('CeramicCoilSeparator',(0,20.4,7.8),9.2,'ceramic',count=12,thickness=1.2,depth=.5)
    shutters=m.group('Furnace_Shutters',(0,21,10.65))
    for x in (-6,6):
        m.box('HeatShutter',(x,0,0),(2.3,10,.8),'navy',(0,0,-10 if x<0 else 10),parent=shutters)
        m.box('ShutterHandle',(x,0,.6),(1,3,.5),'steel',parent=shutters)
    fan=m.group('Furnace_Fan',(0,28,-2))
    m.ring('ExhaustFrame',(0,0,0),5,'edge','y',8,fan,1.2,1)
    for a in (0,90):m.box('FanBlade',(0,0,0),(7,.5,1.7),'dark',(0,a,0),parent=fan)
    m.box('FanHub',(0,.45,0),(2,.5,2),'steel',parent=fan)
    m.box('RearServiceBezel',(0,19,-12.3),(16,10,.5),'edge')
    m.box('RearVentInset',(0,19,-12.65),(13,7,.25),'vent')
    for x in (-7,7):
        for y in (15,23):m.box('RearServiceFastener',(x,y,-12.72),(1,1,.3),'steel')
    m.panel((-6,6.6,14.25),7,2.5)
    m.box('ThermalGauge',(6,6.7,14.65),(5,1.3,.4),'amber',glow=True)
    if tier==2:
        for side in (-1,1):
            m.box('TierTwoCapacitor',(side*12.4,18.5,-1),(3,13,9),'edge')
            for y in (14,17,20,23):m.box('TierTwoCoolingFin',(side*13,y,-1),(3,1,9.8),'copper')
            m.box('TierTwoPowerStrip',(side*14.55,18.5,1),(.3,9,2),'cyan',glow=True)
        m.box('TierTwoBadge',(7.4,27.4,8.8),(3,2,.6),'purple',glow=True)
    return m

def assembler(tier=1):
    m=Model('pattern_assembler'+('' if tier==1 else '_tier'+str(tier)));m.base(60,28,10,16)
    m.box('CeramicWorkSurface',(16,15,0),(58,1,25),'ceramic')
    m.box('AssemblyPlate',(16,15.8,2),(28,.6,18),'deck')
    for x in (3,29):m.box('PlateEmitter',(x,16.3,2),(1,.6,17),'cyan',glow=True)
    m.box('PlateFrontTrace',(16,16.3,10),(25,.6,1),'purple',glow=True)
    for x in (-9,41):
        m.box('GantryFoot',(x,17,-7),(6,3,8),'dark')
        m.box('GantryPillar',(x,29,-7),(4,23,5),'navy')
        m.box('GantryRail',(x,29,-4.2),(1,20,.6),'steel')
        for y in (22,27,32):m.box('PillarBand',(x,y,-7),(4.8,.8,5.8),'copper')
    m.box('Crossbeam',(16,41,-7),(56,4,7),'navy')
    m.box('CrossbeamSlide',(16,39,-3),(49,1.4,1.4),'polished')
    m.box('CrossbeamTop',(16,43.4,-7),(56,.7,7.5),'edge')
    carriage=m.group('Assembler_Carriage',(16,35,-1))
    m.box('ToolCarriage',(0,0,-4),(11,7,8),'edge',parent=carriage)
    m.box('ToolIndicator',(0,1,.2),(6,1.3,.4),'cyan',parent=carriage,glow=True)
    m.box('ToolCeramic',(0,-5,-1),(5,4,5),'ceramic',parent=carriage)
    tool=m.group('Assembler_Tool',(0,-9,-1),parent=carriage)
    m.box('ToolHead',(0,0,0),(4,4,4),'copper',parent=tool)
    m.box('ToolTip',(0,-2.7,0),(1.6,1.5,1.6),'purple',parent=tool,glow=True)
    m.panel((-6,22,9),9,6)
    m.box('ConsoleSupport',(-6,18,8),(10,3.5,8),'navy')
    for x in (37,43):
        m.box('PartTrayFloor',(x,16,5),(4.8,.5,12),'dark')
        m.box('PartTrayFront',(x,17,11),(4.8,1.4,.7),'edge')
        for y,z in ((17,1),(17,5)):m.box('TrayComponent',(x,y,z),(2,1.1,2),'copper')
    m.box('FrontIdentification',(16,9,14.25),(19,5,.3),'dark')
    for x in (10,16,22):m.box('FrontInstrumentation',(x,9,14.55),(3,1,.35),'cyan',glow=True)
    if tier>=2:
        for x in (-4,36):
            m.box('AuxiliaryCoil',(x,36,-7),(5,8,8),'copper')
            m.box('AuxiliaryCoilInsulator',(x,40.4,-7),(6,1,9),'ceramic')
            m.box('TierPowerLamp',(x,36,-2.65),(2,5,.5),'purple',glow=True)
    if tier>=3:
        m.box('TierThreeControlRack',(16,28,-12),(17,11,3),'navy')
        m.panel((16,28,-10),13,7)
        m.box('TierThreeAerial',(30,38,-10),(1,10,1),'steel')
        m.box('TierThreeAerialTip',(30,43.5,-10),(2,1.5,2),'cyan',glow=True)
    return m

DIRECTIONS=((1,0,0),(-1,0,0),(0,1,0),(0,-1,0),(0,0,1),(0,0,-1))
def tube(mask):
    m=Model('gravitic_tube_connection'+str(mask).zfill(2))
    # A central open framework. Caps are only on the unconnected faces.
    for bit,axis in enumerate(DIRECTIONS):
        connected=bool(mask&(1<<bit));a=next(i for i,v in enumerate(axis) if v);other=[i for i in range(3) if i!=a]
        def point(distance,u=0,v=0):
            p=[0,16,0];p[a]+=axis[a]*distance;p[other[0]]+=u;p[other[1]]+=v;return p
        def sized(depth,u,v):
            s=[0,0,0];s[a]=depth;s[other[0]]=u;s[other[1]]=v;return s
        for u,v in ((-5.5,-5.5),(-5.5,5.5),(5.5,-5.5),(5.5,5.5)):
            m.box('Rail_'+str(bit),point(9.5 if connected else 3.5,u,v),sized(12.5 if connected else 5.5,1.1,1.1),'navy')
        # Four-piece square collar leaves the central view completely open.
        for distance in ((7.3,14.6) if connected else (6.7,)):
            for sign in (-1,1):
                m.box('Collar_'+str(bit),point(distance,sign*6.1,0),sized(1.5,1.2,13.4),'edge')
                m.box('Collar_'+str(bit),point(distance,0,sign*6.1),sized(1.5,11,1.2),'edge')
                m.box('Coil_'+str(bit),point(distance+.83,sign*6.1,0),sized(.25,.65,8),'purple',glow=True)
        if not connected:
            m.box('ClosedCollar_'+str(bit),point(6.9),sized(.6,10.5,10.5),'navy')
            m.box('ClosedIndicator_'+str(bit),point(7.25),sized(.15,1.7,4),'trace')
    return m

def concentrate():
    materials={'grain':0,'light':0,'shade':0,'tray':0};m=Model('metal_concentrate',materials,32,2)
    m.box('SampleTray',(0,1,0),(17,1.2,13),'tray')
    for x in (-8,8):m.box('TrayBinding',(x,1.8,0),(.8,1.4,13),'tray')
    rng=random.Random('SM concentrate')
    for i,(x,y,z,s) in enumerate(((-4,3,-2,5),(2,3,-3,5),(5,3,2,4),(-2,3,3,5),(0,5,0,5))):
        m.box('ConcentratedMineral'+str(i),(x,y,z),(s,s*.65,s*.8),'grain',(rng.randrange(-15,16),rng.randrange(360),rng.randrange(-12,13)),faces={'top':'light','back':'shade'})
    for i in range(7):m.box('FineGrain'+str(i),(rng.uniform(-6,6),2.2,rng.uniform(-4,4)),(1.2,1,1.3),'light',(0,rng.randrange(360),0))
    return m

def tracks():return {k:[] for k in ('position','orientation','shapeStretch','shapeVisible','shapeUvOffset')}
def keys(values):return [{'time':t,'delta':d,'interpolationType':'smooth'} for t,d in values]
def animation(kind):
    duration=120;nodes={}
    def pos(name,axis,values):
        track=tracks();track['position']=keys([(t,art.V([v if a==axis else 0 for a in 'xyz'])) for t,v in values]);nodes[name]=track
    def rotation(name,axis,values):
        track=tracks();track['orientation']=keys([(t,art.quat([v if a==axis else 0 for a in 'xyz'])) for t,v in values]);nodes[name]=track
    if kind=='resonant_separator':
        pos('Separator_Jaw_Left','x',[(0,0),(30,1.1),(60,.2),(90,1.1),(120,0)])
        pos('Separator_Jaw_Right','x',[(0,0),(30,-1.1),(60,-.2),(90,-1.1),(120,0)])
        rotation('Separator_Ring','z',[(0,0),(30,9),(60,0),(90,-9),(120,0)])
        pos('Separator_Sample','y',[(0,0),(30,.6),(60,0),(90,-.6),(120,0)])
    elif kind=='flux_furnace':
        rotation('Furnace_Fan','y',[(0,0),(30,90),(60,180),(90,270),(120,360)])
        pos('Furnace_Shutters','z',[(0,0),(30,.25),(60,0),(90,.25),(120,0)])
    else:
        pos('Assembler_Carriage','x',[(0,0),(22,-7),(48,-7),(72,7),(99,7),(120,0)])
        pos('Assembler_Tool','y',[(0,0),(27,0),(36,-2),(48,0),(78,0),(90,-2),(99,0),(120,0)])
    return {'formatVersion':1,'duration':duration,'holdLastKeyframe':False,'nodeAnimations':nodes}

def item_base(ident,model,texture,height,width=1,action='machine',stack=25):
    return {'TranslationProperties':{'Name':'server.items.'+ident+'.name','Description':'server.items.'+ident+'.description'},
      'Icon':'Icons/ItemsGenerated/'+ident+'.png','MaxStack':stack,'Categories':['Furniture.Benches','SM_StrangeMatter.All'],
      'PlayerAnimationsId':'Block','Quality':'Uncommon','Tags':{'Type':['StrangeMatter']},
      'BlockType':{'Material':'Solid','DrawType':'Model','Opacity':'Transparent','CustomModel':model,
        'CustomModelTexture':[{'Texture':texture,'Weight':1}],'HitboxType':ident,'VariantRotation':'NESW',
        'Gathering':{'Breaking':{'GatherType':'Rocks'}},'BlockParticleSetId':'Stone','ParticleColor':'#243950',
        'BlockSoundSetId':'Stone','PhysicalMaterialId':'Stone','InteractionHint':'server.interactionHints.'+ident,
        'Interactions':{'Use':{'Interactions':[{'Type':'SM_Use','Action':action}],'RequireNewClick':True}}},
      'Interactions':{'Primary':'Block_Primary','Secondary':'Block_Secondary'}}

def sound_event(clip,loop,volume=-22):
    return {'AudioCategory':'AudioCat_Ambient' if loop else 'AudioCat_SFX','SpatialBlend':1,
      'StartAttenuationDistance':1,'MaxDistance':7,'MaxInstance':3,'Volume':volume,
      'Layers':[{'Files':['Sounds/StrangeMatter/Automation/'+clip+'.ogg'],'Looping':loop,'Volume':0}]}

def create(ffmpeg):
    folder=COMMON/FOLDER;folder.mkdir(parents=True,exist_ok=True)
    atlas().save(folder/'automation.png');atlas(working=True).save(folder/'automation_working.png')
    entries=[];hints={}
    names={'Resonant_Separator':('Resonant Separator','Separates supported ore into twice the normal metal yield. Requires resonant power.'),
      'Flux_Furnace':('Flux Furnace','Smelts ordinary furnace recipes using resonant power instead of fuel.'),
      'Pattern_Assembler':('Pattern Assembler','Repeats a known Workbench recipe using stored ingredients and resonant power.')}
    for ident_suffix,tiers,height in MACHINES:
        ident='SM_'+ident_suffix;name=ident_suffix.lower();width=2 if tiers==3 else 1
        build=separator if tiers==1 else furnace if tiers==2 else assembler
        variants=[]
        for tier in range(1,tiers+1):
            model=build() if tiers==1 else build(tier);path=FOLDER+'/'+model.name+'.blockymodel';write(COMMON/path,model.data());variants.append(path)
        anim_path=FOLDER+'/'+name+'_working.blockyanim';write(COMMON/anim_path,animation(name))
        item=item_base(ident,variants[0],FOLDER+'/automation.png',height,width)
        block=item['BlockType'];block['BlockEntity']={'Components':{'SM_Factory':{}}};definitions={}
        if ident_suffix=='Pattern_Assembler':block['BlockEntity']['Components']['BenchBlock']={}
        for tier in range(1,tiers+1):
            if tier>1:definitions['Tier'+str(tier)]={'CustomModel':variants[tier-1],'CustomModelTexture':[{'Texture':FOLDER+'/automation.png','Weight':1}]}
            definitions['Working' if tier==1 else 'Tier'+str(tier)+'Working']={
              'CustomModel':variants[tier-1],'CustomModelTexture':[{'Texture':FOLDER+'/automation_working.png','Weight':1}],
              'CustomModelAnimation':anim_path,'CustomModelAnimationSpeed':1,'Looping':True,'AmbientSoundEventId':ident+'_Hum_SFX'}
        block['State']={'Definitions':definitions}
        factory_pickup_assets.configure_item(item,ident)
        write(ITEMS/(ident+'.json'),item)
        write(HITBOXES/(ident+'.json'),{'Boxes':[{'Min':{'X':0,'Y':0,'Z':0},'Max':{'X':width,'Y':height,'Z':1}}]})
        for suffix,loop in (('hum',True),('complete',False)):
            write(RES/'Server/Audio/SoundEvents/StrangeMatter'/(ident+('_Hum_SFX' if loop else '_Complete_SFX')+'.json'),sound_event(name+'_'+suffix,loop,-23 if loop else -18))
        label,description=names[ident_suffix];hints['server.interactionHints.'+ident]='[{key}] Open '+label.lower()
        entries.append({'id':ident,'name':label,'description':description,'research':{'Resonant_Separator':'resonant_separation','Flux_Furnace':'flux_smelting','Pattern_Assembler':'pattern_assembly'}[ident_suffix],
          'model':variants[0],'tierModels':variants,'texture':FOLDER+'/automation.png','workingTexture':FOLDER+'/automation_working.png',
          'animation':anim_path,'animationSeconds':4,'tiers':tiers,'boundsBlocks':[width,height,1],
          'hum':ident+'_Hum_SFX','completionSound':ident+'_Complete_SFX','workingParticle':ident+'_Work',
          'particleOriginBlock':([1,.65,.5] if tiers==3 else [.5,.92,.5] if tiers==1 else [.5,.65,.78])})
    for drop_id,document in factory_pickup_assets.drop_documents().items():
        write(RES/'Server/Drops/StrangeMatter'/(drop_id+'.json'),document)
    tube_id='SM_Gravitic_Tube';tube_models=[];defs={}
    for mask in range(64):
        m=tube(mask);path=FOLDER+'/'+m.name+'.blockymodel';write(COMMON/path,m.data());tube_models.append(path)
        # Native collision is the narrow central body plus only connected arms.
        boxes=[{'Min':{'X':.27,'Y':.27,'Z':.27},'Max':{'X':.73,'Y':.73,'Z':.73}}]
        for bit,d in enumerate(DIRECTIONS):
            if not mask&(1<<bit):continue
            lo=[.3,.3,.3];hi=[.7,.7,.7];axis=next(i for i,v in enumerate(d) if v)
            if d[axis]>0:lo[axis]=.5;hi[axis]=1
            else:lo[axis]=0;hi[axis]=.5
            boxes.append({'Min':dict(zip('XYZ',lo)),'Max':dict(zip('XYZ',hi))})
        hit=tube_id+'_Connection'+str(mask).zfill(2);write(HITBOXES/(hit+'.json'),{'Boxes':boxes})
        defs['Connection'+str(mask).zfill(2)]={'CustomModel':path,'HitboxType':hit,'InteractionHitboxType':hit}
    item=item_base(tube_id,tube_models[0],FOLDER+'/automation_working.png',1,action='machine',stack=100)
    item['BlockType'].update({'VariantRotation':'None','HitboxType':tube_id+'_Connection00','InteractionHitboxType':tube_id+'_Connection00','State':{'Definitions':defs}})
    item['BlockType']['BlockEntity']={'Components':{'SM_GraviticTube':{}}}
    item['Recipe']={'Input':[{'ItemId':'SM_Resonite_Nugget','Quantity':5},{'ItemId':'SM_Resonant_Coil','Quantity':1},{'ItemId':'SM_Gravitic_Shard','Quantity':1}],
      'OutputQuantity':10,'TimeSeconds':2,'KnowledgeRequired':True,'BenchRequirement':[{'Type':'Crafting','Id':'SM_Laboratory','Categories':['SM_Laboratory_All']}]}
    write(ITEMS/(tube_id+'.json'),item);hints['server.interactionHints.'+tube_id]='[{key}] Configure gravitic tube'
    entries.append({'id':tube_id,'name':'Gravitic Tube','description':'Moves items between configured containers through a suspended gravity field.',
      'research':'gravitic_transport','model':tube_models[0],'connectionModels':tube_models,'texture':FOLDER+'/automation_working.png','bitDirections':['+X','-X','+Y','-Y','+Z','-Z']})
    con=concentrate();con_path=ITEMFOLDER+'/metal_concentrate.blockymodel';write(COMMON/con_path,con.data())
    for metal,color in METALS.items():
        ident='SM_'+metal+'_Concentrate';colors={'grain':color,'light':tuple(min(255,c+30) for c in color),'shade':tuple(round(c*.62) for c in color),'tray':(86,109,126)}
        tex=ITEMFOLDER+'/'+metal.lower()+'_concentrate.png';atlas(colors,32,2,True).save(COMMON/tex)
        item={'TranslationProperties':{'Name':'server.items.'+ident+'.name','Description':'server.items.'+ident+'.description'},
          'Icon':'Icons/ItemsGenerated/'+ident+'.png','MaxStack':100,'Categories':['Items','SM_StrangeMatter.All'],'PlayerAnimationsId':'Item',
          'Quality':'Uncommon','Tags':{'Type':['StrangeMatter']},'Model':con_path,'Texture':tex}
        write(ITEMS/(ident+'.json'),item)
        entries.append({'id':ident,'name':metal+' Concentrate','description':'Separated '+metal.lower()+' mineral. Smelt one concentrate into one matching bar.',
          'research':'resonant_separation','model':con_path,'texture':tex,'metal':metal})
    write(REPORT,{'items':entries,'interactionHints':hints,'textureSize':[512,128],
      'modelUnitsPerBlock':32,'front':'+Z','constructionRecipes':'Central Forge recipes owned by Factory content integration.',
      'preservation':'Only new Automation and Concentrates resources. Current existing art remains authoritative.'})
    effects();audio(ffmpeg);icons();return entries

def effects():
    template=read(PARTICLES/'Spawners/SM_Nullifier_Motes.particlespawner')
    entries=[('SM_Resonant_Separator_Work','#70e6ec',9,.2),('SM_Flux_Furnace_Work','#ffd08d',5,.12),
      ('SM_Pattern_Assembler_Work','#b58bf5',6,.23),('SM_Tube_Field','#936dd4',3,.12),('SM_Tube_Transfer','#85e9eb',4,.07)]
    for ident,color,count,radius in entries:
        value=copy.deepcopy(template);value.update({'LifeSpan':.35,'TotalParticles':span(count),'MaxConcurrentParticles':count,
          'ParticleLifeSpan':{'Min':.25,'Max':.5},'SpawnRate':span(count),'SpawnBurst':True})
        value['EmitOffset']={a:{'Min':-radius,'Max':radius} for a in 'XYZ'}
        value['Particle']['Animation']['0'].update({'Color':color,'Opacity':.5})
        value['Particle']['InitialAnimationFrame']['Scale']={'X':span(.008),'Y':span(.008)}
        write(PARTICLES/'Spawners'/(ident+'_Motes.particlespawner'),value)
        write(PARTICLES/(ident+'.particlesystem'),{'Spawners':[{'SpawnerId':ident+'_Motes'}],'LifeSpan':.9,'CullDistance':24,'BoundingRadius':.65,'IsImportant':False})

def audio(ffmpeg):
    assert ffmpeg and Path(ffmpeg).is_file(),'Supply --ffmpeg or --sndfile to encode the new original positional sounds'
    out=COMMON/'Sounds/StrangeMatter/Automation';out.mkdir(parents=True,exist_ok=True);work=ROOT/'build/automation-art/audio';work.mkdir(parents=True,exist_ok=True)
    for index,name in enumerate(('resonant_separator','flux_furnace','pattern_assembler')):
        for suffix,seconds in (('hum',4),('complete',.38)):
            rate=48000;t=np.arange(round(rate*seconds))/rate;freq=(95,70,115)[index]
            if suffix=='hum':
                signal=(.08*np.sin(math.tau*freq*t)+.025*np.sin(math.tau*freq*2*t)+.011*np.sin(math.tau*(freq*3+2)*t))
                signal*=.85+.15*np.cos(math.tau*(1 if index<2 else 2)*t)
                # Deterministic integer-period partials make an unobtrusive seamless loop.
                for i in range(8):signal+=.001*np.sin(math.tau*(230+index*13+i*11)*t+.3*i)
            else:
                env=(1-np.exp(-t*180))*np.exp(-t*15);signal=env*(.11*np.sin(math.tau*(420+index*95)*t)+.04*np.sin(math.tau*(710+index*90)*t))
                signal*=np.minimum(1,(seconds-t)*80)
            wav=work/(name+'_'+suffix+'.wav')
            with wave.open(str(wav),'wb') as stream:
                stream.setnchannels(1);stream.setsampwidth(2);stream.setframerate(rate);stream.writeframes((signal*32767).astype('<i2').tobytes())
            target=out/(name+'_'+suffix+'.ogg')
            if str(ffmpeg).lower().endswith('.dll'):
                class Info(ctypes.Structure):
                    _fields_=[('frames',ctypes.c_int64),('samplerate',ctypes.c_int),('channels',ctypes.c_int),('format',ctypes.c_int),('sections',ctypes.c_int),('seekable',ctypes.c_int)]
                lib=ctypes.CDLL(ffmpeg);lib.sf_open.argtypes=[ctypes.c_char_p,ctypes.c_int,ctypes.POINTER(Info)];lib.sf_open.restype=ctypes.c_void_p
                lib.sf_writef_double.argtypes=[ctypes.c_void_p,ctypes.POINTER(ctypes.c_double),ctypes.c_int64];lib.sf_writef_double.restype=ctypes.c_int64
                lib.sf_close.argtypes=[ctypes.c_void_p];lib.sf_close.restype=ctypes.c_int
                info=Info(0,rate,1,0x200060,0,0);handle=lib.sf_open(str(target).encode(),0x20,ctypes.byref(info));assert handle,'Vorbis output could not open'
                try:assert lib.sf_writef_double(handle,signal.ctypes.data_as(ctypes.POINTER(ctypes.c_double)),len(signal))==len(signal)
                finally:assert lib.sf_close(handle)==0,'Vorbis output did not flush'
            else:subprocess.run([ffmpeg,'-hide_banner','-loglevel','error','-y','-i',str(wav),'-c:a','libvorbis','-q:a','4',str(target)],check=True)

def icons():
    for entry in read(REPORT)['items']:
        model=read(COMMON/entry['model']);texture=entry.get('workingTexture',entry['texture'])
        if entry['id']=='SM_Gravitic_Tube':model=read(COMMON/entry['connectionModels'][3])
        im=renderer.render(renderer.model_faces(model,Image.open(COMMON/texture)),256,35,23).resize((64,64),Image.Resampling.LANCZOS)
        target=COMMON/('Icons/ItemsGenerated/'+entry['id']+'.png');target.parent.mkdir(parents=True,exist_ok=True);im.save(target)

def preview():
    entries=read(REPORT)['items'];panels=[]
    for entry in entries[:3]:
        for tier,path in enumerate(entry['tierModels'],1):
            for label,yaw in [('front',35),('rear',215)]:
                panels.append((entry['name']+' '+str(tier)+' '+label,renderer.model_faces(read(COMMON/path),Image.open(COMMON/entry['workingTexture'])),yaw))
    tube_entry=entries[3]
    for mask in (0,3,5,21,63):panels.append(('Gravitic tube '+str(mask),renderer.model_faces(read(COMMON/tube_entry['connectionModels'][mask]),Image.open(COMMON/tube_entry['texture'])),35))
    columns=4;cell=300;sheet=Image.new('RGBA',(columns*cell,math.ceil(len(panels)/columns)*(cell+30)),(13,21,35,255));draw=ImageDraw.Draw(sheet)
    for i,(label,faces,yaw) in enumerate(panels):
        x=i%columns*cell;y=i//columns*(cell+30);sheet.alpha_composite(renderer.render(faces,cell-14,yaw,23),(x+7,y));draw.text((x+12,y+cell),label,fill=(210,233,241))
    target=ROOT/'docs/art/automation-machines.png';target.parent.mkdir(parents=True,exist_ok=True);sheet.save(target)
    small=Image.new('RGBA',(6*180,2*205),(13,21,35,255));d=ImageDraw.Draw(small)
    for i,entry in enumerate(entries[4:]):
        im=renderer.render(renderer.model_faces(read(COMMON/entry['model']),Image.open(COMMON/entry['texture'])),172)
        x=i%6*180;y=i//6*205;small.alpha_composite(im,(x+4,y));d.text((x+10,y+178),entry['name'],fill=(210,233,241))
    small.save(ROOT/'docs/art/automation-concentrates.png');print(target)
    sys.path.insert(0,str(ROOT/'tools'))
    from validate_automation_art import pose
    working=Image.new('RGBA',(3*360,3*390),(13,21,35,255));d=ImageDraw.Draw(working)
    for row,entry in enumerate(entries[:3]):
        model=read(COMMON/entry['model']);clip=read(COMMON/entry['animation'])
        for column,(label,frame,active) in enumerate((('Idle',0,False),('Working frame 30',30,True),('Working frame 90',90,True))):
            mesh=renderer.model_faces(pose(model,clip,frame),Image.open(COMMON/entry['workingTexture' if active else 'texture']))
            x=column*360;y=row*390;working.alpha_composite(renderer.render(mesh,346,35,23),(x+7,y));d.text((x+12,y+358),entry['name']+' '+label,fill=(210,233,241))
    working.save(ROOT/'docs/art/automation-working-poses.png')

def snapshot():
    stamp=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ');path=ROOT/'build/art-preservation'/('automation-'+stamp);path.mkdir(parents=True)
    before={p.relative_to(COMMON).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in COMMON.rglob('*') if p.is_file()}
    write(path/'Common-before.json',before)
    with zipfile.ZipFile(path/'Resources.zip','w',zipfile.ZIP_DEFLATED) as archive:
        for p in RES.rglob('*'):
            if p.is_file():archive.write(p,p.relative_to(RES).as_posix())
    return path,before

def main():
    parser=argparse.ArgumentParser(description=__doc__);g=parser.add_mutually_exclusive_group();g.add_argument('--create',action='store_true');g.add_argument('--reset-new-set',action='store_true')
    parser.add_argument('--ffmpeg');parser.add_argument('--sndfile',help='Native libsndfile DLL with Vorbis encoder');parser.add_argument('--preview',action='store_true');parser.add_argument('--icons',action='store_true');args=parser.parse_args()
    if args.create or args.reset_new_set:
        owned=COMMON/FOLDER
        if args.create and owned.exists() and any(owned.iterdir()):raise SystemExit('Automation art exists. Use current --preview/--icons; --reset-new-set is an explicit replacement.')
        folder,before=snapshot();create(args.sndfile or args.ffmpeg)
        changed=[p for p,h in before.items() if hashlib.sha256((COMMON/p).read_bytes()).hexdigest()!=h]
        if args.create:assert not changed,'Existing art unexpectedly changed: '+str(changed)
        write(folder/'result.json',{'previousCommonFiles':len(before),'changedPreviousCommon':changed,'explicitReset':args.reset_new_set})
        progress=ROOT/'build/automation-art-progress.md';progress.write_text('# Automation art progress\n\nNew set authored. Existing art snapshot: '+str(folder)+'\n\n'+str(len(before))+' prior Common files checked. Existing art changes: '+str(len(changed))+'.\n\nNative asset validation and rendered visual review pending.\n',encoding='utf-8')
        print('Snapshot:',folder)
    if args.icons:icons()
    if args.preview:preview()

if __name__=='__main__':main()
