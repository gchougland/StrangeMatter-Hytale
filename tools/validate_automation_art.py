"""Validate current automation presentation contracts, without historical art locks.

Standard library only. Optional --audit-art compares a specified pre-edit Common
hash manifest; routine builds permit the artist to change valid geometry and UVs.
"""
from pathlib import Path
import argparse, copy, hashlib, json, math, struct, sys

ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT/'tools/assets'))
from validate_architecture_set import check_model_uvs, model_bounds, nodes, png_size
from verify_texture_repack import decode_png

RES=ROOT/'src/main/resources';COMMON=RES/'Common';ITEMS=RES/'Server/Item/Items/StrangeMatter'
REPORT=ROOT/'tools/assets/automation-set.json';PARTICLES=RES/'Server/Particles/StrangeMatter'

def read(path):return json.loads(path.read_text(encoding='utf-8-sig'))
def require(value,message):
    if not value:raise AssertionError(message)
def dimensions(path):
    width,height=png_size(path)
    require(width>=height>=32 and all(v&(v-1)==0 for v in (width,height)),'Model atlas must be square or wide powers of two: '+str(path))
    return width,height
def validate_model(model,texture,label):
    width,height=dimensions(texture);all_nodes=list(nodes(model));ids=[n['id'] for n in all_nodes]
    require(0<len(ids)<=255 and len(set(ids))==len(ids),'Native model node IDs/count invalid: '+label)
    for n in all_nodes:
        q=n.get('orientation',dict(x=0,y=0,z=0,w=1));values=list(q.values())
        require(all(isinstance(v,(int,float)) and math.isfinite(v) for v in values),'Invalid quaternion '+label)
        require(abs(sum(v*v for v in values)-1)<.0001,'Unnormalized quaternion '+label)
        require(n.get('shape',{}).get('visible',True),'Resting model hides authored geometry '+label)
    return check_model_uvs(model,width,height,label)

def animation_check(model,clip,label):
    duration=clip['duration'];require(type(duration)is int and duration>0,'Native animation duration must be positive integer frames')
    by_name={}
    for n in nodes(model):by_name.setdefault(n['name'],[]).append(n)
    for name,tracks in clip['nodeAnimations'].items():
        require(len(by_name.get(name,[]))==1,'Animation target is missing or ambiguous: '+name)
        target=by_name[name][0]
        for n in nodes({'nodes':[target]}):
            if n.get('shape',{}).get('type')=='box':require(not n['shape']['settings'].get('isStaticBox',False),'Animated box must not be static: '+n['name'])
        for kind,keys in tracks.items():
            require(kind in ('position','orientation','shapeStretch','shapeVisible','shapeUvOffset'),'Unknown native animation track')
            times=[k['time'] for k in keys]
            require(times==sorted(set(times)) and all(type(t)is int and 0<=t<=duration for t in times),'Invalid animation key times')
            for key in keys:
                if kind in ('position','orientation','shapeStretch'):
                    v=list(key['delta'].values());require(all(math.isfinite(x) for x in v),'Nonfinite animation delta')
                    if kind=='orientation':require(abs(sum(x*x for x in v)-1)<.0001,'Invalid animation quaternion')
    # Evaluate every authored key time. Motion bounds stay inside placement cells.
    return sorted({0,duration}|{k['time'] for tracks in clip['nodeAnimations'].values() for keys in tracks.values() for k in keys})

def pose(model,clip,frame):
    result=copy.deepcopy(model)
    for n in nodes(result):
        for kind,keys in clip['nodeAnimations'].get(n['name'],{}).items():
            if not keys or kind not in ('position','orientation'):continue
            low=max((k for k in keys if k['time']<=frame),key=lambda k:k['time'],default=keys[0]);high=min((k for k in keys if k['time']>=frame),key=lambda k:k['time'],default=keys[-1])
            weight=0 if high['time']==low['time'] else (frame-low['time'])/(high['time']-low['time'])
            a=low['delta'];b=high['delta']
            if kind=='orientation' and sum(a[k]*b[k] for k in a)<0:b={k:-v for k,v in b.items()}
            value={k:a[k]+(b[k]-a[k])*weight for k in a}
            if kind=='position':n['position']={k:n['position'].get(k,0)+value[k] for k in 'xyz'}
            else:
                norm=math.sqrt(sum(v*v for v in value.values()));n['orientation']={k:v/norm for k,v in value.items()}
    return result

def within(model,bounds,label):
    actual=model_bounds(model);width,height,depth=bounds
    for axis,maximum in zip('XYZ',(width,height,depth)):
        require(-.00001<=actual['Min'][axis]<=actual['Max'][axis]<=maximum+.00001,'Model or work motion crosses its placement footprint: '+label+' '+str(actual))

