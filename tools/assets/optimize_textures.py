"""Lossless model atlas packing. Keeps texel density, geometry, colors and UV orientation.

Plan first, verify against an immutable resource snapshot, then apply. Shared models,
state textures, attachments and all recolors form a single packing group. No art is
painted, scaled, quantized or regenerated. Icons, UI and particle sheets stay intact.
"""
from pathlib import Path
from collections import defaultdict
import argparse, copy, hashlib, io, json, math, zipfile
from PIL import Image

ROOT=Path(__file__).resolve().parents[2]; RES=ROOT/'src/main/resources'; COMMON=RES/'Common'
OUT=ROOT/'build/texture-optimization'
PAD=1

def read(path):return json.loads(path.read_text(encoding='utf-8-sig'))
def sha(data):return hashlib.sha256(data).hexdigest()
def walk(value):
    if isinstance(value,dict):
        yield value
        for child in value.values():yield from walk(child)
    elif isinstance(value,list):
        for child in value:yield from walk(child)

def strings(value):
    if isinstance(value,str):yield value
    elif isinstance(value,dict):
        for child in value.values():yield from strings(child)
    elif isinstance(value,list):
        for child in value:yield from strings(child)

def faces(model):
    for node in walk(model.get('nodes',[])):
        shape=node.get('shape')
        if not isinstance(shape,dict):continue
        kind=shape.get('type'); size=shape.get('settings',{}).get('size',{})
        for side,layout in shape.get('textureLayout',{}).items():
            if kind=='box':
                axes=('x','z') if side in ('top','bottom') else ('z','y') if side in ('left','right') else ('x','y')
            elif kind=='quad':axes=('x','y')
            else:raise ValueError('UV layout on unsupported shape '+str(kind))
            w,h=(size.get(axis,0) for axis in axes)
            if w<=0 or h<=0:continue
            ox,oy=layout['offset']['x'],layout['offset']['y'];mirror=layout.get('mirror',{})
            angle=layout.get('angle',0)%360
            if angle%90:raise ValueError('Noncardinal UV angle')
            corners=[]
            for x,y in ((0,0),(w,0),(0,h),(w,h)):
                if mirror.get('x'):x=-x
                if mirror.get('y'):y=-y
                for _ in range(int(angle//90)):x,y=-y,x
                corners.append((x+ox,y+oy))
            xs,ys=zip(*corners)
            box=(math.floor(min(xs)),math.floor(min(ys)),math.ceil(max(xs)),math.ceil(max(ys)))
            yield layout,box

class Graph:
    def __init__(self):self.links=defaultdict(set);self.unsafe=set()
    def join(self,models,textures):
        for m in models:
            for t in textures:self.links[m].add(t);self.links[t].add(m)
    def components(self):
        seen=set()
        for start in sorted(self.links):
            if start in seen:continue
            todo=[start];component=set()
            while todo:
                item=todo.pop()
                if item in component:continue
                component.add(item);todo.extend(self.links[item]-component)
            seen.update(component);yield component

def groups():
    graph=Graph();documents={}
    for path in (RES/'Server').rglob('*.json'):
        documents[path]=read(path)
    # A model swap inherits its parent's texture; an attachment has its own pair.
    by_id={p.stem:d for p,d in documents.items() if '/Models/' in p.as_posix() or '/Items/' in p.as_posix()}
    def inherited(document,seen=None):
        seen=set() if seen is None else seen
        if not isinstance(document,dict):return [document]
        parent=document.get('Parent')
        if parent in by_id and parent not in seen:
            return [document,*inherited(by_id[parent],seen|{parent})]
        return [document]
    def bind(value,model=None,textures=()):
        if isinstance(value,dict):
            model=value.get('Model',value.get('CustomModel',model))
            if isinstance(value.get('Texture'),str):textures=(value['Texture'],)
            if isinstance(value.get('CustomModelTexture'),list):textures=tuple(t['Texture'] for t in value['CustomModelTexture'])
            if isinstance(model,str) and model.endswith('.blockymodel'):
                local=[t for t in textures if (COMMON/t).is_file()]
                if (COMMON/model).is_file():
                    graph.join([model],local)
                    if len(local)!=len(textures):graph.unsafe.add(model)
                else:graph.unsafe.update(local)
            for child in value.values():bind(child,model,textures)
        elif isinstance(value,list):
            for child in value:bind(child,model,textures)
    def merge(parent,child):
        result=copy.deepcopy(parent)
        for key,value in child.items():
            result[key]=merge(result[key],value) if isinstance(value,dict) and isinstance(result.get(key),dict) else copy.deepcopy(value)
        return result
    for path,document in documents.items():
        docs=inherited(document)
        combined={}
        for doc in reversed(docs):
            if isinstance(doc,dict):combined=merge(combined,doc)
        bind(combined)
        local_textures={obj['Texture'] for obj in walk(document) if isinstance(obj.get('Texture'),str) and (COMMON/obj['Texture']).is_file()}
        # Never relocate a sheet addressed directly by sprite coordinates or cube faces.
        if '/Particles/' in path.as_posix():graph.unsafe.update(local_textures)
        for animation in (value for value in strings(combined) if value.endswith('.blockyanim') and (COMMON/value).is_file()):
            if any(obj.get('shapeUvOffset') for obj in walk(read(COMMON/animation))):
                graph.unsafe.update(local_textures)
                graph.unsafe.update(value for value in strings(combined) if value.endswith('.blockymodel'))
        for obj in walk(document):
            if 'Textures' in obj:
                graph.unsafe.update(v for v in strings(obj['Textures']) if v.endswith('.png'))
            if isinstance(obj.get('Icon'),str):graph.unsafe.add(obj['Icon'])
    # Preserve native editor companions and unused model variants sharing that atlas.
    for path in COMMON.rglob('*.blockymodel'):
        texture=path.with_suffix('.png')
        if texture.is_file():graph.join([path.relative_to(COMMON).as_posix()],[texture.relative_to(COMMON).as_posix()])
    for model,texture in {'resonant_conduit_full':'resonant_conduit','resonite_door_open_in':'resonite_door',
                           'resonite_door_open_out':'resonite_door','resonite_trapdoor_open_out':'resonite_trapdoor'}.items():
        graph.join(['Blocks/StrangeMatter/'+model+'.blockymodel'],['Blocks/StrangeMatter/'+texture+'.png'])
    for path in (COMMON/'UI').rglob('*.ui'):
        text=path.read_text(encoding='utf-8-sig')
        graph.unsafe.update(t for t in graph.links if t.endswith('.png') and t in text)
    for component in graph.components():
        models=sorted(x for x in component if x.endswith('.blockymodel'));textures=sorted(x for x in component if x.endswith('.png'))
        if models and textures:yield models,textures,sorted(component&graph.unsafe)

def crop_with_border(image,box):
    l,t,r,b=box;w,h=image.size
    if not (0<=l<r<=w and 0<=t<b<=h):raise ValueError(f'Existing UV outside atlas: {box} vs {image.size}')
    # Preserve even the original neighboring gutter texels, rather than repainting them.
    tile=image.crop((l-PAD,t-PAD,r+PAD,b+PAD))
    if l==0:tile.paste(tile.crop((1,0,2,tile.height)),(0,0))
    if r==w:tile.paste(tile.crop((tile.width-2,0,tile.width-1,tile.height)),(tile.width-1,0))
    if t==0:tile.paste(tile.crop((0,1,tile.width,2)),(0,0))
    if b==h:tile.paste(tile.crop((0,tile.height-2,tile.width,tile.height-1)),(0,tile.height-1))
    return tile

def pack(rects,width,height):
    free=[(0,0,width,height)];placed={}
    for key,w,h in sorted(rects,key=lambda r:(-max(r[1:]),-r[1]*r[2],r[0])):
        choices=[(min(fw-w,fh-h),max(fw-w,fh-h),y,x,i) for i,(x,y,fw,fh) in enumerate(free) if w<=fw and h<=fh]
        if not choices:return None
        _,_,y,x,_=min(choices);placed[key]=(x,y);new=[]
        for fx,fy,fw,fh in free:
            right,bottom=fx+fw,fy+fh
            if x>=right or x+w<=fx or y>=bottom or y+h<=fy:new.append((fx,fy,fw,fh));continue
            if x>fx:new.append((fx,fy,x-fx,fh))
            if x+w<right:new.append((x+w,fy,right-x-w,fh))
            if y>fy:new.append((fx,fy,fw,y-fy))
            if y+h<bottom:new.append((fx,y+h,fw,bottom-y-h))
        unique=list(dict.fromkeys(new));free=[]
        for i,a in enumerate(unique):
            if not any(i!=j and a[0]>=b[0] and a[1]>=b[1] and a[0]+a[2]<=b[0]+b[2] and a[1]+a[3]<=b[1]+b[3] for j,b in enumerate(unique)):free.append(a)
    return placed

def best_pack(rects,old_size):
    area=sum(w*h for _,w,h in rects);minw=max(w for _,w,h in rects);minh=max(h for _,w,h in rects)
    candidates=[];limit=old_size[0]*old_size[1]
    # Native dimensions are also an art workflow constraint: powers of two, square
    # or landscape. Prefer compact shapes, using wider rows when necessary.
    powers=[2**n for n in range(5,math.floor(math.log2(max(32,limit//32)))+1)]
    for w in powers:
        for h in powers:
            if w<minw or h<minh or h>w or not area<=w*h<=limit:continue
            candidates.append((w*h,w/h,w,h))
    for _,_,w,h in sorted(candidates):
        result=pack(rects,w,h)
        if result is not None:return (w,h),result
    return None,None

def joined_regions(boxes):
    # Preserve preexisting UV sharing, including small faces inside larger painted
    # swatches. Packing every overlapping face independently would duplicate them.
    regions=[]
    for box in boxes:
        region=box;remaining=list(regions);regions=[]
        while remaining:
            other=remaining.pop()
            if region[0]<other[2] and other[0]<region[2] and region[1]<other[3] and other[1]<region[3]:
                region=(min(region[0],other[0]),min(region[1],other[1]),max(region[2],other[2]),max(region[3],other[3]))
                remaining.extend(regions);regions=[]
            else:regions.append(other)
        regions.append(region)
    return regions

def png_bytes(atlas):
    choices=[]
    buffer=io.BytesIO();atlas.save(buffer,format='PNG',optimize=True);choices.append(buffer.getvalue())
    # An exact palette is lossless. Never quantize distinct shades to reduce size.
    colors=atlas.getcolors(257)
    if colors is not None:
        palette=[color for count,color in sorted(colors,reverse=True)];indices={color:i for i,color in enumerate(palette)}
        indexed=Image.new('P',atlas.size);pixels=atlas.get_flattened_data() if hasattr(atlas,'get_flattened_data') else atlas.getdata()
        indexed.putdata([indices[pixel] for pixel in pixels])
        indexed.putpalette([channel for color in palette for channel in color[:3]]+[0]*(768-len(palette)*3))
        buffer=io.BytesIO();indexed.save(buffer,format='PNG',optimize=True,transparency=bytes(color[3] for color in palette))
        encoded=buffer.getvalue()
        assert Image.open(io.BytesIO(encoded)).convert('RGBA').tobytes()==atlas.tobytes()
        choices.append(encoded)
    return min(choices,key=len)

def optimize(models,textures):
    images={t:Image.open(COMMON/t).convert('RGBA') for t in textures}
    sizes={t:list(im.size) for t,im in images.items()}
    if len({tuple(size) for size in sizes.values()})!=1:raise ValueError('Variant atlases have different dimensions')
    old_size=next(iter(images.values())).size;documents={m:read(COMMON/m) for m in models}
    unique={};moves=[];source_cache={};face_count=0
    all_faces=[face for doc in documents.values() for face in faces(doc)]
    boxes=list(dict.fromkeys(box for layout,box in all_faces));regions=joined_regions(boxes)
    # Two candidates retain either individual islands or their shared source regions.
    plans=[]
    for candidate in (boxes,regions):
        patches={};lookup={}
        for box in candidate:
            tiles={t:crop_with_border(im,box) for t,im in images.items()}
            first=next(iter(tiles.values()));key=(first.width,first.height,sha(b''.join(im.tobytes() for im in tiles.values())))
            if key not in patches:patches[key]=tiles
            lookup[box]=key
        size,positions=best_pack([(key,key[0],key[1]) for key in patches],old_size)
        if size is not None:plans.append((size[0]*size[1],len(patches),size,positions,patches,lookup))
    if not plans:raise ValueError('No atlas area reduction with exact texels and gutters')
    _,_,size,positions,unique,source_cache=min(plans,key=lambda p:p[:2])
    for m,doc in documents.items():
        for layout,box in faces(doc):
            face_count+=1
            if box not in source_cache:
                box=next(region for region in source_cache if region[0]<=box[0] and region[1]<=box[1] and region[2]>=box[2] and region[3]>=box[3])
            moves.append((layout,box,source_cache[box]))
    atlases={t:Image.new('RGBA',size) for t in textures}
    for key,tiles in unique.items():
        for t,tile in tiles.items():atlases[t].paste(tile,positions[key])
    for layout,box,key in moves:
        x,y=positions[key];layout['offset']['x']+=x+PAD-box[0];layout['offset']['y']+=y+PAD-box[1]
    outputs={m:(json.dumps(doc,indent=2)+'\n').encode('utf-8') for m,doc in documents.items()}
    for t,atlas in atlases.items():
        outputs[t]=png_bytes(atlas)
    if size[0]*size[1]==old_size[0]*old_size[1] and old_size[0]>=old_size[1] and sum(len(outputs[t]) for t in textures)>=sum((COMMON/t).stat().st_size for t in textures):
        raise ValueError('Already compact at power-of-two landscape dimensions; no file-size gain')
    return outputs,dict(models=models,textures=textures,beforeSizes=sizes,afterSize=list(size),faces=face_count,
        uniquePatches=len(unique),sourceRectangles=len(source_cache),beforePixels=old_size[0]*old_size[1]*len(textures),afterPixels=size[0]*size[1]*len(textures))

def main():
    global OUT
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--apply',action='store_true');parser.add_argument('--resume',action='store_true');parser.add_argument('--apply-plan',action='store_true');parser.add_argument('--work-dir',type=Path,default=OUT);args=parser.parse_args()
    OUT=args.work_dir.resolve();OUT.mkdir(parents=True,exist_ok=True);baseline=OUT/'before-resources.zip'
    if args.apply_plan:
        report=read(OUT/'packing-plan.json')
        from verify_texture_repack import verify
        verification=verify(baseline,OUT/'packing-plan.json',OUT/'staged')
        (OUT/'apply-verification.json').write_text(json.dumps(verification,indent=2)+'\n')
        with zipfile.ZipFile(baseline) as archive:
            for p in RES.rglob('*'):
                if p.is_file() and p.read_bytes()!=archive.read(p.relative_to(RES).as_posix()):raise SystemExit('Resource edited after planning: '+str(p))
        for path,digest in report['sourceHashes'].items():
            if sha((COMMON/path).read_bytes())!=digest:raise SystemExit('Asset changed after planning: '+path)
            if sha((OUT/'staged/Common'/path).read_bytes())!=report['outputHashes'][path]:raise SystemExit('Staged output changed: '+path)
        for path in report['sourceHashes']:(COMMON/path).write_bytes((OUT/'staged/Common'/path).read_bytes())
        print('Applied verified staged atlases and UVs. Original resources remain in '+str(baseline));return
    if baseline.exists():
        if not args.resume:raise SystemExit('Snapshot already exists; use --resume to replan unchanged inputs, or --apply-plan after verification.')
        with zipfile.ZipFile(baseline) as archive:
            for p in RES.rglob('*'):
                if p.is_file() and p.read_bytes()!=archive.read(p.relative_to(RES).as_posix()):raise SystemExit('Resource edited since snapshot: '+str(p))
    else:
        with zipfile.ZipFile(baseline,'w',zipfile.ZIP_DEFLATED) as archive:
            for p in RES.rglob('*'):
                if p.is_file():archive.write(p,p.relative_to(RES).as_posix())
    report={'version':1,'baseline':str(baseline),'groups':[],'skipped':[],'textureAliases':{}}
    outputs={};hashes={}
    for models,textures,unsafe in groups():
        if unsafe:report['skipped'].append({'models':models,'textures':textures,'reason':'External model or nonmodel consumer: '+', '.join(unsafe)});continue
        try:
            changed,group=optimize(models,textures);outputs.update(changed);report['groups'].append(group)
            print(f'{textures[0]}: {next(iter(group["beforeSizes"].values()))} -> {group["afterSize"]}, {group["faces"]} faces / {group["uniquePatches"]} patches',flush=True)
        except ValueError as error:report['skipped'].append({'models':models,'textures':textures,'reason':str(error)})
    with zipfile.ZipFile(baseline) as archive:
        for p in RES.rglob('*'):
            if p.is_file() and p.read_bytes()!=archive.read(p.relative_to(RES).as_posix()):raise SystemExit('Resource edited during planning: '+str(p))
    stage=OUT/'staged'
    with zipfile.ZipFile(baseline) as archive:
        for name in archive.namelist():
            target=stage/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(archive.read(name))
    for path,data in outputs.items():
        hashes[path]=sha((COMMON/path).read_bytes());target=stage/'Common'/path;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)
    report['sourceHashes']=hashes;report['outputHashes']={path:sha(data) for path,data in outputs.items()};report['beforePixels']=sum(g['beforePixels'] for g in report['groups']);report['afterPixels']=sum(g['afterPixels'] for g in report['groups'])
    report['beforePngBytes']=sum((COMMON/t).stat().st_size for g in report['groups'] for t in g['textures'])
    report['afterPngBytes']=sum(len(outputs[t]) for g in report['groups'] for t in g['textures'])
    (OUT/'packing-plan.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({key:report[key] for key in ('beforePixels','afterPixels','beforePngBytes','afterPngBytes')},indent=2))
    if args.apply:
        from verify_texture_repack import verify
        verification=verify(baseline,OUT/'packing-plan.json',stage)
        (OUT/'apply-verification.json').write_text(json.dumps(verification,indent=2)+'\n')
        for path,digest in hashes.items():
            if sha((COMMON/path).read_bytes())!=digest:raise SystemExit('Asset changed while optimizing: '+path)
        for path,data in outputs.items():(COMMON/path).write_bytes(data)
        print('Applied packed model UVs and atlases. Baseline preserved at '+str(baseline))

if __name__=='__main__':main()
