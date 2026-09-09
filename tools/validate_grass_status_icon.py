"""Stdlib PNG and native resource checks for the grass border and Thoughtwell icon."""
from pathlib import Path
import hashlib,json,os,struct,sys,zipfile,zlib
ROOT=Path(__file__).resolve().parents[1];RES=ROOT/'src/main/resources';COMMON=RES/'Common'
sys.path.insert(0,str(ROOT/'tools/assets'))
from terrain_revision_policy import authorized_terrain_edit
def read(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def digest(data):return hashlib.sha256(data).hexdigest()
def rgba(data):
    assert data[:8]==b'\x89PNG\r\n\x1a\n';offset=8;compressed=b'';palette=None;alpha=b''
    while offset<len(data):
        size=struct.unpack('>I',data[offset:offset+4])[0];kind=data[offset+4:offset+8];chunk=data[offset+8:offset+8+size];offset+=size+12
        if kind==b'IHDR':w,h,depth,color,_,_,interlace=struct.unpack('>IIBBBBB',chunk)
        elif kind==b'IDAT':compressed+=chunk
        elif kind==b'PLTE':palette=chunk
        elif kind==b'tRNS':alpha=chunk
        elif kind==b'IEND':break
    assert depth==8 and interlace==0 and color in (2,3,6)
    channels={2:3,3:1,6:4}[color];stride=w*channels;raw=zlib.decompress(compressed);previous=bytearray(stride);out=bytearray();at=0
    def paeth(a,b,c):
        p=a+b-c;aa,bb,cc=abs(p-a),abs(p-b),abs(p-c)
        return a if aa<=bb and aa<=cc else b if bb<=cc else c
    for _ in range(h):
        mode=raw[at];at+=1;row=bytearray(raw[at:at+stride]);at+=stride
        for i in range(stride):
            left=row[i-channels] if i>=channels else 0;up=previous[i];corner=previous[i-channels] if i>=channels else 0
            correction=(0,left,up,(left+up)//2,paeth(left,up,corner))[mode];row[i]=(row[i]+correction)&255
        for i in range(0,stride,channels):
            if color==6:out.extend(row[i:i+4])
            elif color==2:out.extend(row[i:i+3]);out.append(255)
            else:
                index=row[i];out.extend(palette[index*3:index*3+3]);out.append(alpha[index] if index<len(alpha) else 255)
        previous=row
    return w,h,bytes(out)
def weighted_mean(pixels):
    weight=sum(pixels[3::4]);return [sum(pixels[i+c]*pixels[i+3] for i in range(0,len(pixels),4))/weight for c in range(3)]
def stock_mask():
    name='Common/BlockTextures/Transition_Soil_Grass_GS.png';source=ROOT.parent/'HytaleSourceCode/hytale-shared-source/HytaleAssets'/name
    if source.exists():return source.read_bytes()
    archive=Path(os.environ.get('APPDATA',''))/'Hytale/install/release/package/game/latest/Assets.zip'
    if archive.exists():
        with zipfile.ZipFile(archive) as z:return z.read(name)
    return None
def validate():
    report=read(ROOT/'tools/assets/grass-icon-fix.json');grass=read(RES/'Server/Item/Items/StrangeMatter/SM_Anomalous_Grass.json')
    effect=read(RES/'Server/Entity/Effects/StrangeMatter/SM_Cognitive_Dissonance.json')
    assert grass['BlockType']['TransitionTexture']==report['transition']
    assert not any(k.startswith('Tint') or k.startswith('BiomeTint') for k in grass['BlockType']),'Do not multiply existing painted terrain colors'
    border_bytes=(COMMON/report['transition']).read_bytes();w,h,border=rgba(border_bytes)
    assert [w,h]==report['size'] and min(w,h)>=32 and w%32==h%32==0
    assert digest(border_bytes)==report['transitionSha256']
    assert digest(border[3::4])==report['nativeAlphaSha256'],'Native transition alpha changed'
    top_bytes=(COMMON/'BlockTextures/StrangeMatter/Anomalous_Grass_Top.png').read_bytes();top=rgba(top_bytes)[2]
    # A later requested seam repair removes only the original one pixel bevel.
    # Keep the colored fringe byte exact, and accept only the documented top hash.
    top_rel='Common/BlockTextures/StrangeMatter/Anomalous_Grass_Top.png'
    seam_report=ROOT/'tools/assets/seamless-terrain.json'
    top_preserved=digest(top_bytes)==report['grassTopSha256']
    if not top_preserved and seam_report.exists():
        edit=read(seam_report)['textures'][top_rel]
        top_preserved=(edit['beforeSha256']==report['grassTopSha256'] and digest(top_bytes)==edit['afterSha256'])
    assert top_preserved,'Unapproved grass top repaint'
    mean=weighted_mean(top);actual=weighted_mean(border)
    assert all(abs(a-b)<.51 for a,b in zip(mean,actual)),(mean,actual)
    source=stock_mask()
    if source:
        assert digest(source)==report['nativeTransitionSha256'];native=rgba(source)[2]
        assert native[3::4]==border[3::4]
        # Preserve relative native grayscale detail while baking only the current
        # terrain hue. Validate each channel rather than comparing an output hash alone.
        for i in range(0,len(border),4):
            light=sum(native[i:i+3])/3
            for c,factor in enumerate(report['tintFactors']):
                expected=min(255,max(0,int(light*factor+.5)))
                assert border[i+c]==expected
    assert effect['StatusEffectIcon']==report['icon']
    icon_bytes=(COMMON/report['icon']).read_bytes();assert icon_bytes==(COMMON/report['iconSource']).read_bytes()
    iw,ih,icon=rgba(icon_bytes);assert (iw,ih)==(64,64) and max(icon[3::4])==255 and min(icon[3::4])==0
    archive=ROOT/report['snapshot']/'Resources.zip';preserved=0
    if archive.exists():
        with zipfile.ZipFile(archive) as z:
            prefix='src/main/resources/'
            for name in report['unchangedTerrainTextures']:
                rel='Common/BlockTextures/StrangeMatter/'+name
                before=z.read(prefix+rel);after=(RES/rel).read_bytes()
                assert before==after or authorized_terrain_edit(rel,before,after),'Unapproved terrain change: '+rel
                preserved+=int(before==after)
            rel='Server/Item/Items/StrangeMatter/SM_Anomalous_Grass.json';expected=json.loads(z.read(prefix+rel))
            expected['BlockType']['TransitionTexture']=report['transition'];assert expected==grass
            rel='Server/Entity/Effects/StrangeMatter/SM_Cognitive_Dissonance.json';expected=json.loads(z.read(prefix+rel))
            expected['StatusEffectIcon']=report['icon'];assert expected==effect
    print(json.dumps({'result':'PASS','borderSize':[w,h],'grassMeanRGB':mean,'borderMeanRGB':actual,
        'nativeAlphaAndDetailPreserved':source is not None,'originalTerrainTexturesPreserved':preserved,
        'thoughtwellIcon':[iw,ih],'existingIconBytesUnchanged':True}))
if __name__=='__main__':validate()
