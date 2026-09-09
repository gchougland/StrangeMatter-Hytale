"""Finite native Hytale particle systems; original analytic/pixel-painted sprites.

All systems can be emitted once per second without persistent orphan emitters.
Main anomaly shapes, inward/outward motion, glyphs and debris are separate layers.
The contact sheet is an art-direction composite, not a captured Hytale frame.
"""
from pathlib import Path
import json, math, random
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter
from build_assets import ROOT, COMMON, OUT, ART, P, render

SERVER=ROOT/'src/main/resources/Server/Particles/StrangeMatter'
TEXTURES=COMMON/'Particles/StrangeMatter'
R=lambda a,b=None:{'Min':a,'Max':a if b is None else b}
S=lambda x,y=None:{'X':R(x),'Y':R(x if y is None else y)}
OFF=lambda x,y,z:{'X':R(-x,x),'Y':R(-y,y),'Z':R(-z,z)}

def sprite(name,kind,n=128):
    yy,xx=np.mgrid[0:n,0:n];x=(xx+.5-n/2)/(n/2);y=(yy+.5-n/2)/(n/2);r=np.sqrt(x*x+y*y);a=np.arctan2(y,x)
    rgb=np.ones((n,n,3))*255
    if kind=='halo':alpha=np.exp(-((r-.57)/.1)**2)*.72+np.exp(-((r-.55)/.25)**2)*.13
    elif kind=='ring':alpha=np.exp(-((r-.70)/.014)**2)*.85+np.exp(-((r-.7)/.07)**2)*.21;alpha*=.70+.30*np.sin(a*7+r*2)**2
    elif kind=='vortex':
        alpha=(np.sin(a*4-r*27)*.5+.5)**11*np.exp(-((r-.53)/.32)**2)*.85
        alpha+=np.exp(-((r-.79)/.014)**2)*.8;alpha*=np.clip(r*6,0,1)
    elif kind=='smoke':
        billow=.15*np.sin(x*12+y*7)+.10*np.cos(y*18-x*3)+.07*np.sin(x*27+y*13)
        alpha=np.clip(1-(r+billow),0,1)**1.8
    elif kind=='shadow':
        alpha=np.clip((.73-r)*11,0,1);alpha+=np.exp(-((r-.76)/.07)**2)*.4
        rgb[:]=[10,14,31];edge=np.exp(-((r-.69)/.022)**2);rgb[:,:,0]+=edge*75;rgb[:,:,1]+=edge*30;rgb[:,:,2]+=edge*110
    elif kind=='spark':alpha=np.exp(-(x*x/.0025+y*y/.12))*1.3+np.exp(-(x*x+y*y)/.04)*.22
    elif kind=='fissure':
        path=.14*np.sin(y*13)+.06*np.sin(y*39);alpha=np.exp(-((x-path)/.018)**2)+np.exp(-((x-path)/.10)**2)*.3;alpha*=np.clip(1-abs(y),0,1)**.5
        branch=np.exp(-((x-(y*.8+.25))/.011)**2)*((y>-.4)&(y<.1))*.7;alpha+=branch
    elif kind=='star':alpha=np.exp(-(x*x+y*y)/.018)*.8+np.exp(-abs(x)*85-abs(y)*5)*.8+np.exp(-abs(y)*85-abs(x)*5)*.8
    elif kind=='petal':
        width=.15+.4*(1-y*y);alpha=(abs(x)<width)&(abs(y)<.85);rgb[:]=[190,198,237];rgb[:,:,0]+=np.clip(-x*70,0,60);rgb[:,:,1]+=np.clip(-x*70,0,50);rgb[:,:,2]=255
        alpha=alpha.astype(float)*np.clip((.88-abs(y))*9,0,1);alpha*=np.clip((width-abs(x))*25,0,1)
    elif kind=='debris':
        alpha=((abs(x)+abs(y)*.32)<.62)&((abs(y)+abs(x)*.30)<.64);rgb[:]=[92,104,132];rgb[y<x]=[55,65,86];rgb[y<-.24]=[127,137,161]
    elif kind in ('rune','rune_ring'):
        im=Image.new('RGBA',(n,n));d=ImageDraw.Draw(im)
        if kind=='rune':
            d.line([(35,25),(35,95),(88,95),(88,59),(60,59),(60,79)],fill=(255,255,255),width=5);d.line((35,42,81,42,65,25),fill='white',width=4);d.rectangle((86,23,95,32),fill='white')
        else:
            d.ellipse((14,14,114,114),outline=(255,255,255,215),width=2);d.ellipse((23,23,105,105),outline=(255,255,255,120),width=1)
            for i in range(16):
                angle=i*math.tau/16;cx=64+math.cos(angle)*46;cy=64+math.sin(angle)*46;d.line((cx-3,cy-4,cx-3,cy+4,cx+3,cy+4),fill='white',width=2)
            for i in range(4):angle=i*math.tau/4;cx=64+math.cos(angle)*29;cy=64+math.sin(angle)*29;d.polygon([(cx,cy-4),(cx+4,cy),(cx,cy+4),(cx-4,cy)],fill='white')
        im.save(TEXTURES/(name+'.png'));return im
    else:raise ValueError(kind)
    alpha=np.clip(alpha,0,1)*(r<.99);rgba=np.concatenate((np.clip(rgb,0,255),alpha[:,:,None]*255),axis=2).astype(np.uint8);im=Image.fromarray(rgba);im.save(TEXTURES/(name+'.png'));return im

