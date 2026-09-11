"""Rebuild the Hytale content registry and audited recipes from the owner's Minecraft source.

No Minecraft runtime classes or models are shipped. Vanilla ingredients have explicit
Hytale equivalents; quantities and the separate forge shard costs are preserved.
"""
import argparse, collections, json, pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools/assets'))
from content_policy import descriptions as item_descriptions, family_light, model_reference
from nullifier_content import apply as apply_nullifier_content, add_recipe as add_nullifier_recipe
from block_support import apply as apply_block_support
RES = ROOT / 'src/main/resources'
DEFAULT_MC = pathlib.Path('C:/Users/gchou/Documents/Projects/StrangeMatter-1.20.1/strange-matter')
DEFAULT_ASSETS = ROOT.parent / 'HytaleSourceCode/hytale-shared-source/HytaleAssets'
MACHINES = set('research_machine resonant_burner resonance_condenser reality_forge paradoxical_energy_cell resonant_conduit rift_stabilizer stasis_projector levitation_pad time_dilation_block'.split())
TOOLS = set('research_tablet research_notes field_scanner anomaly_resonator echo_vacuum warp_gun chrono_blister gravit on_hammer echoform_imprinter hoverboard'.replace('gravit on','graviton').split())
MAP = {
    'minecraft:crafting_table': 'Bench_WorkBench', 'minecraft:furnace': 'Bench_Furnace',
    'minecraft:glass': 'Rock_Crystal_White_Block', 'minecraft:glass_pane': 'Rock_Crystal_White_Block',
    'minecraft:glowstone_dust': 'Ingredient_Fire_Essence',
    'minecraft:iron_ingot': 'Ingredient_Bar_Iron', 'minecraft:planks': 'Wood_Softwood_Planks',
    'minecraft:redstone': 'Ingredient_Bar_Copper', 'minecraft:redstone_block': 'Rock_Crystal_Red_Block',
    'minecraft:stick': 'Ingredient_Stick', 'minecraft:stone': 'Rock_Stone'
}
RESOURCE_TAGS = {'minecraft:planks': 'Wood_Planks', 'strangematter:anomaly_shards': 'SM_Anomaly_Shards'}
INTERACTION_PROMPTS = {
    'SM_Research_Machine':'open the research machine',
    'SM_Resonant_Burner':'open the resonant burner',
    'SM_Resonance_Condenser':'open the resonance condenser',
    'SM_Reality_Forge':'open the Reality Forge',
    'SM_Paradoxical_Energy_Cell':'inspect the paradoxical energy cell',
    'SM_Rift_Stabilizer':'inspect the rift stabilizer',
    'SM_Stasis_Projector':'turn the stasis projector on or off',
    'SM_Levitation_Pad':'configure the levitation pad',
    'SM_Laboratory_Bench':'open the laboratory bench',
    'SM_Resonite_Door_Open':'open the resonite door',
    'SM_Resonite_Door_Close':'close the resonite door',
    'SM_Resonite_Trapdoor_Open':'open the resonite hatch',
    'SM_Resonite_Trapdoor_Close':'close the resonite hatch'
}

def hid(s): return 'SM_' + '_'.join(w.capitalize() for w in s.split(':')[-1].split('_'))
def item(s): return hid(s) if s.startswith('strangematter:') else MAP[s]
def ingredient(value):
    if 'item' in value: return item(value['item'])
    # A Minecraft tag is a choice of materials, never an item with the tag's name.
    return 'resource:' + RESOURCE_TAGS[value['tag']]
def native_inputs(counts):
    return [{'ResourceTypeId' if k.startswith('resource:') else 'ItemId': k.removeprefix('resource:'), 'Quantity': v} for k,v in counts.items()]
def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')

