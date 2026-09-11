"""Small native-format fixtures for current-art rendering, independent of generator output."""
import unittest
import numpy as np
from PIL import Image
import render_current_icons as renderer


def node(name,position=(0,0,0),offset=(0,0,0),kind='box',children=None):
    return {'name':name,'position':dict(zip('xyz',position)),
      'shape':{'type':kind,'offset':dict(zip('xyz',offset)),'settings':{'size':{'x':2,'y':2,'z':2}},
      'textureLayout':{face:{'offset':{'x':0,'y':0}} for face in renderer.art.FACE_VERTS}},
      'children':children or []}


class CurrentIconRendererTest(unittest.TestCase):
    def test_parent_shape_offset_is_child_origin_like_native_parser(self):
        child=node('child',position=(0,2,0),offset=(0,3,0))
        parent=node('parent',offset=(0,10,0),kind='none',children=[child])
        faces=renderer.model_faces({'nodes':[parent]},Image.new('RGBA',(32,32),'white'))
        points=np.concatenate([face[0] for face in faces])
        np.testing.assert_allclose(points.min(0),[-1,14,-1])
        np.testing.assert_allclose(points.max(0),[1,16,1])

    def test_native_mirror_uses_signed_span_from_stored_origin(self):
        normal=renderer.uv_corners({'offset':{'x':10,'y':20}},8,4)
        flipped=renderer.uv_corners({'offset':{'x':10,'y':20},'mirror':{'x':True}},8,4)
        np.testing.assert_allclose(flipped[:,0],20-normal[:,0])
        self.assertGreaterEqual(flipped[:,0].min(),2)
        self.assertLessEqual(flipped[:,0].max(),10)
        # Moving the origin to the far edge mirrors inside the original tile.
        relocated=renderer.uv_corners({'offset':{'x':18,'y':20},'mirror':{'x':True}},8,4)
        np.testing.assert_allclose(relocated,normal[[1,0,3,2]])

    def test_uv_quarter_turn_swaps_rectangle_dimensions(self):
        uv=renderer.uv_corners({'offset':{'x':10,'y':20},'angle':90},8,4)
        np.testing.assert_allclose(uv.max(0)-uv.min(0),np.array([4,8])*.99999)
        np.testing.assert_allclose(uv[0],[6,20],atol=.0001)

    def test_native_signed_rotation_spans_all_mirror_combinations(self):
        for mx in (False,True):
            for my in (False,True):
                for angle in (0,90,180,270):
                    uv=renderer.uv_corners({'offset':{'x':100,'y':100},'mirror':{'x':mx,'y':my},'angle':angle},12,8)
                    centre=np.array([-6 if mx else 6,-4 if my else 4],float)
                    for _ in range(angle//90):centre=np.array([-centre[1],centre[0]])
                    np.testing.assert_allclose(uv.mean(0),100+centre)

    def test_hidden_parent_hides_descendants(self):
        parent=node('hidden',kind='none',children=[node('child')]);parent['shape']['visible']=False
        self.assertEqual([],renderer.model_faces({'nodes':[parent]},Image.new('RGBA',(32,32))))

    def test_current_ore_includes_native_cube_host(self):
        item=renderer.read(renderer.RES/'Server/Item/Items/StrangeMatter/SM_Gravitic_Shard_Ore.json')
        block=item['BlockType'];ore=renderer.model_faces(renderer.read(renderer.resolve(block['CustomModel'])),
            Image.open(renderer.resolve(block['CustomModelTexture'][0]['Texture'])))
        self.assertEqual(len(ore)+6,len(renderer.item_faces(item)))


if __name__=='__main__':unittest.main()
