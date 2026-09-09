"""Targeted 0.3 art/content revision. Does not run any broad content generator.

Only the explicitly requested lamps, raw resonite, Shade palette and hat attachment
are edited. Current models/textures elsewhere are preserved for icons-only rendering.
"""
from pathlib import Path
import colorsys, copy, json, math
import numpy as np
from PIL import Image, ImageDraw
import build_assets as art
import render_current_icons as render
from content_policy import family_light

ROOT=art.ROOT;COMMON=art.COMMON;RES=ROOT/'src/main/resources'
ITEMS=RES/'Server/Item/Items/StrangeMatter'
FAMILIES=('gravitic','chrono','energetic','spatial','shade','insight')
SHADE=(59,119,157)

def read(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def write(p,d):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(d,indent=2)+'\n',encoding='utf8')
def shade_texture(image):
    rgba=np.array(image.convert('RGBA'));rgb=rgba[:,:,:3].astype(float)
    mask=(rgb[:,:,0]>rgb[:,:,1]*1.12)&(rgb[:,:,2]>rgb[:,:,0]*1.08)&(rgb[:,:,2]>70)&(rgba[:,:,3]>0)
    target_h,_,_=colorsys.rgb_to_hsv(*(v/255 for v in SHADE))
    for y,x in np.argwhere(mask):
        h,s,v=colorsys.rgb_to_hsv(*(rgb[y,x]/255));r,g,b=colorsys.hsv_to_rgb(target_h,min(.78,s),v*(157/237))
        rgba[y,x,:3]=[round(r*255),round(g*255),round(b*255)]
    return Image.fromarray(rgba)

def lamp(family):
    name=family+'_shard_lamp';m=art.Model(name,True);color=art.family(name)
    m.box('weighted_foot',(0,2,0),(24,4,24),'dark')
    m.box('stepped_base',(0,4.5,0),(20,3,20),'navy')
    m.ring('base_inlay',(0,6.2,0),7,1,.5,color,axis='y',count=12)
    m.box('upright',(0,24,0),(5,35,5),'navy')
    for z in (-2.6,2.6):m.box('luminous_spine',(0,24,z),(1.5,31,.25),color,glow=True)
    for y in (8,37):m.box('stem_collar',(0,y,0),(8,3,8),'copper')
    m.box('head_pedestal',(0,40,0),(16,3,16),'edge')
    m.ring('lower_crown',(0,42,0),9,1.8,2,'copper',axis='y',count=8)
    m.crystal('suspended_lamp_crystal',(0,51,0),17,color,w=7)
    for x in (-8,8):
        for z in (-8,8):
            m.box('cage_upright',(x,51,z),(1.5,18,1.5),'edge')
            m.box('corner_finial',(x,61,z),(3,3,3),'copper')
    m.ring('upper_crown',(0,60,0),9,1.8,2,'copper',axis='y',count=8)
    m.box('canopy',(0,62,0),(22,2,22),'navy')
    m.box('canopy_cap',(0,63.5,0),(14,1,14),'edge')
    entry=m.save()
    if family=='shade':shade_texture(Image.open(COMMON/entry['texture'])).save(COMMON/entry['texture'])
    item=read(ITEMS/(art.hid(name)+'.json'));b=item['BlockType']
    b['Light']={'Color':family_light(family,True),'Radius':15}
    b['CustomModel']=entry['model'];b['CustomModelTexture']=[{'Texture':entry['texture'],'Weight':1}]
    b['VariantRotation']='NESW';b['HitboxType']=art.hid(name);b['InteractionHitboxType']=art.hid(name)
    b['PlacementSettings']={'AllowRotationKey':True}
    write(RES/f'Server/Item/Block/Hitboxes/StrangeMatter/{art.hid(name)}.json',{'Boxes':[
      {'Min':{'X':.125,'Y':0,'Z':.125},'Max':{'X':.875,'Y':.22,'Z':.875}},
      {'Min':{'X':.42,'Y':.20,'Z':.42},'Max':{'X':.58,'Y':1.25,'Z':.58}},
      {'Min':{'X':.15625,'Y':1.25,'Z':.15625},'Max':{'X':.84375,'Y':2,'Z':.84375}}]})
    write(ITEMS/(art.hid(name)+'.json'),item)