def main():
    ap=argparse.ArgumentParser(); ap.add_argument('--minecraft',type=pathlib.Path,default=DEFAULT_MC); ap.add_argument('--assets',type=pathlib.Path,default=DEFAULT_ASSETS); a=ap.parse_args()
    source=(a.minecraft/'src/main/java/com/hexvane/strangematter/StrangeMatterMod.java').read_text()
    items=sorted(set(re.findall(r'ITEMS\.register\("([a-z_]+)"',source)))
    blocks=set(re.findall(r'BLOCKS\.register\("([a-z_]+)"',source))
    vanilla={p.stem:p for p in (a.assets/'Server/Item/Items').rglob('*.json')}
    # Pick the actual transparent glass item in this installed patchline.
    if MAP['minecraft:glass'] not in vanilla:
        candidates=[k for k in vanilla if k in ('Glass_Block','Glass','Deco_Glass_Block')]
        if not candidates: raise ValueError('No vanilla glass block found')
        MAP['minecraft:glass']=MAP['minecraft:glass_pane']=candidates[0]
    assert all(v in vanilla for v in MAP.values()), [v for v in MAP.values() if v not in vanilla]
    recipes=[]; standard=collections.defaultdict(list)
    shard_members={item(s) for s in json.loads((a.minecraft/'src/main/resources/data/strangematter/tags/items/anomaly_shards.json').read_text())['values']}
    for f in sorted((a.minecraft/'src/main/resources/data/strangematter/recipes').rglob('*.json')):
        d=json.loads(f.read_text()); counts=collections.Counter(); t=d['type']
        if 'pattern' in d:
            symbols=collections.Counter(''.join(d['pattern']).replace(' ',''))
            for sym,n in symbols.items(): counts[ingredient(d['key'][sym])]+=n
        elif 'ingredients' in d:
            for ing in d['ingredients']: counts[ingredient(ing)]+=1
        else:
            counts[ingredient(d['ingredient'])]+=1
        result=d['result']; out=item(result if isinstance(result,str) else result['item']); count=1 if isinstance(result,str) else result.get('count',1)
        rec={'id':f.stem, 'source':str(f.relative_to(a.minecraft)).replace('\\','/'),'output':out,'quantity':count,'ingredients':dict(counts),'shards':d.get('shards',{}),'research':d.get('required_research',''),'seconds':5.0 if t.endswith('reality_forge') else d.get('cookingtime',40)/20.0,'station':'forge' if t.endswith('reality_forge') else 'furnace' if t in ('minecraft:blasting','minecraft:smelting') else 'workbench'}
        recipes.append(rec)
        if rec['station']!='forge': standard[out].append(rec)
    lang=[]
    descriptions=item_descriptions()
    for name in items+['laboratory_bench']:
        i=hid(name); isblock=name in blocks or name=='laboratory_bench'
        desc=descriptions[name]
        lang.extend([f'items.{i}.name={name.replace("_"," ").title()}',f'items.{i}.description={desc}'])
        d={'TranslationProperties':{'Name':f'server.items.{i}.name','Description':f'server.items.{i}.description'},'Icon':f'Icons/ItemsGenerated/{i}.png','MaxStack':1 if name in TOOLS and name!='research_notes' else 64,'Categories':['Furniture.Benches' if name in MACHINES else 'Blocks.Ores' if name.endswith('_ore') else 'Blocks' if isblock else 'Items'],'PlayerAnimationsId':'Block' if isblock else 'Item','Quality':'Uncommon','Tags':{'Type':['StrangeMatter']}}
        if i in shard_members: d['ResourceTypes']=[{'Id':'SM_Anomaly_Shards'}]
        if isblock:
            b={'Material':'Solid','DrawType':'Model','Opacity':'Transparent','CustomModel':f'Blocks/StrangeMatter/{name}.blockymodel','CustomModelTexture':[{'Texture':f'Blocks/StrangeMatter/{name}.png','Weight':1}],'HitboxType':i,'VariantRotation':'NESW','Gathering':{'Breaking':{'GatherType':'Rocks'}},'BlockParticleSetId':'Stone','ParticleColor':'#243950','BlockSoundSetId':'Stone','PhysicalMaterialId':'Stone'}
            if name.endswith('_ore'):
                drop=hid('raw_resonite' if name=='resonite_ore' else name[:-4]); b['Gathering']['Breaking']['DropList']={'Container':{'Type':'Single','Item':{'ItemId':drop}}}
            if (name in MACHINES and name!='resonant_conduit') or name in ('resonite_door','resonite_trapdoor'):
                b['Interactions']={'Use':{'Interactions':[{'Type':'SM_Use','Action':'machine'}]}}
            if i in INTERACTION_PROMPTS:b['InteractionHint']='server.interactionHints.'+i
            if name=='resonant_conduit':b['InteractionHint']=''
            if name.endswith(('_lamp','_lantern','_crystal')):
                fixture=name.endswith(('_lamp','_lantern'))
                b['Light']={'Color':family_light(name.split('_')[0],fixture),'Radius':0 if fixture else 5}
            if name=='time_dilation_block':
                b['Material']='Empty'
                b['ParticleColor']='#ffda68'
                b.pop('Gathering',None)
                b.pop('Interactions',None)
                b.pop('InteractionHint',None)
            b['CustomModel']=model_reference(b['CustomModel'])
            apply_block_support(name,b)
            d['BlockType']=b
            if name=='laboratory_bench':
                b['Bench']={'Type':'Crafting','Id':'SM_Laboratory','Categories':[{
                  'Id':'SM_Laboratory_All','Icon':'Icons/CraftingCategories/SM_Laboratory.png','Name':'server.benchCategories.sm.laboratory'}],
                  'LocalOpenSoundEventId':'SFX_Workbench_Open','LocalCloseSoundEventId':'SFX_Workbench_Close',
                  'CompletedSoundEventId':'SFX_Workbench_Craft','FailedSoundEventId':'SFX_Generic_Crafting_Failed'}
                b['BlockEntity']={'Components':{'BenchBlock':{}}}
                b['Gathering']={'Breaking':{'GatherType':'Benches'}}
                b['State']={'Definitions':{'CraftCompleted':{},'CraftCompletedInstant':{}}}
                d['MaxStack']=1
            d['Interactions']={'Primary':'Block_Primary','Secondary':'Block_Secondary'}
            if name in MACHINES and name not in ('time_dilation_block','resonant_conduit'): d['Interactions']['Use']={'Interactions':[{'Type':'SM_Use','Action':'machine'}]}
            height=2 if name=='resonite_door' else .5 if name.endswith('_slab') else .22 if name in ('levitation_pad','stasis_projector','resonite_trapdoor') else 1
            write(RES/f'Server/Item/Block/Hitboxes/StrangeMatter/{i}.json', {'Boxes':[{'Min':{'X':0,'Y':0,'Z':0},'Max':{'X':1,'Y':height,'Z':1}}]})
        else:
            d['Model']=model_reference(f'Items/StrangeMatter/{name}.blockymodel');d['Texture']=f'Items/StrangeMatter/{name}.png'
            if name in TOOLS or name.startswith('containment_capsule'):
                d['Interactions']={key:{'Interactions':[{'Type':'SM_Use','Action':action}]} for key,action in [('Primary','primary'),('Secondary','secondary'),('Use','use')]}
            if name=='tinfoil_hat':
                d['MaxStack']=1; d['Armor']={'ArmorSlot':'Head','BaseDamageResistance':0};d['Interactions']={'Secondary':{'Interactions':[{'Type':'EquipItem'}]}}
            def custom(action): return {'Type':'SM_Use','Action':action}
            def charging(start,finish,seconds):
                return {'Interactions':[custom(start),{'Type':'Charging','AllowIndefiniteHold':False,'Next':{'0':custom('cancel'),str(seconds):custom(finish)}}]}
            if name=='field_scanner': d['Interactions']={key:charging('scan_start','scan_complete',2.0) for key in ('Primary','Secondary','Use')}
            if name=='echo_vacuum': d['Interactions']={key:charging('vacuum_start','vacuum_complete',2.0) for key in ('Primary','Secondary','Use')}
            if name=='echoform_imprinter': d['Interactions']={'Primary':charging('imprint_start','imprint_complete',1.0),**{key:{'Interactions':[custom('imprint_revert')]} for key in ('Secondary','Use')}}
            if name=='chrono_blister': d['Interactions']={key:{'Interactions':[{'Type':'Charging','AllowIndefiniteHold':True,'Next':{'0':custom('cancel'),'1.0':custom('chrono_fire')}}]} for key in ('Primary','Secondary')}
            if name=='graviton_hammer':
                d['Interactions']={'Primary':{'Interactions':[custom('hammer0')]},'Secondary':{'Interactions':[{'Type':'Charging','AllowIndefiniteHold':True,'Next':{'0':custom('cancel'),'1.0':custom('hammer1'),'2.0':custom('hammer2'),'3.0':custom('hammer3')}}]}}
                d['PlayerAnimationsId']='Pickaxe'
                d['Tool']=json.loads(vanilla['Tool_Pickaxe_Thorium'].read_text())['Tool']
                d['Interactions']['Primary']['Cooldown']={'Id':'SM_Graviton_Hammer_Swing','Cooldown':.25,'ClickBypass':False}
            if name in ('warp_gun','chrono_blister','echo_vacuum'): d['MaxDurability']=200 if name=='echo_vacuum' else 100
        if standard[i]:
            r=standard[i][0]
            d['Recipe']={'Input':native_inputs(r['ingredients']), 'OutputQuantity':r['quantity'],'TimeSeconds':r['seconds'],'BenchRequirement':[{'Type':'Processing' if r['station']=='furnace' else 'Crafting','Id':'Furnace' if r['station']=='furnace' else 'SM_Laboratory',**({} if r['station']=='furnace' else {'Categories':['SM_Laboratory_All']})}]}
        if name=='laboratory_bench':
            d['Recipe']={'Input':[{'ItemId':'Bench_WorkBench','Quantity':1},{'ItemId':'Ingredient_Bar_Iron','Quantity':4},
              {'ItemId':'Ingredient_Bar_Copper','Quantity':2},{'ResourceTypeId':'Wood_Planks','Quantity':6}],
              'OutputQuantity':1,'TimeSeconds':3,'BenchRequirement':[{'Type':'Crafting','Id':'Workbench','Categories':['Workbench_Crafting']}]}
        for owner in (d,d.get('BlockType',{})):
            for key,interaction in owner.get('Interactions',{}).items():
                if isinstance(interaction,dict) and 'Interactions' in interaction:
                    interaction['RequireNewClick']=not (name=='graviton_hammer' and key=='Primary')
        write(RES/f'Server/Item/Items/StrangeMatter/{i}.json',d)
    # Secondary recipes (packing/unpacking and blasting) use standalone native recipe assets.
    for out,rs in standard.items():
        for r in rs[1:]:
            # Standalone recipes do not receive Item's implicit primary output. The
            # client indexes this field during UpdateRecipes even if Output is present.
            write(RES/f'Server/Item/Recipes/StrangeMatter/{hid(r["id"])}.json',{'Input':native_inputs(r['ingredients']),'PrimaryOutput':{'ItemId':out,'Quantity':r['quantity']},'Output':[{'ItemId':out,'Quantity':r['quantity']}],'TimeSeconds':r['seconds'],'BenchRequirement':[{'Type':'Processing' if r['station']=='furnace' else 'Crafting','Id':'Furnace' if r['station']=='furnace' else 'SM_Laboratory',**({} if r['station']=='furnace' else {'Categories':['SM_Laboratory_All']})}]})
    write(RES/'Server/Item/ResourceTypes/StrangeMatter/SM_Anomaly_Shards.json',{'Icon':'Icons/ResourceTypes/SM_Anomaly_Shards.png'})
    lang.extend(f'interactionHints.{key}=Press [{{key}}] to {description}' for key,description in INTERACTION_PROMPTS.items())
    lang.extend(['benchCategories.sm.laboratory=Strange Matter','ui.itemcategory.SM_StrangeMatter=Strange Matter'])
    p=RES/'Server/Languages/en-US/server.lang';p.parent.mkdir(parents=True,exist_ok=True)
    generated={line.split('=',1)[0] for line in lang}
    if p.exists(): lang.extend(line for line in p.read_text(encoding='utf8').splitlines() if '=' in line and line.split('=',1)[0] not in generated and not line.startswith(('interactionHints.SM_Till_Grass=','interactionHints.SM_Resonant_Conduit=','sm.interact=')))
    p.write_text('\n'.join(lang)+'\n',encoding='utf8')
    write(RES/'Server/StrangeMatter/recipes.json',recipes)
    apply_nullifier_content(RES)
    add_nullifier_recipe(recipes)
    write(ROOT/'docs/content-mapping.json',{'sourceItems':len(items),'sourceBlocks':len(blocks),'recipes':len(recipes),'portAddedItems':['SM_Laboratory_Bench','SM_Anomaly_Nullifier'],'nativeCraftingBench':'SM_Laboratory','vanillaIngredientMapping':MAP,'ingredientTagResources':RESOURCE_TAGS,'items':{n:hid(n) for n in items}})
    print(f'Generated {len(items)} items, {len(blocks)-1} placeable block types and {len(recipes)} audited recipes.')

if __name__=='__main__':main()
