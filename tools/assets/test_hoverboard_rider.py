"""Validate shipped surfing tracks against the actual native player skeleton."""
import copy, json, math, unittest
import numpy as np
import build_hoverboard_rider as rig


class HoverboardRiderTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.animations = {name: json.loads((rig.DEST/f'Surf_{name}.blockyanim').read_text(encoding='utf-8')) for name in ('Idle','Glide','Boost')}

    def test_native_animation_registration_keeps_player_model_and_first_person_intact(self):
        config=json.loads(rig.PLAYER_ANIMATIONS.read_text(encoding='utf-8'))
        self.assertNotIn('Parent',config,'Action-only rider clips must not inherit first-person-only movement entries')
        native_default=json.loads((rig.NATIVE.parent/'Server/Item/Animations/Default.json').read_text())
        for field in ('Camera','WiggleWeights'):
            self.assertEqual(native_default[field],config[field],'Previously inherited non-animation settings remain unchanged')
        self.assertEqual({'SurfIdle','SurfGlide','SurfBoost'},set(config['Animations']))
        self.assertEqual(rig.player_animation_config(),config)
        for entry in config['Animations'].values():
            self.assertTrue((rig.COMMON/entry['ThirdPerson']).is_file())
            self.assertEqual(entry['ThirdPerson'],entry['ThirdPersonMoving'])
            self.assertTrue(entry['Looping'])
            self.assertEqual(1,entry['Speed'])
            self.assertLessEqual(entry['BlendingDuration'],.25)
            self.assertTrue(entry['KeepPreviousFirstPersonAnimation'])
            self.assertNotIn('FirstPerson',entry)
            self.assertNotIn('FirstPersonOverride',entry)
        self.assertFalse((rig.ROOT/'src/main/resources/Server/Models/StrangeMatter/SM_Hoverboard_Rider.json').exists(),
                         'Obsolete model wrapper must not be shipped alongside the safe native animation family')

    def test_shipped_frame_tokens_are_native_int32_and_match_generator(self):
        for mode, anim in self.animations.items():
            self.assertEqual(153,rig.validate_client_frame_numbers(anim))
            self.assertEqual(rig.animation(mode),anim,'Generator must reproduce the shipped poses and integer times')
            expected = [(anim['duration']*i+4)//8 for i in range(9)]
            self.assertEqual(expected,[k['time'] for k in anim['nodeAnimations']['Pelvis']['position']])
            self.assertLessEqual(max(abs(t-anim['duration']*i/8) for i,t in enumerate(expected)),.5)

    def test_rejects_client_crashing_decimal_frame_tokens_and_invalid_bounds(self):
        # System.Text.Json.GetInt32 rejects 0.0 as well as genuinely fractional 11.25.
        for bad in (0.0,11.25,2**31,-1,True):
            anim=copy.deepcopy(self.animations['Glide'])
            anim['nodeAnimations']['R-Hand']['orientation'][0]['time']=bad
            with self.assertRaisesRegex(ValueError,'Int32'):
                rig.validate_client_frame_numbers(anim)
        for bad in (90.0,2**31,-1,True):
            anim=copy.deepcopy(self.animations['Glide']);anim['duration']=bad
            with self.assertRaisesRegex(ValueError,'Int32'):
                rig.validate_client_frame_numbers(anim)

    def test_tracks_bind_native_bones_and_close_with_finite_unit_quaternions(self):
        for mode,anim in self.animations.items():
            self.assertGreaterEqual(anim['duration'],72)
            self.assertLessEqual(anim['duration'],120)
            self.assertFalse(anim['holdLastKeyframe'])
            self.assertEqual(16,len(anim['nodeAnimations']))
            self.assertTrue(set(anim['nodeAnimations'])<=set(rig.BONES))
            for bone,tracks in anim['nodeAnimations'].items():
                for kind,keys in tracks.items():
                    if not keys:continue
                    self.assertEqual(9,len(keys),(mode,bone,kind))
                    self.assertEqual(0,keys[0]['time'])
                    self.assertEqual(anim['duration'],keys[-1]['time'])
                    self.assertEqual(keys[0]['delta'],keys[-1]['delta'])
                    self.assertEqual(sorted(set(k['time'] for k in keys)),[k['time'] for k in keys])
                    for key in keys:
                        self.assertTrue(all(math.isfinite(v) for v in key['delta'].values()))
                        if kind=='orientation':self.assertAlmostEqual(1,sum(v*v for v in key['delta'].values()),places=5)

    def test_actual_shoes_remain_flat_centered_and_planted(self):
        for mode,anim in self.animations.items():
            for frame in range(9):
                body=rig.posed_rig(anim,frame)
                for side,sign in (('R',1),('L',-1)):
                    low,high=rig.shoe_bounds(body,side)
                    np.testing.assert_allclose((low+high)/2,[0,3.8,sign*23],atol=.0001,err_msg=f'{mode} {frame} {side}')
                    self.assertAlmostEqual(0,low[1],places=4)
                    self.assertAlmostEqual(7.6,high[1],places=4)

    def test_surf_pose_is_sideways_and_crouched_without_horse_pelvis_drop(self):
        for anim in self.animations.values():
            tracks=anim['nodeAnimations']; pelvis=tracks['Pelvis']
            heights=[key['delta']['y'] for key in pelvis['position']]
            self.assertGreater(min(heights),-16)
            self.assertLess(max(heights),-8)
            self.assertLess(max(heights)-min(heights),1.4)
            for key in pelvis['orientation']:
                q=np.array([key['delta'][k] for k in 'xyzw'])
                forward=rig.matrix(q)@np.array([0.,0.,1.])
                self.assertGreater(forward[0],.98)
            for side in ('R','L'):
                calf=tracks[side+'-Calf']['orientation'][0]['delta']
                self.assertLess(abs(calf['w']),.96,'Knees visibly bend rather than locking straight')


if __name__=='__main__':unittest.main()
