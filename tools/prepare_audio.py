"""Export original Strange Matter sounds as mono Vorbis point sources for Hytale.

Uses libsndfile (Audacity/Blender ship it); never repeatedly re-encodes an export.
The original source directory and DLL can be overridden for another workstation.
"""
from pathlib import Path
import argparse, ctypes as C, json, os
import numpy as np

ROOT = Path(__file__).resolve().parents[1]

class Info(C.Structure):
    _fields_ = [('frames', C.c_int64), ('samplerate', C.c_int), ('channels', C.c_int),
                ('format', C.c_int), ('sections', C.c_int), ('seekable', C.c_int)]

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--source', type=Path, default=Path.home()/'Documents/Projects/StrangeMatter-1.20.1/strange-matter/src/main/resources/assets/strangematter/sounds')
    ap.add_argument('--sndfile', default='C:/Program Files/Audacity/sndfile.dll')
    args = ap.parse_args()
    lib = C.CDLL(args.sndfile)
    lib.sf_open.argtypes = [C.c_char_p, C.c_int, C.POINTER(Info)]; lib.sf_open.restype = C.c_void_p
    for name in ['sf_readf_float', 'sf_writef_float']:
        fn = getattr(lib, name); fn.argtypes = [C.c_void_p, C.POINTER(C.c_float), C.c_int64]; fn.restype = C.c_int64
    lib.sf_close.argtypes = [C.c_void_p]
    sounds = ROOT/'src/main/resources/Common/Sounds/StrangeMatter'
    events = ROOT/'src/main/resources/Server/Audio/SoundEvents/StrangeMatter'
    sounds.mkdir(parents=True, exist_ok=True); events.mkdir(parents=True, exist_ok=True)
    report = []
    for source in sorted(args.source.glob('*.ogg')):
        info = Info(); handle = lib.sf_open(os.fsencode(source), 0x10, C.byref(info))
        if not handle: raise RuntimeError(f'Cannot decode {source}')
        samples = np.empty((info.frames, info.channels), dtype=np.float32)
        try: assert lib.sf_readf_float(handle, samples.ctypes.data_as(C.POINTER(C.c_float)), info.frames) == info.frames
        finally: lib.sf_close(handle)
        mono = np.ascontiguousarray(samples.mean(axis=1), dtype=np.float32)
        target = sounds/source.name
        out = Info(0, info.samplerate, 1, 0x200060, 0, 0)
        handle = lib.sf_open(os.fsencode(target), 0x20, C.byref(out))
        if not handle: raise RuntimeError(f'Cannot encode {target}')
        try: assert lib.sf_writef_float(handle, mono.ctypes.data_as(C.POINTER(C.c_float)), len(mono)) == len(mono)
        finally: lib.sf_close(handle)
        name = 'SM_'+'_'.join(w.title() for w in source.stem.split('_'))
        anomaly = source.stem in {'gravity_anomaly_loop','temporal_bloom_loop','energetic_rift_loop','warp_gate_loop','echoing_shadow_loop','thoughtwell_loop'}
        if not anomaly: name += '_SFX'
        data = {'AudioCategory':'AudioCat_SFX','SpatialBlend':1.0,
                'StartAttenuationDistance':1.5, 'MaxDistance':18 if anomaly else 24,
                'MaxInstance':8, 'Volume':-8 if anomaly else -5,
                'Layers':[{'Files':['Sounds/StrangeMatter/'+source.name]}]}
        (events/(name+'.json')).write_text(json.dumps(data,indent=2)+'\n')
        report.append({'sound':source.stem,'channels':1,'seconds':round(info.frames/info.samplerate,6)})
    assert len(report)>=6, 'Original sounds were not found; no audio was exported.'
    out = ROOT/'build/reports'; out.mkdir(parents=True,exist_ok=True)
    (out/'audio-export.json').write_text(json.dumps(report,indent=2)+'\n')
    print(f'Exported {len(report)} mono sounds with fully positional event settings.')

if __name__ == '__main__': main()
