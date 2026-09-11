"""Add native creative subsections without changing models, crafting, or item interactions."""
from pathlib import Path
import argparse
import json
from collections import Counter

ROOT=Path(__file__).resolve().parents[2];RES=ROOT/'src/main/resources';COMMON=RES/'Common'
ITEMS=RES/'Server/Item/Items/StrangeMatter'
GROUPS={'Gadgets':'SM_Field_Scanner','Machines':'SM_Resonant_Separator','Materials':'SM_Resonite_Ingot',
        'Decorations':'SM_Resonite_Chair','Building':'SM_Resonite_Roof','Nature':'SM_Anomalous_Grass','Research':'SM_Research_Notes'}
MACHINES={'SM_Anomaly_Nullifier','SM_Laboratory_Bench','SM_Research_Machine','SM_Reality_Forge','SM_Flux_Furnace',
 'SM_Pattern_Assembler','SM_Resonant_Separator','SM_Resonant_Burner','SM_Resonance_Condenser','SM_Rift_Stabilizer',
 'SM_Paradoxical_Energy_Cell','SM_Levitation_Pad','SM_Stasis_Projector','SM_Time_Dilation_Block','SM_Gravitic_Tube','SM_Resonant_Conduit'}
GADGETS={'SM_Anomaly_Resonator','SM_Field_Scanner','SM_Research_Tablet','SM_Graviton_Hammer','SM_Warp_Gun','SM_Echo_Vacuum',
 'SM_Echoform_Imprinter','SM_Hoverboard','SM_Tinfoil_Hat','SM_Chrono_Blister'}
BUILDING={'SM_Resonite_Block','SM_Fancy_Resonite_Tile','SM_Resonite_Tile','SM_Resonite_Floor_Panel','SM_Resonite_Tile_Slab','SM_Resonite_Tile_Stairs',
 'SM_Resonite_Pillar','SM_Resonite_Door','SM_Resonite_Trapdoor','SM_Resonite_Window','SM_Resonite_Ladder','SM_Lab_Cyan_Glass'}
HEADINGS={
 'ResearchEquipment':('Research equipment','Tools and workstations for research.'),
 'FieldEquipment':('Field equipment','Devices for studying and handling anomalies.'),
 'ContainmentCapsules':('Containment capsules','Empty capsules and contained anomalies.'),
 'PoweredTools':('Powered tools','Resonant tools for mining and travel.'),
 'PersonalEquipment':('Personal equipment','Equipment worn or carried by the scientist.'),
 'ProcessingMachines':('Processing machines','Machines for crafting and refining materials.'),
 'PowerEquipment':('Power equipment','Generate, store and carry resonant power.'),
 'FieldControl':('Field control','Devices that change the surrounding field.'),
 'ItemTransport':('Item transport','Move items between inventories.'),
 'AnomalyShards':('Anomaly shards','Six kinds of crystallized anomaly matter.'),
 'Metals':('Metals','Raw resonite and refined metal parts.'),
 'Concentrates':('Ore concentrates','Refined ore prepared for smelting.'),
 'Components':('Laboratory components','Parts for machines and powered equipment.'),
 'Furniture':('Laboratory furniture','Seating, storage and furnishings.'),
 'CrystalClusters':('Crystal clusters','Decorative clusters of anomaly shards.'),
 'StandingLamps':('Lamps','Standing lamps and laboratory lights.'),
 'Lanterns':('Lanterns','Compact lights made with anomaly shards.'),
 'Masonry':('Blocks and tiles','Resonite masonry, slabs, stairs and pillars.'),
 'Roofing':('Roofing','Resonite roofs in several shapes.'),
 'Entrances':('Doors and windows','Doors, hatches, windows and ladders.'),
 'Ores':('Natural ores','Resonite ore and deposits of anomaly shards.'),
 'AnomalousTerrain':('Anomalous soil','Grass and soil changed by anomalies.'),
}


