"""Current placement contracts, not a historical art lock."""
import copy, unittest
import block_support

class BlockSupportTests(unittest.TestCase):
    def test_current_full_building_faces_and_doubled_slab(self):
        for source in block_support.FULL:
            self.assertEqual(block_support.read(block_support.item_path(source))['BlockType']['Supporting'],block_support.full_faces())
        slab=block_support.read(block_support.item_path('resonite_tile_slab'))['BlockType']
        self.assertEqual(slab['State']['Definitions']['Block']['Supporting'],block_support.full_faces())
        self.assertNotEqual(slab.get('Supporting'),block_support.full_faces())

    def test_patch_preserves_every_other_definition_and_is_idempotent(self):
        for source in block_support.TARGETS:
            block=block_support.read(block_support.item_path(source))['BlockType'];before=copy.deepcopy(block)
            target=block if source in block_support.FULL else block['State']['Definitions']['Block']
            target.pop('Supporting',None)
            block_support.apply(source,block);self.assertEqual(block,before)
            block_support.apply(source,block);self.assertEqual(block,before)

    def test_nonfull_models_unchanged_and_mount_requirements_retained(self):
        block={'DrawType':'Model','CustomModel':'UserArtwork.blockymodel'}
        for source in ('resonite_tile_stairs','resonite_roof','resonite_chair','resonite_wall_monitor','resonite_sign'):
            actual=copy.deepcopy(block);block_support.apply(source,actual);self.assertEqual(actual,block)
        for source in ('resonite_wall_monitor','resonite_sign'):
            self.assertEqual(block_support.read(block_support.item_path(source))['BlockType']['Support'],{'North':[{'FaceType':'Full'}]})

if __name__=='__main__':unittest.main()
