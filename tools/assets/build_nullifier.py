"""Author only the Anomaly Nullifier assets. Never regenerate any other artwork.

--create refuses to overwrite existing nullifier art. --reset-art is an explicit
destructive reset of this device only and snapshots all replaced files first.
--preview renders CURRENT model and textures without rewriting production art.
--icon refreshes only the64 pixel icon from CURRENT powered model and texture.
Requires the established Pillow/numpy art runtime; validation uses stdlib Python.
"""
from pathlib import Path
import argparse, copy, datetime, hashlib, json, math, zipfile
import numpy as np
from PIL import Image, ImageDraw
import build_assets as art
import render_current_icons as render

ROOT=Path(__file__).resolve().parents[2]
RES=ROOT/'src/main/resources'; COMMON=RES/'Common'
NAME='anomaly_nullifier'; FOLDER=COMMON/'Blocks/StrangeMatter'
PARTICLES=RES/'Server/Particles/StrangeMatter'
SOUND=RES/'Server/Audio/SoundEvents/StrangeMatter/SM_Nullifier_Hum_SFX.json'
ICON=COMMON/'Icons/ItemsGenerated/SM_Anomaly_Nullifier.png'
OWNED=[FOLDER/(NAME+s) for s in ('.blockymodel','.png','_working.png','_working.blockyanim')]+[
    ICON, PARTICLES/'SM_Nullifier_Field.particlesystem',
    PARTICLES/'Spawners/SM_Nullifier_Collapse.particlespawner',
    PARTICLES/'Spawners/SM_Nullifier_Motes.particlespawner',SOUND]
PAINT=art.paint

def read(path):return json.loads(path.read_text(encoding='utf-8-sig'))
def write(path,data):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8')

def snapshot():
    stamp=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    folder=ROOT/'build/art-preservation'/('nullifier-'+stamp);folder.mkdir(parents=True)
    before={p.relative_to(COMMON).as_posix():hashlib.sha256(p.read_bytes()).hexdigest()
            for p in COMMON.rglob('*') if p.is_file()}
    write(folder/'Common-before.json',before)
    with zipfile.ZipFile(folder/'Nullifier-before.zip','w',zipfile.ZIP_DEFLATED) as archive:
        for p in OWNED:
            if p.exists():archive.write(p,p.relative_to(RES).as_posix())
    return folder,before

