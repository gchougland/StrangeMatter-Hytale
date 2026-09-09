"""Targeted electric rift revision using existing painted textures only.

Never writes a bitmap or touches the player zap effects. The existing seven core
nodes are retained byte-for-structure; new fracture pieces and a separate shell
attachment use existing atlases. Run with the full resource snapshot explicitly.
"""
from pathlib import Path
import argparse, copy, hashlib, json, math, zipfile

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / 'src/main/resources'
COMMON = RES / 'Common'
PARTICLES = RES / 'Server/Particles/StrangeMatter'
SPAWNERS = PARTICLES / 'Spawners'
OUT = ROOT / 'tools/assets'
R = lambda a, b=None: {'Min': a, 'Max': a if b is None else b}
S = lambda x, y=None: {'X': R(x), 'Y': R(x if y is None else y)}

def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')

def quaternion(x=0,y=0,z=0):
    a,b,c = [math.radians(v)/2 for v in (x,y,z)]
    sx,cx,sy,cy,sz,cz=math.sin(a),math.cos(a),math.sin(b),math.cos(b),math.sin(c),math.cos(c)
    return dict(zip('xyzw', (sx*cy*cz-cx*sy*sz,cx*sy*cz+sx*cy*sz,cx*cy*sz-sx*sy*cz,cx*cy*cz+sx*sy*sz)))

def frame(texture, color, width, height, opacity, life, count=1, spread=(0,0,0), speed=(0,0), burst=True,
          influence='Billboard', rotation=None, spin=0, attractors=None, mode='BlendAdd'):
    initial={'Color':'#ffffff','Opacity':1,'Scale':S(width/4,height/4),'FrameIndex':R(0)}
    if rotation: initial['Rotation']={k:R(v) for k,v in rotation.items()}
    anim={'0':{'Opacity':0,'Color':color,'Scale':S(1)},'8':{'Opacity':opacity},
          '38':{'Opacity':opacity*.8,'Scale':S(1.04,.96)},'66':{'Opacity':opacity,'Scale':S(.95,1.05)},
          '100':{'Opacity':0,'Scale':S(1.12)}}
    if spin: anim['100']['Rotation']={'Z':R(spin)}
    data={'Shape':'Sphere','RenderMode':mode,'LifeSpan':.92,'TotalParticles':R(count),
          'MaxConcurrentParticles':count,'ParticleLifeSpan':R(*life),'SpawnRate':R(count/.9),
          'SpawnBurst':burst,'EmitOffset':{k:R(-v,v) for k,v in zip('XYZ',spread)},
          'ParticleRotationInfluence':influence,'ParticleRotateWithSpawner':influence=='None',
          'LinearFiltering':True,'LightInfluence':0,
          'InitialVelocity':{'Speed':R(*speed),'Yaw':R(-180,180),'Pitch':R(-90,90)},
          'Particle':{'Texture':'Particles/StrangeMatter/'+texture+'.png','FrameSize':{'Width':128,'Height':128},
                      'ScaleRatioConstraint':'None','UVOption':'None','InitialAnimationFrame':initial,'Animation':anim}}
    if attractors: data['Attractors']=attractors
    return data

def write_stabilizer():
    """A one-unit long velocity-oriented strip, scaled by each runtime segment."""
    refs=[]
    for suffix,color,width,opacity in [('Filament','#eaffff',1.10,.95),('Glow','#28cfff',2.10,.42)]:
        name='SM_Stabilizer_Link_'+suffix
        data=frame('fissure',color,width,1.45,opacity,(.23,.27),influence='BillboardVelocity',speed=(.01,.01))
        data['LifeSpan']=.05;data['SpawnRate']=R(1)
        data['InitialVelocity']={'Speed':R(.01),'Yaw':R(0),'Pitch':R(0)}
        data['UseEmitDirection']=False
        data['Particle']['Animation']={'0':{'Opacity':opacity,'Color':color,'Scale':S(1)},
            '74':{'Opacity':opacity},'100':{'Opacity':0,'Scale':S(1)}}
        write(SPAWNERS/(name+'.particlespawner'),data);refs.append({'SpawnerId':name})
    write(PARTICLES/'SM_Stabilizer_Link.particlesystem',
          {'Spawners':refs,'LifeSpan':.30,'CullDistance':64,'BoundingRadius':1.5,'IsImportant':False})

