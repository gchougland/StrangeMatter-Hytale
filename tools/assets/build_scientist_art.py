"""Create a Klops scientist using the native animation rig and custom attached geometry."""
import copy, json, math
from PIL import Image, ImageDraw
import build_assets as art
import render_current_icons as render

ROOT=art.ROOT;COMMON=art.COMMON;NATIVE=render.NATIVE;RES=ROOT/'src/main/resources'

def write(path,data):path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(data,indent=2)+'\n')

def main():
    base=render.read(NATIVE/'NPC/Intelligent/Klops/Models/Model.blockymodel')
    skin=Image.open(NATIVE/'NPC/Intelligent/Klops/Models/Model_default.png').convert('RGBA')
    addon=art.Model('scientist_attachments');owners={}
    def attach(bone,callback):
        start=len(addon.nodes);callback()
        for node in addon.nodes[start:]:owners[node['id']]=bone
    def box(bone,name,position,size,material='white',rotation=(0,0,0)):
        attach(bone,lambda:addon.box('SM_'+name,position,size,material,rotation,glow=material in ('cyan','purple')))
    for x in (-11,11):
        box('Belly','coat_front',(x,0,16),(17,24,3))
        box('Pelvis','split_coat_tail',(x,-7,13),(17,18,3),rotation=(0,0,4 if x<0 else -4))
    box('Belly','coat_back',(0,0,-16),(42,24,3))
    for x in (-20.5,20.5):box('Belly','coat_side',(x,0,0),(3,24,31))
    box('Pelvis','coat_rear_tail',(0,-7,-13),(38,18,3))
    box('Belly','navy_waistcoat',(0,1,16.5),(6,20,2),'navy')
    for x in (-10,10):
        box('Chest','white_lapel',(x,0,14),(13,19,3))
        box('Chest','lapel_fold',(x*.6,3,16),(5,15,1),'white',(0,0,-18 if x>0 else 18))
    box('Chest','coat_upper_back',(0,0,-14),(38,19,3))
    for side in ('L','R'):
        box(side+'-Arm','sleeve',(0,0,0),(10,19,14))
        box(side+'-Forearm','rolled_sleeve',(0,2,0),(10,8,14))
        box(side+'-Forearm','cuff_binding',(0,-2,0),(10.4,1.2,14.4),'edge')
    box('Belly','utility_belt',(0,-9,17.9),(38,3,2),'dark')
    box('Belly','brass_buckle',(0,-9,19.2),(5,4,1),'copper')
    box('Belly','sample_pouch',(-12,-5,19),(8,8,4),'navy')
    box('Belly','pouch_rune',(-12,-4,21.3),(4,3,.5),'purple')
    box('Chest','name_badge',(11,-1,16),(7,5,.8),'paper')
    box('Chest','badge_clip',(11,2,16.4),(3,1,1),'steel')
    box('Belly','pencil_pocket',(12,4,18),(7,7,2))
    for x,color in ((10,'cyan'),(13,'copper')):box('Belly','lab_probe',(x,8,18.5),(1.2,8,1.2),color)
    for x,color in ((-8,'cyan'),(8,'purple')):
        box('Chest','reagent_tank',(x,-2,-20),(8,19,8),'navy')
        box('Chest','tank_window',(x,-2,-24.2),(4,13,.5),color)
        for y in (-9,5):box('Chest','tank_clamp',(x,y,-20),(10,2,10),'copper')
        box('Chest','tank_valve',(x,9,-20),(3,5,3),'steel')
    box('Chest','pack_frame',(0,-2,-16),(22,23,3),'edge')
    # Klops has one large eye, so the protective goggles use a single large lens.
    attach('Head',lambda:addon.ring('SM_goggle_brass_rim',(0,2,20),9,2,3,'copper',count=12))
    attach('Head',lambda:addon.ring('SM_goggle_inner_seal',(0,2,21.6),7.5,1,1,'dark',count=12))
    for x in (-16,16):box('Head','goggle_strap',(x,3,1),(2,4,34),'dark')
    box('Head','goggle_back_strap',(0,3,-16),(31,4,2),'dark')
    box('Head','goggle_lens',(0,2,21.5),(12,11,.25),'lens')
    box('Head','magnifier_hinge',(12,4,21),(4,5,4),'steel')
    box('Head','inspection_lens',(15,6,24),(6,7,1),'cyan',(0,-18,0))
    original_paint=art.paint;original_common=art.COMMON
    def paint(width,height,material,seed):
        if material=='lens':return Image.new('RGBA',(width,height),(93,202,216,62))
        return original_paint(width,height,material,seed)
    try:
        art.paint=paint;art.COMMON=ROOT/'build/art-revision-npc'
        entry=addon.save();addon_texture=Image.open(art.COMMON/entry['texture']).convert('RGBA')
    finally:art.paint=original_paint;art.COMMON=original_common
    width=max(skin.width,addon_texture.width);height=2**math.ceil(math.log2(skin.height+addon_texture.height))
    texture=Image.new('RGBA',(width,height));texture.paste(skin,(0,0));texture.paste(addon_texture,(0,skin.height))
    bones={}
    def index(nodes):
        for n in nodes:bones[n['name']]=n;index(n.get('children',[]))
    index(base['nodes'])
    for index,node in enumerate(addon.nodes):
        bone=owners[node['id']];node=copy.deepcopy(node);node['id']=str(10000+index)
        for uv in node['shape']['textureLayout'].values():uv['offset']['y']+=skin.height
        bones[bone].setdefault('children',[]).append(node)
    modelpath='NPC/StrangeMatter/Scientist/Scientist.blockymodel';texpath='NPC/StrangeMatter/Scientist/Scientist.png'
    write(COMMON/modelpath,base);texture.save(COMMON/texpath)
    merchant=render.read(NATIVE.parent/'Server/Models/Intelligent/Klops/Klops_Merchant.json')
    attachments=[a for a in merchant['DefaultAttachments'] if any(word in a['Model'] for word in ('/Tail/','/Eyebrows/','/Shoes.'))]
    write(RES/'Server/Models/StrangeMatter/SM_Klops_Scientist.json',{
      'Parent':'Klops','Model':modelpath,'Texture':texpath,'DefaultAttachments':attachments})
    faces=render.model_faces(base,texture)
    # Review native rig plus custom coat/goggles/gear; inherited minor cosmetics are native.
    sheet=Image.new('RGBA',(1080,600),(17,25,42,255))
    for column,yaw in enumerate((30,150,225)):
        image=render.render(faces,360,yaw=yaw,pitch=12);sheet.alpha_composite(image,(column*360,100))
    sheet.convert('RGB').save(ROOT/'docs/art/scientist-wardrobe.png')
    print(json.dumps({'model':'SM_Klops_Scientist','newAttachedNodes':len(addon.nodes),'atlas':[width,height],'nativeBonesPreserved':len(bones)}))

if __name__=='__main__':main()
