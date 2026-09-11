"""Current floor content and ceiling light contracts, without Pillow or old art locks."""
import copy
import json
import unittest
from build_floor_panel import ROOT,RES,COMMON,MODEL,TEXTURE,ICON,ITEM,LAMP,ID,SOURCE,read
from test_warp_gun_revision import validate_model
from verify_texture_repack import decode_png,nodes
from fixture_toggles import apply as toggle
from group_creative_inventory import group,heading


class FloorPanelTests(unittest.TestCase):
    def test_native_art_and_floor_recipe(self):
        item=read(ITEM);block=item['BlockType'];w,h,pixels=decode_png(TEXTURE.read_bytes())
        self.assertGreater(validate_model(read(MODEL),(w,h)),0)
        self.assertEqual(decode_png(ICON.read_bytes())[:2],(64,64))
        self.assertEqual(block['Material'],'Solid');self.assertEqual(block['HitboxType'],'Full')
        self.assertEqual(block['Supporting'],{'BlockSides':[{'FaceType':'Full'}]})
        self.assertEqual(COMMON/block['CustomModel'],MODEL)
        self.assertEqual(COMMON/block['CustomModelTexture'][0]['Texture'],TEXTURE)
        self.assertEqual(item['MaxStack'],100)
        self.assertEqual(item['SubCategory'],'SM_Masonry')
        self.assertEqual(group(ID,item),'Building');self.assertEqual(heading(ID,item),'Masonry')
        recipe=item['Recipe'];self.assertTrue(recipe['KnowledgeRequired'])
        self.assertEqual(recipe['BenchRequirement'],[{'Type':'Crafting','Id':'SM_Laboratory','Categories':['SM_Laboratory_Building']}])
        self.assertEqual(recipe['OutputQuantity'],4)
        actual={entry['ItemId']:entry['Quantity'] for entry in recipe['Input']}
        self.assertEqual(actual,{'SM_Resonite_Tile':4,'SM_Resonite_Ingot':1})
        catalog=[r for r in read(RES/'Server/StrangeMatter/recipes.json') if r['output']==ID]
        self.assertEqual(len(catalog),1);self.assertEqual(catalog[0]['ingredients'],actual);self.assertEqual(catalog[0]['research'],'resonite')
        language=(RES/'Server/Languages/en-US/server.lang').read_text(encoding='utf-8-sig')
        for key in ('name','description'):self.assertIn(f'items.{ID}.{key}=',language)

    def test_top_edges_tile_without_an_unmatched_fringe(self):
        # The actual current atlas and actual native top UV are used, not a
        # hidden copy of the generator's paint. Allows any future seamless motif.
        from verify_texture_repack import face_bounds
        model=read(MODEL);shape=next(n['shape'] for n in nodes(model) if 'top' in n.get('shape',{}).get('textureLayout',{}))
        left,top,right,bottom=face_bounds(shape,'top',shape['textureLayout']['top'])
        left+=1;top+=1;right-=1;bottom-=1
        width,height,pixels=decode_png(TEXTURE.read_bytes())
        def color(x,y):return pixels[(y*width+x)*4:(y*width+x+1)*4]
        self.assertEqual([color(left,y) for y in range(top,bottom)],[color(right-1,y) for y in range(top,bottom)])
        self.assertEqual([color(x,top) for x in range(left,right)],[color(x,bottom-1) for x in range(left,right)])

    def test_lab_lamp_optical_fix_and_saved_state_contract(self):
        block=read(LAMP)['BlockType']
        self.assertEqual(block['Opacity'],'Transparent');self.assertEqual(block['Material'],'Solid');self.assertEqual(block['HitboxType'],'Full')
        self.assertEqual(block['Light'],{'Color':'#5ff','Radius':10})
        on=block['State']['Definitions']['On'];off=block['State']['Definitions']['Off']
        self.assertEqual(on['Light'],block['Light']);self.assertIsNone(off['Light'])
        for state in (on,off):self.assertEqual(state.get('Opacity',block['Opacity']),'Transparent')
        self.assertEqual(block['Interactions']['Use']['Interactions'][0]['Changes'],{'default':'Off','On':'Off','Off':'On'})

    def test_surgical_switch_generator_repairs_old_solid_lamp(self):
        item=read(LAMP);item['BlockType']['Opacity']='Solid'
        before=copy.deepcopy(item)
        toggle('SM_Lab_Lamp',item,RES,write_models=False)
        self.assertEqual(item['BlockType']['Opacity'],'Transparent')
        for field in ('Material','HitboxType','CustomModel','CustomModelTexture','Light','Gathering'):
            self.assertEqual(item['BlockType'][field],before['BlockType'][field])


if __name__=='__main__':unittest.main()
