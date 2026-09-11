"""Current native creative grouping and transparent glyph contracts. No historical artwork lock."""
from pathlib import Path
import json
from assets.verify_texture_repack import decode_png

ROOT=Path(__file__).resolve().parents[1]
RES=ROOT/'src/main/resources'


def validate_definitions(category,items,language):
    children={child['Id']:child for child in category['Children']}
    assert set(children)=={'All','Gadgets','Machines','Materials','Decorations','Building','Nature','Research'},'Keep the existing navigation tabs'
    headings=set()
    for child in children.values():
        definitions=child.get('SubCategories',[])
        declared={entry['Id'] for entry in definitions}
        assert definitions and len(declared)==len(definitions),'Each tab needs unique native SubCategories headings'
        orders=[]
        for entry in definitions:
            assert entry['Name'].removeprefix('server.') in language,'Missing heading name'
            assert entry['Description'].removeprefix('server.') in language,'Missing heading description'
            orders.append(entry['Order']);headings.add(entry['Id'])
        assert orders==sorted(set(orders)),'Heading orders must be distinct and sorted'
        used=set()
        for ident,item in items.items():
            if 'SM_StrangeMatter.'+child['Id'] not in item.get('Categories',[]):continue
            heading=item.get('SubCategory')
            assert heading in declared,ident+' does not map to a declared item heading in '+child['Id']
            used.add(heading)
        assert used==declared,'Do not add empty headings or extra navigation tabs'
    return len(headings)


def validate_glyph(raw,size):
    width,height,pixels=decode_png(raw)
    assert (width,height)==(size,size),'Native category glyph canvas has the wrong size'
    alpha=pixels[3::4]
    assert all(alpha[y*width+x]<=8 for y in range(height) for x in range(width)
               if x<3 or y<3 or x>=width-3 or y>=height-3),'Glyph must leave transparent outer padding'
    coverage=sum(a>32 for a in alpha)/(width*height)
    assert .07<coverage<.55,'An icon must contain a clear symbol without a filled tab plate'
    assert all(pixels[i:i+3]==b'\xff\xff\xff' for i in range(0,len(pixels),4) if pixels[i+3]>0),'Category glyphs must be white silhouettes on transparency'


def validate():
    category=json.loads((RES/'Server/Item/Category/CreativeLibrary/SM_StrangeMatter.json').read_text())
    items={p.stem:json.loads(p.read_text()) for p in (RES/'Server/Item/Items/StrangeMatter').glob('SM_*.json')}
    language=dict(line.split('=',1) for line in (RES/'Server/Languages/en-US/server.lang').read_text(encoding='utf-8-sig').splitlines() if '=' in line and not line.startswith('#'))
    headings=validate_definitions(category,items,language)
    validate_glyph((RES/'Common'/category['Icon']).read_bytes(),88)
    for child in category['Children']:
        # All is the user's unchanged reference glyph. Validate its canvas, not a new paint recipe.
        raw=(RES/'Common'/child['Icon']).read_bytes()
        if child['Id']=='All':assert decode_png(raw)[:2]==(48,48)
        else:validate_glyph(raw,48)
    return {'status':'PASS','items':len(items),'navigationTabs':len(category['Children']),'headings':headings,
            'revisedTransparentIcons':8,'schema':'Item.SubCategory matched to ItemCategory.SubCategories',
            'liveClientTested':False}


if __name__=='__main__':print(json.dumps(validate(),indent=2))
