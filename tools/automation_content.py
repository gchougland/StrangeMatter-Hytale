"""Targeted factory recipes and research text. Never regenerates existing art."""
from pathlib import Path
import json

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'src/main/resources'
RESEARCH = {
 'resonant_separation': ('Resonant Separation', 'Separate raw ore into twice the normal metal yield.', 'resonant_separator',
  'Supply the Resonant Separator with supported raw ore and resonant power. It makes twice the normal metal yield as concentrate. Smelt concentrate in a regular furnace or a Flux Furnace. Bars, blocks, shards and concentrate cannot be separated again.'),
 'flux_smelting': ('Flux Smelting', 'Heat ordinary furnace recipes with resonant energy.', 'flux_furnace',
  'The Flux Furnace uses resonant power instead of fuel. Insert ordinary furnace ingredients or metal concentrate and it chooses the recipe automatically. It keeps all recipe outputs and smelts twenty percent faster than a regular furnace at the same tier. Electricity makes no charcoal. Upgrade inside the machine to add five ingredient slots and improve its speed.'),
 'gravitic_transport': ('Gravitic Transport', 'Move supplies through carefully controlled gravity fields.', 'gravitic_tube',
  'Connect Gravitic Tubes to supported storage and machine inventories. Choose Take from container at the source and Send into container at the destination. Set the correct ingredient, fuel or finished item section. Filters, priority and stock limits control each connection. Tubes need no resonant power. Items stay in their source until the visible batch arrives safely.'),
 'pattern_assembly': ('Pattern Assembly', 'Repeat discovered Workbench recipes with stored ingredients.', 'pattern_assembler',
  'Press Choose Recipe to open the Workbench category tabs. Pick a discovered recipe, then supply its ingredients and resonant power. Craft once or enable Repeat and choose how many finished items to keep in this machine. The assembler works slowly for you while powered. The owner provides recipe knowledge, even while offline. Visitors cannot lend their discoveries. Upgrade using the ordinary Workbench tier materials plus resonant parts. Other crafting stations keep their own recipes.'),
}
def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False)+'\n', encoding='utf-8')
def recipe(id, output, ingredients, shards, research, station='forge', quantity=1):
    return dict(id=id, output=output, quantity=quantity, ingredients=ingredients, shards=shards, research=research, seconds=5, station=station, source='Hytale factory expansion')

def interaction_hints(manifest):
    hints={}
    for key, value in manifest.get('interactionHints',{}).items():
        key=key.removeprefix('server.').removeprefix('interactionHints.')
        action=value.removeprefix('Press [{key}] to ').removeprefix('[{key}] ').strip()
        hints['interactionHints.'+key]='Press [{key}] to '+action[:1].lower()+action[1:]
    return hints
