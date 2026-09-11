import copy
import json
import struct
import unittest
import zlib
from validate_creative_groups import validate,validate_definitions,validate_glyph,RES


class CreativeGroupTests(unittest.TestCase):
    def test_current_native_contract(self):
        self.assertEqual(validate()['items'],len(list((RES/'Server/Item/Items/StrangeMatter').glob('SM_*.json'))))

    def test_reject_unmapped_item_header(self):
        category={'Children':[{'Id':key,'SubCategories':[{'Id':'SM_Test','Name':'server.test.name','Description':'server.test.description','Order':10}]} for key in ['All','Gadgets','Machines','Materials','Decorations','Building','Nature','Research']]}
        item={'Categories':['SM_StrangeMatter.'+child['Id'] for child in category['Children']],'SubCategory':'SM_Test'}
        language={'test.name':'A heading','test.description':'A group of items.'}
        self.assertEqual(validate_definitions(category,{'SM_Test':item},language),1)
        broken=copy.deepcopy(item);broken['SubCategory']='SM_Missing'
        with self.assertRaisesRegex(AssertionError,'declared item heading'):
            validate_definitions(category,{'SM_Test':broken},language)

    def test_reject_baked_background_plate(self):
        def chunk(kind,data):return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data)&0xffffffff)
        raw=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',48,48,8,6,0,0,0))
        raw+=chunk(b'IDAT',zlib.compress((b'\0'+b'\xff\xff\xff\xff'*48)*48))+chunk(b'IEND',b'')
        with self.assertRaisesRegex(AssertionError,'transparent outer padding'):validate_glyph(raw,48)


if __name__=='__main__':unittest.main()
