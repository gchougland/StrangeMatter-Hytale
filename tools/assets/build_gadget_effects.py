"""Instrument-scale native effects: finite bursts, directed shafts, and contrasting portals.

Run after build_particles.py. This keeps gadget feedback separate from full anomaly fields.
"""
from pathlib import Path
import json, copy
from PIL import Image, ImageDraw
from build_particles import ROOT, SERVER, TEXTURES, spawn, system, R

AUDIO = ROOT/'src/main/resources/Server/Audio/SoundEvents/StrangeMatter'
CYAN, PURPLE, GOLD = '#58f4ff', '#bf77ff', '#ffda68'

def emit(name, layers, radius=3, duration=1.2):
    system(name,layers,radius)
    path=SERVER/(name+'.particlesystem'); data=json.loads(path.read_text())
    data.update(LifeSpan=duration, CullDistance=64)
    path.write_text(json.dumps(data,indent=2)+'\n')

def sp(name, texture, color, **kw):
    kw.setdefault('life',(.18,.40)); kw.setdefault('opacity',.9)
    result=spawn(name,texture,color,**kw)
    path=SERVER/'Spawners'/(name+'.particlespawner'); data=json.loads(path.read_text())
    data['LifeSpan']=.1  # bursts are budgeted at birth; their particles can outlive the emitter
    path.write_text(json.dumps(data,indent=2)+'\n')
    return result

def sound(effect, parent, pitch=0, volume=-5):
    data={'Parent':parent,'AudioCategory':'AudioCat_SFX','SpatialBlend':1,
          'StartAttenuationDistance':1.5,'MaxDistance':24,'Volume':volume,'Pitch':pitch,'MaxInstance':8}
    (AUDIO/(effect+'_SFX.json')).write_text(json.dumps(data,indent=2)+'\n')

def burst(name,color,size=1,parent='SFX_Staff_Ice_Shoot',pitch=0,inward=False):
    ringsize=size*(1.6 if inward else .35); end=size*(.15 if inward else 1.6)
    attract=[{'Position':{'X':0,'Y':0,'Z':0},'RadialAcceleration':-9}] if inward else None
    emit(name,[sp(name+'_Ring','ring',color,size=ringsize,end_size=end),
               sp(name+'_Core','star','#efffff',size=size*.4,end_size=.08,life=(.1,.2)),
               sp(name+'_Sparks','spark',color,size=size*.10,count=9,spread=(size*.3,)*3,
                  speed=(.6,2.0),stretch=2,attract=attract)],radius=max(2,size*2))
    sound(name,parent,pitch)

def trace(name,color,size=.10,texture='spark',life=(.10,.22)):
    emit(name,[sp(name+'_Trace',texture,color,size=size,count=2,life=life,
                  spread=(.045,.045,.045),speed=(.05,.18),end_size=.015)],radius=1,duration=.5)