def raw_resonite():
    m=art.Model('raw_resonite')
    for index,(p,size,rotation) in enumerate([
      ((-4,7,1),(13,11,12),(12,18,-12)),((5,6,-2),(11,10,12),(-9,-18,17)),
      ((1,11,2),(10,8,10),(18,30,8)),((-5,4,-4),(9,7,8),(-15,9,15))]):
        m.box('rough_ferrous_matrix'+str(index),p,size,'stone',rotation)
    for index,(p,height,width,rotation) in enumerate([
      ((-3,13,3),11,4,(9,-17,-21)),((5,11,0),9,3,(20,11,25)),
      ((0,10,6),7,4,(-38,8,-9)),((-7,8,-3),6,3,(0,12,-35))]):
        m.crystal('unrefined_resonite'+str(index),p,height,'cyan',rotation,w=width)
    m.box('exposed_vein',(-4,6,7),(8,2,.8),'cyan',(0,0,-12))
    m.save()

def hat():
    p=COMMON/'Items/StrangeMatter/tinfoil_hat_worn.blockymodel';model=read(p);root=model['nodes'][0]
    assert root['name']=='Head','Review changed hat attachment before patching'
    if root['shape']['type']=='box':
        # Native armor binds an empty Head piece to the skin's Head center. A box
        # merged into that piece is not equivalent to a brim child above the head.
        brim=copy.deepcopy(root);brim['id']='9001';brim['name']='foil_brim';brim['position']=art.V((0,0,0))
        brim['shape']['settings']['isPiece']=False
        brim['shape']['offset']['y']=14.4+brim['shape']['settings']['size']['y']*brim['shape']['stretch']['y']/2
        model['nodes']=[{'id':'9000','name':'Head','position':art.V((0,0,-2)),'orientation':art.quat(),
          'shape':{'type':'none','offset':art.V((0,15,3)),'stretch':art.V((1,1,1)),
            'settings':{'isPiece':True},'textureLayout':{},'unwrapMode':'custom','visible':True,
            'doubleSided':False,'shadingMode':'flat'},'children':[brim]}]
    else:
        assert root['shape']['type']=='none' and any(n['name']=='foil_brim' for n in root.get('children',[])), 'Review changed hat hierarchy before patching'
    write(p,model)

def shade_family():
    paths=[COMMON/'Items/StrangeMatter/shade_shard.png']
    paths += [COMMON/f'Blocks/StrangeMatter/shade_shard_{kind}.png' for kind in ('crystal','ore','lantern')]
    for p in paths:shade_texture(Image.open(p)).save(p)
    for kind in ('crystal','ore','lamp','lantern'):
        p=ITEMS/f'SM_Shade_Shard_{kind.title()}.json';item=read(p);block=item['BlockType'];block['ParticleColor']='#3b779d'
        if 'Light' in block:block['Light']['Color']='#235' if kind=='crystal' else '#59f'
        write(p,item)

def category_icons():
    # Match native category dimensions:88px root plate and48px child glyph.
    def flask(size,plate):
        scale=4;im=Image.new('RGBA',(size*scale,size*scale));d=ImageDraw.Draw(im)
        def pts(points):return [(int(x*size*scale/88),int(y*size*scale/88)) for x,y in points]
        if plate:
            d.rounded_rectangle((4,4,size*scale-5,size*scale-5),radius=9*scale,fill=(53,65,79),outline=(111,129,145),width=scale)
            d.rounded_rectangle((9,9,size*scale-10,size*scale-10),radius=7*scale,outline=(31,43,61),width=2*scale)
        outline=pts([(32,18),(54,18),(54,25),(50,25),(50,39),(65,64),(64,70),(60,73),(27,73),(23,70),(23,64),(37,39),(37,25),(32,25)])
        d.polygon(outline,fill=(219,233,231) if plate else (255,255,255))
        d.polygon(pts([(41,26),(46,26),(46,41),(60,65),(59,68),(29,68),(28,65),(41,41)]),fill=(27,45,66) if plate else (0,0,0,0))
        d.polygon(pts([(35,53),(53,53),(60,65),(59,68),(29,68),(28,65)]),fill=(76,221,224) if plate else (255,255,255))
        for x,y,r in [(62,29,3),(28,39,2),(55,45,2)]:
            a,b=pts([(x-r,y-r),(x+r,y+r)]);d.ellipse((*a,*b),fill=(186,120,240) if plate else (255,255,255))
        return im.resize((size,size),Image.Resampling.LANCZOS)
    for name,size,plate in [('SM_StrangeMatter',88,True),('SM_StrangeMatter_All',48,False)]:
        p=COMMON/f'Icons/ItemCategories/{name}.png';p.parent.mkdir(parents=True,exist_ok=True);flask(size,plate).save(p)
    im=Image.new('RGBA',(192,192));d=ImageDraw.Draw(im)
    for x,y,color in [(42,104,(118,192,211)),(96,80,(199,157,249)),(148,105,(244,206,121))]:
        d.polygon([(x,y-62),(x+23,y-27),(x+23,y+38),(x,y+62),(x-23,y+38),(x-23,y-27)],fill=color)
        d.line([(x,y-60),(x,y+58)],fill=(246,252,255),width=5)
    im.resize((48,48),Image.Resampling.LANCZOS).save(COMMON/'Icons/CraftingCategories/SM_Laboratory_Shards.png')

