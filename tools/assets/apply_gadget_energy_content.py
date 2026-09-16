"""Apply only gadget-energy recipes and teaching; no broad asset regeneration."""
from pathlib import Path
import json
ROOT=Path(__file__).resolve().parents[2];RES=ROOT/'src/main/resources'
def read(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def write(p,obj):p.write_text(json.dumps(obj,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
def main():
    path=RES/'Server/StrangeMatter/recipes.json';recipes=read(path)
    additions=[
      ('resonant_charging_station','SM_Resonant_Charging_Station','resonant_energy','workbench',{'Ingredient_Bar_Iron':4,'Ingredient_Bar_Copper':2,'SM_Resonite_Ingot':2,'SM_Resonant_Coil':1},{}),
      ('resonant_battery_pack','SM_Resonant_Battery_Pack','resonant_battery_pack','forge',{'SM_Resonite_Ingot':6,'SM_Resonant_Coil':2,'SM_Resonant_Circuit':1},{'energetic':3,'gravitic':1}),
      ('gravitic_manipulator','SM_Gravitic_Manipulator','gravitic_manipulation','forge',{'SM_Resonite_Ingot':5,'SM_Resonant_Coil':2,'SM_Resonant_Circuit':1,'SM_Containment_Capsule_Gravity':1},{'gravitic':2,'chrono':1}),
      ('arc_projector','SM_Arc_Projector','arc_projection','forge',{'SM_Resonite_Ingot':5,'SM_Resonant_Coil':2,'SM_Resonant_Circuit':1,'SM_Containment_Capsule_Energetic':1},{'energetic':3})]
    for id,output,research,station,ingredients,shards in additions:
        recipe={'id':id,'source':'StrangeMatter gadget energy expansion','output':output,'quantity':1,'ingredients':ingredients,'shards':shards,'research':research,'seconds':2 if station=='workbench' else 6,'station':station}
        recipes=[r for r in recipes if r['id']!=id];recipes.append(recipe)
    write(path,recipes)
    path=RES/'Server/StrangeMatter/Research/Tablet.properties';text=path.read_text(encoding='utf-8')
    descriptions={
      'resonant_energy':('Resonant Energy Fundamentals','Generate Resonant Energy from fuel, store a reserve, route it through conduits and recharge your gadgets. Unlocks the burner, energy storage, charging station and basic resonant components.'),
      'resonant_battery_pack':('Resonant Battery Pack','A wearable reserve of Resonant Energy that automatically replenishes inventory gadgets. Occupies the chest equipment slot.'),
      'gravitic_manipulation':('Gravitic Manipulation','Suspend blocks, creatures and intact chests in front of you. Carry them safely or launch them with a focused gravity impulse.'),
      'arc_projection':('Arc Projection','Discharge a directed stream of electricity that branches through nearby enemies and briefly slows their advance.')}
    for id,(name,description) in descriptions.items():
        for key,value in ((id+'.name',name),(id+'.description',description)):
            lines=text.splitlines();found=False
            for i,line in enumerate(lines):
                if line.startswith(key+'='):lines[i]=key+'='+value;found=True
            if not found:lines.append(key+'='+value)
            text='\n'.join(lines)+'\n'
    path.write_text(text,encoding='utf-8')
    path=RES/'Server/StrangeMatter/Research/Teaching.json';teaching=read(path)
    teaching['resonant_energy']=[
      {'title':'Resonant Energy Fundamentals','content':'Gadgets store Resonant Energy (RE). This is electrical power, separate from the Energy observations in your research journal. Crafted gadgets begin at full charge. An empty gadget remains intact and can always be recharged.\n\nThe burner, conduits and charging station form your first power network. Build them at the Laboratory Bench and supply fuel to keep your equipment charged.'},
      {'title':'Fuel becomes power','content':'Craft the Resonant Burner at the Laboratory Bench. Fuel produces a shared store of RE. Its separate gadget dock charges a single gadget directly from that store; the ordinary inventory continues to accept fuel.\n\nThe dock transfers up to 200 RE per second while energy remains available. You may remove your gadget at any charge level.','recipe':'resonant_burner'},
      {'title':'Connect the laboratory','content':'Place Resonant Conduits between the burner and charging station. Connected machines share the actual generated supply: conduits cannot create power. A station directly adjacent to the burner can connect without an intervening conduit.\n\nCoils, conduits and the charging station use ordinary laboratory materials. No captured anomaly or Reality Forge is needed to establish charging.','recipe':'resonant_conduit'},
      {'title':'The charging cradle','content':'The charging station holds one gadget and buffers up to 10,000 RE. It transfers up to 800 RE per second while supplied with enough power. Its native gadget slot preserves the item and its stored energy when moved or saved.\n\nAutomated tubes insert depleted gadgets and extract them once full. Manual removal works at any charge level. Battery packs recharge in this same slot.','recipe':'resonant_charging_station'},
      {'title':'Keep your scanner ready','content':'A Field Scanner begins with 2,000 RE, enough for 100 fresh completed scans at the default settings. Cancelled scans and previously recorded fields cost nothing. Recharge before a long expedition.\n\nThe energy bar on the gadget HUD shows your remaining charge. Empty gadgets remain intact; put them in the burner dock or a supplied charging station to restore their power.'}]
    teaching['resonant_battery_pack']=[{'title':'Portable reserve','content':'Twin caged resonant cells carry 60,000 RE on your back. Equip the pack in the chest equipment slot. It provides no armor resistance.\n\nThe pack transfers up to 200 RE per second across your inventory. Your held gadget receives power first; other depleted gadgets take turns sharing the remaining allowance. Packs do not recharge each other.\n\nRemove the pack to stop automatic transfer. Recharge it in the station or burner dock.','recipe':'resonant_battery_pack'}]
    teaching['gravitic_manipulation']=[{'title':'Carry a suspended target','content':'Secondary captures the aimed ordinary block, dropped item or eligible creature within 8 blocks. It floats about 3 blocks ahead of you. Secondary again gently releases it.\n\nPrimary launches your held target along your aim. Blocks and chests settle in clear space. Single and joined chests keep their inventory, item data and facing; joined chests require room for the entire chest. Chest access pauses briefly while a placement is saved. If no safe placement is available, the original location is used when it becomes clear. The manipulator never overwrites occupied terrain. Other machines, fluids, players and protected blocks cannot be transported.\n\nAcquisition costs 100 RE, holding uses 40 RE per second and launching costs 300 RE. Releasing is always available, even when empty. Switching items, losing the target or leaving the world safely releases it.','recipe':'gravitic_manipulator'}]
    teaching['arc_projection']=[{'title':'Electricity under control','content':'Hold primary to fire five electrical pulses per second. Each pulse traces up to 18 blocks and can arc through three additional hostile enemies within 5 blocks of the preceding target. Walls stop both the first strike and subsequent hops.\n\nDamage falls to 75 percent at each hop. A brief movement slow helps contain crowds without permanent stuns. The bolt uses the same bright electrical filament seen between an Energetic Rift and a Rift Stabilizer.\n\nEvery fired pulse uses 200 RE, including misses. The 12,000 RE reserve allows 60 pulses before recharging. A battery pack extends this reserve but cannot sustain continuous fire.','recipe':'arc_projector'}]
    write(path,teaching)
    path=RES/'Server/Languages/en-US/server.lang';text=path.read_text(encoding='utf-8')
    values={
      'SM_Resonant_Charging_Station':('Resonant Charging Station','Recharge one gadget using conduit power. Stores 10,000 RE; transfers up to 800 RE/s. Crafted at the Laboratory Bench after Resonant Energy Fundamentals.'),
      'SM_Resonant_Battery_Pack':('Resonant Battery Pack','Equip in your chest slot to charge inventory gadgets from a 60,000 RE reserve. Prioritizes the held gadget. Recharge in a charging station or burner dock. No armor resistance.'),
      'SM_Gravitic_Manipulator':('Gravitic Manipulator','Secondary lifts or releases a block, creature or intact chest; primary launches it. Chests keep their inventory. Stores 12,000 RE. Acquisition, holding and launching consume power.'),
      'SM_Arc_Projector':('Arc Projector','Hold primary to fire electrical pulses that chain through nearby enemies. Stores 12,000 RE; each pulse costs 200 RE. Walls stop the arc.')}
    for id,(name,description) in values.items():
        for suffix,value in (('name',name),('description',description)):
            key=f'items.{id}.{suffix}';lines=[line for line in text.splitlines() if not line.startswith(key+'=')];lines.append(key+'='+value);text='\n'.join(lines)+'\n'
    hint='interactionHints.SM_Resonant_Charging_Station';lines=[line for line in text.splitlines() if not line.startswith(hint+'=')];lines.append(hint+'=Press [{key}] to charge a gadget');text='\n'.join(lines)+'\n'
    # Surface the persistent energy model in all powered device tooltips.
    for id in ('Field_Scanner','Echo_Vacuum','Anomaly_Resonator','Warp_Gun','Chrono_Blister','Graviton_Hammer','Hoverboard','Echoform_Imprinter'):
        key=f'items.SM_{id}.description=';lines=text.splitlines()
        for i,line in enumerate(lines):
            if line.startswith(key) and 'Resonant Energy' not in line:lines[i]=line+' Uses rechargeable Resonant Energy; crafted gadgets start fully charged.'
        text='\n'.join(lines)+'\n'
    path.write_text(text,encoding='utf-8')

if __name__=='__main__':
    main()
    from build_energy_storage import content
    content()
