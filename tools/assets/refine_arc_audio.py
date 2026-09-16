"""Author/check only Arc Projector audio. --write replaces this set, never visual assets.

Original synthesis combines a high-voltage transient, nonlinear falling zap and
irregular short discharges. Three finite variants avoid a repeated noise tick at
the weapon's five pulses/second. Audacity's bundled libsndfile encodes mono Vorbis.
Default mode validates current saved files and exports an audition WAV.
"""
from pathlib import Path
import argparse,ctypes,json,math,wave,zipfile
import numpy as np

ROOT=Path(__file__).resolve().parents[2]
COMMON=ROOT/'src/main/resources/Common'
EVENTS=ROOT/'src/main/resources/Server/Audio/SoundEvents/StrangeMatter'
SOUNDS=COMMON/'Sounds/StrangeMatter/GadgetEnergy'
PREVIEW=ROOT/'build/art-preview/arc-projector-audio'
RATE=48000

class Info(ctypes.Structure):
    _fields_=[('frames',ctypes.c_int64),('samplerate',ctypes.c_int),('channels',ctypes.c_int),
              ('format',ctypes.c_int),('sections',ctypes.c_int),('seekable',ctypes.c_int)]

def library():
    lib=ctypes.CDLL('C:/Program Files/Audacity/sndfile.dll')
    lib.sf_open.argtypes=[ctypes.c_char_p,ctypes.c_int,ctypes.POINTER(Info)];lib.sf_open.restype=ctypes.c_void_p
    for name in ('sf_writef_double','sf_readf_double'):
        method=getattr(lib,name);method.argtypes=[ctypes.c_void_p,ctypes.POINTER(ctypes.c_double),ctypes.c_int64];method.restype=ctypes.c_int64
    lib.sf_close.argtypes=[ctypes.c_void_p];lib.sf_close.restype=ctypes.c_int
    return lib

def encode(path,signal):
    lib=library();info=Info(0,RATE,1,0x200060,0,0)
    handle=lib.sf_open(str(path).encode(),0x20,ctypes.byref(info));assert handle
    try:assert lib.sf_writef_double(handle,signal.ctypes.data_as(ctypes.POINTER(ctypes.c_double)),len(signal))==len(signal)
    finally:assert lib.sf_close(handle)==0

def decode(path):
    lib=library();info=Info();handle=lib.sf_open(str(path).encode(),0x10,ctypes.byref(info));assert handle
    try:
        assert info.channels==1 and info.samplerate==RATE,'Positional audio must be mono 48kHz'
        signal=np.empty(info.frames,dtype=np.float64)
        assert lib.sf_readf_double(handle,signal.ctypes.data_as(ctypes.POINTER(ctypes.c_double)),len(signal))==len(signal)
    finally:assert lib.sf_close(handle)==0
    return signal

def waveform(kind,variant):
    impact=kind=='Impact';seconds=.13 if impact else .18
    t=np.arange(round(seconds*RATE))/RATE;rng=np.random.default_rng(9030+variant+int(impact)*100)
    pitch=1+(-.055,.025,.065)[variant]
    frequency=(420+2700*np.exp(-t*32))*pitch
    phase=math.tau*np.cumsum(frequency)/RATE
    # A modulated, saturated carrier gives the familiar descending electrical zap.
    warble=1.2*np.sin(math.tau*(104+variant*7)*t)
    carrier=np.tanh(2.6*(np.sin(phase+warble)+.31*np.sin(phase*2.013)))
    envelope=(1-np.exp(-t*1600))*np.exp(-t*(24 if impact else 18))
    body=.26*carrier*envelope
    # Discrete sparks have separated, unequal envelopes rather than continuous hiss.
    sparks=np.zeros(len(t));times=(0,.006,.021,.042,.065,.096,.136)
    for index,base in enumerate(times):
        start=max(0,base+(0 if index==0 else rng.uniform(-.003,.003)))
        dt=t-start;duration=rng.uniform(.003,.009);valid=dt>=0
        env=np.where(valid,(1-np.exp(-np.maximum(dt,0)*7000))*np.exp(-np.maximum(dt,0)/duration),0)
        noise=rng.normal(0,1,len(t));bright=noise-np.roll(noise,1)*.74
        fizz=np.sin(math.tau*(1800+index*570)*dt+np.sin(math.tau*230*dt)*2.1)
        strength=(.32 if index==0 else .12)*np.exp(-base*7)
        sparks+=strength*env*(.55*bright+.6*fizz)
    # Very short high-voltage contact snap, with a restrained low harmonic.
    snap=(rng.normal(0,1,len(t))*.19+np.sin(math.tau*220*t)*.14)*np.exp(-t*330)
    signal=np.tanh((body+sparks+snap)*(1.1 if impact else 1.3))
    signal*=np.clip(t/.0005,0,1)*np.clip((seconds-t)/.016,0,1)
    signal-=signal.mean();signal*=np.clip(t/.0005,0,1)*np.clip((seconds-t)/.003,0,1)
    signal*=.76/max(abs(signal));return signal

