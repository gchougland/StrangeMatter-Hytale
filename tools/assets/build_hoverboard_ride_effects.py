"""Author quiet seamless riding audio and bounded coil effects; preserve old assets."""
from pathlib import Path
import ctypes, hashlib, json, math, wave
import numpy as np

ROOT=Path(__file__).resolve().parents[2]
RES=ROOT/'src/main/resources'
OUT=ROOT/'tools/assets/hoverboard-ride-effects.json'

def write(relative,value):
    path=RES/relative;path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(value,indent=2)+'\n',encoding='utf8')

def pair(a,b=None):return {'Min':a,'Max':a if b is None else b}

def emitter(texture,color,count,size,life,speed,opacity):
    result={
        'Shape':'Sphere','RenderMode':'BlendAdd','LifeSpan':.08,
        'TotalParticles':pair(count),'MaxConcurrentParticles':count,
        'ParticleLifeSpan':pair(*life),'SpawnRate':pair(count),'SpawnBurst':True,
        'EmitOffset':{k:pair(-.025,.025) for k in 'XYZ'},
        'ParticleRotationInfluence':'Billboard','ParticleRotateWithSpawner':False,
        'LinearFiltering':True,'LightInfluence':0,
        'InitialVelocity':{'Speed':pair(*speed),'Yaw':pair(170,190),'Pitch':pair(-12,3)},
        'Particle':{'Texture':'Particles/StrangeMatter/'+texture+'.png',
            'FrameSize':{'Width':128,'Height':128},'ScaleRatioConstraint':'None','UVOption':'None',
            'InitialAnimationFrame':{'Color':color,'Opacity':0,'Scale':{'X':pair(size[0]),'Y':pair(size[1])},'FrameIndex':pair(0)},
            'Animation':{
                '0':{'Opacity':0,'Color':color,'Scale':{'X':pair(1),'Y':pair(1)}},
                '14':{'Opacity':opacity},
                '55':{'Opacity':opacity*.55},
                '100':{'Opacity':0,'Scale':{'X':pair(.25),'Y':pair(.55)}}
            }
        }
    }
    return result

def main():
    files=[]
    spawners={
        'SM_Hoverboard_Glide_Wisp':emitter('halo','#66e9ff',3,(.08,.15),(.3,.48),(.3,.7),.52),
        'SM_Hoverboard_Glide_Spark':emitter('spark','#b597ff',1,(.027,.027),(.23,.35),(.2,.5),.65),
        'SM_Hoverboard_Lift_Ring':emitter('ring','#56cddd',1,(.065,.042),(.18,.32),(0,.035),.33),
    }
    ring=spawners['SM_Hoverboard_Lift_Ring']
    ring['Particle']['Animation']['100']['Scale']={'X':pair(1.6),'Y':pair(1.6)}
    for name,data in spawners.items():
        relative='Server/Particles/StrangeMatter/Spawners/'+name+'.particlespawner';write(relative,data);files.append(relative)
    for name,emitters in {'SM_Hoverboard_Glide':['SM_Hoverboard_Glide_Wisp','SM_Hoverboard_Glide_Spark'],
                          'SM_Hoverboard_Idle_Lift':['SM_Hoverboard_Lift_Ring']}.items():
        relative='Server/Particles/StrangeMatter/'+name+'.particlesystem'
        write(relative,{'Spawners':[{'SpawnerId':x} for x in emitters],'LifeSpan':.65,'CullDistance':24,'BoundingRadius':1.2,'IsImportant':False});files.append(relative)

    # Low A plus a soft fifth and octave; slow periodic modulation gives air
    # without broadband hiss, clicks, harsh overtones or a repeated attack.
    rate=48000;duration=8;t=np.arange(rate*duration,dtype=np.float64)/rate
    phase=.08*np.sin(math.tau*.25*t)
    samples=(.60*np.sin(math.tau*110*t+phase)+.20*np.sin(math.tau*165*t+phase*.5)
             +.13*np.sin(math.tau*220*t)+.035*np.sin(math.tau*440*t))
    samples*=.94+.06*np.cos(math.tau*.5*t)
    samples*=.36/np.max(np.abs(samples))
    pcm=np.round(samples*32767).astype('<i2')
    temp=ROOT/'build/art-preservation/hoverboard-ride-hum.wav';temp.parent.mkdir(parents=True,exist_ok=True)
    with wave.open(str(temp),'wb') as wav:
        wav.setnchannels(1);wav.setsampwidth(2);wav.setframerate(rate);wav.writeframes(pcm.tobytes())
    audio='Common/Sounds/StrangeMatter/hoverboard_riding_hum.ogg'
    class Info(ctypes.Structure):
        _fields_=[('frames',ctypes.c_int64),('samplerate',ctypes.c_int),('channels',ctypes.c_int),('format',ctypes.c_int),('sections',ctypes.c_int),('seekable',ctypes.c_int)]
    encoder=ctypes.CDLL('C:/Program Files/Audacity/sndfile.dll')
    encoder.sf_open.argtypes=[ctypes.c_char_p,ctypes.c_int,ctypes.POINTER(Info)];encoder.sf_open.restype=ctypes.c_void_p
    encoder.sf_writef_double.argtypes=[ctypes.c_void_p,ctypes.POINTER(ctypes.c_double),ctypes.c_int64];encoder.sf_writef_double.restype=ctypes.c_int64
    encoder.sf_close.argtypes=[ctypes.c_void_p];encoder.sf_close.restype=ctypes.c_int
    info=Info(0,rate,1,0x200060,0,0)
    handle=encoder.sf_open(str(RES/audio).encode(),0x20,ctypes.byref(info));assert handle,'Native mono Vorbis encoder did not open'
    try:assert encoder.sf_writef_double(handle,samples.ctypes.data_as(ctypes.POINTER(ctypes.c_double)),len(samples))==len(samples)
    finally:assert encoder.sf_close(handle)==0,'Native Vorbis output did not flush'
    files.append(audio)
    event='Server/Audio/SoundEvents/StrangeMatter/SM_Hoverboard_Riding_Hum.json'
    write(event,{'AudioCategory':'AudioCat_SFX','SpatialBlend':1,'StartAttenuationDistance':1.6,
        'MaxDistance':8,'MaxInstance':8,'Volume':-16,
        'Layers':[{'Files':['Sounds/StrangeMatter/hoverboard_riding_hum.ogg'],'Looping':True,'Volume':0}]})
    files.append(event)
    report={'status':'PASS','monoSampleRate':rate,'durationSeconds':duration,'peak':float(np.max(np.abs(samples))),
            'rms':float(np.sqrt(np.mean(samples**2))),'seamDelta':float(abs(samples[0]-samples[-1])),
            'largestAdjacentSampleDelta':float(np.max(np.abs(np.diff(samples)))),
            'maxParticlesPerGlidePulse':8,'maxConcurrentPerRiderAtFiveHz':24,
            'files':{p:hashlib.sha256((RES/p).read_bytes()).hexdigest() for p in files}}
    assert report['seamDelta']<=report['largestAdjacentSampleDelta']*1.01 and report['peak']<.5
    OUT.write_text(json.dumps(report,indent=2)+'\n',encoding='utf8');print(json.dumps(report,indent=2))

if __name__=='__main__':main()