def heading(item_id,item):
    if item_id in {'SM_Research_Tablet','SM_Research_Machine','SM_Research_Notes','SM_Laboratory_Bench'}:return 'ResearchEquipment'
    if item_id.startswith('SM_Containment_Capsule'):return 'ContainmentCapsules'
    if item_id in {'SM_Anomaly_Resonator','SM_Field_Scanner','SM_Echo_Vacuum','SM_Echoform_Imprinter'}:return 'FieldEquipment'
    if item_id in {'SM_Graviton_Hammer','SM_Warp_Gun'}:return 'PoweredTools'
    if item_id in {'SM_Chrono_Blister','SM_Tinfoil_Hat','SM_Hoverboard'}:return 'PersonalEquipment'
    if item_id in {'SM_Reality_Forge','SM_Resonant_Separator','SM_Flux_Furnace','SM_Pattern_Assembler'}:return 'ProcessingMachines'
    if item_id in {'SM_Resonant_Burner','SM_Resonance_Condenser','SM_Paradoxical_Energy_Cell','SM_Resonant_Conduit'}:return 'PowerEquipment'
    if item_id=='SM_Gravitic_Tube':return 'ItemTransport'
    if item_id in MACHINES:return 'FieldControl'
    if item_id.endswith('_Concentrate'):return 'Concentrates'
    if item_id.endswith('_Shard'):return 'AnomalyShards'
    if item_id in {'SM_Resonant_Circuit','SM_Resonant_Coil','SM_Stabilized_Core'}:return 'Components'
    if item_id.endswith('_Ore'):return 'Ores'
    if item_id in {'SM_Anomalous_Grass','SM_Anomalous_Dirt'}:return 'AnomalousTerrain'
    if item_id.endswith('_Crystal'):return 'CrystalClusters'
    if item_id.endswith('_Lantern'):return 'Lanterns'
    if item_id.endswith('_Lamp'):return 'StandingLamps'
    if item_id.startswith('SM_Resonite_Roof'):return 'Roofing'
    if item_id in {'SM_Resonite_Door','SM_Resonite_Trapdoor','SM_Resonite_Window','SM_Resonite_Ladder','SM_Lab_Cyan_Glass'}:return 'Entrances'
    if item_id in BUILDING:return 'Masonry'
    if 'BlockType' in item:return 'Furniture'
    return 'Metals'


def group(item_id,item):
    if item_id in MACHINES:return 'Machines'
    if item_id in GADGETS or item_id.startswith('SM_Containment_Capsule'):return 'Gadgets'
    if item_id=='SM_Research_Notes':return 'Research'
    if item_id.endswith('_Ore') or item_id in {'SM_Anomalous_Dirt','SM_Anomalous_Grass'}:return 'Nature'
    if item_id in BUILDING or item_id.startswith('SM_Resonite_Roof'):return 'Building'
    if 'BlockType' in item:return 'Decorations'
    return 'Materials'


def write(path,doc):path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(doc,indent=2)+'\n',encoding='utf-8')


def apply():
    entries={};headings={}
    for path in sorted(ITEMS.glob('SM_*.json')):
        item=json.loads(path.read_text(encoding='utf-8-sig'));category=group(path.stem,item)
        extras=['Research'] if path.stem in {'SM_Research_Tablet','SM_Research_Machine'} else []
        categories=[c for c in item.get('Categories',[]) if not c.startswith('SM_StrangeMatter')]
        item['Categories']=categories+['SM_StrangeMatter.All','SM_StrangeMatter.'+category]+['SM_StrangeMatter.'+g for g in extras]
        headings[path.stem]=heading(path.stem,item)
        item['SubCategory']='SM_'+headings[path.stem]
        write(path,item);entries[path.stem]=[category]+extras
    path=RES/'Server/Item/Category/CreativeLibrary/SM_StrangeMatter.json';doc=json.loads(path.read_text(encoding='utf-8-sig'))
    all_child=next(c for c in doc['Children'] if c['Id']=='All')
    doc['Children']=[all_child]+[{'Id':g,'Name':'server.ui.itemcategory.SM_StrangeMatter_'+g,
                                  'Icon':'Icons/ItemCategories/SM_StrangeMatter_'+g+'.png'} for g in GROUPS]
    for child in doc['Children']:
        present={headings[item] for item,tabs in entries.items() if child['Id']=='All' or child['Id'] in tabs}
        child['SubCategories']=[{'Id':'SM_'+key,'Name':'server.ui.itemcategory.subcategory.SM_'+key+'.name',
             'Description':'server.ui.itemcategory.subcategory.SM_'+key+'.description','Order':(i+1)*10}
             for i,key in enumerate(HEADINGS) if key in present]
    write(path,doc)
    language=RES/'Server/Languages/en-US/server.lang';text=language.read_text(encoding='utf-8-sig')
    lines=text.splitlines()
    labels={'ui.itemcategory.SM_StrangeMatter_'+g:g for g in GROUPS}
    for key,(name,description) in HEADINGS.items():
        labels['ui.itemcategory.subcategory.SM_'+key+'.name']=name
        labels['ui.itemcategory.subcategory.SM_'+key+'.description']=description
    for key,value in labels.items():
        line=key+'='+value
        found=next((i for i,value in enumerate(lines) if value.startswith(key+'=')),None)
        if found is None:lines.append(line)
        else:lines[found]=line
    language.write_text('\n'.join(lines)+'\n',encoding='utf-8')
    write(ROOT/'tools/assets/creative-groups.json',{'groups':list(GROUPS),'items':entries,'itemHeadings':headings})
    print('Creative sections:',dict(Counter(g for values in entries.values() for g in values)))


