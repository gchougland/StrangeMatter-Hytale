"""Native signed UV regressions, independent of any historical artwork."""
import unittest

from validate_architecture_set import check_model_uvs, uv_bounds


def shape(kind='box',normal='+Z'):
    return {'type':kind,'settings':{'size':{'x':6,'y':3,'z':8},'normal':normal},
            'stretch':{'x':7,'y':2,'z':4}}


def layout(x=16,y=12,angle=0,mirror_x=False,mirror_y=False):
    return {'offset':{'x':x,'y':y},'angle':angle,
            'mirror':{'x':mirror_x,'y':mirror_y}}


def model(value,face,uv):
    return {'nodes':[{'name':'parent','shape':{'type':'none'},'children':[
        {'name':'authored face','shape':dict(value,textureLayout={face:uv})}]}]}


class NativeArchitectureUVTest(unittest.TestCase):
    def test_cardinal_rotation_moves_origin(self):
        expected={0:(16,12,22,15),90:(13,12,16,18),
                  180:(10,9,16,12),270:(16,6,19,12),-90:(16,6,19,12)}
        for angle,bounds in expected.items():
            with self.subTest(angle=angle):
                self.assertEqual(bounds,uv_bounds(shape(),'front',layout(angle=angle)))

    def test_mirrors_precede_rotation(self):
        expected={(True,False):(13,6,16,12),(False,True):(16,12,19,18),
                  (True,True):(16,6,19,12)}
        for mirrors,bounds in expected.items():
            with self.subTest(mirrors=mirrors):
                self.assertEqual(bounds,uv_bounds(shape(),'front',layout(angle=90,mirror_x=mirrors[0],mirror_y=mirrors[1])))
        # The packed roof's formerly rejected origin points into a negative span.
        roof=shape();roof['settings']['size']={'x':32,'y':32,'z':32}
        value=model(roof,'left',layout(243,95,mirror_x=True))
        self.assertEqual(1,check_model_uvs(value,256,256))

    def test_face_dimensions_ignore_geometry_stretch(self):
        self.assertEqual((16,12,24,15),uv_bounds(shape(),'left',layout()))
        self.assertEqual((16,12,22,20),uv_bounds(shape(),'bottom',layout()))
        for normal in ('+X','-X','+Y','-Y','+Z','-Z'):
            with self.subTest(normal=normal):
                # Even a quad called top has original 2D dimensions, not box X/Z.
                self.assertEqual((13,12,16,18),uv_bounds(shape('quad',normal),'top',layout(angle=90)))

    def test_out_of_bounds_still_rejected(self):
        for uv in (layout(2,12,mirror_x=True),layout(16,2,angle=180),
                   layout(30,12),layout(16,30,angle=90)):
            with self.subTest(uv=uv),self.assertRaisesRegex(AssertionError,'UV out of atlas'):
                check_model_uvs(model(shape(),'front',uv),32,32)
        with self.assertRaisesRegex(AssertionError,'quarter turns'):
            uv_bounds(shape(),'front',layout(angle=45))

    def test_fractional_face_boundaries_remain_exact(self):
        value=shape();value['settings']['size']['x']=6.25
        self.assertEqual((9.75,12,16,15),uv_bounds(value,'front',layout(mirror_x=True)))


if __name__=='__main__':
    unittest.main()
