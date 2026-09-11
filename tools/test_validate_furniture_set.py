"""Furniture validator regressions use in memory edits, never resource mutations."""
import copy
import math
import unittest
from unittest.mock import patch
import validate_furniture_set as validator


class FurnitureValidationTests(unittest.TestCase):
    def setUp(self):
        self.model=validator.read(validator.RES/'Common/Blocks/StrangeMatter/Furniture/chair.blockymodel')

    def test_legitimate_current_geometry_and_palette_edits_are_allowed(self):
        model=copy.deepcopy(self.model)
        first=model['nodes'][0]
        first['position']['x']+=.15
        first['shape']['settings']['size']['x']+=1
        first['shape']['textureLayout']['front']['offset']['x']+=2
        names,points,faces=validator.model_geometry(model,(512,512))
        self.assertGreater(len(points),0)
        self.assertIn(first['name'],names)
        self.assertGreater(faces,0)

    def test_current_resource_set_passes_full_structural_validation(self):
        report=validator.validate()
        self.assertEqual(report['status'],'PASS')
        self.assertEqual(report['pieces'],17)

    @staticmethod
    def quad_model(normal):
        quad={'id':'quad','name':'Painted_Quad','position':{'x':2,'y':3,'z':4},'shape':{
            'type':'quad','offset':{'x':1,'y':2,'z':3},'stretch':{'x':-1.5,'y':.5,'z':3},
            'settings':{'size':{'x':12,'y':8},'normal':normal},
            'textureLayout':{'front':{'offset':{'x':20,'y':30},'angle':90,'mirror':{'x':True,'y':True}}}}}
        parent={'id':'parent','name':'Parent','position':{'x':5,'y':7,'z':11},
                'orientation':{'z':math.sqrt(.5),'w':math.sqrt(.5)},'children':[quad]}
        return {'nodes':[parent]}

    def test_native_quads_accept_all_normals_signed_stretch_and_parent_transforms(self):
        expected_span={'X':(4,0,18),'Y':(0,18,4),'Z':(4,18,0)}
        for axis in 'XYZ':
            positive=None
            for sign in ('+','-'):
                with self.subTest(normal=sign+axis):
                    names,points,faces=validator.model_geometry(self.quad_model(sign+axis),(512,512))
                    self.assertEqual(names,{'Parent','Painted_Quad'});self.assertEqual(faces,1)
                    for i,center in enumerate((0,10,18)):
                        low=min(p[i] for p in points);high=max(p[i] for p in points)
                        self.assertAlmostEqual((low+high)/2,center)
                        self.assertAlmostEqual(high-low,expected_span[axis][i])
                    if positive is None:positive=points
                    else:self.assertEqual(points,list(reversed(positive)))

    def test_native_quad_out_of_atlas_uv_is_rejected(self):
        model=self.quad_model('-Y')
        model['nodes'][0]['children'][0]['shape']['textureLayout']['front']['offset']['x']=511
        with self.assertRaisesRegex(AssertionError,'UV rectangle'):
            validator.model_geometry(model,(512,512))

    def test_out_of_atlas_uv_is_rejected(self):
        self.model['nodes'][0]['shape']['textureLayout']['front']['offset']['x']=511
        with self.assertRaisesRegex(AssertionError,'UV rectangle'):
            validator.model_geometry(self.model,(512,512))

    def test_signed_mirror_underflow_is_rejected(self):
        face=self.model['nodes'][0]['shape']['textureLayout']['back']
        face['offset']['x']=0;face['mirror']['x']=True
        with self.assertRaisesRegex(AssertionError,'UV rectangle'):
            validator.model_geometry(self.model,(512,512))

    def test_missing_native_hinge_shape_is_rejected(self):
        actual_read=validator.read
        def altered(path):
            data=actual_read(path)
            if path.name=='chest.blockymodel':
                for node in data['nodes']:
                    if node['name']=='Lid':node.pop('shape')
            return data
        with patch.object(validator,'read',side_effect=altered):
            with self.assertRaises((KeyError,AssertionError)):
                validator.validate()

    def test_container_rim_rails_do_not_overlap_side_wall_top_surfaces(self):
        for piece in ('chest','chest_large','cabinet','wardrobe'):
            model=validator.read(validator.RES/'Common/Blocks/StrangeMatter/Furniture'/(piece+'.blockymodel'))
            nodes=list(validator.all_nodes(model['nodes']))
            def interval(node,axis):
                shape=node['shape'];centre=node['position'][axis]+shape['offset'][axis]
                half=shape['settings']['size'][axis]*shape['stretch'][axis]/2
                return centre-half,centre+half
            for rail in (n for n in nodes if n['name']=='Corner_Rail'):
                for wall in (n for n in nodes if n['name']=='Container_Side'):
                    top_match=abs(interval(rail,'y')[1]-interval(wall,'y')[1])<1e-7
                    overlap=[min(interval(rail,a)[1],interval(wall,a)[1])-max(interval(rail,a)[0],interval(wall,a)[0]) for a in 'xz']
                    self.assertFalse(top_match and all(v>1e-7 for v in overlap),piece+' coplanar rim overlap')

    def test_invalid_native_rotation_is_rejected(self):
        self.model['nodes'][0]['orientation']['w']=0
        with self.assertRaisesRegex(AssertionError,'unit quaternion'):
            validator.model_geometry(self.model,(512,512))

    def test_missing_container_animation_target_is_rejected(self):
        actual_read=validator.read
        def altered_read(path):
            data=actual_read(path)
            if path.name=='chest_open.blockyanim':
                data['nodeAnimations']['Missing_Hinge']=data['nodeAnimations'].pop('Lid')
            return data
        with patch.object(validator,'read',side_effect=altered_read):
            with self.assertRaisesRegex(AssertionError,'missing animated node Missing_Hinge'):
                validator.validate()


if __name__=='__main__':unittest.main()
