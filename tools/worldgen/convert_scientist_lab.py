"""Audit and convert the user's original Minecraft structure; no third-party NBT dependency."""
import gzip, io, json, struct, sys
from collections import Counter
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools/assets'))
from fixture_toggles import apply as apply_fixture_toggle
SOURCE=Path(r'C:/Users/gchou/Documents/Projects/StrangeMatter-1.20.1/strange-matter/src/main/resources/data/strangematter/structures/anomaly_scientist_lab.nbt')
stream=io.BytesIO(gzip.decompress(SOURCE.read_bytes()))
def take(fmt):return struct.unpack('>'+fmt,stream.read(struct.calcsize('>'+fmt)))[0]
def string():return stream.read(take('H')).decode('utf8')
def tag(kind):
    if kind in [1,2,3,4,5,6]:return take({1:'b',2:'h',3:'i',4:'q',5:'f',6:'d'}[kind])
    if kind==7:return list(stream.read(take('i')))
    if kind==8:return string()
    if kind==9:
        child=take('b');length=take('i');return [tag(child) for _ in range(length)]
    if kind==10:
        out={}
        while True:
            child=take('b')
            if not child:return out
            name=string();out[name]=tag(child)
    if kind in [11,12]:return [take('i' if kind==11 else 'q') for _ in range(take('i'))]
    raise ValueError(kind)
kind=take('b');name=string();data=tag(kind)
audit=ROOT/'docs/worldgen/scientist-lab-source.json';audit.parent.mkdir(parents=True,exist_ok=True)
audit.write_text(json.dumps(data,indent=2),encoding='utf8')
print('Structure size:',data['size'])
print('Palette:',json.dumps(data['palette'],indent=2))
print('Block counts:',dict(Counter(data['palette'][b['state']]['Name'] for b in data['blocks'])))
print('Entities:',json.dumps(data.get('entities',[]),indent=2))
print('Block entities:',json.dumps([b for b in data['blocks'] if 'nbt' in b],indent=2))

# Model orientation: Strange Matter stairs rise toward -Z; native yaw90 rotates north to west.
facing={'north':0,'west':1,'south':2,'east':3}
mapping={
    'minecraft:sea_lantern':'SM_Lab_Lamp',
    'minecraft:cyan_stained_glass_pane':'SM_Lab_Cyan_Glass',
    'minecraft:blue_orchid':'Plant_Flower_Orchid_Blue',
    'minecraft:soul_wall_torch':'Furniture_Temple_Emerald_Torch',
    'minecraft:cyan_bed':'Furniture_Crude_Bed',
    'minecraft:jigsaw':'SM_Resonite_Pillar',
    'minecraft:air':'Empty',
}
converted=[]
for block in data['blocks']:
    source=data['palette'][block['state']];name=source['Name'];p=source.get('Properties',{})
    # Native doors and beds carry their own multi-cell collision fillers.
    if name.endswith('resonite_door') and p.get('half')=='upper':continue
    if name=='minecraft:cyan_bed' and p.get('part')=='head':continue
    target=mapping.get(name)
    if target is None:
        assert name.startswith('strangematter:'),name
        target='SM_'+'_'.join(word.title() for word in name.split(':')[1].split('_'))
    rotation=facing.get(p.get('facing','north'),0)
    if name.endswith('_stairs') and p.get('half')=='top':rotation+=32
    if name=='minecraft:cyan_stained_glass_pane':rotation=1 if p.get('north')=='true' or p.get('south')=='true' else 0
    if name=='minecraft:cyan_bed':rotation=1 # Native bed extends +Z; source head is east of foot.
    if name.endswith('resonite_pillar') and p.get('axis')=='x':rotation=16
    if name.endswith('resonite_pillar') and p.get('axis')=='z':rotation=4
    if name.endswith('resonite_trapdoor') and p.get('open')=='true':
        target='*SM_Resonite_Trapdoor_State_Definitions_OpenDoorOut'
    x,y,z=block['pos'];entry={'x':x,'y':y,'z':z,'name':target}
    if rotation:entry['rotation']=rotation
    converted.append(entry)