def validate(audit_art=None):
    catalog=read(REPORT);entries=catalog['items'];require(len(entries)==15,'Expected three machines, tube and eleven concentrate items')
    faces=0;models=set();textures=set();animations=0
    for entry in entries:
        ident=entry['id'];item=read(ITEMS/(ident+'.json'));require(png_size(COMMON/item['Icon'])==(64,64),'Inventory icon must be 64 by 64: '+ident)
        require('SM_StrangeMatter.All' in item['Categories'],'Missing creative category '+ident)
        require(item['MaxStack']==(25 if 'tiers' in entry else 100),'Wrong stack convention '+ident)
        paths=entry.get('tierModels',entry.get('connectionModels',[entry['model']]))
        for path in paths:
            model=read(COMMON/path)
            faces+=validate_model(model,COMMON/entry['texture'],path);models.add(path);textures.add(entry['texture'])
            if 'workingTexture' in entry:
                faces+=check_model_uvs(model,*dimensions(COMMON/entry['workingTexture']),path+' Working');textures.add(entry['workingTexture'])
            if 'tiers' in entry:
                clip=read(COMMON/entry['animation']);times=animation_check(model,clip,path)
                for frame in times:within(pose(model,clip,frame),entry['boundsBlocks'],path+' frame '+str(frame))
                animations+=1
            elif 'connectionModels' in entry:within(model,[1,1,1],path)
        if 'tiers' in entry:
            block=item['BlockType'];require('SM_Factory' in block['BlockEntity']['Components'],'Missing factory inventory component')
            require(block['Interactions']['Use']['Interactions'][0]=={'Type':'SM_Use','Action':'machine'},'Machine use interaction changed')
            definitions=block['State']['Definitions'];expected={'Working'}
            for tier in range(2,entry['tiers']+1):expected.update(('Tier'+str(tier),'Tier'+str(tier)+'Working'))
            require(set(definitions)==expected,'Tier state contract changed '+ident)
            for state,value in definitions.items():
                if state.endswith('Working') or state=='Working':
                    require(value.get('Looping') and value['CustomModelAnimation']==entry['animation'],'Working state animation missing')
                    require(value['AmbientSoundEventId']==entry['hum'],'Native working hum missing')
            require('Recipe' not in item,'These machines are made through the custom forge catalog')
            hit=read(RES/'Server/Item/Block/Hitboxes/StrangeMatter'/(ident+'.json'))['Boxes'][0]
            require(hit['Min']==dict.fromkeys('XYZ',0) and [hit['Max'][a] for a in 'XYZ']==entry['boundsBlocks'],'Declared placement filler footprint differs')
        elif 'connectionModels' in entry:
            block=item['BlockType'];require('SM_GraviticTube' in block['BlockEntity']['Components'],'Missing tube component')
            require(block['VariantRotation']=='None','Tube masks must remain world aligned')
            defs=block['State']['Definitions'];require(set(defs)=={'Connection'+str(i).zfill(2) for i in range(64)},'Incomplete tube masks')
            for mask,path in enumerate(entry['connectionModels']):
                definition=defs['Connection'+str(mask).zfill(2)];require(definition['CustomModel']==path,'Tube state model mismatch')
                names=[n['name'] for n in nodes(read(COMMON/path))]
                for bit in range(6):require(('ClosedCollar_'+str(bit) in names)==(not bool(mask&(1<<bit))),'Tube cap does not match connection mask')
                hit=read(RES/'Server/Item/Block/Hitboxes/StrangeMatter'/(definition['HitboxType']+'.json'))
                require(len(hit['Boxes'])==1+mask.bit_count(),'Tube collision has disconnected arms')
            w,h,pixels=decode_png((COMMON/entry['texture']).read_bytes());require(set(pixels[3::4])=={255},'Tube atlas must be opaque; translucency comes from particles')
            recipe=item['Recipe'];require(recipe['KnowledgeRequired'] and recipe['OutputQuantity']==10,'Tube laboratory recipe must be research gated')
            require(recipe['BenchRequirement'][0]['Id']=='SM_Laboratory','Tube recipe uses wrong bench')
    require(len({e['model'] for e in entries if 'metal' in e})==1,'Concentrates should share geometry')
    require(len({e['texture'] for e in entries if 'metal' in e})==11,'Concentrates need eleven metal identities')
    effect_ids=['SM_Resonant_Separator_Work','SM_Flux_Furnace_Work','SM_Pattern_Assembler_Work','SM_Tube_Field','SM_Tube_Transfer']
    for ident in effect_ids:
        system=read(PARTICLES/(ident+'.particlesystem'));require(0<system['LifeSpan']<=1,'Automation effects must expire within one second')
        total=0
        for group in system['Spawners']:
            spawner=read(PARTICLES/'Spawners'/(group['SpawnerId']+'.particlespawner'));total+=spawner['TotalParticles']['Max']
            require(0<spawner['LifeSpan']<=.5 and spawner['MaxConcurrentParticles']<=12,'Unbounded automation particles')
            require((COMMON/spawner['Particle']['Texture']).is_file(),'Missing effect sprite')
        require(total<=12,'Automation pulse budget exceeded')
    sounds=0
    for entry in entries[:3]:
        for ident in (entry['hum'],entry['completionSound']):
            event=read(RES/'Server/Audio/SoundEvents/StrangeMatter'/(ident+'.json'));require(event['SpatialBlend']==1 and event['MaxDistance']<=8 and event['Volume']<=-15,'Factory sounds must stay quiet and local')
            for layer in event['Layers']:
                require(layer['Looping']==(ident==entry['hum']),'Working audio loop ownership changed')
                for path in layer['Files']:
                    raw=(COMMON/path).read_bytes();index=raw.find(b'\x01vorbis');require(index>=0 and raw[index+11]==1,'Positional audio must be mono Vorbis')
            sounds+=1
    audited=None
    if audit_art:
        old=read(Path(audit_art));changed=[p for p,digest in old.items() if not (COMMON/p).is_file() or hashlib.sha256((COMMON/p).read_bytes()).hexdigest()!=digest]
        require(not changed,'Preexisting art changed: '+str(changed));audited=len(old)
    result={'items':len(entries),'models':len(models),'atlases':len(textures),'uvFacesChecked':faces,'animatedTierModels':animations,'tubeMasks':64,'finiteEffects':len(effect_ids),'spatialSoundEvents':sounds,'historicalFilesAudited':audited}
    print('AUTOMATION_ART_VALIDATION_PASSED '+json.dumps(result));return result

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--audit-art',type=Path);args=parser.parse_args();validate(args.audit_art)
