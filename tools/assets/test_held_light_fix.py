"""Stdlib-only native hand binding, UV preservation and light hue checks.

Uses the supplied or installed native player rig. Historical archive comparisons
are skipped only when its local build snapshot is absent on another machine.
"""
import copy, json, math, os, struct, unittest, zipfile
from pathlib import Path
from apply_held_light_fix import attach, ROOT, COMMON, RES
from content_policy import FAMILIES, family_light, FIXTURE_MINERAL_RGB
from verify_texture_repack import (decode_png, face_bounds, patch_rgba,
                                  without_offset_values, nodes as model_nodes)

I=((1,0,0),(0,1,0),(0,0,1))
def read(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def v(o):return tuple(o.get(k,0) for k in 'xyz')
def add(a,b):return tuple(x+y for x,y in zip(a,b))
def mv(a,b):return tuple(sum(a[i][j]*b[j] for j in range(3)) for i in range(3))
def mm(a,b):return tuple(tuple(sum(a[i][k]*b[k][j] for k in range(3)) for j in range(3)) for i in range(3))
def qm(q):
    x,y,z,w=(q[k] for k in 'xyzw')
    return ((1-2*(y*y+z*z),2*(x*y-z*w),2*(x*z+y*w)),(2*(x*y+z*w),1-2*(x*x+z*z),2*(y*z-x*w)),(2*(x*z-y*w),2*(y*z+x*w),1-2*(x*x+y*y)))
def geometry(model,rotation=I,position=(0,0,0)):
    points=[];bones={}
    def walk(nodes,pr,pp):
        for n in nodes:
            r=mm(pr,qm(n['orientation']));s=n.get('shape',{})
            p=add(add(pp,mv(pr,v(n.get('position',{})))),mv(r,v(s.get('offset',{}))));bones[n['name']]=(r,p)
            if s.get('type')=='box':
                half=[s['settings']['size'][k]*s.get('stretch',{}).get(k,1)/2 for k in 'xyz']
                for x in (-1,1):
                    for y in (-1,1):
                        for z in (-1,1):points.append(add(p,mv(r,(x*half[0],y*half[1],z*half[2]))))
            walk(n.get('children',[]),r,p)
    walk(model['nodes'],rotation,position);return points,bones
def player():
    relative='Common/Characters/Player_With_Face.blockymodel';p=ROOT.parent/'HytaleSourceCode/hytale-shared-source/HytaleAssets'/relative
    if p.exists():return read(p)
    p=Path(os.environ.get('APPDATA',''))/'Hytale/install/release/package/game/latest/Assets.zip'
    if p.exists():
        with zipfile.ZipFile(p) as z:return json.loads(z.read(relative))
    raise unittest.SkipTest('Native player rig unavailable; install Hytale or provide shared assets')


def verify_face_pixels(original, current, before, after):
    """Preserve rendered texels and their filtering margin while allowing atlas moves."""
    assert without_offset_values(original)==without_offset_values(current),'Held structure or non-offset UV data changed'
    count=0
    for old_node,new_node in zip(model_nodes(original),model_nodes(current)):
        shape=old_node.get('shape',{})
        if shape.get('type') not in ('box','quad'):continue
        for face,old_uv in shape.get('textureLayout',{}).items():
            new_uv=new_node['shape']['textureLayout'][face]
            for axis in ('x','y'):
                delta=new_uv['offset'][axis]-old_uv['offset'][axis]
                assert math.isfinite(delta) and abs(delta-round(delta))<1e-8,'Held UV sampling phase changed'
            old_bounds=face_bounds(shape,face,old_uv);new_bounds=face_bounds(shape,face,new_uv)
            assert patch_rgba(before,old_bounds)==patch_rgba(after,new_bounds),old_node['name']+'/'+face+': painted pixels or one texel filtering margin changed'
            count+=1
    assert count>0,'Held model has no sampled faces'
    return count

class HeldLightTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        p=ROOT/read(ROOT/'tools/assets/held-light-fix.json')['snapshot']/'Resources.zip'
        cls.zip=zipfile.ZipFile(p) if p.exists() else None
    @classmethod
    def tearDownClass(cls):
        if cls.zip:cls.zip.close()
    def old(self,p):return json.loads(self.zip.read('src/main/resources/'+p))
    def test_geometry_correction_survives_native_piece_binding(self):
        _,bones=geometry(player());br,bp=bones['R-Attachment']
        for name,yaw in [('field_scanner',180),('graviton_hammer',90)]:
            current=read(COMMON/f'Items/StrangeMatter/{name}.blockymodel');piece=current['nodes'][0];art=piece['children'][0]
            self.assertEqual(piece['name'],'R-Attachment');self.assertEqual(piece['shape']['type'],'none');self.assertTrue(piece['shape']['settings']['isPiece'])
            self.assertEqual(piece['orientation'],{'x':0,'y':0,'z':0,'w':1})
            if self.zip:
                old=self.old(f'Common/Items/StrangeMatter/{name}.blockymodel')
                self.assertEqual(without_offset_values(current),without_offset_values(attach(old,yaw)),
                                 'Native grip correction preserves every hierarchy, geometry, angle and mirror field')
            fitted,_=geometry({'nodes':piece['children']},br,bp)
            baseline=copy.deepcopy(art);baseline['position']={'x':0,'y':0,'z':0};baseline['orientation']={'x':0,'y':0,'z':0,'w':1}
            plain,_=geometry({'nodes':[baseline]});a=math.radians(yaw);turn=((math.cos(a),0,math.sin(a)),(0,1,0),(-math.sin(a),0,math.cos(a)))
            expected=[add(mv(br,mv(turn,p)),bp) for p in plain];self.assertEqual(len(fitted),len(expected))
            for x,y in zip(fitted,expected):
                for p,q in zip(x,y):self.assertAlmostEqual(p,q,places=6)
            self.assertAlmostEqual(abs(art['orientation']['y']),abs(math.sin(a/2)),places=6)
    def test_handheld_textures_and_interactions_unchanged(self):
        if not self.zip:self.skipTest('Historical snapshot unavailable on this machine')
        for name,id in [('field_scanner','SM_Field_Scanner'),('graviton_hammer','SM_Graviton_Hammer')]:
            p=f'Common/Items/StrangeMatter/{name}.png'
            old=self.old(f'Common/Items/StrangeMatter/{name}.blockymodel')
            current=read(COMMON/f'Items/StrangeMatter/{name}.blockymodel')
            before=decode_png(self.zip.read('src/main/resources/'+p));after=decode_png((RES/p).read_bytes())
            verify_face_pixels(attach(old,180 if name=='field_scanner' else 90),current,before,after)
            p=f'Server/Item/Items/StrangeMatter/{id}.json';self.assertEqual(read(RES/p),self.old(p))
            data=(COMMON/f'Icons/ItemsGenerated/{id}.png').read_bytes();self.assertEqual(struct.unpack('>II',data[16:24]),(64,64))
    def test_lights_preserve_hue_without_white_component(self):
        for family in FAMILIES:
            old=FIXTURE_MINERAL_RGB[family];new=[int(c,16) for c in family_light(family,True)[1:]]
            for a,b in zip(old,new):self.assertLessEqual(abs((a-min(old))/(max(old)-min(old))-b/15),.034)
            self.assertEqual(min(new),0);self.assertEqual(max(new),15)
            for suffix in ('Lamp','Lantern'):
                p=f'Server/Item/Items/StrangeMatter/SM_{family.title()}_Shard_{suffix}.json';item=read(RES/p);light={'Color':family_light(family,True),'Radius':0}
                self.assertEqual(item['BlockType']['Light'],light)
                if self.zip:
                    expected=self.old(p);expected['BlockType']['Light']=light;self.assertEqual(item,expected,'Unexpected fixture mutation')

    def test_repacked_held_art_rejects_changed_pixels_and_geometry(self):
        model={'nodes':[{'name':'Probe','position':{'x':0,'y':0,'z':0},'shape':{'type':'quad',
                'settings':{'size':{'x':2,'y':2}},'textureLayout':{'front':{
                'offset':{'x':1,'y':1},'angle':0,'mirror':{'x':False,'y':False}}}}}]}
        moved=copy.deepcopy(model);moved['nodes'][0]['shape']['textureLayout']['front']['offset']={'x':2,'y':2}
        pixels=bytes((74,130,211,0))*16;repacked=bytes((74,130,211,0))*64
        self.assertEqual(verify_face_pixels(model,moved,(4,4,pixels),(8,8,repacked)),1)
        changed=bytearray(repacked);changed[(1*8+1)*4]^=1
        with self.assertRaisesRegex(AssertionError,'filtering margin'):
            verify_face_pixels(model,moved,(4,4,pixels),(8,8,bytes(changed)))
        moved['nodes'][0]['shape']['textureLayout']['front']['mirror']['x']=True
        with self.assertRaisesRegex(AssertionError,'non-offset UV'):
            verify_face_pixels(model,moved,(4,4,pixels),(8,8,repacked))
        moved=copy.deepcopy(model);moved['nodes'][0]['position']['x']=.1
        with self.assertRaisesRegex(AssertionError,'structure'):
            verify_face_pixels(model,moved,(4,4,pixels),(4,4,pixels))

if __name__=='__main__':unittest.main()
