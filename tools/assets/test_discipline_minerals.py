"""Check material identity against the shipped discipline symbols and native UVs."""
import colorsys
import hashlib
import json
import unittest
from collections import Counter
import numpy as np
from PIL import Image
from build_discipline_minerals import COMMON, ROOT, ITEMS, KINDS, read, tiles
from discipline_palette import DISCIPLINES
from content_policy import descriptions, family_light
import build_assets as art

class DisciplineMinerals(unittest.TestCase):
    def test_descriptions_use_current_discipline_colors(self):
        language=dict(line.split('=',1) for line in (ROOT/'src/main/resources/Server/Languages/en-US/server.lang').read_text(encoding='utf-8').splitlines() if '=' in line)
        expected=descriptions()
        for family in DISCIPLINES:
            for kind in KINDS:
                name=family+'_shard'+kind
                self.assertEqual(language['items.'+art.hid(name)+'.description'],expected[name])

    def test_symbols_and_research_labels_agree(self):
        source=(ROOT/'src/main/java/com/hexvane/strangematter/research/ResearchType.java').read_text()
        for family,(discipline,color) in DISCIPLINES.items():
            pixels=np.array(Image.open(COMMON/f'UI/Custom/StrangeMatter/Disciplines/{discipline}.png').convert('RGBA'))
            dominant=Counter(map(tuple,pixels[pixels[:,:,3]>200,:3])).most_common(1)[0][0]
            self.assertEqual(dominant,color,discipline)
            self.assertIn('"#'+''.join(f'{c:02x}' for c in color)+'"',source)

    def test_current_native_uvs_sample_correct_mineral(self):
        # Checking used UV regions catches correct inventory art paired with a
        # stale in-world atlas, and correct textures assigned to the wrong ore.
        for family in DISCIPLINES:
            for kind in KINDS:
                name=family+'_shard'+kind;item=read(ITEMS/(art.hid(name)+'.json'));block=item.get('BlockType',{})
                model=read(COMMON/(item.get('Model') or block['CustomModel']))
                atlas=Image.open(COMMON/(item.get('Texture') or block['CustomModelTexture'][0]['Texture'])).convert('RGBA')
                for (x,y,w,h),expected in tiles(name,kind,model):
                    self.assertEqual(atlas.crop((x,y,x+w,y+h)).tobytes(),expected.tobytes(),name)
                if kind:
                    self.assertEqual(block['ParticleColor'],'#'+''.join(f'{c:02x}' for c in DISCIPLINES[family][1]))
                if block.get('Light'):
                    self.assertEqual(block['Light']['Color'],family_light(family,kind in ('_lamp','_lantern')))
                    for state in block.get('State',{}).get('Definitions',{}).values():
                        if state.get('Light'):
                            self.assertEqual(state['Light'],block['Light'])

    def test_inventory_minerals_are_distinct_and_match_their_symbols(self):
        hues=[]
        for family,(_,color) in DISCIPLINES.items():
            expected=colorsys.rgb_to_hsv(*color)[0]
            hues.append(expected)
            for kind in ('','_ore'):
                icon=np.array(Image.open(COMMON/f'Icons/ItemsGenerated/{art.hid(family+"_shard"+kind)}.png').convert('RGBA'))
                pixels=icon[icon[:,:,3]>230,:3]
                saturated=[]
                for px in pixels:
                    h,s,v=colorsys.rgb_to_hsv(*map(int,px))
                    if s>.45 and v>50:
                        saturated.append(h)
                self.assertGreater(len(saturated),15,(family,kind))
                delta=abs(float(np.median(saturated))-expected)
                self.assertLess(min(delta,1-delta),.025,(family,kind))
        for i,a in enumerate(hues):
            for b in hues[i+1:]:
                self.assertGreater(min(abs(a-b),1-abs(a-b)),.04)

    def test_geometry_is_unchanged(self):
        manifest=read(ROOT/'tools/assets/discipline-minerals.json')
        for row in manifest['assets']:
            item=read(ITEMS/(row['item']+'.json'))
            model=COMMON/(item.get('Model') or item['BlockType']['CustomModel'])
            self.assertEqual(hashlib.sha256(model.read_bytes()).hexdigest(),row['geometrySha256'],row['item'])

if __name__=='__main__':
    unittest.main()
