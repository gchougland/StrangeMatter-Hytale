"""Stdlib validation of native vector glyph identity, geometry, click bounds and preservation."""
from pathlib import Path
import json
import re
import sys
import zipfile

ROOT=Path(__file__).resolve().parents[1]
UI=ROOT/'src/main/resources/Common/UI/Custom/StrangeMatter'
sys.path.insert(0,str(ROOT/'tools/assets'))
from build_cognition_glyphs import glyphs
from validate_ui import structure


def validate(audit_unrelated=False):
    report=json.loads((ROOT/'tools/assets/cognition-glyphs.json').read_text())
    symbols=report['glyphs'];assert symbols==glyphs() and len(symbols)==9
    template=(UI/'CognitionGlyphs.ui').read_text();page=(UI/'ResearchMachine.ui').read_text()
    structure(template,'CognitionGlyphs.ui');structure(page,'ResearchMachine.ui')
    assert not re.search(r'\b(?:Label|TextButton|Text|TexturePath)\b',template),'Glyphs must be native vector strokes, not text or images'
    occupied=[];strokes=0
    for i,symbol in enumerate(symbols):
        name=symbol['name']
        variants=[]
        for suffix,palette in [('',report['normal']),('Lit',report['cue'])]:
            start=template.index('@'+name+suffix+' = Group {');end=template.index('\n};',start)
            body=template[start:end]
            assert 'HitTestVisible: false;' in body and not re.search(r'@\w+\s*=',body[len('@'+name+suffix+' = Group {'):])
            actual=[tuple(map(int,m[:4]))+(m[4],) for m in re.findall(r'Left: (\d+), Top: (\d+), Width: (\d+), Height: (\d+)\); Background: (#[0-9a-f]{6}); HitTestVisible: false;',body)]
            assert actual==[(r['x'],r['y'],r['w'],r['h'],palette[r['role']]) for r in symbol['rectangles']]
            variants.append([r[:4] for r in actual])
        assert variants[0]==variants[1],'Lit and normal templates must have identical geometry'
        pixels=set()
        for x,y,w,h,role in actual:
            assert 0<x<x+w<37 and 0<y<y+h<29 and min(w,h)>=2
            pixels.update((xx,yy) for xx in range(x,x+w) for yy in range(y,y+h));strokes+=1
        occupied.append(pixels)
        x,y=76+(i%3)*42,(i//3)*34
        anchor=f'Anchor: (Left: {x}, Top: {y}, Width: 37, Height: 29);'
        assert f'$R.@Control #Rune{i} {{ Text: ""; {anchor} }}' in page
        assert f'$G.@{name} #RuneMark{i} {{ {anchor} }}' in page
        assert f'$G.@{name}Lit #RuneGlow{i} {{ {anchor} Background: #31526a; Visible: false; HitTestVisible: false; }}' in page
    # Shape alone must distinguish the glyphs; color is only an additional cue.
    for i,a in enumerate(occupied):
        for b in occupied[i+1:]:
            assert len(a^b)>=80,'Glyph silhouettes are too similar at actual UI size'
    assert not re.search(r'#Rune\d+\s*\{[^}]*Text:\s*"\d+"',page)
    snapshot=ROOT/report['snapshot']/'Resources.zip'
    if audit_unrelated and snapshot.exists():
        with zipfile.ZipFile(snapshot) as archive:
            before=archive.read('src/main/resources/Common/UI/Custom/StrangeMatter/ResearchMachine.ui').decode('utf-8-sig')
            def surrounding(text):
                text=text.replace('$G = "CognitionGlyphs.ui";\n','')
                first=text.index('$R.@Control #Rune0');last=text.index('Label #CognitionReadout',first)
                return (text[:first]+text[last:]).replace('Watch the glowing symbols, then repeat their order.','Watch the lit keys, then repeat their order.').replace('\r\n','\n')
            assert surrounding(before)==surrounding(page),'An unrelated instrument or selection panel changed'
    print(json.dumps({'result':'PASS','distinctVectorGlyphs':9,'nativeTemplates':18,
                      'uniqueStrokeGeometry':strokes,'nativeStrokeGroups':strokes*2,
                      'unchangedClickTargets':9,'buttonSize':[37,29],'identicalCueAndIdleShapes':True,
                      'fontOrBitmapDependency':False,'lateParameterOverrides':False,
                      'unrelatedInstrumentMarkupPreserved':True if audit_unrelated and snapshot.exists() else None}))


if __name__=='__main__':
    import argparse
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--audit-unrelated',action='store_true',help='Compare all surrounding UI to the original glyph snapshot before later intentional UI revisions')
    validate(parser.parse_args().audit_unrelated)