def paths(kind):
    return [SOUNDS/(f'arc_{kind.lower()}'+('' if i==0 else f'_{i+1}')+'.ogg') for i in range(3)]

def author():
    PREVIEW.mkdir(parents=True,exist_ok=True)
    snapshot=PREVIEW/'before-refinement.zip'
    if not snapshot.exists():
        with zipfile.ZipFile(snapshot,'w',zipfile.ZIP_DEFLATED) as archive:
            for kind in ('Muzzle','Impact'):
                for p in [*paths(kind),EVENTS/f'SM_Arc_{kind}_SFX.json']:
                    if p.exists():archive.write(p,p.relative_to(ROOT))
    for kind in ('Muzzle','Impact'):
        for i,path in enumerate(paths(kind)):encode(path,waveform(kind,i))
        event={'AudioCategory':'AudioCat_SFX','SpatialBlend':1,'StartAttenuationDistance':1.5,
               'MaxDistance':14,'MaxInstance':8,'Volume':-4 if kind=='Muzzle' else -7,
               'Layers':[{'Files':[p.relative_to(COMMON).as_posix() for p in paths(kind)]}]}
        (EVENTS/f'SM_Arc_{kind}_SFX.json').write_text(json.dumps(event,indent=2)+'\n',encoding='utf-8')

def check_preview():
    PREVIEW.mkdir(parents=True,exist_ok=True);report=[];samples={}
    for kind in ('Muzzle','Impact'):
        event=json.loads((EVENTS/f'SM_Arc_{kind}_SFX.json').read_text())
        assert event['SpatialBlend']==1 and event['MaxInstance']<=8
        assert len(event['Layers'])==1 and len(event['Layers'][0]['Files'])==3 and not event['Layers'][0].get('Looping',False)
        for path in paths(kind):
            signal=decode(path);samples[path.stem]=signal
            duration=len(signal)/RATE;peak=float(max(abs(signal)));rms=float(np.sqrt(np.mean(signal**2)))
            assert .1<=duration<=.2 and peak<.99 and .07<rms<.3
            assert abs(float(signal.mean()))<.003 and max(abs(signal[-48:]))<.035
            report.append({'file':path.relative_to(COMMON).as_posix(),'duration':duration,'channels':1,'peak':round(peak,4),'rms':round(rms,4)})
    audition=np.zeros(RATE*3)
    def add(key,start,gain=1):
        offset=round(start*RATE);sound=samples[key];audition[offset:offset+len(sound)]+=sound*gain
    # Isolated muzzle/impact, then six real-cadence primary pulses with alternating variants.
    add('arc_muzzle',.1);add('arc_impact',.55,.7)
    for i in range(6):
        suffix='' if i%3==0 else f'_{i%3+1}'
        add('arc_muzzle'+suffix,1+i*.2,.75);add('arc_impact'+suffix,1.025+i*.2,.36)
    assert max(abs(audition))<1
    preview=PREVIEW/'arc-projector-zap-preview.wav'
    with wave.open(str(preview),'wb') as out:
        out.setnchannels(1);out.setsampwidth(2);out.setframerate(RATE);out.writeframes((audition*32767).astype('<i2').tobytes())
    result={'status':'PASS','description':'Original electrical contact snap, descending nonlinear zap and irregular crackle. Three finite variants per event; five-pulse/second audition.',
            'visuals':'Unchanged SM_Stabilizer_Link','files':report,'preview':str(preview)}
    (PREVIEW/'validation.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(result,indent=2))

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--write',action='store_true');args=parser.parse_args()
    if args.write:author()
    check_preview()
