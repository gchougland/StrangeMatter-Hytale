"""Surgical working animation/state additions. Preserves existing machine geometry and UVs."""
from pathlib import Path
import argparse, copy, ctypes, hashlib, json, math, subprocess, wave as wave_file

ROOT=Path(__file__).resolve().parents[2]
RES=ROOT/'src/main/resources'
MODELS=RES/'Common/Blocks/StrangeMatter'
ITEMS=RES/'Server/Item/Items/StrangeMatter'
TARGETS={'resonance_condenser':'SM_Resonance_Condenser','resonant_burner':'SM_Resonant_Burner',
         'reality_forge':'SM_Reality_Forge','rift_stabilizer':'SM_Rift_Stabilizer'}

def nodes(model):
    for n in model['nodes']:
        yield n
        yield from nodes({'nodes':n.get('children',[])})

def geometry(model):
    model=copy.deepcopy(model)
    for n in nodes(model):
        n.pop('name',None)
        n.get('shape',{}).get('settings',{}).pop('isStaticBox',None)
    return json.dumps(model,sort_keys=True,separators=(',',':'))

def vector(x=0,y=0,z=0):return {'x':x,'y':y,'z':z}
def key(time,value):return {'time':time,'delta':value,'interpolationType':'smooth'}
def tracks():return {k:[] for k in ('position','orientation','shapeStretch','shapeVisible','shapeUvOffset')}
def wave(track,kind,axis,amount,phase=0,duration=90):
    values=[]
    for frame in range(0,duration+1,15):
        a=math.sin(2*math.pi*(frame/duration+phase))
        base=1 if kind=='shapeStretch' else 0
        v=dict(track[kind][len(values)]['delta']) if track[kind] else vector(base,base,base)
        v[axis]=round(base+amount*a,6)
        values.append(key(frame,v))
    track[kind]=values