def apply(resources=RES):
    path=resources/'Server/StrangeMatter/recipes.json'; recipes=json.loads(path.read_text(encoding='utf-8'))
    new=[
      recipe('resonant_separator','SM_Resonant_Separator',dict(SM_Resonite_Ingot=5,SM_Resonant_Coil=2,SM_Resonant_Circuit=2,SM_Stabilized_Core=1,SM_Containment_Capsule_Gravity=1),dict(gravitic=2,energetic=1),'resonant_separation'),
      recipe('flux_furnace','SM_Flux_Furnace',dict(Bench_Furnace=1,SM_Resonite_Ingot=5,SM_Resonant_Coil=2,SM_Resonant_Circuit=1,SM_Containment_Capsule_Energetic=1),dict(energetic=2),'flux_smelting'),
      recipe('pattern_assembler','SM_Pattern_Assembler',dict(Bench_WorkBench=1,SM_Resonite_Ingot=5,SM_Resonant_Circuit=3,SM_Resonant_Coil=2,SM_Stabilized_Core=1,SM_Containment_Capsule_Thoughtwell=1),dict(insight=2,gravitic=2),'pattern_assembly'),
      recipe('gravitic_tube','SM_Gravitic_Tube',dict(SM_Resonite_Nugget=5,SM_Resonant_Coil=1),dict(gravitic=1),'gravitic_transport','workbench',10),
    ]
    ids={r['id'] for r in new}; recipes=[r for r in recipes if r['id'] not in ids]+new; write_json(path,recipes)
    teaching_path=resources/'Server/StrangeMatter/Research/Teaching.json'; teaching=json.loads(teaching_path.read_text(encoding='utf-8'))
    for key,(name,description,rid,text) in RESEARCH.items(): teaching[key]=[dict(title=name,content=text,recipe=rid)]
    teaching['reality_forge'] += [] if any(p['title']=='Powered forging' for p in teaching['reality_forge']) else [dict(title='Powered forging',content='The Reality Forge now uses resonant power. Keep ingredients in its supply inventory. Starting a job reserves those exact materials. A lack of power pauses progress. Stop returns reserved ingredients without refunding power already used. Finished items stay in the output inventory. Repeat uses the owner\'s discoveries.')]
    write_json(teaching_path,teaching)
    properties=resources/'Server/StrangeMatter/Research/Tablet.properties'; lines=properties.read_text(encoding='utf-8').splitlines(); lines=[s for s in lines if not any(s.startswith(k+'.') for k in RESEARCH)]
    for key,(name,description,_,_) in RESEARCH.items(): lines.extend([f'{key}.name={name}',f'{key}.description={description}'])
    properties.write_text('\n'.join(lines)+'\n',encoding='utf-8')
    manifest=json.loads((ROOT/'tools/assets/automation-set.json').read_text(encoding='utf-8'))
    lang=resources/'Server/Languages/en-US/server.lang'; lines=lang.read_text(encoding='utf-8').splitlines(); keys={f'items.{i["id"]}.{field}' for i in manifest['items'] for field in ('name','description')}
    hints=interaction_hints(manifest);keys.update(hints)
    keys.update('interactionHints.'+key for key in manifest.get('interactionHints',{}))
    lines=[line for line in lines if line.split('=',1)[0] not in keys]
    for item in manifest['items']:
        lines.extend([f'items.{item["id"]}.name={item["name"]}',f'items.{item["id"]}.description={item["description"]}'])
    lines.extend(f'{key}={value}' for key,value in hints.items());lang.write_text('\n'.join(lines)+'\n',encoding='utf-8')
    seconds=dict(Copper=10,Iron=14,Gold=10,Thorium=18,Cobalt=18,Adamantite=20,Mithril=30,Silver=4,Onyxium=4,Prisma=4,Resonite=10)
    separator=[]
    for metal,time in seconds.items():
        bar='SM_Resonite_Ingot' if metal=='Resonite' else 'Ingredient_Bar_'+metal
        ore='SM_Raw_Resonite' if metal=='Resonite' else 'Ore_'+metal
        concentrate='SM_'+metal+'_Concentrate'; out=dict(ItemId=bar,Quantity=1)
        write_json(resources/f'Server/Item/Recipes/StrangeMatter/SM_{metal}_Concentrate_Smelting.json',dict(Input=[dict(ItemId=concentrate,Quantity=1)],PrimaryOutput=out,Output=[out],TimeSeconds=time,BenchRequirement=[dict(Type='Processing',Id='Furnace')]))
        separator.append(dict(id=metal,ore=ore,bar=bar,concentrate=concentrate))
    write_json(resources/'Server/StrangeMatter/Factory/Separator.json',separator)
    # These native components hold the real shared inventories. Keep them when a
    # developer deliberately reruns the original Minecraft content converter.
    for id in ('SM_Reality_Forge','SM_Resonant_Burner','SM_Resonance_Condenser','SM_Resonant_Separator','SM_Flux_Furnace','SM_Pattern_Assembler'):
        item_path=resources/f'Server/Item/Items/StrangeMatter/{id}.json'
        if not item_path.exists(): continue
        item=json.loads(item_path.read_text(encoding='utf-8')); components=item['BlockType'].setdefault('BlockEntity',{}).setdefault('Components',{})
        components.setdefault('SM_Factory',{})
        if id=='SM_Pattern_Assembler': components.setdefault('BenchBlock',{})
        write_json(item_path,item)
    desk_path=resources/'Server/Item/Items/StrangeMatter/SM_Research_Machine.json'
    if desk_path.exists():
        desk=json.loads(desk_path.read_text(encoding='utf-8'))
        desk['BlockType'].setdefault('BlockEntity',{}).setdefault('Components',{}).setdefault('ItemContainerBlock',{'Capacity':1})
        write_json(desk_path,desk)
if __name__=='__main__':
    apply()
    print('Factory content: 4 recipes, 4 research guides, 11 concentrate furnace recipes')