prefab={'version':8,'blockIdVersion':11,'anchorX':0,'anchorY':0,'anchorZ':0,'blocks':converted}
dest=ROOT/'src/main/resources/Server/Prefabs/StrangeMatter/Anomaly_Scientist_Lab.prefab.json';dest.parent.mkdir(parents=True,exist_ok=True)
dest.write_text(json.dumps(prefab,indent=2)+'\n',encoding='utf8')

# The canonical role is maintained with ScientistService: its native wandering,
# home return, trading state and appearance must survive laboratory regeneration.
# Never replace it with the stock stationary Klops_Merchant template.
role_path=ROOT/'src/main/resources/Server/NPC/Roles/StrangeMatter/SM_Anomaly_Scientist.json'
if not role_path.is_file():
    raise FileNotFoundError('Restore the canonical scientist role before converting the laboratory: '+str(role_path))
json.loads(role_path.read_text(encoding='utf-8-sig'))

for ident,path,hitbox,description in [
    ('SM_Lab_Cyan_Glass','lab_cyan_glass','SM_Lab_Cyan_Glass','Cyan glazing from an anomaly scientist laboratory.'),
    ('SM_Lab_Lamp','lab_lamp','Full','A cold luminous ceiling panel from an anomaly scientist laboratory.')]:
    item={'TranslationProperties':{'Name':f'server.items.{ident}.name','Description':f'server.items.{ident}.description'},
          'Icon':f'Icons/ItemsGenerated/{ident}.png','MaxStack':64,'Categories':['Blocks'],'PlayerAnimationsId':'Block',
          'BlockType':{'Material':'Solid','DrawType':'Model','Opacity':'Transparent' if 'Glass' in ident else 'Solid',
              'CustomModel':f'Blocks/StrangeMatter/{path}.blockymodel','CustomModelTexture':[{'Texture':f'Blocks/StrangeMatter/{path}.png','Weight':1}],
              'HitboxType':hitbox,'VariantRotation':'NESW','BlockSoundSetId':'Glass' if 'Glass' in ident else 'Stone',
              'BlockParticleSetId':'Stone','ParticleColor':'#5eeeff','Gathering':{'Breaking':{'GatherType':'Rocks'}}},
          'Interactions':{'Primary':'Block_Primary','Secondary':'Block_Secondary'}}
    if 'Lamp' in ident:item['BlockType']['Light']={'Color':'#5ff','Radius':10}
    apply_fixture_toggle(ident,item,ROOT/'src/main/resources')
    dest=ROOT/f'src/main/resources/Server/Item/Items/StrangeMatter/{ident}.json';dest.write_text(json.dumps(item,indent=2)+'\n',encoding='utf8')
hitbox={'Boxes':[{'Min':{'X':0,'Y':0,'Z':0.46875},'Max':{'X':1,'Y':1,'Z':0.53125}}]}
dest=ROOT/'src/main/resources/Server/Item/Block/Hitboxes/StrangeMatter/SM_Lab_Cyan_Glass.json';dest.parent.mkdir(parents=True,exist_ok=True)
dest.write_text(json.dumps(hitbox,indent=2)+'\n',encoding='utf8')
language=ROOT/'src/main/resources/Server/Languages/en-US/server.lang';language.parent.mkdir(parents=True,exist_ok=True)
labels={'npcRoles.SM_Anomaly_Scientist.name':'Anomaly Scientist','items.SM_Lab_Cyan_Glass.name':'Laboratory Cyan Glass','items.SM_Lab_Cyan_Glass.description':'Cyan glazing from an anomaly scientist laboratory.','items.SM_Lab_Lamp.name':'Laboratory Lamp','items.SM_Lab_Lamp.description':'A cold luminous ceiling panel from an anomaly scientist laboratory.'}
lines=language.read_text(encoding='utf8').splitlines() if language.exists() else []
lines=[line for line in lines if line.split('=',1)[0].strip() not in labels]
language.write_text('\n'.join(lines)+'\n'+'\n'.join(f'{key} = {value}' for key,value in labels.items())+'\n',encoding='utf8')
print(f'Converted native prefab: {len(converted)} cells, source layout {data["size"]}; canonical role preserved, laboratory fixtures exported.')

# The native editor paste path uses NO_SET_FILLER; export the same checked native collision cells as runtime generation.
import validate_scientist_lab
validate_scientist_lab.write_fillers()