def audio(ffmpeg=None,sndfile=None):
    import numpy as np
    rate=48000;seconds=8;t=np.arange(rate*seconds)/rate
    # Every oscillator and modulation completes an integer number of cycles per file.
    # No sharp attack, alarms, clicks or repeated one-shot starts: a low induction drone.
    signal=(.43*np.sin(2*np.pi*82.5*t+.07*np.sin(2*np.pi*.125*t))
            +.14*np.sin(2*np.pi*165*t)+.055*np.sin(2*np.pi*247.5*t)
            +.025*np.sin(2*np.pi*330*t))*(.96+.04*np.cos(2*np.pi*.25*t))
    rng=np.random.default_rng(1909)
    for f in np.arange(120,481,7.5):signal+=.0017*np.sin(2*np.pi*f*t+rng.uniform(0,2*np.pi))
    signal*=.2/np.sqrt(np.mean(signal**2))
    work=ROOT/'build/machine-work-effects';work.mkdir(parents=True,exist_ok=True)
    wav=work/'condenser_working_hum.wav'
    with wave_file.open(str(wav),'wb') as out:
        out.setnchannels(1);out.setsampwidth(2);out.setframerate(rate)
        out.writeframes(np.clip(signal*32767,-32768,32767).astype('<i2').tobytes())
    target=RES/'Common/Sounds/StrangeMatter/condenser_working_hum.ogg'
    if sndfile:
        class Info(ctypes.Structure):
            _fields_=[('frames',ctypes.c_int64),('samplerate',ctypes.c_int),('channels',ctypes.c_int),('format',ctypes.c_int),('sections',ctypes.c_int),('seekable',ctypes.c_int)]
        lib=ctypes.CDLL(sndfile);lib.sf_open.argtypes=[ctypes.c_char_p,ctypes.c_int,ctypes.POINTER(Info)];lib.sf_open.restype=ctypes.c_void_p
        lib.sf_writef_double.argtypes=[ctypes.c_void_p,ctypes.POINTER(ctypes.c_double),ctypes.c_int64];lib.sf_writef_double.restype=ctypes.c_int64
        lib.sf_close.argtypes=[ctypes.c_void_p];lib.sf_close.restype=ctypes.c_int
        info=Info(0,rate,1,0x200060,0,0) # SF_FORMAT_OGG | SF_FORMAT_VORBIS
        handle=lib.sf_open(str(target).encode(),0x20,ctypes.byref(info));assert handle,'Native libsndfile could not open mono Vorbis output'
        try:assert lib.sf_writef_double(handle,signal.ctypes.data_as(ctypes.POINTER(ctypes.c_double)),len(signal))==len(signal)
        finally:assert lib.sf_close(handle)==0,'Native Vorbis encoder did not flush successfully'
    else:subprocess.run([ffmpeg,'-hide_banner','-loglevel','error','-y','-i',str(wav),'-c:a','libvorbis','-q:a','4',str(target)],check=True)
    report={'sampleRate':rate,'channels':1,'seconds':seconds,'sourceRms':float(np.sqrt(np.mean(signal**2))),
            'sourcePeak':float(np.max(np.abs(signal))),'loopBoundaryStep':float(abs(signal[-1]-signal[0])),
            'largestSampleStep':float(np.max(np.abs(np.diff(signal)))),'eventDb':-18,'maxDistanceBlocks':7,'maxInstances':3}
    assert report['loopBoundaryStep']<=report['largestSampleStep']*1.05,'Unexpected discontinuity at loop boundary'
    (ROOT/'tools/assets/condenser-hum-validation.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--ffmpeg',help='Also render the authored mono OGG loop with this encoder');parser.add_argument('--sndfile',help='Alternative native libsndfile DLL with Vorbis support');args=parser.parse_args()
    report=[]
    for name,item_id in TARGETS.items():
        path=MODELS/(name+'.blockymodel');model=json.loads(path.read_text());before=geometry(model)
        animation={'formatVersion':1,'duration':90,'holdLastKeyframe':False,'nodeAnimations':{}}
        for node in nodes(model):
            original=node['name'];base=original.split('__SMWorking')[0];track=tracks();node_id=node['id']
            if name=='resonance_condenser':
                if base=='induction_winding':
                    phase=(node['position']['y']-20)/24+(0 if node['position']['x']<0 else .5)
                    wave(track,'position','y',.48,phase);wave(track,'shapeStretch','x',.018,phase)
                elif base=='capacitor_window':wave(track,'shapeStretch','y',.035,0 if node['position']['x']<0 else .5)
            elif name=='resonant_burner':
                if base=='plasma_window':wave(track,'shapeStretch','y',.06);wave(track,'shapeStretch','x',.035,.25)
                elif base=='flue_fins':wave(track,'position','y',.2,(node['position']['y']-28)/24)
            elif name=='reality_forge':
                if base in ('quantum_press','press_tip'):
                    track['position']=[key(t,vector(y=y)) for t,y in ((0,0),(18,2.6),(39,2.6),(51,0),(62,.35),(71,0),(90,0))]
                elif base=='pylon_conductor':wave(track,'shapeStretch','y',.022,0 if node['position']['x']<0 else .5)
            else:
                if base.startswith('suspended_seed_'):wave(track,'position','y',.72)
                elif base.startswith('inner_conductor'):wave(track,'shapeStretch','x',.065,int(base[len('inner_conductor'):])/12)
            if not any(track.values()):continue
            # Animation binding is by name. Give duplicate authored coil/window names unique
            # animation names without changing IDs, transforms, sizes, hierarchy or texture layout.
            node['name']=base+'__SMWorking'+node_id
            if node.get('shape',{}).get('type')=='box':node['shape']['settings']['isStaticBox']=False
            animation['nodeAnimations'][node['name']]=track
        assert animation['nodeAnimations'],name
        assert geometry(model)==before,(name,'geometry/UV changed')
        path.write_text(json.dumps(model,indent=2)+'\n')
        animation_path=MODELS/(name+'_working.blockyanim');animation_path.write_text(json.dumps(animation,indent=2)+'\n')
        item_path=ITEMS/(item_id+'.json');item=json.loads(item_path.read_text())
        state={'Looping':True,'CustomModelAnimation':'Blocks/StrangeMatter/'+animation_path.name,'CustomModelAnimationSpeed':1}
        if name=='resonance_condenser':state['AmbientSoundEventId']='SM_Condenser_Working_Hum'
        item['BlockType'].setdefault('State',{}).setdefault('Definitions',{})['Working']=state
        item_path.write_text(json.dumps(item,indent=2)+'\n')
        report.append({'machine':item_id,'animatedNodes':len(animation['nodeAnimations']),'geometryUVSha256':hashlib.sha256(before.encode()).hexdigest()})
    sound={'AudioCategory':'AudioCat_Ambient','SpatialBlend':1,'StartAttenuationDistance':1,'MaxDistance':7,'MaxInstance':3,'Volume':-18,
           'Layers':[{'Files':['Sounds/StrangeMatter/condenser_working_hum.ogg'],'Looping':True,'Volume':0}]}
    (RES/'Server/Audio/SoundEvents/StrangeMatter/SM_Condenser_Working_Hum.json').write_text(json.dumps(sound,indent=2)+'\n')
    (ROOT/'tools/assets/machine-work-effects.json').write_text(json.dumps({'machines':report,'preserved':'All model IDs, geometry, hierarchy, UVs and textures; only animated node names/static flags change.'},indent=2)+'\n')
    print(json.dumps(report))
    if args.ffmpeg or args.sndfile:audio(args.ffmpeg,args.sndfile)

if __name__=='__main__':main()