def icons():
    from PIL import Image,ImageDraw
    # Native navigation supplies the button plate. These assets contain only a white glyph,
    # just like the existing All icon. Supersampling keeps the tiny silhouettes readable.
    for g in ['Root',*GROUPS]:
        size=88 if g=='Root' else 48
        scale=4;image=Image.new('RGBA',(size*scale,size*scale));draw=ImageDraw.Draw(image)
        unit=size*scale/48
        def points(p):return [(round(x*unit),round(y*unit)) for x,y in p]
        def line(p,w=3):draw.line(points(p),fill='white',width=max(1,round(w*unit)),joint='curve')
        def poly(p,fill='white'):draw.polygon(points(p),fill=fill)
        def box(b,fill='white'):draw.rectangle(tuple(v*unit for v in b),fill=fill)
        def oval(b,fill='white',width=0):
            draw.ellipse(tuple(v*unit for v in b),fill=fill if not width else None,outline='white' if width else None,width=max(1,round(width*unit)))
        if g=='Root':
            poly([(18,9),(30,9),(30,13),(28,13),(28,22),(36,35),(35,39),(33,40),(15,40),(12,37),(13,34),(20,22),(20,13),(18,13)])
            poly([(22,14),(26,14),(26,23),(33,35),(32,37),(16,37),(15,35),(22,23)],(0,0,0,0))
            poly([(19,29),(29,29),(33,35),(32,37),(16,37),(15,35)])
            oval((33,14,37,18));oval((12,22,15,25));oval((29,23,32,26))
        elif g=='Gadgets':
            line([(16,13),(12,9)],2);line([(32,13),(36,9)],2)
            box((14,15,34,38));box((17,18,31,28),(0,0,0,0))
            line([(18,24),(22,20),(25,25),(29,21)],2);oval((18,31,22,35),(0,0,0,0));oval((27,31,31,35),(0,0,0,0))
            line([(20,11),(24,7),(28,11)],2);line([(24,7),(24,13)],2)
        elif g=='Machines':
            box((9,33,39,39));box((12,14,17,32));box((31,14,36,32));box((10,10,38,15))
            oval((19,18,29,29),width=3);line([(24,15),(24,18)],2);line([(24,29),(24,33)],2)
            box((13,35,17,37),(0,0,0,0));box((31,35,35,37),(0,0,0,0))
        elif g=='Materials':
            poly([(24,6),(34,18),(28,31),(20,31),(14,18)]);line([(24,9),(24,28)],2)
            line([(15,18),(23,20),(32,18)],2)
            poly([(10,32),(27,32),(34,37),(30,41),(7,41),(6,36)])
            box((11,35,25,37),(0,0,0,0))
        elif g=='Decorations':
            box((13,8,35,27));box((17,12,31,23),(0,0,0,0));box((10,27,38,33))
            box((13,33,17,41));box((31,33,35,41));line([(11,20),(11,28)],3);line([(37,20),(37,28)],3)
        elif g=='Building':
            poly([(5,22),(24,6),(43,22),(40,26),(24,13),(8,26)])
            box((11,24,37,40));box((22,28,26,40),(0,0,0,0));box((15,28,19,32),(0,0,0,0));box((29,28,33,32),(0,0,0,0))
        elif g=='Nature':
            line([(24,40),(24,18)],3)
            poly([(23,27),(15,26),(9,16),(9,10),(16,11),(23,18)])
            poly([(26,23),(32,22),(39,12),(39,6),(31,9),(26,15)])
            line([(10,40),(15,35),(21,37),(26,34),(32,36),(38,40)],3)
        elif g=='Research':
            poly([(7,11),(18,9),(24,12),(30,9),(41,11),(41,36),(30,34),(24,38),(18,34),(7,36)])
            line([(24,14),(24,34)],2)
            box((11,15,18,18),(0,0,0,0));box((11,21,20,23),(0,0,0,0));box((11,27,20,29),(0,0,0,0))
            box((29,15,37,18),(0,0,0,0));box((28,21,37,23),(0,0,0,0));box((28,27,37,29),(0,0,0,0))
            # The fold is a transparent gap rather than a dark painted line.
            box((23,14,25,34),(0,0,0,0))
        image=image.resize((size,size),Image.Resampling.LANCZOS)
        # Unassociated RGB in transparent antialias pixels stays white too.
        alpha=image.getchannel('A');image=Image.new('RGBA',(size,size),'white');image.putalpha(alpha)
        name='SM_StrangeMatter' if g=='Root' else 'SM_StrangeMatter_'+g
        image.save(COMMON/'Icons/ItemCategories'/(name+'.png'))


