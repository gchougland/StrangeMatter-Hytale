"""Validate current Nullifier art and finite native effects, without historical locks.

Uses Python's standard library. Authored meshes and atlases may be edited freely
provided the native UV, animation binding and one block placement contracts hold.
Historical preservation is a separate, explicitly requested --audit-art operation.
"""
from pathlib import Path
import argparse, hashlib, json, math, struct
from validate_furniture_set import model_geometry, png, matrix

ROOT=Path(__file__).resolve().parents[1];RES=ROOT/'src/main/resources';COMMON=RES/'Common'
MODEL=COMMON/'Blocks/StrangeMatter/anomaly_nullifier.blockymodel'
BASE=MODEL.with_suffix('.png');WORKING=MODEL.with_name('anomaly_nullifier_working.png')
ANIMATION=MODEL.with_name('anomaly_nullifier_working.blockyanim')
PARTICLES=RES/'Server/Particles/StrangeMatter'

def read(path):return json.loads(path.read_text(encoding='utf-8-sig'))

def validate_model(model,atlas):
    names,points,faces=model_geometry(model,atlas)
    low=[min(p[i] for p in points) for i in range(3)];high=[max(p[i] for p in points) for i in range(3)]
    assert low[0]>=-16 and high[0]<=16 and low[2]>=-16 and high[2]<=16,'Nullifier exceeds its one block horizontal footprint'
    assert low[1]>=0 and high[1]<=32,'Nullifier exceeds its one block vertical footprint'
    assert faces>=6,'Nullifier has no visible model'
    return names,faces,{'min':low,'max':high}

def validate_animation(animation,names):
    duration=animation['duration'];assert 0<duration<=900,'Invalid native animation duration'
    assert animation['formatVersion']==1 and animation.get('nodeAnimations'),'Missing animation tracks'
    for name,tracks in animation['nodeAnimations'].items():
        assert name in names,'Animation targets a missing model node: '+name
        for kind,keys in tracks.items():
            assert kind in ('position','orientation','shapeStretch','shapeVisible','shapeUvOffset'),'Invalid native animation track'
            times=[key['time'] for key in keys]
            assert times==sorted(set(times)) and all(0<=t<=duration for t in times),'Invalid animation key times'
            for key in keys:
                if kind=='orientation':matrix(key['delta'])
                elif kind in ('position','shapeStretch'):
                    assert all(math.isfinite(v) for v in key['delta'].values()),'Nonfinite animation transform'
    return len(animation['nodeAnimations'])

def validate_spawner(spawner):
    assert 0<spawner['LifeSpan']<=1,'Nullifier pulse emitter does not terminate within the one second interval'
    assert 0<spawner['TotalParticles']['Max']<=16,'Nullifier emitter particle budget'
    assert 0<spawner['MaxConcurrentParticles']<=16,'Nullifier concurrent particle budget'
    assert 0<spawner['ParticleLifeSpan']['Max']<=1,'Nullifier particles survive too long'
    particle=spawner['Particle'];texture=COMMON/particle['Texture']
    size=png(texture);frame=particle['FrameSize']
    assert frame['Width']>0 and frame['Height']>0 and frame['Width']<=size[0] and frame['Height']<=size[1],'Particle frame exceeds texture'
    assert particle['Animation']['100']['Opacity']==0,'Nullifier particles do not fade completely'
    return spawner['TotalParticles']['Max']

def vorbis_info(path):
    data=path.read_bytes();start=data.index(b'\x01vorbis')
    channels=data[start+11];rate=struct.unpack_from('<I',data,start+12)[0]
    pos=0;granule=0
    while pos<len(data):
        assert data[pos:pos+4]==b'OggS','Invalid Ogg page framing'
        page_granule=struct.unpack_from('<Q',data,pos+6)[0]
        if page_granule<2**63:granule=max(granule,page_granule)
        segments=data[pos+26];length=sum(data[pos+27:pos+27+segments]);pos+=27+segments+length
    return channels,granule/rate

def audit_art(folder):
    before=read(folder/'Common-before.json')
    own={'Blocks/StrangeMatter/anomaly_nullifier'+suffix for suffix in ('.blockymodel','.png','_working.png','_working.blockyanim')}
    own.add('Icons/ItemsGenerated/SM_Anomaly_Nullifier.png')
    checked=0;changed=[]
    for relative,digest in before.items():
        if relative in own:continue
        checked+=1;path=COMMON/relative
        if not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest()!=digest:changed.append(relative)
    assert not changed,'Historical unrelated artwork changed: '+str(changed)
    return checked

def validate():
    dims=png(BASE);assert dims==png(WORKING),'Working texture must use the same UV atlas dimensions'
    assert all(n>=32 and n%32==0 for n in dims),'Native model atlas dimensions must be multiples of32'
    names,faces,bounds=validate_model(read(MODEL),dims)
    tracks=validate_animation(read(ANIMATION),names)
    assert png(COMMON/'Icons/ItemsGenerated/SM_Anomaly_Nullifier.png')==(64,64),'Nullifier icon must be64 square'
    system=read(PARTICLES/'SM_Nullifier_Field.particlesystem')
    assert 0<system['LifeSpan']<=2 and 0<system['BoundingRadius']<=2,'Field effect must be finite and compact'
    budget=0
    for layer in system['Spawners']:
        budget+=validate_spawner(read(PARTICLES/'Spawners'/(layer['SpawnerId']+'.particlespawner')))
    assert budget<=16,'Combined Nullifier particle budget exceeds16 per pulse'
    sound=read(RES/'Server/Audio/SoundEvents/StrangeMatter/SM_Nullifier_Hum_SFX.json')
    assert sound['SpatialBlend']==1 and 0<sound['StartAttenuationDistance']<sound['MaxDistance']<=8,'Hum must remain positional and close'
    assert sound['Volume']<=-16 and sound['MaxInstance']<=4,'Hum is not quiet and bounded'
    seconds=[]
    for layer in sound['Layers']:
        assert layer['Looping'],'Use native looping for continuous operation'
        for clip in layer['Files']:
            channels,length=vorbis_info(COMMON/clip);assert channels==1 and 0<length<=20,'Invalid positional hum clip'
            seconds.append(length)
    return {'status':'PASS','faces':faces,'atlas':dims,'boundsModelUnits':bounds,'animatedGroups':tracks,
      'maxParticlesPerPulse':budget,'systemSeconds':system['LifeSpan'],'soundClipSeconds':seconds,
      'historicalArtAuditPerformed':False,'historicalArtFilesChecked':None}

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--audit-art',type=Path);args=parser.parse_args()
    report=validate()
    if args.audit_art:
        report['historicalArtFilesChecked']=audit_art(args.audit_art);report['historicalArtAuditPerformed']=True
    print(json.dumps(report,indent=2))