def paint(w,h,mat,seed,working=False):
    if mat in ('coil_light','core_light','signal'):
        bright={'coil_light':'cyan','core_light':'purple','signal':'cyan'}[mat]
        image=PAINT(w,h,bright if working else 'edge',seed)
        draw=ImageDraw.Draw(image)
        if w>=3 and h>=3:
            color=(216,255,248,255) if working and mat!='core_light' else (216,176,255,255) if working else (82,122,148,255)
            draw.line((1,1,max(1,w-2),1),fill=color)
        return image
    if mat=='dial' and w>=6 and h>=6:
        image=PAINT(w,h,'dark',seed);d=ImageDraw.Draw(image)
        d.ellipse((1,1,w-2,h-2),outline=(106,143,159,255))
        d.arc((2,2,w-3,h-3),185,350,fill=(70,225,229,255) if working else (69,104,126,255))
        d.line((w//2,h//2,w-3,2 if working else h-3),fill=(203,232,221,255))
        return image
    if mat=='inlay':
        image=PAINT(w,h,'dark',seed);d=ImageDraw.Draw(image)
        for x in range(2,w-1,5):d.line((x,1,x,h-2),fill=(46,83,111,255))
        d.line((1,h//2,w-2,h//2),fill=(68,136,151,255))
        return image
    return PAINT(w,h,mat,seed)

def group(model,name,children,position):
    chosen=[n for n in model['nodes'] if n['name'] in children]
    model['nodes']=[n for n in model['nodes'] if n['name'] not in children]
    for n in chosen:
        n['position']={k:n['position'][k]-position[i] for i,k in enumerate('xyz')}
        n['shape']['settings']['isStaticBox']=False
    model['nodes'].append({'id':name,'name':name,'position':art.V(position),'orientation':art.quat(),
      'shape':{'type':'none','offset':art.V((0,0,0)),'stretch':art.V((1,1,1)),
               'settings':{'isPiece':False},'visible':True},'children':chosen})

def model_art():
    m=art.Model(NAME,True)
    # Low isolated plinth leaves a genuine open cage above it. Front is +Z.
    for x in (-10.7,10.7):
        for z in (-10.7,10.7):
            m.box('isolation_foot_'+str(x)+'_'+str(z),(x,1.2,z),(5,2.4,5),'dark')
            m.box('foot_shoe_'+str(x)+'_'+str(z),(x,2.55,z),(5.2,.5,5.2),'steel')
    m.box('armoured_base',(0,4.25,0),(28,3.9,28),'navy')
    m.box('base_upper_lip',(0,6.65,0),(29,1,29),'edge')
    m.box('inset_deck',(0,7.28,0),(24,.25,24),'inlay')
    # A deliberate front gauge, recessed toggle and service screws read at 64px.
    m.box('front_instrument_housing',(0,6.7,12.8),(14,5.2,4),'navy',(-12,0,0))
    m.box('power_dial',(0,7.1,14.75),(6,6,.35),'dark',(-12,0,0),faces={'front':'dial'})
    m.box('power_toggle_bezel',(5.2,6.65,14.6),(2.7,3.2,.4),'dark')
    m.box('power_toggle',(5.2,7.0,15),(1.2,1.7,.8),'steel',(0,0,-18))
    m.box('status_lamp',(-5.2,7.2,14.75),(1.7,1.3,.6),'signal',glow=True)
    for x in (-11.7,11.7):m.box('front_screw_'+str(x),(x,4.35,14.17),(1,1,.3),'steel')
    m.box('rear_service_plate',(0,4.55,-14.13),(14,2.4,.25),'vent')
    # Three inward facing pole assemblies frame the contained, visible core.
    for i,angle in enumerate((60,180,300)):
        a=math.radians(angle);sx,cz=math.sin(a),math.cos(a)
        def at(r,y):return (r*sx,y,r*cz)
        yaw=angle
        m.box('pylon_socket_'+str(i),at(10.25,8.2),(6,2,6),'dark',(0,yaw,0))
        m.box('pylon_'+str(i),at(11,15),(4,13,5),'navy',(0,yaw,0))
        m.box('pylon_back_binding_'+str(i),at(13.62,15),(2,10,.65),'steel',(0,yaw,0))
        m.box('inward_conductor_'+str(i),at(8.35,15.8),(2.1,10,.5),'coil_light',(0,yaw,0),glow=True)
        for j,y in enumerate((12,16,20)):
            m.box('induction_band_'+str(i)+'_'+str(j),at(11,y),(4.8,.8,5.8),'edge',(0,yaw,0))
        m.box('emitter_shoulder_'+str(i),at(10.25,22.5),(6,3,6.2),'navy',(0,yaw,0))
        m.box('emitter_cap_'+str(i),at(10.25,24.35),(5.5,.6,5.7),'steel',(0,yaw,0))
        m.box('inward_nozzle_'+str(i),at(6.9,21.9),(3.9,2.7,2.1),'dark',(0,yaw,0))
        m.box('emitter_lens_'+str(i),at(5.72,21.9),(2.8,1.9,.3),'coil_light',(0,yaw,0),glow=True)
    # Segmented annulus with deliberate gaps prevents coplanar box overlap.
    rotor=[]
    for i in range(12):
        a=2*math.pi*i/12
        for suffix,r,y,t,mat in (('shoe',5.4,19.3,1.6,'dark'),('trace',5.4,20.28,.75,'coil_light')):
            name='rotor_'+suffix+'_'+str(i);rotor.append(name)
            m.box(name,(r*math.sin(a),y,r*math.cos(a)),(2.35,.65,t),mat,(0,math.degrees(a),0),glow=mat=='coil_light')
    # Suspended fracture clamp: a dark diamond nested in a violet split housing.
    core=[]
    for name,pos,size,mat,rot in (
        ('core_lower_clamp',(0,15.5,0),(4,1.1,4),'edge',(0,45,0)),
        ('core_upper_clamp',(0,24.0,0),(4,1.1,4),'edge',(0,45,0)),
        ('core_diamond',(0,19.8,0),(3.4,3.4,3.4),'core_light',(0,45,45)),
        ('core_dark_face',(0,19.8,1.87),(1.7,1.7,.35),'dark',(0,0,45))):
        core.append(name);m.box(name,pos,size,mat,rot,glow=mat=='core_light')
    m.box('lower_focus_mount',(0,9,0),(7,3,7),'dark',(0,45,0))
    m.box('lower_focus_coil',(0,11,0),(5,1,5),'edge',(0,45,0))
    m.box('lower_focus_tip',(0,12.3,0),(2,1.5,2),'core_light',(0,45,0),glow=True)
    # Side ventilation and rivets complete the back and both side views.
    for side in (-1,1):
        m.box('side_vent_'+str(side),(side*14.15,4.35,0),(.25,2.5,12),'vent')
        for z in (-10,10):m.box('side_rivet_'+str(side)+'_'+str(z),(side*14.3,4.4,z),(.3,1,1),'steel')
    art.paint=lambda w,h,mat,seed:paint(w,h,mat,seed,False)
    try:entry=m.save()
    finally:art.paint=PAINT
    working=Image.open(FOLDER/(NAME+'.png')).convert('RGBA')
    for index,node in enumerate(m.nodes):
        mat,overrides=m.materials[index];size=node['shape']['settings']['size'];x,y,z=(size[k] for k in 'xyz')
        for face,w,h in [('front',x,y),('back',x,y),('left',z,y),('right',z,y),('top',x,z),('bottom',x,z)]:
            face_mat=overrides.get(face,mat)
            if face_mat not in ('coil_light','core_light','signal','dial'):continue
            tile=paint(w,h,face_mat,f'{NAME}/{index}/{face}',True);uv=node['shape']['textureLayout'][face]['offset'];px,py=uv['x'],uv['y']
            working.paste(tile,(px,py));working.paste(tile.crop((0,0,w,1)),(px,py-1));working.paste(tile.crop((0,h-1,w,h)),(px,py+h))
            working.paste(tile.crop((0,0,1,h)),(px-1,py));working.paste(tile.crop((w-1,0,w,h)),(px+w,py))
    working.save(FOLDER/(NAME+'_working.png'))
    model=read(FOLDER/(NAME+'.blockymodel'))
    group(model,'Nullifier_Rotor',rotor,(0,20,0));group(model,'Nullifier_Core',core,(0,19.8,0))
    write(FOLDER/(NAME+'.blockymodel'),model)
    empty=lambda:{k:[] for k in ('position','orientation','shapeStretch','shapeVisible','shapeUvOffset')}
    rotor_track=empty();core_track=empty()
    rotor_track['orientation']=[{'time':i*60,'delta':art.quat((0,i*90,0)),'interpolationType':'linear'} for i in range(5)]
    core_track['position']=[{'time':t,'delta':art.V((0,y,0)),'interpolationType':'smooth'} for t,y in ((0,0),(60,.38),(120,0),(180,-.38),(240,0))]
    write(FOLDER/(NAME+'_working.blockyanim'),{'formatVersion':1,'duration':240,'holdLastKeyframe':False,
          'nodeAnimations':{'Nullifier_Rotor':rotor_track,'Nullifier_Core':core_track}})
    mesh=render.model_faces(model,working);points=np.concatenate([f[0] for f in mesh])
    entry.update({'boundsModelUnits':{'min':points.min(0).round(5).tolist(),'max':points.max(0).round(5).tolist()},
      'workingTexture':'Blocks/StrangeMatter/'+NAME+'_working.png','workingAnimation':'Blocks/StrangeMatter/'+NAME+'_working.blockyanim',
      'animationFrames':240,'animationSeconds':8,'front':'+Z','particle':'SM_Nullifier_Field',
      'particleOriginBlock':[.5,.65,.5],'particlePulseSeconds':1,'sound':'SM_Nullifier_Hum_SFX',
      'soundClipSeconds':8,'soundLooping':True,'soundMaxDistance':7,'previewYaw':35,'previewPitch':23})
    write(ROOT/'tools/assets/nullifier.json',entry)
    # The inventory shows the powered apparatus. Render current authored UVs.
    render.render(mesh,256,35,23).resize((64,64),Image.Resampling.LANCZOS).save(ICON)
    return entry

def span(value):return {'Min':value,'Max':value}
def effects():
    ring=read(PARTICLES/'Spawners/SM_Condenser_Receipt_Ring.particlespawner')
    ring.update({'LifeSpan':.1,'ParticleLifeSpan':{'Min':.65,'Max':.8},'ParticleRotationInfluence':'None','ParticleRotateWithSpawner':False})
    initial=ring['Particle']['InitialAnimationFrame'];initial['Scale']={'X':span(.14),'Y':span(.14)}
    initial['Rotation']={'X':span(90),'Y':span(0),'Z':span(0)}
    ring['Particle']['Animation']={'0':{'Color':'#81edf1','Opacity':0,'Scale':{'X':span(1),'Y':span(1)}},
      '15':{'Opacity':.38},'75':{'Opacity':.26},'100':{'Opacity':0,'Scale':{'X':span(.16),'Y':span(.16)},'Rotation':{'Z':span(35)}}}
    motes=read(PARTICLES/'Spawners/SM_Warp_Bolt_Cyan_Motes.particlespawner')
    motes.update({'LifeSpan':.7,'TotalParticles':span(6),'MaxConcurrentParticles':6,'ParticleLifeSpan':{'Min':.3,'Max':.48},
      'SpawnRate':span(8),'SpawnBurst':False,'TrailSpawnerPositionMultiplier':0,'TrailSpawnerRotationMultiplier':0})
    motes['EmitOffset']={k:{'Min':-.17,'Max':.17} for k in 'XZ'};motes['EmitOffset']['Y']={'Min':-.12,'Max':.12}
    motes['InitialVelocity']={'Speed':{'Min':.025,'Max':.06},'Yaw':{'Min':-180,'Max':180},'Pitch':{'Min':-90,'Max':90}}
    motes['Particle']['InitialAnimationFrame']['Scale']={'X':span(.009),'Y':span(.009)}
    motes['Particle']['Animation']['0'].update({'Color':'#b992ff','Opacity':.5})
    write(PARTICLES/'Spawners/SM_Nullifier_Collapse.particlespawner',ring)
    write(PARTICLES/'Spawners/SM_Nullifier_Motes.particlespawner',motes)
    write(PARTICLES/'SM_Nullifier_Field.particlesystem',{'Spawners':[{'SpawnerId':'SM_Nullifier_Collapse'},{'SpawnerId':'SM_Nullifier_Motes'}],
      'LifeSpan':1.25,'CullDistance':28,'BoundingRadius':.8,'IsImportant':False})
    write(SOUND,{'AudioCategory':'AudioCat_Ambient','SpatialBlend':1,'StartAttenuationDistance':1,'MaxDistance':7,'MaxInstance':3,'Volume':-22,
      'Layers':[{'Files':['Sounds/StrangeMatter/condenser_working_hum.ogg'],'Looping':True,'Volume':0}]})

def preview():
    model=read(FOLDER/(NAME+'.blockymodel'));panels=[]
    for label,yaw,pitch,active in [('Idle front',0,14,False),('Working front',0,14,True),('Working three quarter',35,23,True),
                                  ('Rear service panel',215,23,True),('Top and cage',35,55,True),('Right profile',90,12,True)]:
        texture=Image.open(FOLDER/(NAME+('_working' if active else '')+'.png'))
        panels.append((label,render.render(render.model_faces(model,texture),400,yaw,pitch)))
    sheet=Image.new('RGBA',(1200,880),(13,21,34,255));draw=ImageDraw.Draw(sheet)
    for i,(label,im) in enumerate(panels):
        x=i%3*400;y=i//3*440;sheet.alpha_composite(im,(x,y));draw.text((x+18,y+409),label,fill=(217,236,245))
    path=ROOT/'docs/art/anomaly-nullifier.png';path.parent.mkdir(parents=True,exist_ok=True);sheet.save(path)
    print(path)

def main():
    parser=argparse.ArgumentParser(description=__doc__);group_args=parser.add_mutually_exclusive_group()
    group_args.add_argument('--create',action='store_true');group_args.add_argument('--reset-art',action='store_true')
    parser.add_argument('--preview',action='store_true');parser.add_argument('--icon',action='store_true');args=parser.parse_args()
    if args.create or args.reset_art:
        if args.create:
            existing=[p for p in OWNED if p.exists()]
            if existing:raise SystemExit('Nullifier art already exists. Use --preview or explicitly request --reset-art after preserving manual edits.')
        folder,before=snapshot();entry=model_art();effects()
        ours={p.relative_to(COMMON).as_posix() for p in OWNED if p.is_relative_to(COMMON)}
        changed=[rel for rel,digest in before.items() if rel not in ours and hashlib.sha256((COMMON/rel).read_bytes()).hexdigest()!=digest]
        assert not changed,'Unrelated artwork changed during scoped generation: '+str(changed)
        write(folder/'result.json',{'unrelatedCommonFilesChecked':len(before)-len(ours.intersection(before)),
          'unrelatedCommonChanges':changed,'newDevice':entry})
        print('Nullifier created. Preservation snapshot:',folder)
    if args.preview:preview()
    if args.icon:
        faces=render.model_faces(read(FOLDER/(NAME+'.blockymodel')),Image.open(FOLDER/(NAME+'_working.png')))
        render.render(faces,256,35,23).resize((64,64),Image.Resampling.LANCZOS).save(ICON)
        print('Current model icon:',ICON)
    if not(args.create or args.reset_art or args.preview or args.icon):parser.print_help()

if __name__=='__main__':main()