def spawn(name,texture,color='#9a62f4',size=.8,count=1,life=(1.15,1.35),spread=(0,0,0),speed=(0,0),mode='BlendAdd',billboard=True,end_size=None,opacity=.8,spin=0,attract=None,stretch=1,burst=True,pitch=None,rot=None):
    # The whole system is finite and each emitter also has its own finite budget.
    # Hytale Animation scale/opacity MULTIPLY InitialAnimationFrame values.
    # A 128px sprite at native 32px/block needs size/4 for a requested world size.
    ratio=end_size/size if end_size is not None else 1.1
    frames={'0':{'Opacity':0,'Color':color,'Scale':S(1)},'12':{'Opacity':opacity},'70':{'Opacity':opacity*.8},'100':{'Opacity':0,'Scale':S(ratio)}}
    if spin:frames['100']['Rotation']={'Z':R(spin)}
    initial={'Color':'#ffffff','Opacity':1,'Scale':S(size/4,size*stretch/4),'FrameIndex':R(0)}
    if rot:initial['Rotation']=rot
    data={'Shape':'Sphere','RenderMode':mode,'LifeSpan':1.05,'TotalParticles':R(count),'MaxConcurrentParticles':count,'ParticleLifeSpan':R(*life),'SpawnRate':R(count),
       'SpawnBurst':burst,'EmitOffset':OFF(*spread),'ParticleRotationInfluence':'Billboard' if billboard else 'None','ParticleRotateWithSpawner':not billboard,'LinearFiltering':True,'LightInfluence':0,
       'InitialVelocity':{'Speed':R(*speed),'Yaw':R(-180,180),'Pitch':R(*(pitch if pitch else (-90,90)))},
       'Particle':{'Texture':'Particles/StrangeMatter/'+texture+'.png','FrameSize':{'Width':128,'Height':128},'ScaleRatioConstraint':'None','UVOption':'None','Animation':frames,'InitialAnimationFrame':initial}}
    if attract:data['Attractors']=attract
    (SERVER/'Spawners'/f'{name}.particlespawner').write_text(json.dumps(data,indent=2)+'\n')
    return name

SYSTEMS=[]
def system(name,layers,radius=4):
    refs=[]
    for layer in layers:
        if isinstance(layer,str):refs.append({'SpawnerId':layer})
        else:refs.append(layer)
    data={'Spawners':refs,'LifeSpan':3.5,'CullDistance':96,'BoundingRadius':radius,'IsImportant':False}
    (SERVER/(name+'.particlesystem')).write_text(json.dumps(data,indent=2)+'\n');SYSTEMS.append({'id':name,'layers':len(refs),'emitterLifeSpan':1.05,'systemLifeSpan':3.5})

