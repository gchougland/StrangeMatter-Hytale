"""Stdlib regressions for tablet grip binding and current editable artwork."""
import copy
import math
import unittest
from fit_tablet_grip import MODEL, VIEWS, empty_shape, fit, read
from test_held_light_fix import add, geometry, mm, mv, player

class TabletGripTests(unittest.TestCase):
    def test_authored_geometry_uvs_and_metadata_survive_wrapping(self):
        shape=empty_shape();shape.update(type='box',offset={'x':2,'y':-1,'z':3},
            settings={'size':{'x':7,'y':11,'z':2}},textureLayout={'front':{'offset':{'x':34,'y':28},'mirror':{'x':True},'angle':90}})
        original={'format':'prop','lod':'auto','nodes':[{'id':'17','name':'Manual_Screen',
            'position':{'x':3,'y':8,'z':5},'orientation':{'x':0,'y':0,'z':0,'w':1},'shape':shape}]}
        before=copy.deepcopy(original);result=fit(original)
        self.assertEqual(original,before)
        self.assertEqual(result['nodes'][0]['children'][0]['children'],original['nodes'])
        self.assertEqual(result['format'],'prop');self.assertEqual(result['lod'],'auto')
        self.assertEqual(fit(result),result)
        _,bones=geometry(player());rotation,position=bones['R-Attachment']
        actual,_=geometry({'nodes':result['nodes'][0]['children']},rotation,position)
        plain,_=geometry(original);turn=((-1,0,0),(0,1,0),(0,0,-1))
        expected=[add(position,mv(mm(rotation,turn),p)) for p in plain]
        for got,want in zip(actual,expected):
            for a,b in zip(got,want):self.assertAlmostEqual(a,b,places=7)

    def test_current_tablet_turn_survives_native_attachment(self):
        model=read(MODEL);self.assertEqual(len(model['nodes']),1)
        root=model['nodes'][0];self.assertEqual(root['name'],'R-Attachment')
        self.assertTrue(root['shape']['settings']['isPiece']);self.assertEqual(root['shape']['type'],'none')
        self.assertEqual(root['orientation'],{'x':0,'y':0,'z':0,'w':1})
        _,bones=geometry({'nodes':root['children']});rotation,_=bones['SM_Tablet_Grip']
        self.assertEqual(mv(rotation,(0,0,1)),(0,0,-1))
        self.assertEqual(mv(rotation,(0,1,0)),(0,1,0))
        self.assertTrue(root['children'][0]['children'])

    def test_current_icon_camera_is_on_the_screen_side(self):
        view=read(VIEWS)['SM_Research_Tablet'];yaw=math.radians(view['yaw'])
        model=read(MODEL);_,bones=geometry(model);rotation,_=bones['SM_Tablet_Grip']
        normal=mv(rotation,(0,0,1));camera=(math.sin(yaw),0,math.cos(yaw))
        self.assertGreater(sum(a*b for a,b in zip(normal,camera)),.5)

if __name__=='__main__':unittest.main()
