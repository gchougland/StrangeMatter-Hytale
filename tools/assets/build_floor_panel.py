"""Targeted Resonite Floor Panel authoring and the Lab Lamp optical correction.

--write snapshots resources before creating this one model, atlas and icon.
Existing art is read only. Default execution renders a static current preview.
Requires the established Pillow/numpy asset runtime only when authoring/rendering.
"""
from pathlib import Path
import argparse, copy, datetime, hashlib, json, zipfile

ROOT=Path(__file__).resolve().parents[2];RES=ROOT/'src/main/resources';COMMON=RES/'Common'
ID='SM_Resonite_Floor_Panel';SOURCE='resonite_floor_panel'
MODEL=COMMON/f'Blocks/StrangeMatter/{SOURCE}.blockymodel'
TEXTURE=COMMON/f'Blocks/StrangeMatter/{SOURCE}.png'
ICON=COMMON/f'Icons/ItemsGenerated/{ID}.png'
ITEM=RES/f'Server/Item/Items/StrangeMatter/{ID}.json'
LAMP=RES/'Server/Item/Items/StrangeMatter/SM_Lab_Lamp.json'
OUT=ROOT/'build/floor-panel-revision'

def read(path):return json.loads(path.read_text(encoding='utf-8-sig'))
def write(path,value):
    path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(value,indent=2)+'\n',encoding='utf-8')

def item_asset():
    return {'TranslationProperties':{'Name':f'server.items.{ID}.name','Description':f'server.items.{ID}.description'},
      'Icon':ICON.relative_to(COMMON).as_posix(),'MaxStack':100,'Categories':['Blocks','SM_StrangeMatter.All','SM_StrangeMatter.Building'],
      'SubCategory':'SM_Masonry','PlayerAnimationsId':'Block','Quality':'Uncommon','Tags':{'Type':['StrangeMatter']},
      'BlockType':{'Material':'Solid','DrawType':'Model','Opacity':'Solid','CustomModel':MODEL.relative_to(COMMON).as_posix(),
        'CustomModelTexture':[{'Texture':TEXTURE.relative_to(COMMON).as_posix(),'Weight':1}],
        'HitboxType':'Full','VariantRotation':'NESW','Gathering':{'Breaking':{'GatherType':'Rocks'}},
        'BlockParticleSetId':'Stone','ParticleColor':'#263c53','BlockSoundSetId':'Stone','PhysicalMaterialId':'Stone',
        'Supporting':{'BlockSides':[{'FaceType':'Full'}]}},
      'Interactions':{'Primary':'Block_Primary','Secondary':'Block_Secondary'},
      'Recipe':{'Input':[{'ItemId':'SM_Resonite_Tile','Quantity':4},{'ItemId':'SM_Resonite_Ingot','Quantity':1}],
        'OutputQuantity':4,'TimeSeconds':2.0,'BenchRequirement':[{'Type':'Crafting','Id':'SM_Laboratory','Categories':['SM_Laboratory_Building']}],
        'KnowledgeRequired':True}}

def snapshot():
    folder=ROOT/'build/art-preservation'/('floor-panel-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ'))
    folder.mkdir(parents=True)
    with zipfile.ZipFile(folder/'Resources.zip','w',zipfile.ZIP_DEFLATED) as archive:
        for path in RES.rglob('*'):
            if path.is_file():archive.write(path,path.relative_to(RES).as_posix())
    hashes={p.relative_to(COMMON).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in COMMON.rglob('*') if p.is_file()}
    write(folder/'Common-before.json',hashes)
    return folder,hashes

def painted_tiles():
    from PIL import Image,ImageDraw
    # The outer rows/columns are equal and connect without discontinuous trim.
    # Paint is original code-native pixel geometry, matching the established atlas workflow.
    tiles=[]
    for kind in ('top','side','bottom'):
        image=Image.new('RGBA',(32,32),(18,29,45,255));draw=ImageDraw.Draw(image)
        draw.rectangle((1,1,30,30),fill=(38,55,76,255))
        if kind=='top':
            outer=[(8,3),(23,3),(28,8),(28,23),(23,28),(8,28),(3,23),(3,8)]
            inner=[(9,5),(22,5),(26,9),(26,22),(22,26),(9,26),(5,22),(5,9)]
            draw.polygon(outer,fill=(62,81,101,255));draw.polygon(inner,fill=(27,43,63,255))
            draw.line([(9,6),(22,6),(25,9)],fill=(46,65,87,255))
            draw.line([(6,10),(6,22),(9,25)],fill=(36,55,78,255))
            draw.rectangle((10,13,21,18),fill=(23,37,55,255))
            for x in (10,14,18):draw.line((x,15,x+1,15),fill=(67,91,108,255))
            for x in (2,29):
                draw.line((x,12,x,19),fill=(28,155,171,255));draw.line((x,14,x,17),fill=(93,220,222,255))
            for y in (2,29):
                draw.line((12,y,19,y),fill=(28,155,171,255));draw.line((14,y,17,y),fill=(93,220,222,255))
            for x,y in ((6,6),(25,6),(6,25),(25,25)):draw.point((x,y),fill=(105,127,141,255))
        elif kind=='side':
            draw.rectangle((3,5,28,26),fill=(26,42,61,255));draw.line((3,5,28,5),fill=(58,76,97,255))
            draw.line((3,25,28,25),fill=(15,27,41,255))
            draw.rectangle((7,12,24,19),fill=(17,31,47,255))
            for y in (14,17):draw.line((10,y,21,y),fill=(40,61,81,255))
            for x in (4,27):draw.line((x,14,x,17),fill=(42,160,178,255))
        else:
            draw.rectangle((3,3,28,28),fill=(25,39,56,255));draw.rectangle((6,6,25,25),outline=(40,55,72,255))
        tiles.append(image)
    return tiles