def main():
    TEXTURES.mkdir(parents=True,exist_ok=True);(SERVER/'Spawners').mkdir(parents=True,exist_ok=True)
    for name in ('halo','ring','vortex','smoke','shadow','spark','fissure','star','petal','debris','rune','rune_ring'):sprite(name,name)
    cat=json.loads((OUT/'catalog.json').read_text())
    # The 3D silhouettes rendered as fallback billboards keep anomalies visible even
    # when their optional persistent core display entities are disabled.
    for e in cat['anomalyCores']:
        im=render(e,128,yaw=35,pitch=18);im.save(TEXTURES/(e['sourceId']+'.png'))
    orbit=[{'Position':{'X':0,'Y':0,'Z':0},'RadialAxis':{'X':0,'Y':1,'Z':0},'RadialAcceleration':-1.7,'RadialTangentImpulse':1.5}]
    inward=[{'Position':{'X':0,'Y':0,'Z':0},'RadialAcceleration':-2.5,'RadialTangentImpulse':.8,'RadialAxis':{'X':0,'Y':1,'Z':0}}]
    system('SM_Gravity_Anomaly',[
      spawn('SM_Gravity_Core','anomaly_gravity','#ffffff',1.65,mode='BlendLinear',opacity=1),
      spawn('SM_Gravity_Aura','halo','#7a46dc',2.8,opacity=.45,spin=23),
      spawn('SM_Gravity_Orbit','ring','#c6a0ff',2.4,opacity=.8,spin=45,billboard=False,rot={'X':R(70),'Z':R(12)}),
      spawn('SM_Gravity_Debris','debris','#b1a6d8',.2,count=12,life=(1.5,2.1),spread=(1.5,.5,1.5),speed=(.05,.2),mode='BlendLinear',opacity=.9,attract=orbit,spin=110),
      spawn('SM_Gravity_Dust','star','#985eee',.09,count=20,life=(1.0,1.5),spread=(1.7,1.2,1.7),attract=inward,opacity=.75)])
    system('SM_Energetic_Rift',[
      spawn('SM_Energetic_Fissure','fissure','#d1ffff',.7,stretch=2.8,opacity=1,life=(1.1,1.3)),
      spawn('SM_Energetic_Glow','fissure','#49cdea',1.15,stretch=2.1,opacity=.55),
      spawn('SM_Energetic_Corona','halo','#5689f6',2.0,stretch=1.5,opacity=.25),
      spawn('SM_Energetic_Sparks','spark','#a2ffef',.1,count=26,life=(.25,.65),spread=(.24,.75,.24),speed=(1.4,3.2),opacity=1,stretch=2.2,burst=False),
      spawn('SM_Energetic_Arcs','fissure','#6b92ef',.4,count=5,life=(.3,.6),spread=(.45,.7,.45),spin=45,opacity=.65,stretch=1.8,burst=False)])
    system('SM_Temporal_Bloom',[
      spawn('SM_Temporal_Core','anomaly_temporal_bloom','#ffffff',1.7,mode='BlendLinear',opacity=1),
      spawn('SM_Temporal_Ripple','ring','#f7d188',2.8,opacity=.52,end_size=3.6,billboard=False,rot={'X':R(88)}),
      spawn('SM_Temporal_Halo','halo','#baa1fa',2.3,opacity=.28),
      spawn('SM_Temporal_Petals','petal','#efc580',.2,count=10,spread=(1.2,.7,1.2),life=(1.3,2.0),speed=(.03,.2),attract=orbit,spin=-80,mode='BlendLinear',opacity=.85),
      spawn('SM_Temporal_Motes','star','#b4c7ff',.08,count=15,spread=(1.3,1.0,1.3),life=(1.1,1.7),speed=(.08,.2),opacity=.7)])
    system('SM_Echoing_Shadow',[
      spawn('SM_Shadow_Core','shadow','#ffffff',1.75,opacity=1,mode='BlendLinear'),
      spawn('SM_Shadow_Echo','ring','#8455b9',2.15,opacity=.38,end_size=2.8),
      spawn('SM_Shadow_Veil','smoke','#14162f',1.15,count=9,spread=(.75,.65,.75),life=(1.4,2.2),attract=inward,mode='BlendLinear',opacity=.65,spin=-65),
      spawn('SM_Shadow_Fragments','debris','#302548',.13,count=9,spread=(1.2,.8,1.2),life=(1.2,1.8),attract=inward,mode='BlendLinear',opacity=.8),
      spawn('SM_Shadow_Whispers','star','#9172cd',.065,count=16,spread=(1.15,.9,1.15),attract=inward,opacity=.65)])
    system('SM_Thoughtwell',[
      spawn('SM_Thoughtwell_Core','anomaly_thoughtwell','#ffffff',2.05,opacity=1,mode='BlendLinear'),
      spawn('SM_Thoughtwell_Seal','rune_ring','#66eeec',2.65,opacity=.7,spin=28,billboard=False,rot={'X':R(75)}),
      spawn('SM_Thoughtwell_Aura','halo','#50aece',2.3,opacity=.22),
      spawn('SM_Thoughtwell_Runes','rune','#99f6ee',.22,count=10,spread=(1.25,.65,1.25),life=(1.5,2.1),speed=(.1,.25),attract=orbit,opacity=.7,spin=20),
      spawn('SM_Thoughtwell_Ideas','star','#d5fff7',.09,count=12,spread=(.65,.75,.65),life=(1.2,1.8),speed=(.35,.6),pitch=(65,115),opacity=.6)])
    system('SM_Warp_Gate',[
      spawn('SM_Warp_Event_Horizon','shadow','#352654',2.6,stretch=1.5,mode='BlendLinear',opacity=1),
      spawn('SM_Warp_Vortex','vortex','#9465ef',2.8,stretch=1.5,opacity=.85,spin=70),
      spawn('SM_Warp_Rim','ring','#d69fff',2.9,stretch=1.5,opacity=.95,spin=-25),
      spawn('SM_Warp_Outer_Rim','halo','#622bc0',3.3,stretch=1.5,opacity=.36),
      spawn('SM_Warp_Infall','spark','#a983f7',.10,count=22,spread=(1.6,2.2,.25),life=(1.0,1.6),attract=[{'RadialAcceleration':-2.5,'RadialAxis':{'X':0,'Y':0,'Z':1},'RadialTangentImpulse':1.8}],opacity=.85)],radius=5)
    system('SM_Anomaly_Capture',[
      spawn('SM_Capture_Implosion','rune_ring','#b8ffff',2.7,life=(.6,.8),end_size=.08,opacity=1,spin=-135),
      spawn('SM_Capture_Flash','star','#e2dcff',1.2,life=(.2,.35),end_size=.05,opacity=1),
      spawn('SM_Capture_Motes','spark','#ae84ff',.1,count=24,spread=(1.4,1.4,1.4),life=(.6,1.0),attract=[{'RadialAcceleration':-8}],opacity=1)])
    system('SM_Anomaly_Scan',[
      spawn('SM_Scan_Ring','rune_ring','#63f7ed',.3,life=(.7,.9),end_size=3.2,opacity=.8,spin=25),
      spawn('SM_Scan_Glint','star','#dbfffd',.35,life=(.2,.4),opacity=.9)])
    system('SM_Rift_Discharge',[
      spawn('SM_Discharge_Flash','star','#e8ffff',1.8,life=(.15,.25),end_size=.2,opacity=1),
      spawn('SM_Discharge_Shockwave','ring','#69c8f7',.25,life=(.3,.5),end_size=2.2,opacity=.75),
      spawn('SM_Discharge_Sparks','spark','#a6ffff',.12,count=22,life=(.3,.6),speed=(2,5),opacity=1,stretch=2)])
    # Supplementary effects consumed by machines/tools can reference these IDs.
    system('SM_Resonant_Burner_Active',[spawn('SM_Burner_Plasma','fissure','#eabe83',.22,count=5,spread=(.16,.1,.16),life=(.3,.7),speed=(.15,.45),pitch=(70,110),opacity=.8,stretch=1.6,burst=False)])
    system('SM_Resonance_Transfer',[spawn('SM_Transfer_Sparks','star','#67f2ec',.06,count=8,spread=(.1,.1,.1),life=(.2,.5),speed=(.4,.8),opacity=.8)])
    # Structural validation against our owned graph; server codec validation is a separate integration check.
    seen=[]
    for p in SERVER.rglob('*.particlespawner'):
        d=json.loads(p.read_text());assert d['LifeSpan']>0;assert d['TotalParticles']['Max']>0;assert d['MaxConcurrentParticles']<=512
        assert (COMMON/d['Particle']['Texture']).exists();assert d['Particle']['InitialAnimationFrame']['Opacity']>0;seen.append(p.stem)
    for p in SERVER.glob('*.particlesystem'):
        d=json.loads(p.read_text());assert all(x['SpawnerId'] in seen for x in d['Spawners'])
    (OUT/'particle-catalog.json').write_text(json.dumps({'systems':SYSTEMS,'spawners':len(seen),'textures':len(list(TEXTURES.glob('*.png'))),'validation':'PASS: finite emitters, particle budgets, every texture and spawner resolves'},indent=2)+'\n')
    preview();print('Generated',len(SYSTEMS),'systems,',len(seen),'spawners')