def content():
    machines=set(art.MACHINES)
    for p in ITEMS.glob('*.json'):
        item=read(p);name=p.stem.removeprefix('SM_').lower();block=item.get('BlockType')
        current=item.get('MaxStack',1)
        if name.startswith('containment_capsule_') or current==1 and not block:item['MaxStack']=1
        elif name=='containment_capsule' or name=='research_notes' or name in machines or name.endswith(('_lamp','_lantern')):item['MaxStack']=25
        else:item['MaxStack']=100
        item['Categories']=[c for c in item.get('Categories',[]) if not c.startswith('SM_StrangeMatter')]+['SM_StrangeMatter.All']
        if 'Recipe' in item:
            recipe=item['Recipe']
            if any(b['Type']=='Crafting' for b in recipe.get('BenchRequirement',[])):
                recipe['KnowledgeRequired']=True
                if name.endswith(('_shard','_crystal','_lamp','_lantern')):
                    for bench in recipe['BenchRequirement']:
                        if bench['Id']=='SM_Laboratory':bench['Categories']=['SM_Laboratory_Shards']
            else:recipe.pop('KnowledgeRequired',None)
        if name=='laboratory_bench':
            categories=block['Bench']['Categories'];categories[:]=[c for c in categories if c['Id']!='SM_Laboratory_Shards']
            categories.append({'Id':'SM_Laboratory_Shards','Icon':'Icons/CraftingCategories/SM_Laboratory_Shards.png','Name':'server.benchCategories.sm.shards'})
        write(p,item)
    for p in (RES/'Server/Item/Recipes/StrangeMatter').glob('*.json'):
        recipe=read(p)
        if any(b['Type']=='Crafting' for b in recipe.get('BenchRequirement',[])):recipe['KnowledgeRequired']=True
        else:recipe.pop('KnowledgeRequired',None)
        write(p,recipe)
    write(RES/'Server/Item/Category/CreativeLibrary/SM_StrangeMatter.json',{
      'Id':'SM_StrangeMatter','Name':'server.ui.itemcategory.SM_StrangeMatter',
      'Icon':'Icons/ItemCategories/SM_StrangeMatter.png','Order':5,'Children':[{
        'Id':'All','Name':'server.ui.itemcategory.SM_StrangeMatter_All','Icon':'Icons/ItemCategories/SM_StrangeMatter_All.png'}]})
    lang=RES/'Server/Languages/en-US/server.lang';lines=lang.read_text(encoding='utf8').splitlines()
    additions={'ui.itemcategory.SM_StrangeMatter_All':'All Strange Matter','benchCategories.sm.shards':'Shards & Lighting'}
    lines=[line for line in lines if line.split('=',1)[0] not in additions];lines += [f'{k}={v}' for k,v in additions.items()]
    lang.write_text('\n'.join(lines)+'\n',encoding='utf8')
    catalog=RES/'Server/StrangeMatter/recipes.json';recipes=read(catalog)
    for recipe in recipes:
        values=recipe.get('ingredients',{})
        if 'Wood_Softwood_Planks' in values:values['resource:Wood_Planks']=values.get('resource:Wood_Planks',0)+values.pop('Wood_Softwood_Planks')
    write(catalog,recipes)

def main():
    for family in FAMILIES:lamp(family)
    raw_resonite();hat();shade_family();category_icons();content()
    print('Applied only requested art targets and native item/category/recipe patches. Run render_current_icons.py LAST.')

if __name__=='__main__':main()