def preview():
    from PIL import Image,ImageDraw
    from build_shared_buttons import font,nine_slice,OUT
    doc=json.loads((ROOT/'tools/assets/creative-groups.json').read_text());counts=Counter(g for values in doc['items'].values() for g in values)
    image=Image.new('RGBA',(1080,700),(8,18,31,255));draw=ImageDraw.Draw(image)
    draw.text((36,24),'STRANGE MATTER   /   CREATIVE COLLECTION',font=font(24,True),fill='#b7f6ff')
    draw.text((36,63),'Native category definitions and current item icons. Static preview only.',font=font(15),fill='#829bad')
    for row,g in enumerate(GROUPS):
        y=112+row*77
        image.alpha_composite(nine_slice(Image.open(OUT/'Secondary_Default.png').convert('RGBA'),1008,66),(36,y))
        image.alpha_composite(Image.open(COMMON/'Icons/ItemCategories'/('SM_StrangeMatter_'+g+'.png')).convert('RGBA').resize((52,52)),(45,y+7))
        draw.text((114,y+12),g,font=font(17,True),fill='#d8f0f4');draw.text((114,y+37),str(counts[g])+' items',font=font(12),fill='#829bad')
        members=[item for item,groups in doc['items'].items() if g in groups]
        for i,item in enumerate(members[:11]):
            icon=Image.open(COMMON/'Icons/ItemsGenerated'/(item+'.png')).convert('RGBA');icon.thumbnail((46,46),Image.Resampling.LANCZOS)
            image.alpha_composite(icon,(318+i*62+(46-icon.width)//2,y+10+(46-icon.height)//2))
    path=ROOT/'docs/art/creative-groups-preview.png';image.convert('RGB').save(path);print(path)
    # A second view shows actual native SubCategories as section headings, without inventing
    # a new level of tabs or setting the editor's adjacent item description mode.
    category=json.loads((RES/'Server/Item/Category/CreativeLibrary/SM_StrangeMatter.json').read_text())
    image=Image.new('RGBA',(1160,870),(8,18,31,255));draw=ImageDraw.Draw(image)
    draw.text((32,22),'STRANGE MATTER   /   CREATIVE GROUP HEADINGS',font=font(23,True),fill='#b7f6ff')
    draw.text((32,58),'Current icon pixels, item assignments and native heading definitions. Static preview only.',font=font(14),fill='#829bad')
    navigation=[category,*category['Children']]
    for i,tab in enumerate(navigation):
        x=32+i*124
        # This is a preview of the native navigation surface, not part of any icon bitmap.
        draw.rounded_rectangle((x,95,x+104,181),radius=5,fill='#1c2b40',outline='#395168')
        glyph=Image.open(COMMON/tab['Icon']).convert('RGBA');glyph.thumbnail((48,48),Image.Resampling.LANCZOS)
        image.alpha_composite(glyph,(x+(104-glyph.width)//2,103))
        draw.text((x+52,165),'Main' if i==0 else tab['Id'],font=font(12),fill='#c9dbe4',anchor='mm')
    for column,tab_name in enumerate(['Machines','Materials','Decorations']):
        x=32+column*378;y=217
        draw.text((x,y),tab_name.upper(),font=font(18,True),fill='#b7f6ff');y+=38
        tab=next(c for c in category['Children'] if c['Id']==tab_name)
        for section in tab['SubCategories']:
            key=section['Id'].removeprefix('SM_')
            draw.line((x,y+23,x+350,y+23),fill='#30475e')
            draw.text((x,y),HEADINGS[key][0],font=font(14,True),fill='#c7b8e6');y+=31
            members=[item for item,tabs in doc['items'].items() if tab_name in tabs and doc['itemHeadings'][item]==key]
            for i,item in enumerate(members):
                ix=x+(i%7)*49;iy=y+(i//7)*48
                draw.rectangle((ix,iy,ix+42,iy+42),fill='#132236')
                icon=Image.open(COMMON/'Icons/ItemsGenerated'/(item+'.png')).convert('RGBA');icon.thumbnail((38,38),Image.Resampling.LANCZOS)
                image.alpha_composite(icon,(ix+(42-icon.width)//2,iy+(42-icon.height)//2))
            y+=((len(members)+6)//7)*48+19
    path=ROOT/'docs/art/creative-native-groups-preview.png';image.convert('RGB').save(path);print(path)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--apply',action='store_true');parser.add_argument('--icons',action='store_true');parser.add_argument('--preview',action='store_true');args=parser.parse_args()
    if args.apply:apply()
    if args.icons:icons()
    if args.preview:preview()
