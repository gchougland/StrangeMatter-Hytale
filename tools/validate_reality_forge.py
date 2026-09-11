"""Current forge UVs, motion envelope and native Working state; standard library only."""
from pathlib import Path
import collections, copy, json, math, sys
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools/assets'))
from validate_architecture_set import model_bounds, nodes, png_size, read
RES=ROOT/'src/main/resources';COMMON=RES/'Common';FOLDER=COMMON/'Blocks/StrangeMatter'
def require(condition,message):
    if not condition:raise AssertionError(message)
def posed(model,animation,time):
    value=copy.deepcopy(model)
    for node in nodes(value):
        for channel,keys in animation['nodeAnimations'].get(node['name'],{}).items():
            if not keys:continue
            for first,last in zip(keys,keys[1:]):
                if first['time']<=time<=last['time']:
                    t=(time-first['time'])/(last['time']-first['time']);t=t*t*(3-2*t)
                    delta={axis:first['delta'][axis]*(1-t)+last['delta'][axis]*t for axis in first['delta']}
                    if channel=='position':
                        for axis in 'xyz':node['position'][axis]+=delta[axis]
                    elif channel=='shapeStretch':
                        for axis in 'xyz':node['shape']['stretch'][axis]*=delta[axis]
                    break
    return value
def validate():
    item=read(RES/'Server/Item/Items/StrangeMatter/SM_Reality_Forge.json');block=item['BlockType'];working=block['State']['Definitions']['Working']
    model=read(COMMON/block['CustomModel']);animation=read(COMMON/working['CustomModelAnimation'])
    idle=COMMON/block['CustomModelTexture'][0]['Texture'];hot=COMMON/working['CustomModelTexture'][0]['Texture']
    width,height=png_size(idle);require(png_size(hot)==(width,height),'Both forge states must share the same UV atlas dimensions')
    require(idle.read_bytes()!=hot.read_bytes(),'Working heat texture must visibly differ from idle')
    names=collections.Counter(n['name'] for n in nodes(model));ids=[n['id'] for n in nodes(model)]
    require(len(ids)==len(set(ids)),'Duplicate forge node IDs')
    require(all(names[n]==1 for n in ('molten_reality','hearth_back','arch_keystone','anvil_striking_face','hammer_die','chimney_throat')),'Forge silhouette/animation parts missing or ambiguous')
    faces=0
    for node in nodes(model):
        shape=node['shape'];size=shape.get('settings',{}).get('size',{});layout=shape.get('textureLayout',{})
        if shape['type']!='box':continue
        require(set(layout)=={'front','back','left','right','top','bottom'},'Forge solid box missing a textured face: '+node['name'])
        require(layout['front']['offset']!=layout['back']['offset'],'Opposite forge faces must have independently painted UV islands')
        for face,uv in layout.items():
            w,h=(size['x'],size['z']) if face in ('top','bottom') else (size['z'],size['y']) if face in ('left','right') else (size['x'],size['y'])
            if uv.get('angle',0)%180:w,h=h,w
            x,y=uv['offset']['x'],uv['offset']['y'];require(1<=x and 1<=y and x+w<width and y+h<height,'Forge UV or gutter exceeds atlas: '+node['name']+'/'+face)
            require(not any(uv.get('mirror',{}).values()),'Forge faces use explicit unmirrored UV islands');faces+=1
    hitbox=read(RES/'Server/Item/Block/Hitboxes/StrangeMatter/SM_Reality_Forge.json')['Boxes'][0]
    require(animation['duration']==90 and animation['holdLastKeyframe'] is False and working['Looping'] is True,'Native looping Working state changed')
    require(block['Interactions']['Use']['Interactions'][0]['Action']=='machine','Original machine controls changed')
    for name,tracks in animation['nodeAnimations'].items():
        require(names[name]==1,'Animation target missing: '+name)
        for channel,keys in tracks.items():
            if not keys:continue
            require(keys[0]['time']==0 and keys[-1]['time']==90 and keys[0]['delta']==keys[-1]['delta'],'Loop is not seamless: '+name+'/'+channel)
            require(all(type(k['time']) is int for k in keys),'Native client key time must be Int32')
            require(all(a['time']<b['time'] for a,b in zip(keys,keys[1:])),'Animation keys not ordered')
            require(all(math.isfinite(v) for k in keys for v in k['delta'].values()),'Nonfinite animation value')
    for time in range(91):
        bounds=model_bounds(posed(model,animation,time))
        require(all(bounds['Min'][a]>=hitbox['Min'][a]-1e-8 and bounds['Max'][a]<=hitbox['Max'][a]+1e-8 for a in 'XYZ'),'Forge animation leaves its unchanged native collision envelope at frame '+str(time)+': '+str(bounds))
    print(f'REALITY_FORGE_VALIDATION_PASSED: {len(ids)} nodes, {faces} individually mapped faces, idle/working atlases, five seamless native animation tracks, all91 frames inside original collision and unchanged machine action.')
if __name__=='__main__':validate()
