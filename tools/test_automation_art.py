"""Focused current-art regressions. No generators or historical snapshots run."""
import copy, unittest
from validate_automation_art import COMMON, REPORT, animation_check, pose, read, within

class AutomationArtTest(unittest.TestCase):
    def setUp(self):
        self.entry=read(REPORT)['items'][0];self.model=read(COMMON/self.entry['model']);self.clip=read(COMMON/self.entry['animation'])
    def test_actual_animation_has_valid_targets(self):
        self.assertIn(30,animation_check(self.model,self.clip,'separator'))
        self.assertNotEqual(self.model,pose(self.model,self.clip,30))
    def test_unknown_animation_target_rejected(self):
        bad=copy.deepcopy(self.clip);bad['nodeAnimations']['UnknownPart']=bad['nodeAnimations'].pop('Separator_Ring')
        with self.assertRaisesRegex(AssertionError,'missing or ambiguous'):animation_check(self.model,bad,'bad')
    def test_static_animated_box_rejected(self):
        bad=copy.deepcopy(self.model)
        jaw=next(n for n in bad['nodes'] if n['name']=='Separator_Jaw_Left');jaw['children'][0]['shape']['settings']['isStaticBox']=True
        with self.assertRaisesRegex(AssertionError,'must not be static'):animation_check(bad,self.clip,'bad')
    def test_motion_outside_footprint_rejected(self):
        bad=copy.deepcopy(self.clip);bad['nodeAnimations']['Separator_Sample']['position'][1]['delta']['y']=100
        with self.assertRaisesRegex(AssertionError,'placement footprint'):within(pose(self.model,bad,30),self.entry['boundsBlocks'],'bad')

if __name__=='__main__':unittest.main()