def write_rift():
    refs=[];budget=0
    def add(name, data, **group):
        nonlocal budget
        write(SPAWNERS/(name+'.particlespawner'),data)
        refs.append({'SpawnerId':name,**group});budget+=data['TotalParticles']['Max']
    add('SM_Energetic_Fissure',frame('fissure','#dbffff',.42,2.6,.95,(1.05,1.12)))
    add('SM_Energetic_Glow',frame('fissure','#18cfff',.90,2.85,.45,(1.05,1.15)))
    add('SM_Energetic_Corona',frame('halo','#5550ee',2.65,3.05,.18,(.95,1.15)))
    add('SM_Energetic_Ring_Oblique',frame('ring','#54efff',2.5,2.5,.48,(.85,1.1),
        influence='None',rotation={'X':63,'Y':22,'Z':12},spin=135))
    add('SM_Energetic_Ring_Counter',frame('ring','#b06bff',2.1,2.1,.32,(.75,1),
        influence='None',rotation={'X':-54,'Y':-32,'Z':38},spin=-165),StartDelay=.12)
    # Crossing arcs occupy different fixed planes, rather than piling upright billboards.
    for n,(yaw,roll,color) in enumerate([(0,52,'#adffff'),(68,-48,'#65e6ff'),(133,76,'#976bff')]):
        add('SM_Energetic_Cross_Arc_'+str(n+1),frame('fissure',color,.46,1.9,.8,(.12,.28),count=3,
            spread=(.22,.52,.18),burst=False,influence='None',rotation={'Y':yaw,'Z':roll},spin=(-1 if n%2 else 1)*35))
    orbit=[{'Position':{'X':0,'Y':0,'Z':0},'RadialAxis':{'X':0,'Y':1,'Z':0},
            'RadialAcceleration':-1.1,'RadialTangentImpulse':2.7}]
    arcs=frame('fissure','#70baff',.24,.80,.8,(.26,.5),count=10,spread=(1.1,.5,1.1),speed=(.02,.05),
        burst=False,influence='BillboardVelocity',attractors=orbit)
    arcs['EmitOffset']['X']=R(.85,1.15);arcs['EmitOffset']['Z']=R(.85,1.15)
    add('SM_Energetic_Arcs',arcs)
    debris=frame('debris','#526aa3',.22,.32,.95,(.85,1.2),count=8,spread=(1.03,.83,1.03),
        speed=(.04,.10),attractors=orbit,spin=240,mode='BlendLinear')
    debris['EmitOffset']['X']=R(.78,1.18);debris['EmitOffset']['Z']=R(.78,1.18)
    add('SM_Energetic_Orbit_Debris',debris)
    add('SM_Energetic_Orbit_Glint',frame('star','#81eaff',.10,.16,.95,(.65,1.05),count=6,
        spread=(1.05,.76,1.05),speed=(.03,.1),attractors=orbit,burst=False))
    add('SM_Energetic_Sparks',frame('spark','#b1ffff',.06,.38,1,(.18,.42),count=22,spread=(.20,.94,.2),
        speed=(1.7,3.4),burst=False,influence='BillboardVelocity',
        attractors=[{'DampingMultiplier':{'X':.8,'Y':.8,'Z':.8}}]))
    flash=frame('star','#e6ffff',.42,.42,.85,(.08,.15),count=4,spread=(.14,.95,.14),burst=False)
    flash['Particle']['Animation']['0']['Opacity']=.75
    add('SM_Energetic_Core_Flashes',flash)
    write(PARTICLES/'SM_Energetic_Rift.particlesystem',
        {'Spawners':refs,'LifeSpan':2.25,'CullDistance':96,'BoundingRadius':4,'IsImportant':False})
    return {'layers':len(refs),'particlesPerOneSecondPulse':budget,'maximumParticleLife':1.2,
            'conservativeConcurrentLimit':budget*2,'emitterLife':.92,'systemLife':2.25}

def box(node_id,name,position,size,rotation=(0,0,0),patch=(72,4),fullbright=False):
    # Every face is explicitly mapped inside an existing opaque hand-painted panel.
    layout={face:{'offset':{'x':patch[0],'y':patch[1]},'mirror':{'x':face in ('back','left'),'y':False},'angle':0}
            for face in ('front','back','left','right','top','bottom')}
    return {'id':str(node_id),'name':name,'position':dict(zip('xyz',position)),
        'orientation':quaternion(*rotation),'shape':{'type':'box','offset':dict.fromkeys('xyz',0),
        'stretch':dict.fromkeys('xyz',1),'settings':{'isPiece':False,'size':dict(zip('xyz',size)),'isStaticBox':True},
        'textureLayout':layout,'unwrapMode':'custom','visible':True,'doubleSided':False,
        'shadingMode':'fullbright' if fullbright else 'standard'}}

def empty(node_id,name,piece=False,children=None):
    result={'id':str(node_id),'name':name,'position':dict.fromkeys('xyz',0),'orientation':quaternion(),
        'shape':{'type':'none','offset':dict.fromkeys('xyz',0),'stretch':dict.fromkeys('xyz',1),
                 'settings':{'isPiece':piece},'visible':True}}
    if children is not None:result['children']=children
    return result

