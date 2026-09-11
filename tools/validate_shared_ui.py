"""Validate current scalable controls and native creative grouping. Historical art audit is opt in."""
from pathlib import Path
import argparse
import hashlib
import json
import re
import struct
from collections import Counter
from validate_ui import structure, label_alignments

ROOT=Path(__file__).resolve().parents[1];RES=ROOT/'src/main/resources';COMMON=RES/'Common';UI=COMMON/'UI/Custom/StrangeMatter'
GROUPS={'Gadgets','Machines','Materials','Decorations','Building','Nature','Research'}


def png_size(path):
    data=path.read_bytes();assert data[:8]==b'\x89PNG\r\n\x1a\n',str(path)+' is not PNG'
    return struct.unpack('>II',data[16:24])


def validate():
    library=(UI/'SharedButtons.ui').read_text(encoding='utf-8-sig');structure(library,'SharedButtons.ui');label_alignments(library,'SharedButtons.ui')
    for template in ('TextButton','SecondaryTextButton','CancelTextButton','IconButton','Button','Control','ControlStyle','RowStyle','TreeNodeStyle'):
        assert re.search('@'+template+r'\s*=',library),'Missing shared control '+template
    textures={}
    for name,path,border in re.findall(r'@(\w+)\s*=\s*PatchStyle\(TexturePath:\s*"([^"]+)",\s*Border:\s*(\d+)\)',library):
        size=png_size(UI/path);assert all(n>0 and not n&(n-1) for n in size) and size[0]>=size[1],path+' must be square or wide powers of two'
        assert 0<int(border)<min(size)//2,path+' border must leave a stretchable center'
        textures[name]=path
    assert len(textures)>=12,'Three distinct complete four-state button themes'
    for style,kind,body in re.findall(r'@(\w+)\s*=\s*(TextButtonStyle|ButtonStyle)\((.*?)\n\);',library,re.S):
        if style=='TreeNodeStyle':
            for state in ('Default','Disabled'):
                assert state+': (Background: #000000(0))' in body,'Diagram node hit targets must leave circuit wires visible'
            for state in ('Hovered','Pressed'):
                assert re.search(state+r':\s*\(Background:\s*#[0-9a-f]{6}\(0\.[0-9]+\)\)',body),'Diagram node feedback must remain translucent'
            continue
        for state in ('Default','Hovered','Pressed','Disabled'):
            match=re.search(state+r':\s*\(Background:\s*@(\w+)',body)
            assert match and match[1] in textures,style+' lacks a native background for '+state
    assert 'TextTooltipStyle: $C.@DefaultTextTooltipStyle;' in library,'Icon controls need native tooltip styling'
    documents=0
    for path in UI.glob('*.ui'):
        text=path.read_text(encoding='utf-8-sig');structure(text,path.name);label_alignments(text,path.name)
        assert not re.search(r'\$C\.@(?:TextButton|CancelTextButton|SecondaryTextButton|Button|IconButton)\b',text),path.name+' still uses a stock button'
        if '$R.@Control' in text or '$R.@RowStyle' in text or '$B.@' in text:documents+=1
    category=json.loads((RES/'Server/Item/Category/CreativeLibrary/SM_StrangeMatter.json').read_text())
    children={c['Id']:c for c in category['Children']};assert set(children)==GROUPS|{'All'},'All native creative subcategories must remain available'
    language=dict(line.split('=',1) for line in (RES/'Server/Languages/en-US/server.lang').read_text(encoding='utf-8-sig').splitlines() if '=' in line and not line.startswith('#'))
    for name,child in children.items():
        assert child['Name'].removeprefix('server.') in language,'Missing category label '+name
        assert child['Icon'].startswith('Icons/ItemCategories/'),'Native creative icon root is required'
        size=png_size(COMMON/child['Icon'])
        assert size==(48,48),'Native subtab symbols use the same 48 pixel canvas as All'
    members=Counter();items=0
    for path in (RES/'Server/Item/Items/StrangeMatter').glob('SM_*.json'):
        item=json.loads(path.read_text());categories=item.get('Categories',[])
        assert 'SM_StrangeMatter.All' in categories,path.name+' missing All collection'
        groups={c.split('.',1)[1] for c in categories if c.startswith('SM_StrangeMatter.') and c!='SM_StrangeMatter.All'}
        assert groups and groups<=GROUPS,path.name+' lacks a valid creative subsection'
        members.update(groups);items+=1
    assert set(members)==GROUPS,'No empty creative sections'
    from validate_creative_groups import validate as validate_creative
    creative=validate_creative()
    return {'status':'PASS','sharedUiDocuments':documents,'buttonPatches':len(textures),'items':items,'creativeSections':dict(members),'creativeHeadings':creative['headings'],'historicalArtAudited':False}


def audit_art(path):
    before=json.loads(path.read_text());checked=0;changes=[]
    for name,digest in before.items():
        # Existing UI text is the authorized migration. Every other preexisting Common file is protected.
        if name.endswith('.ui'):continue
        source=COMMON/name
        if not source.exists() or hashlib.sha256(source.read_bytes()).hexdigest()!=digest:changes.append(name)
        checked+=1
    assert not changes,'Existing artwork changed: '+', '.join(changes)
    return checked


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--audit-art',type=Path);args=parser.parse_args();report=validate()
    if args.audit_art:report['historicalArtAudited']=True;report['historicalFilesChecked']=audit_art(args.audit_art)
    print(json.dumps(report,indent=2))
