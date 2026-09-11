"""Validate native effect graphs and spatial audio without third-party Python packages."""
from pathlib import Path
import json, struct

ROOT=Path(__file__).resolve().parents[1]
RES=ROOT/'src/main/resources'

def main():
    errors=[]; particles=0; clips=0
    directory=RES/'Server/Particles/StrangeMatter'
    for path in directory.glob('*.particlesystem'):
        data=json.loads(path.read_text())
        if not 0<data.get('LifeSpan',0)<=4: errors.append(f'{path.name}: unbounded or excessive system life')
        for layer in data.get('Spawners',[]):
            target=directory/'Spawners'/(layer['SpawnerId']+'.particlespawner')
            if not target.is_file(): errors.append(f'{path.name}: missing {target.name}');continue
            emitter=json.loads(target.read_text())
            if emitter.get('TotalParticles',{}).get('Max',0)>64: errors.append(f'{target.name}: exceeds particle budget')
            texture=RES/'Common'/emitter['Particle']['Texture']
            if not texture.is_file():errors.append(f'{target.name}: missing texture')
        particles+=1
    for path in (RES/'Common/Sounds/StrangeMatter').glob('*.ogg'):
        data=path.read_bytes(); index=data.find(b'\x01vorbis')
        if index<0 or data[index+11]!=1:errors.append(f'{path.name}: positional clips must be mono Vorbis')
        clips+=1
    for path in (RES/'Server/Audio/SoundEvents/StrangeMatter').glob('*.json'):
        data=json.loads(path.read_text())
        if data.get('SpatialBlend')!=1:errors.append(f'{path.name}: source must be fully positional')
        if not 0<data.get('StartAttenuationDistance',0)<data.get('MaxDistance',0)<=32: errors.append(f'{path.name}: invalid distance falloff')
        for layer in data.get('Layers',[]):
            for clip in layer.get('Files',[]):
                if clip.startswith('Sounds/StrangeMatter/') and not (RES/'Common'/clip).is_file():errors.append(f'{path.name}: missing {clip}')
    assert clips>=6 and particles>=35, 'Effect resources were not generated.'
    if errors:raise SystemExit('\n'.join(errors))
    print(f'Effect graphs PASS: {particles} finite particle systems, {clips} mono clips, fully spatial sound events.')
    from validate_temporal_expiry import main as validate_temporal_expiry
    validate_temporal_expiry()

if __name__=='__main__':main()
