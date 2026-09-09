"""Static native-UI cross-reference audit. Not a substitute for client UI parsing.

Checks native string escapes, label alignment literals, delimiter/string balance, duplicate selectors, imported style symbols,
all page Java literal selectors, and generated minigame selector ranges.
"""
from pathlib import Path
import json,re
ROOT=Path(__file__).resolve().parents[1]
UI=ROOT/'src/main/resources/Common/UI/Custom/StrangeMatter'
BASE=ROOT.parent/'HytaleSourceCode/hytale-shared-source/HytaleAssets/Common/UI/Custom'

def structure(text,path):
    stack=[];quote=False;escape=False;escape_at=0;i=0
    while i<len(text):
        c=text[i]
        if quote:
            if escape:
                if c not in ('\\','"'):
                    line=text.count('\n',0,escape_at)+1
                    column=escape_at-text.rfind('\n',0,escape_at)
                    raise ValueError(f'{path}:{line}:{column}: unsupported UI string escape {text[escape_at:i+1]!r}; only escaped backslash and double quote are allowed')
                escape=False
            elif c=='\\':escape=True;escape_at=i
            elif c=='"':quote=False
        elif c=='"':quote=True
        elif text[i:i+2]=='//':i=text.find('\n',i) if '\n' in text[i:] else len(text)
        elif c in '{(':stack.append(c)
        elif c in '})':assert stack and stack.pop()=={'}':'{',')':'('}[c],path
        i+=1
    assert not stack and not quote,path

def label_alignments(text,path):
    # Both axes use LabelAlignment: Start/Center/End. Anchor.Right is a different property.
    # Mask quoted text and comments, keeping positions for actionable client-style diagnostics.
    masked=re.sub(r'"(?:\\.|[^"\\])*"|//[^\n]*',lambda m:re.sub(r'[^\n]',' ',m.group()),text)
    for match in re.finditer(r'\b((?:Horizontal|Vertical)Alignment)\s*:\s*([A-Za-z_]\w*)',masked):
        if match.group(2) not in {'Start','Center','End'}:
            at=match.start(2);line=text.count('\n',0,at)+1;column=at-text.rfind('\n',0,at)
            raise ValueError(f'{path}:{line}:{column}: invalid {match.group(1)} {match.group(2)!r}; LabelAlignment uses Start, Center or End')

def main():
    files={p.name:p.read_text() for p in UI.glob('*.ui')};selectors={};imports=0;java_refs=0
    for name,text in files.items():
        structure(text,name)
        label_alignments(text,name)
        ids=re.findall(r'(?:Group|Label|ItemIcon|Button|TextButton|\$\w+\.@\w+)\s+#(\w+)\s*\{',text)
        assert len(ids)==len(set(ids)),(name,'duplicate selector');selectors[name]=set(ids)
        aliases=dict(re.findall(r'\$(\w+)\s*=\s*"([^"]+)"',text))
        for alias,symbol in re.findall(r'\$(\w+)\.@(\w+)',text):
            assert alias in aliases,(name,alias);rel=aliases[alias];path=(UI/rel).resolve()
            if not path.exists():path=(BASE/'StrangeMatter'/rel).resolve()
            assert path.exists(),(name,rel)
            assert re.search(r'@'+re.escape(symbol)+r'\s*=',path.read_text()),(name,alias,symbol)
            imports+=1
    pages={'research/ResearchMachinePage.java':['ResearchMachine.ui','ResearchNoteRow.ui'],
      'research/ResearchTabletPage.java':['ResearchTablet.ui','ResearchNodeRow.ui'],
      'research/ResearchInfoPage.java':['ResearchInfo.ui'],
      'machine/MachinePage.java':['Machine.ui','RealityForge.ui','ForgeRecipeRow.ui']}
    for source,ui_files in pages.items():
        text=(ROOT/'src/main/java/com/hexvane/strangematter'/source).read_text();ids=set().union(*(selectors[f] for f in ui_files))
        refs=set(re.findall(r'"\s*#([A-Za-z]\w*)',text))
        for ref in refs:
            if re.fullmatch(r'[0-9a-fA-F]{6}',ref):continue
            assert ref in ids or any(s.startswith(ref) for s in ids),(source,ref)
            java_refs+=1
        for ref in re.findall(r'bindControl\(events, "(\w+)"',text):assert ref in ids or any(s.startswith(ref) for s in ids),(source,ref)
    machine=selectors['ResearchMachine.ui']
    forge=selectors['RealityForge.ui']
    for family in ('Material','MaterialAccent','MaterialIcon','MaterialName','MaterialCount','ForgeMote'):
        assert all(family+str(i) in forge for i in range(8)),family
    catalog=json.loads((ROOT/'src/main/resources/Server/StrangeMatter/recipes.json').read_text())
    assert all(len(recipe['ingredients'])+len(recipe['shards'])<=8 for recipe in catalog if recipe['station']=='forge'),'Forge material cards must cover every ingredient'
    for family,count in [('Rune',9),('RuneGlow',9),('Force',11),('WaveTarget',32),('WaveLive',32),('ShadowTarget',12),('ShadowLive',12),('SpaceDot',49),('TimeTarget',12),('TimeLive',12),('ClockMark',12)]:
        assert all(family+str(i) in machine for i in range(count)),family
    for discipline in ['COGNITION','ENERGY','GRAVITY','SHADOW','SPACE','TIME']:
        assert all(discipline+suffix in machine for suffix in ['Controls','Shutter','State']),discipline
    report={'status':'PASS','uiFiles':len(files),'selectors':sum(map(len,selectors.values())),'importedSymbolUses':imports,'javaLiteralSelectorFamilies':java_refs,
      'checks':['Native string escape rules','Native label alignment literals','String and delimiter balance','Unique selectors per template','Imported UI files and style symbols resolve','Java page selectors resolve','Every dynamic minigame selector range exists'],
      'limitation':'Static comparison with supplied native UI assets; client UI parser and visual session still required.'}
    (ROOT/'tools/assets/ui-validation.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report))

if __name__=='__main__':main()