def author_art():
    from PIL import Image
    import build_assets as art
    atlas=Image.new('RGBA',(128,64));tiles=painted_tiles()
    for index,tile in enumerate(tiles):
        x=1+index*34;y=1;atlas.paste(tile,(x,y))
        for px in range(-1,33):
            for py in (-1,32):atlas.putpixel((x+px,y+py),tile.getpixel((max(0,min(31,px)),max(0,min(31,py)))))
        for py in range(32):
            atlas.putpixel((x-1,y+py),tile.getpixel((0,py)));atlas.putpixel((x+32,y+py),tile.getpixel((31,py)))
    model=art.Model(SOURCE,True).box('flush_floor_panel',(0,16,0),(32,32,32),'navy')
    layout=model.nodes[0]['shape']['textureLayout']
    for face in ('front','back','left','right','top','bottom'):
        x=1 if face=='top' else 69 if face=='bottom' else 35
        layout[face]={'offset':{'x':x,'y':1},'mirror':{'x':False,'y':False},'angle':0}
    write(MODEL,{'nodes':model.nodes,'format':'prop','lod':'auto'})
    atlas.save(TEXTURE,optimize=True)

def content():
    from fixture_toggles import apply as switch
    write(ITEM,item_asset())
    lamp=read(LAMP);switch('SM_Lab_Lamp',lamp,RES,write_models=False);write(LAMP,lamp)
    path=RES/'Server/StrangeMatter/recipes.json';recipes=read(path)
    record={'id':SOURCE,'source':'Hytale floor panel/'+ID,'output':ID,'quantity':4,
      'ingredients':{'SM_Resonite_Tile':4,'SM_Resonite_Ingot':1},'shards':{},'research':'resonite','seconds':2.0,'station':'workbench'}
    write(path,[entry for entry in recipes if entry['id']!=SOURCE]+[record])
    labels={f'items.{ID}.name':'Resonite Floor Panel',f'items.{ID}.description':'A smooth navy floor panel with cyan guide marks. Mix with resonite tiles for clear paths and work areas.'}
    path=RES/'Server/Languages/en-US/server.lang';lines=path.read_text(encoding='utf-8-sig').splitlines()
    lines=[line for line in lines if line.split('=',1)[0].strip() not in labels]
    path.write_text('\n'.join(lines)+'\n'+'\n'.join(k+'='+v for k,v in labels.items())+'\n',encoding='utf-8')
    path=ROOT/'tools/assets/catalog.json';catalog=read(path)
    entry={'sourceId':SOURCE,'id':ID,'block':True,'model':MODEL.relative_to(COMMON).as_posix(),
      'texture':TEXTURE.relative_to(COMMON).as_posix(),'icon':ICON.relative_to(COMMON).as_posix(),'atlas':[128,64],'nodes':1,
      'boundsModelUnits':{'min':[-16,0,-16],'max':[16,32,16]}}
    catalog['items']=[old for old in catalog['items'] if old['id']!=ID]+[entry];write(path,catalog)
    path=ROOT/'tools/assets/creative-groups.json';groups=read(path)
    groups['items'][ID]=['Building'];groups['itemHeadings'][ID]='Masonry';write(path,groups)

def preview():
    from PIL import Image,ImageDraw
    import render_current_icons as render
    import numpy as np
    OUT.mkdir(parents=True,exist_ok=True)
    faces=render.item_faces(read(ITEM));old=render.item_faces(read(RES/'Server/Item/Items/StrangeMatter/SM_Resonite_Tile.json'))
    tiled=[]
    for x in range(3):
        for z in range(3):
            for vertices,uv,texture,*rest in faces:tiled.append((vertices+np.array([x*32,0,z*32]),uv,texture,*rest))
    render.contact([('Resonite Floor Panel',faces),('Existing Resonite Tile',old),('Nine connected floor panels',tiled)],OUT/'floor-panel-preview.png',cols=3,cell=400)
    texture=Image.open(TEXTURE).convert('RGBA');tile=texture.crop((1,1,33,33));surface=Image.new('RGBA',(96,96))
    for x in range(3):
        for y in range(3):surface.paste(tile,(x*32,y*32))
    surface.resize((576,576),Image.Resampling.NEAREST).save(OUT/'floor-panel-tiling.png')
    print(OUT/'floor-panel-preview.png')

def apply():
    folder,before=snapshot();author_art();content()
    from PIL import Image
    import render_current_icons as render
    render.render(render.item_faces(read(ITEM)),size=192,yaw=35,pitch=23).resize((64,64),Image.Resampling.LANCZOS).save(ICON,optimize=True)
    changed=[name for name,digest in before.items() if hashlib.sha256((COMMON/name).read_bytes()).hexdigest()!=digest]
    allowed={p.relative_to(COMMON).as_posix() for p in (MODEL,TEXTURE,ICON)}
    assert set(changed)<=allowed,changed
    write(OUT/'revision.json',{'snapshot':folder.relative_to(ROOT).as_posix(),'item':ID,'atlas':[128,64],
      'textureBytes':TEXTURE.stat().st_size,'existingCommonFilesPreserved':len(before)-len(set(before)&allowed),'changedExistingCommon':changed,
      'labLampCause':'Native flood propagation skips Solid source blocks; optical opacity is now Transparent while physical material and collider stay Solid/Full.'})
    preview()

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--write',action='store_true');args=parser.parse_args()
    if args.write:apply()
    else:preview()
