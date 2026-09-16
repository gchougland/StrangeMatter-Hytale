"""Targeted authoring for the gadget-energy set. Never regenerates existing artwork.

--create refuses existing new artwork. Existing burner geometry and painted pixels
are preserved while a distinct side dock is appended. --preview reads current art.
Run only for initial authoring; subsequent manual edits are authoritative.
"""
from pathlib import Path
import argparse, copy, ctypes, json, math
import numpy as np
from PIL import Image, ImageDraw
import build_assets as art
import render_current_icons as render

ROOT=Path(__file__).resolve().parents[2]; RES=ROOT/'src/main/resources'; COMMON=RES/'Common'
ITEMS=RES/'Server/Item/Items/StrangeMatter'; PARTICLES=RES/'Server/Particles/StrangeMatter'
NAMES=('resonant_charging_station','resonant_battery_pack','gravitic_manipulator','arc_projector')
IDS=['SM_'+''.join(s.title()+'_' for s in n.split('_')).rstrip('_') for n in NAMES]
POWERED_IDS=['SM_'+name for name in ('Field_Scanner','Echo_Vacuum','Anomaly_Resonator','Warp_Gun','Chrono_Blister',
    'Graviton_Hammer','Hoverboard','Echoform_Imprinter','Resonant_Battery_Pack','Gravitic_Manipulator','Arc_Projector')]