def tinted(name,color,sz):
    im=Image.open(TEXTURES/(name+'.png')).convert('RGBA').resize(sz,Image.Resampling.LANCZOS);a=np.array(im,dtype=np.float32);a[:,:,:3]*=np.array(color)/255;return Image.fromarray(a.astype(np.uint8))
def preview():
    # Art composite of the real sprite assets, deliberately labeled below.
    im=Image.new('RGB',(1440,930),(10,15,29));d=ImageDraw.Draw(im);font=ImageFont.truetype('C:/Windows/Fonts/consola.ttf',18)
    names=[('Gravity Anomaly','anomaly_gravity',(151,88,237)),('Energetic Rift','fissure',(97,237,244)),('Temporal Bloom','anomaly_temporal_bloom',(250,187,83)),('Echoing Shadow','shadow',(135,91,184)),('Thoughtwell','anomaly_thoughtwell',(72,231,235)),('Warp Gate','vortex',(173,110,247))]
    for i,(title,core,c) in enumerate(names):
        x=i%3*480;y=i//3*440;d.rounded_rectangle((x+12,y+10,x+467,y+429),radius=16,fill=(15,24,41),outline=(38,57,76))
        def paste(name,color,w,h,ox=0,oy=0):
            pic=tinted(name,color,(w,h));im.paste(pic,(x+240-w//2+ox,y+205-h//2+oy),pic)
        if title=='Warp Gate':paste('shadow',(70,46,115),240,360);paste('vortex',c,265,388);paste('ring',(221,165,255),274,395)
        elif title=='Energetic Rift':paste('halo',(62,105,163),230,330);paste('fissure',c,125,305);paste('fissure',(233,255,255),83,288)
        else:
            paste('halo',c,340,340)
            if title=='Echoing Shadow':paste('shadow',(255,255,255),250,250);paste('ring',c,295,295)
            else:paste(core,(255,255,255),250,250)
            if title=='Thoughtwell':paste('rune_ring',c,350,142,oy=82)
            elif title=='Gravity Anomaly':paste('ring',(191,151,252),350,140,oy=35)
            elif title=='Temporal Bloom':paste('ring',c,350,126,oy=90)
        rng=random.Random(title)
        for k in range(13):
            px=rng.randrange(-175,176);py=rng.randrange(-150,151);paste('rune' if title=='Thoughtwell' and k%3==0 else 'star',c,13 if k%3 else 21,13 if k%3 else 21,px,py)
        d.text((x+240,y+396),title,font=font,fill=(206,226,232),anchor='mm')
    d.text((720,903),'PARTICLE ART COMPOSITE • Actual sprites • In-game motion verification still required',font=font,fill=(125,150,170),anchor='mm');im.save(ART/'anomaly-particles.png')

if __name__=='__main__':main()