def write_core(snapshot):
    name='Common/Items/StrangeMatter/anomaly_energetic_rift.blockymodel'
    with zipfile.ZipFile(snapshot/'Resources.zip') as archive:
        entry=name if name in archive.namelist() else 'src/main/resources/'+name
        original=json.loads(archive.read(entry))
    current=json.loads((RES/name).read_text())
    if current['nodes'][:len(original['nodes'])]!=original['nodes']:
        raise SystemExit('The saved core nodes were manually changed after this snapshot; preserve them before rerunning.')
    model=copy.deepcopy(original)
    # Preserve the original hand-authored core and all of its UVs. Added splinters use
    # the existing painted luminous faces, with geometry stretch rather than altered UVs.
    seed=original['nodes'][0]
    extra=[]
    for i,(x,y,z,scale,roll) in enumerate([
        (-1,-28,0,(.5,.8,.6),-18),(2,27,0,(.45,.9,.5),23),
        (-7,-18,3,(.7,.5,.65),-48),(8,-8,-2,(.5,.6,.8),52),
        (-8,9,-3,(.8,.6,.7),-51),(7,19,2,(.55,.55,.6),44),
        (-13,-1,4,(.36,.3,.35),67),(13,7,-4,(.3,.35,.4),-54)]):
        node=copy.deepcopy(seed);node['id']=str(100+i);node['name']='plasma_splinter_'+str(i+1)
        node['position']=dict(zip('xyz',(x,y,z)));node['orientation']=quaternion(14 if i%2 else -12,i*31,roll)
        node['shape']['stretch']=dict(zip('xyz',scale));extra.append(node)
    model['nodes']+=extra+[empty(200,'RiftShell')]
    write(RES/name,model)
    shell=[];node_id=300
    for side in (-1,1):
        for i,y in enumerate((-23,-10,5,20)):
            x=side*(10+(i%2)*4);z=(-1 if i%2 else 1)*4
            # Disconnected, inward-pointing fragments make a torn silhouette.
            shell.append(box(node_id,'rift_fragment_'+str(node_id),(x,y,z),(7,14 if i%2 else 11,5),
                (side*18,i*23,side*(18+i*9)),(73,4)));node_id+=1
            shell.append(box(node_id,'fracture_face_'+str(node_id),(x-side*2.6,y+1,z+2.7),(2,8,1),
                (side*18,i*23,side*(18+i*9)),(16,34),True));node_id+=1
            shell.append(box(node_id,'violet_fault_'+str(node_id),(x+side*2,y-3,z),(2,5,4),
                (side*25,i*31,side*38),(134,35),True));node_id+=1
    for i,angle in enumerate((10,87,160,236,303)):
        a=math.radians(angle)
        shell.append(box(node_id,'suspended_fragment_'+str(i),(math.cos(a)*25,math.sin(a)*13,math.sin(a)*18),
            (3,5,3),(i*29,angle,35),(105,5)));node_id+=1
    shell_model={'nodes':[empty(290,'RiftShell',True,shell)],'format':original['format']}
    write(COMMON/'Items/StrangeMatter/anomaly_energetic_shell.blockymodel',shell_model)
    registration=RES/'Server/Models/StrangeMatter/SM_Energetic_Rift_Core.json'
    asset=json.loads(registration.read_text())
    # Native ModelAttachment restricts texture roots to character/item resources.
    # Copy bytes unchanged; this is not a reskin or an image conversion.
    source_atlas=COMMON/'Blocks/StrangeMatter/reality_forge.png'
    attachment_atlas=COMMON/'Items/StrangeMatter/anomaly_energetic_shell.png'
    if attachment_atlas.exists() and attachment_atlas.read_bytes()!=source_atlas.read_bytes():
        raise SystemExit('Shell attachment atlas was edited; preserve the edit instead of overwriting it.')
    attachment_atlas.write_bytes(source_atlas.read_bytes())
    asset['DefaultAttachments']=[{'Model':'Items/StrangeMatter/anomaly_energetic_shell.blockymodel',
                                  'Texture':'Items/StrangeMatter/anomaly_energetic_shell.png'}]
    asset['HitBox']={'Min':{'X':-.88,'Y':-1.05,'Z':-.75},'Max':{'X':.88,'Y':1.05,'Z':.75}}
    write(registration,asset)
    return {'preservedOriginalNodes':len(original['nodes']),'addedPlasmaSplinters':len(extra),
            'shellNodes':len(shell),'shellAtlas':'Items/StrangeMatter/anomaly_energetic_shell.png',
            'unchangedAtlasSource':'Blocks/StrangeMatter/reality_forge.png'}

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--snapshot',type=Path,required=True);args=parser.parse_args()
    textures={str(p.relative_to(COMMON)):hashlib.sha256(p.read_bytes()).hexdigest() for p in COMMON.rglob('*.png')}
    core=write_core(args.snapshot);write_stabilizer();budget=write_rift()
    assert all(hashlib.sha256((COMMON/p).read_bytes()).hexdigest()==digest for p,digest in textures.items())
    write(OUT/'energetic-revision.json',{'snapshot':str(args.snapshot),'existingBitmapsUnchanged':len(textures),
        'core':core,'particles':budget,'stabilizer':{'orientation':'BillboardVelocity','localForwardSpeed':.01,
        'runtimeScale':'segment length','runtimeRotation':'native Rotation3f.lookAt(segment start, segment end)',
        'particlesPerSegment':2,'maximumSegments':56}})
    print(json.dumps({'core':core,'budget':budget,'unchangedBitmaps':len(textures)}))

if __name__=='__main__':main()