def read(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def write(p,data):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8')
def span(n):return {'Min':n,'Max':n}
def empty(name,children,piece=False):
    return {'id':name,'name':name,'position':art.V((0,0,0)),'orientation':art.quat(),
      'shape':{'type':'none','offset':art.V((0,0,0)),'stretch':art.V((1,1,1)),
               'settings':{'isPiece':piece},'visible':True},'children':children}
def ring(m,name,p,r,mat='copper',axis='z',count=10,thickness=1,depth=1):
    # Each ring segment is separated by a true gap, including transformed end faces.
    for i in range(count):
        a=i*math.tau/count;length=2*(r-thickness/2)*math.tan(math.pi/count)-.28
        if axis=='z':pos=(p[0]+r*math.sin(a),p[1]+r*math.cos(a),p[2]);size=(length,thickness,depth);rot=(0,0,-math.degrees(a))
        else:pos=(p[0]+r*math.sin(a),p[1],p[2]+r*math.cos(a));size=(length,depth,thickness);rot=(0,math.degrees(a),0)
        m.box(name+str(i),pos,size,mat,rot,glow=mat in ('cyan','purple'))
def finish(m,attachment=None):
    entry=m.save();path=COMMON/entry['model'];model=read(path)
    if attachment:
        for node in model['nodes']:node['shape']['settings']['isStaticBox']=False
        model['nodes']=[empty(attachment,model['nodes'],True)]
        if attachment=='Chest':
            # Match native chest armor exports while retaining the authored geometry and UVs.
            piece=model['nodes'][0];piece['id']='1000'
            piece['shape'].update({'doubleSided':False,'shadingMode':'flat','unwrapMode':'custom','textureLayout':{}})
            model.pop('format',None);model['lod']='auto'
        write(path,model)
    return entry

def station():
    m=art.Model(NAMES[0],True)
    for x in (-10.6,10.6):
        for z in (-10.6,10.6):
            m.box('RubberFoot',(x,1.35,z),(4.6,2.7,4.6),'dark')
            m.box('FootShoe',(x,3.05,z),(5,.6,5),'steel')
    m.box('ArmouredBase',(0,5.7,0),(28,4.4,27),'navy')
    m.box('BaseLip',(0,8.35,0),(28.7,.8,27.7),'edge')
    m.box('CeramicDeck',(0,9.2,0),(24,.8,23),'white')
    m.box('DockRecess',(0,9.78,0),(11.7,.3,15),'dark')
    for x in (-8.5,8.5):
        m.box('GuideRail',(x,10.5,0),(2.3,1.5,19),'steel')
        m.box('CopperContact',(x*.65,10.1,0),(1.2,.3,11),'copper')
        m.box('CeramicClamp',(x,13,-5),(4,4,6),'white')
        m.box('ClampCap',(x,15.35,-5),(4.2,.5,6.2),'navy')
        m.box('ClampLight',(x,13,-1.8),(2.4,1.2,.3),'cyan',glow=True)
    m.box('PowerTower',(0,16,-9.4),(12,13,6),'navy')
    m.box('PowerTowerTop',(0,23.05,-9.4),(13,.75,7),'steel')
    m.box('CellWindow',(0,16.5,-6.15),(7,7.8,.35),'dark')
    for y in (13.8,16.2,18.6):m.box('StoredCharge',(0,y,-5.84),(5,1.1,.22),'cyan',glow=True)
    for x in (-5.2,5.2):m.box('TowerCopperLead',(x,16.1,-6.18),(.65,10,.5),'copper')
    m.control((0,6.6,13.95),9,3,'screen')
    m.box('ConduitSocket',(0,5,-14.15),(7,3.4,1),'dark')
    m.box('ConduitContact',(0,5,-14.85),(4,1.4,.3),'copper')
    return finish(m)

def pack():
    m=art.Model(NAMES[1])
    # Native Chest piece is centered on the torso; rear surface is Z=-9.5.
    m.box('Backplate',(0,0,-11.2),(22,22,2),'navy')
    m.box('BackplateTop',(0,11.4,-11.2),(23,.6,2.5),'steel')
    m.box('BackplateBottom',(0,-11.4,-11.2),(23,.6,2.5),'edge')
    for x in (-6,6):
        m.box('CellBody',(x,0,-16.4),(7.4,18.2,7.4),'dark')
        m.box('CellChargeWindow',(x,0,-20.26),(4.2,14,.3),'cyan',glow=True)
        for y in (-10,10):
            m.box('CeramicCellCap',(x,y,-16.4),(8,1.3,8),'white')
            m.box('CopperCollar',(x,y*.8,-16.4),(8.5,.8,8.5),'copper')
        for dx in (-4.3,4.3):m.box('CellCage',(x+dx,0,-19.6),(.65,17,1),'edge')
        for y in (-4.9,4.9):m.box('CellCageCrossbar',(x,y,-20.55),(8.2,.75,.45),'navy')
        m.box('ShoulderStrap',(x,2,10.65),(3.1,18,.9),'dark')
        m.box('StrapOverShoulder',(x,11.65,.2),(3.1,.9,22),'dark')
        m.box('StrapCopperBuckle',(x,-3.8,11.28),(3.5,3.1,.3),'copper')
        m.box('StrapBuckleInset',(x,-3.8,11.52),(1.8,1.4,.14),'dark')
    m.box('CentralPowerBus',(0,0,-15),(2,17,2),'copper')
    m.box('Controller',(0,-4,-20.8),(5.7,7,2.4),'navy')
    m.box('ReserveGauge',(0,-3.5,-22.2),(3.3,4.7,.3),'screen')
    m.box('ChargeConnector',(0,9,-20.6),(3.8,2.6,2),'steel')
    m.box('ConnectorLight',(0,9,-21.8),(2,1.1,.3),'cyan',glow=True)
    return finish(m,'Chest')

def gun_base(name,color):
    m=art.Model(name)
    m.box('TexturedGrip',(0,0,-4.3),(4.7,12,5.7),'dark',(-14,0,0))
    m.box('GripHeel',(0,-6.6,-2.8),(5.8,1.1,6.8),'steel',(-14,0,0))
    m.box('Receiver',(0,8.5,-.5),(10,8,16),'navy')
    m.box('ReceiverTop',(0,12.98,-.5),(10.6,.7,15),'edge')
    m.box('RearCap',(0,8.5,-9.05),(9,6.8,.8),'steel')
    for x in (-5.2,5.2):
        m.box('SideInlay',(x,8.5,-1),(.3,4,8),'dark')
        m.box('SideEnergyRail',(x*1.035,8.5,-1),(.15,1,6),color,glow=True)
        m.box('RearSideBolt',(x,10.5,-6.5),(.45,1,1),'copper')
    m.box('TriggerGuard',(0,1.5,1),(1.2,5.5,1.2),'copper',(-20,0,0))
    m.box('Trigger',(0,4,-.2),(1,3,1),'steel',(-15,0,0))
    m.box('Sight',(0,14,-3),(2,1.1,5),'dark')
    return m

def gravity():
    m=gun_base(NAMES[2],'purple')
    m.box('EmitterShoulder',(0,8.5,9.05),(11,9,3),'edge')
    ring(m,'CopperInduction',(0,8.5,11),5,'copper',thickness=.9,depth=1.6)
    ring(m,'VioletField',(0,8.5,13),4.4,'purple',thickness=.5,depth=.6)
    m.box('FloatingCore',(0,8.5,15.5),(3.7,3.7,3.7),'purple',(20,35,30),glow=True)
    for i in range(3):
        a=i*math.tau/3; x=6*math.sin(a);y=8.5+6*math.cos(a)
        m.box('EmitterClaw'+str(i),(x,y,14.8),(2.3,2.3,10),'navy',(0,0,-math.degrees(a)))
        m.box('ClawCeramic'+str(i),(x,y,20.35),(2.6,2.6,1),'white',(0,0,-math.degrees(a)))
        m.box('ClawCopper'+str(i),(x*.75,8.5+(y-8.5)*.75,21.5),(2,2,2.5),'copper',(0,0,-math.degrees(a)))
    return finish(m,'R-Attachment')

def arc():
    m=gun_base(NAMES[3],'cyan')
    m.box('CoilHousing',(0,8.5,9.05),(8.5,7.5,3),'dark')
    for z in (10.5,12.5,14.5):ring(m,'Winding'+str(z),(0,8.5,z),4,'copper',count=8,thickness=1,depth=1)
    for side in (-1,1):
        x=side*4.3
        m.box('SplitEmitter',(x,8.5,20.1),(2.6,3.3,12),'navy')
        for z in (17,20,23):m.box('CeramicInsulator',(x,8.5,z),(3.9,4.5,1.1),'white')
        m.box('CopperElectrode',(x,8.5,26.2),(2.4,2.8,2.5),'copper')
        m.box('LiveElectrodeTip',(x,8.5,27.8),(1.8,2.1,.5),'cyan',glow=True)
    m.box('UpperCoolingRail',(0,13.7,8),(3,1,13),'steel')
    m.box('HighVoltageMarker',(0,14.35,7),(2,.22,6),'warning')
    return finish(m,'R-Attachment')

def burner_dock():
    modelpath=COMMON/'Blocks/StrangeMatter/resonant_burner.blockymodel';texpath=modelpath.with_suffix('.png')
    original=read(modelpath);assert not any(n['name']=='GadgetDock' for n in original['nodes']),'Dock already authored'
    m=art.Model('resonant_burner_gadget_dock',True)
    m.box('DockBracket',(15.8,12,0),(3.5,4,10),'edge')
    m.box('DockTray',(19.6,11.2,0),(6.8,1.2,12),'navy')
    m.box('DockInset',(19.7,12,0),(4.6,.3,8),'dark')
    for z in (-4.7,4.7):
        m.box('DockInsulator',(19.6,13.8,z),(5,3.4,1.6),'white')
        m.box('DockContact',(19.6,15.7,z),(3.8,.35,1.2),'copper')
    m.box('DockIndicator',(23.2,11.5,0),(.35,.8,4),'cyan',glow=True)
    entry=m.save();dock=read(COMMON/entry['model']);old=Image.open(texpath).convert('RGBA');new=Image.open(COMMON/entry['texture']).convert('RGBA')
    width=max(old.width,new.width);height=2**math.ceil(math.log2(old.height+new.height))
    combined=Image.new('RGBA',(width,height));combined.paste(old,(0,0));combined.paste(new,(0,old.height))
    for i,node in enumerate(dock['nodes']):
        node['id']='GadgetDock'+str(i)
        for face in node['shape']['textureLayout'].values():face['offset']['y']+=old.height
    original['nodes'].append(empty('GadgetDock',dock['nodes']));write(modelpath,original);combined.save(texpath)
    # The retained standalone dock is also useful for authored-model preservation audits.
    return entry

def items():
    for name,itemid in zip(NAMES,IDS):
        block=name==NAMES[0];packitem=name==NAMES[1]
        item={'TranslationProperties':{'Name':f'server.items.{itemid}.name','Description':f'server.items.{itemid}.description'},
          'Icon':f'Icons/ItemsGenerated/{itemid}.png','MaxStack':25 if block else 1,
          'Categories':['Furniture.Benches' if block else 'Items.Armors' if packitem else 'Items','SM_StrangeMatter.All','SM_StrangeMatter.Machines' if block else 'SM_StrangeMatter.Gadgets'],
          'PlayerAnimationsId':'Block' if block or packitem else 'Item','Quality':'Uncommon','Tags':{'Type':['StrangeMatter']},
          'SubCategory':'SM_PowerEquipment' if block else 'SM_PersonalEquipment' if packitem else 'SM_PoweredTools'}
        action=lambda a:{'Interactions':[{'Type':'SM_Use','Action':a}],'RequireNewClick':True}
        if block:
            item['BlockType']={'BlockEntity':{'Components':{'SM_Factory':{}}},'Material':'Solid','DrawType':'Model','Opacity':'Transparent',
              'CustomModel':f'Blocks/StrangeMatter/{name}.blockymodel','CustomModelTexture':[{'Texture':f'Blocks/StrangeMatter/{name}.png','Weight':1}],
              'HitboxType':itemid,'VariantRotation':'NESW','Gathering':{'Breaking':{'GatherType':'Rocks'}},'BlockParticleSetId':'Stone','ParticleColor':'#243950',
              'BlockSoundSetId':'Stone','PhysicalMaterialId':'Stone','Interactions':{'Use':action('machine')},'InteractionHint':f'server.interactionHints.{itemid}'}
            item['Interactions']={'Primary':'Block_Primary','Secondary':'Block_Secondary','Use':action('machine')}
            item['Recipe']={'Input':[{'ItemId':i,'Quantity':q} for i,q in [('Ingredient_Bar_Iron',4),('Ingredient_Bar_Copper',2),('SM_Resonite_Ingot',2),('SM_Resonant_Coil',1)]],
              'OutputQuantity':1,'TimeSeconds':2,'BenchRequirement':[{'Type':'Crafting','Id':'SM_Laboratory','Categories':['SM_Laboratory_All']}],'KnowledgeRequired':True}
            write(RES/f'Server/Item/Block/Hitboxes/StrangeMatter/{itemid}.json',{'Boxes':[{'Min':{'X':.045,'Y':0,'Z':.02},'Max':{'X':.96,'Y':.75,'Z':1}}]})
        else:
            item.update({'Model':f'Items/StrangeMatter/{name}.blockymodel','Texture':f'Items/StrangeMatter/{name}.png'})
            if packitem:
                item['Tags']['Type'].append('Armor')
                item.update({'Armor':{'ArmorSlot':'Chest','BaseDamageResistance':0,'CosmeticsToHide':['Cape']},'Interactions':{k:{'Interactions':[{'Type':'EquipItem'}]} for k in ('Primary','Secondary')}})
            elif name==NAMES[2]:item['Interactions']={'Primary':action('gravity_launch'),'Secondary':action('gravity_grab')}
            else:item['Interactions']={'Primary':{'Interactions':[{'Type':'SM_Use','Action':'arc_fire','Next':{'Type':'Simple','RunTime':.2}}],
                       'RequireNewClick':False,'Cooldown':{'Id':'SM_Arc_Projector_Pulse','Cooldown':.2,'ClickBypass':False}}}
        write(ITEMS/(itemid+'.json'),item)
    for old in ('SM_Echo_Vacuum','SM_Warp_Gun','SM_Chrono_Blister','SM_Graviton_Hammer'):
        p=ITEMS/(old+'.json');item=read(p);item.pop('MaxDurability',None)
        if 'Tool' in item:item['Tool'].pop('DurabilityLossBlockTypes',None)
        write(p,item)

def effects():
    effectfolder=RES/'Server/Entity/Effects/StrangeMatter'
    hold=read(effectfolder/'SM_Stasis_Hold.json');hold['Duration']=.35;hold.pop('Invulnerable',None)
    hold['ApplicationEffects'].update({'EntityBottomTint':'#604096','EntityTopTint':'#c59dff','HorizontalSpeedMultiplier':0})
    write(effectfolder/'SM_Gravitic_Hold.json',hold)
    slow=read(effectfolder/'SM_Temporal_Slow.json');slow['Duration']=.35
    slow['ApplicationEffects'].update({'EntityBottomTint':'#326888','EntityTopTint':'#a7f9ff','HorizontalSpeedMultiplier':.65})
    write(effectfolder/'SM_Arc_Slow.json',slow)
    configs=[('Gravitic_Acquire','#b895ff',.23,5),('Gravitic_Launch','#d8bbff',.33,7),('Gravitic_Suspension','#aa80ec',.14,2),
             ('Arc_Muzzle','#bafaff',.08,3),('Arc_Impact','#e8ffff',.14,5),('Gadget_Charge','#7bedf4',.08,2)]
    for name,color,size,count in configs:
        base=read(PARTICLES/'Spawners/SM_Condenser_Receipt_Ring.particlespawner')
        base['ParticleLifeSpan']={'Min':.18,'Max':.32};base['Particle']['InitialAnimationFrame']['Scale']={'X':span(size),'Y':span(size)}
        base['Particle']['Animation']={'0':{'Color':color,'Opacity':.5},'100':{'Opacity':0,'Scale':{'X':span(.15),'Y':span(.15)}}}
        if name=='Gravitic_Suspension':
            base['ParticleLifeSpan']={'Min':.3,'Max':.4};base['ParticleRotationInfluence']='None'
            base['Particle']['InitialAnimationFrame']['Rotation']={'X':span(80),'Y':span(20),'Z':span(0)}
        write(PARTICLES/f'Spawners/SM_{name}_Ring.particlespawner',base)
        sparks=read(PARTICLES/'Spawners/SM_Chrono_Impact_Sparks.particlespawner')
        sparks['TotalParticles']=span(count);sparks['MaxConcurrentParticles']=count;sparks['SpawnRate']=span(count)
        sparks['EmitOffset']={k:{'Min':-.1,'Max':.1} for k in 'XYZ'}
        sparks['Particle']['InitialAnimationFrame']['Color']=color
        for frame in sparks['Particle'].get('Animation',{}).values():
            if 'Color' in frame:frame['Color']=color
        write(PARTICLES/f'Spawners/SM_{name}_Sparks.particlespawner',sparks)
        write(PARTICLES/f'SM_{name}.particlesystem',{'Spawners':[{'SpawnerId':f'SM_{name}_Ring'},{'SpawnerId':f'SM_{name}_Sparks'}],
          'LifeSpan':.4,'CullDistance':48,'BoundingRadius':1.5,'IsImportant':False})

def no_wear():
    # Native numeric stack fields hold RE without opting into repair, combat or death wear.
    for itemid in POWERED_IDS:
        path=ITEMS/(itemid+'.json');item=read(path)
        item.pop('MaxDurability',None)
        item.update({'Repairable':False,'DurabilityLossOnDeath':False,'DurabilityLossOnHit':0})
        if 'Tool' in item:item['Tool'].pop('DurabilityLossBlockTypes',None)
        write(path,item)

def audio():
    class Info(ctypes.Structure):
        _fields_=[('frames',ctypes.c_int64),('samplerate',ctypes.c_int),('channels',ctypes.c_int),('format',ctypes.c_int),('sections',ctypes.c_int),('seekable',ctypes.c_int)]
    lib=ctypes.CDLL('C:/Program Files/Audacity/sndfile.dll');lib.sf_open.argtypes=[ctypes.c_char_p,ctypes.c_int,ctypes.POINTER(Info)];lib.sf_open.restype=ctypes.c_void_p
    lib.sf_writef_double.argtypes=[ctypes.c_void_p,ctypes.POINTER(ctypes.c_double),ctypes.c_int64];lib.sf_writef_double.restype=ctypes.c_int64
    lib.sf_close.argtypes=[ctypes.c_void_p];lib.sf_close.restype=ctypes.c_int
    for i,(name,seconds) in enumerate((('Gravitic_Acquire',.35),('Gravitic_Launch',.55),('Gravitic_Suspension',.35),('Arc_Muzzle',.16),('Arc_Impact',.13),('Gadget_Charge',.6),('Charging_Hum',2))):
        if name.startswith('Arc'):continue # Authored electrical transients and variations below.
        rate=48000;t=np.arange(round(rate*seconds))/rate;rng=np.random.default_rng(710+i)
        env=(1-np.exp(-t*260))*np.exp(-t*(12 if name.startswith('Arc') else 7))*np.clip((seconds-t)*90,0,1)
        if name=='Charging_Hum':
            signal=(.06*np.sin(math.tau*100*t)+.018*np.sin(math.tau*201*t)+.007*np.sin(math.tau*401*t))*(.9+.1*np.cos(math.tau*t))
        else:
            phase=math.tau*((130+i*24)*t+(150 if name=='Gravitic_Acquire' else -50)*t*t)
            signal=env*(.16*np.sin(phase)+.065*np.sin(phase*2.01)+.025*np.sin(phase*4.03))
        path=COMMON/f'Sounds/StrangeMatter/GadgetEnergy/{name.lower()}.ogg';path.parent.mkdir(parents=True,exist_ok=True)
        info=Info(0,rate,1,0x200060,0,0);handle=lib.sf_open(str(path).encode(),0x20,ctypes.byref(info));assert handle
        try:assert lib.sf_writef_double(handle,signal.ctypes.data_as(ctypes.POINTER(ctypes.c_double)),len(signal))==len(signal)
        finally:assert lib.sf_close(handle)==0
        write(RES/f'Server/Audio/SoundEvents/StrangeMatter/SM_{name}_SFX.json',{'AudioCategory':'AudioCat_SFX','SpatialBlend':1,
          'StartAttenuationDistance':1.5,'MaxDistance':6 if name=='Charging_Hum' else 14 if name.startswith('Arc') else 10,'MaxInstance':4 if name=='Charging_Hum' else 8,'Volume':-16 if name=='Charging_Hum' else -8 if name=='Gravitic_Suspension' else -4,
          'Layers':[{'Files':[str(path.relative_to(COMMON)).replace('\\','/')],**({'Looping':True} if name=='Charging_Hum' else {})}]})
    import refine_arc_audio
    refine_arc_audio.author()

def charging_motion():
    path=COMMON/'Blocks/StrangeMatter/resonant_charging_station.blockymodel';model=read(path)
    animation={'formatVersion':1,'duration':60,'holdLastKeyframe':False,'nodeAnimations':{}}
    for index,node in enumerate(model['nodes']):
        if node['name'] not in ('ClampLight','StoredCharge'):continue
        node['name']+='_'+str(index);node['shape']['settings']['isStaticBox']=False
        track={key:[] for key in ('position','orientation','shapeStretch','shapeVisible','shapeUvOffset')}
        track['shapeStretch']=[{'time':t,'delta':art.V((x,1,1)),'interpolationType':'smooth'} for t,x in ((0,1),(15,.76),(30,1),(45,.86),(60,1))]
        animation['nodeAnimations'][node['name']]=track
    write(path,model);write(path.with_name('resonant_charging_station_working.blockyanim'),animation)
    itempath=ITEMS/'SM_Resonant_Charging_Station.json';item=read(itempath)
    item['BlockType']['State']={'Definitions':{'Working':{'Looping':True,'CustomModelAnimation':'Blocks/StrangeMatter/resonant_charging_station_working.blockyanim',
      'CustomModelAnimationSpeed':1,'AmbientSoundEventId':'SM_Charging_Hum_SFX','Particles':[{'SystemId':'SM_Gadget_Charge','PositionOffset':{'X':0,'Y':.4,'Z':0},'Scale':.5}]}}}
    write(itempath,item)

def preview():
    entries=[]
    for name,itemid in zip(NAMES,IDS):
        item=read(ITEMS/(itemid+'.json'));faces=render.item_faces(item)
        yaw=155 if name==NAMES[1] else 35
        render.render(faces,256,yaw=yaw).resize((64,64),Image.Resampling.LANCZOS).save(COMMON/item['Icon'])
        entries.append((itemid.removeprefix('SM_').replace('_',' '),faces))
    burner=read(ITEMS/'SM_Resonant_Burner.json');faces=render.item_faces(burner)
    render.render(faces,256).resize((64,64),Image.Resampling.LANCZOS).save(COMMON/burner['Icon']);entries.append(('Burner side dock',faces))
    render.contact(entries,ROOT/'docs/art/gadget-energy-set.png',cols=3,cell=360,views={'Resonant Battery Pack':{'yaw':155,'pitch':15}})
    player=read(render.NATIVE/'Characters/Player_With_Face.blockymodel');body=render.model_faces(player,Image.open(render.NATIVE/'Characters/Player_Textures/Player_Greyscale.png'))
    item=read(ITEMS/'SM_Resonant_Battery_Pack.json');fitted=render.attachment_faces(read(COMMON/item['Model']),Image.open(COMMON/item['Texture']),player)
    sheet=Image.new('RGBA',(1200,600),(17,25,42,255))
    for i,yaw in enumerate((0,145,220)):
        pic=render.render(body+fitted,400,yaw=yaw,pitch=10);sheet.alpha_composite(pic,(400*i,50))
    sheet.convert('RGB').save(ROOT/'docs/art/battery-pack-fitting.png')

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--create',action='store_true');parser.add_argument('--preview',action='store_true');args=parser.parse_args()
    if args.create:
        for name in NAMES:
            assert not list(COMMON.glob(f'*/StrangeMatter/{name}.blockymodel')),f'Existing {name} art is authoritative; use --preview only'
        entries=[station(),pack(),gravity(),arc()];dock=burner_dock();items();no_wear();effects();audio();charging_motion()
        write(ROOT/'tools/assets/gadget-energy-set.json',{'items':entries,'burnerDock':dock,'mainArcEffect':'SM_Stabilizer_Link','modelUnitsPerBlock':32,
          'preservedResourcesSnapshot':'build/art-preservation/20260911T223853988404Z/Resources.zip','notes':'Native per-face painted UVs; true segmented-ring gaps; native Chest and R-Attachment pieces.'})
    if args.create or args.preview:preview()

if __name__=='__main__':main()