def main():
    AUDIO.mkdir(parents=True,exist_ok=True)
    for channel,color,pitch in [('Cyan',CYAN,2),('Purple',PURPLE,-2)]:
        burst('SM_Warp_Muzzle_'+channel,color,.7,'SM_Warp_Gun_Shoot_SFX',pitch)
        burst('SM_Warp_Impact_'+channel,color,1.7,'SFX_Portal_Neutral_Open',pitch)
        # Preserve the established aperture design, recoloring all five emitter layers.
        base=json.loads((SERVER/'SM_Warp_Gate.particlesystem').read_text())
        for layer in base['Spawners']:
            old=layer['SpawnerId']; new=old+'_'+channel
            data=json.loads((SERVER/'Spawners'/(old+'.particlespawner')).read_text())
            for frame in data['Particle']['Animation'].values():
                if 'Color' in frame: frame['Color']=color
            (SERVER/'Spawners'/(new+'.particlespawner')).write_text(json.dumps(data,indent=2)+'\n')
            layer['SpawnerId']=new
        (SERVER/('SM_Warp_Gate_'+channel+'.particlesystem')).write_text(json.dumps(base,indent=2)+'\n')
    for name,color,size,parent,inward in [
        ('SM_Vacuum_Intake',PURPLE,.75,'SM_Echo_Vacuum_Charge_Up_SFX',True),
        ('SM_Vacuum_Capture',PURPLE,1.7,'SM_Echo_Vacuum_Contain_SFX',True),
        ('SM_Scanner_Lock',CYAN,.55,'SM_Field_Scanner_Scan_SFX',False),
        ('SM_Scanner_Complete',CYAN,1.3,'SM_Minigame_Stable_SFX',False),
        ('SM_Chrono_Muzzle',GOLD,.8,'SM_Chronoblister_Fire_SFX',False),
        ('SM_Chrono_Impact',GOLD,2.0,'SFX_Staff_Ice_Shoot',False),
        ('SM_Hammer_Pulse',PURPLE,.8,'SM_Graviton_Chargeup_SFX',True),
        ('SM_Hammer_Impact',PURPLE,1.3,'SFX_Crystal_Break',False),
        ('SM_Imprint',PURPLE,1.6,'SFX_Avatar_Powers_Enable',True),
        ('SM_Imprint_Revert',CYAN,1.6,'SFX_Avatar_Powers_Disable',False),
        ('SM_Hoverboard_Engage',CYAN,1.5,'SM_Hoverboard_Jump_SFX',False),
        ('SM_Hoverboard_Disengage',PURPLE,.9,'SFX_Avatar_Powers_Disable',True),
        ('SM_Capsule_Throw',CYAN,.45,'SFX_Crystal_Build',False),
        ('SM_Capsule_Impact',PURPLE,1.6,'SM_Echo_Vacuum_Contain_SFX',False),
    ]: burst(name,color,size,parent,inward=inward)
    trace('SM_Chrono_Trail',GOLD)
    trace('SM_Hoverboard_Exhaust',CYAN,.18,life=(.2,.4))
    trace('SM_Stasis_Beam',CYAN,.13,life=(.25,.4))
    trace('SM_Rift_Arc',CYAN,.26,texture='fissure',life=(.16,.3))
    emit('SM_Rift_Hit',[
        sp('SM_Rift_Hit_Lightning','fissure','#deffff',size=.9,stretch=2.6,count=3,spread=(.35,.1,.35),life=(.22,.45)),
        sp('SM_Rift_Hit_Corona','halo',CYAN,size=2.2,stretch=1.2,opacity=.5,end_size=2.8),
        sp('SM_Rift_Hit_Sparks','spark','#a7faff',size=.12,count=18,speed=(2,4),stretch=2)],radius=4)
    # Native billboards preserve Z roll; these fissure sprites are painted vertically.
    for name in ('SM_Rift_Arc_Trace', 'SM_Rift_Hit_Lightning'):
        path=SERVER/'Spawners'/(name+'.particlespawner'); data=json.loads(path.read_text())
        data['Particle']['InitialAnimationFrame']['Rotation']={'Z':R(90)}
        path.write_text(json.dumps(data,indent=2)+'\n')
    sound('SM_Rift_Hit','SFX_Eye_Void_Attack_Blast',pitch=3,volume=-9)
    emit('SM_Temporal_Field',[
        sp('SM_Temporal_Field_Glyphs','rune',GOLD,size=.18,count=3,spread=(.42,.42,.42),life=(.35,.6),opacity=.7),
        sp('SM_Temporal_Field_Motes','star','#fff2c0',size=.06,count=6,spread=(.5,.5,.5),life=(.3,.7),speed=(.02,.12))],duration=.9,radius=2)
    emit('SM_Stasis_Hold',[
        sp('SM_Stasis_Hold_Orbit','ring',CYAN,size=.75,life=(.45,.65),opacity=.6,spin=50,billboard=False,rot={'X':R(90)}),
        sp('SM_Stasis_Hold_Motes','star','#d9c8ff',size=.08,count=4,spread=(.35,.12,.35),life=(.4,.6))],radius=2)
    sound('SM_Stasis_Hold','SM_Stasis_Projector_On_SFX')
    # Original chevron sprite gives direction even to players who cannot distinguish the colors.
    for direction in ['Up','Down']:
        im=Image.new('RGBA',(128,128)); d=ImageDraw.Draw(im)
        d.line([(22,82),(64,40),(106,82)],fill=(255,255,255,255),width=7)
        d.line([(29,101),(64,66),(99,101)],fill=(255,255,255,165),width=4)
        if direction=='Down': im=im.transpose(Image.Transpose.FLIP_TOP_BOTTOM)
        texture='levitation_'+direction.lower();im.save(TEXTURES/(texture+'.png'))
        color=CYAN if direction=='Up' else PURPLE
        emit('SM_Levitation_'+direction,[sp('SM_Levitation_'+direction+'_Arrows',texture,color,
            size=.62,count=2,spread=(.16,.2,.16),speed=(1.1,1.4),pitch=(90,90) if direction=='Up' else (-90,-90),
            life=(.45,.65),opacity=.65)],duration=.9,radius=2)
        sound('SM_Levitation_'+direction,'SM_Stasis_Projector_On_SFX',pitch=2 if direction=='Up' else -2)
    emit('SM_Levitation_Range',[sp('SM_Levitation_Range_Ring','rune_ring','#a4d5ef',
        size=2.2,life=(.5,.7),opacity=.38,billboard=False,rot={'X':R(90)})],radius=3,duration=.9)
    print('Built gadget bursts, trace effects, shaft indicators, body-scale rift impact and two portal palettes.')

if __name__=='__main__': main()
