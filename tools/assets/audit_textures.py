"""Read-only PNG/UV audit. Writes build/texture-audit.json; never modifies art.

UV bounds follow the native signed corner origin convention, including mirrors
and quarter turns. Coverage includes hidden faces and all models sharing an
atlas. It is a texel occupancy measurement, not a guarantee that arbitrary
face repacking is safe: animated UVs and nonmodel consumers are excluded.
"""
from pathlib import Path
from collections import defaultdict, Counter
import hashlib, json, math, re
from PIL import Image
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / 'src/main/resources'
COMMON = RES / 'Common'

def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))

def nodes(items):
    for node in items:
        yield node
        yield from nodes(node.get('children', []))

def strings(value, path='$'):
    if isinstance(value, str):
        yield path, value
    elif isinstance(value, dict):
        for key, item in value.items():
            yield from strings(item, path + '.' + key)
    elif isinstance(value, list):
        for i, item in enumerate(value):
            yield from strings(item, path + '[' + str(i) + ']')

def face_bounds(layout, width, height):
    points = [(0, 0), (width, 0), (width, height), (0, height)]
    mirror = layout.get('mirror', {})
    angle = layout.get('angle', 0)
    if angle % 90:
        raise ValueError('Non-cardinal UV rotation: ' + str(angle))
    offset = layout.get('offset', {})
    result = []
    for x, y in points:
        if mirror.get('x'): x = -x
        if mirror.get('y'): y = -y
        for _ in range(int(angle // 90) % 4): x, y = -y, x
        result.append((x + offset.get('x', 0), y + offset.get('y', 0)))
    return [min(x for x,y in result), min(y for x,y in result),
            max(x for x,y in result), max(y for x,y in result)]

def rectangles(model):
    rects, problems = [], []
    for node in nodes(model.get('nodes', [])):
        shape = node.get('shape', {})
        kind = shape.get('type')
        if kind not in ('box', 'quad'):
            if shape.get('textureLayout'): problems.append('Unknown textured shape: ' + str(kind))
            continue
        size = shape.get('settings', {}).get('size', {})
        for face, layout in shape.get('textureLayout', {}).items():
            if kind == 'quad': w,h = size.get('x',0),size.get('y',0)
            elif face in ('top','bottom'): w,h = size.get('x',0),size.get('z',0)
            elif face in ('left','right'): w,h = size.get('z',0),size.get('y',0)
            else: w,h = size.get('x',0),size.get('y',0)
            try: rects.append({'node':node.get('name'), 'face':face, 'bounds':face_bounds(layout,w,h)})
            except ValueError as error: problems.append(str(error))
    return rects, problems

def occupancy(rects, width, height):
    mask = np.zeros((height, width), dtype=bool)
    outside = []
    for record in rects:
        x0,y0,x1,y1 = record['bounds']
        if x0 < 0 or y0 < 0 or x1 > width or y1 > height: outside.append(record)
        mask[max(0,math.floor(y0)):min(height,math.ceil(y1)),
             max(0,math.floor(x0)):min(width,math.ceil(x1))] = True
    if rects:
        bounds = [min(r['bounds'][0] for r in rects),min(r['bounds'][1] for r in rects),
                  max(r['bounds'][2] for r in rects),max(r['bounds'][3] for r in rects)]
    else: bounds = None
    return int(mask.sum()), bounds, outside

def main():
    pngs, refs = {}, defaultdict(list)
    for path in sorted(COMMON.rglob('*.png')):
        rel = path.relative_to(COMMON).as_posix()
        raw = path.read_bytes()
        with Image.open(path) as image:
            rgba = image.convert('RGBA'); array = np.asarray(rgba)
            pngs[rel] = {'path':rel,'width':image.width,'height':image.height,
                'fileBytes':len(raw),'decodedRGBABytes':image.width*image.height*4,
                'sha256':hashlib.sha256(raw).hexdigest(),
                'pixelSha256':hashlib.sha256(str(rgba.size).encode()+rgba.tobytes()).hexdigest(),
                'nontransparentPixels':int(np.count_nonzero(array[:,:,3]))}
    model_data = {p.relative_to(COMMON).as_posix():read(p) for p in COMMON.rglob('*.blockymodel')}
    model_rects, model_problems = {}, {}
    for path, data in model_data.items():
        model_rects[path], model_problems[path] = rectangles(data)
    bindings = defaultdict(set); binding_sources = defaultdict(set)
    animations = defaultdict(set); particle_contracts = defaultdict(list)
    nonmodel = defaultdict(set); unresolved = []

    def bind(value, source, model=None, textures=()):
        if isinstance(value,list):
            for part in value: bind(part,source,model,textures)
            return
        if not isinstance(value,dict): return
        ownmodel = value.get('CustomModel',value.get('Model',model))
        if not isinstance(ownmodel,str) or not ownmodel.endswith('.blockymodel'): ownmodel=model
        owntextures=textures
        if 'CustomModelTexture' in value:
            owntextures=tuple(t['Texture'] for t in value['CustomModelTexture'] if isinstance(t,dict) and 'Texture' in t)
        elif isinstance(value.get('Texture'),str): owntextures=(value['Texture'],)
        if ownmodel:
            for texture in owntextures:
                if texture in pngs:
                    bindings[texture].add(ownmodel);binding_sources[texture].add(source)
            for _, text in strings({k:v for k,v in value.items() if k in ('CustomModelAnimation','Animation','Animations','AnimationSets')}):
                if text.endswith('.blockyanim'): animations[ownmodel].add(text)
        for part in value.values():
            if isinstance(part,(dict,list)):bind(part,source,ownmodel,owntextures)

    for path in sorted(RES.rglob('*')):
        if not path.is_file() or path.suffix not in ('.json','.particlespawner','.ui'):continue
        source=path.relative_to(RES).as_posix()
        if path.suffix=='.ui':
            text=path.read_text(encoding='utf-8-sig')
            for line_number,line in enumerate(text.splitlines(),1):
                for name in re.findall(r'"([^"\n]+\.png)"',line):
                    candidates=[COMMON/name,COMMON/'UI/Custom'/name,path.parent/name]
                    for candidate in candidates:
                        if candidate.exists():
                            resolved=candidate.resolve().relative_to(COMMON.resolve()).as_posix()
                            refs[resolved].append({'source':source,'line':line_number,'reference':name,'context':line.strip()})
                            nonmodel[resolved].add('UI document texture with explicit layout sizes');break
            continue
        try: data=read(path)
        except (ValueError,UnicodeDecodeError):continue
        bind(data,source)
        for pointer,name in strings(data):
            if name not in pngs:continue
            refs[name].append({'source':source,'pointer':pointer})
            if path.suffix=='.particlespawner':nonmodel[name].add('Particle sprite frame/UV contract')
            elif pointer.endswith('.Icon'):nonmodel[name].add('Item icon full image contract')
            elif '.Textures[' in pointer:nonmodel[name].add('Cube block face texture')
        if path.suffix=='.particlespawner':
            particle=data.get('Particle',{});texture=particle.get('Texture')
            if texture in pngs:
                particle_contracts[texture].append({'source':source,'frameSize':particle.get('FrameSize'),'uvOption':particle.get('UVOption'),'frameRange':particle.get('FrameRange')})

    # Runtime Java constructs some UI paths dynamically, so protect their entire
    # UI namespace even if no literal PNG string appears in a document.
    for path in (ROOT/'src/main/java').rglob('*.java'):
        text=path.read_text(encoding='utf-8-sig')
        for number,line in enumerate(text.splitlines(),1):
            if '.png' in line:
                for name in pngs:
                    if name in line or (name.startswith('UI/Custom/') and name.removeprefix('UI/Custom/') in line):
                        refs[name].append({'source':path.relative_to(ROOT).as_posix(),'line':number,'context':line.strip()})
                        nonmodel[name].add('Runtime texture reference')
    for name in pngs:
        if name.startswith('UI/'):nonmodel[name].add('UI full-image namespace (includes dynamic Java paths)')
        if name.startswith('Icons/'):nonmodel[name].add('Item icon full image contract')
        if name.startswith('Particles/'):nonmodel[name].add('Particle sprite frame/UV contract')
        if name.startswith('BlockTextures/'):nonmodel[name].add('Block terrain face or transition mask sampling contract')

    animated_uv=[]
    for model,paths in animations.items():
        for animation in paths:
            path=COMMON/animation
            if not path.exists():continue
            for bone,tracks in read(path).get('nodeAnimations',{}).items():
                if tracks.get('shapeUvOffset'):
                    animated_uv.append({'model':model,'animation':animation,'node':bone,'keys':tracks['shapeUvOffset']})
    uv_models={entry['model'] for entry in animated_uv}
    atlases=[]
    for name,model_paths in bindings.items():
        image=pngs[name];rects=[];issues=[]
        for model in sorted(model_paths):
            if model not in model_rects:
                issues.append('Native/external model requires its own UV audit: '+model);continue
            rects.extend(dict(r,model=model) for r in model_rects[model]);issues.extend(model_problems[model])
            if model in uv_models:issues.append('Nonempty animated UV offset: '+model)
        used,bounds,outside=occupancy(rects,image['width'],image['height'])
        if outside:issues.append('UV outside texture dimensions')
        issues+=sorted(nonmodel[name])
        width=image['width'];height=image['height']
        crop=None
        if bounds:
            crop=[math.ceil(bounds[2])-math.floor(bounds[0]),math.ceil(bounds[3])-math.floor(bounds[1])]
        atlas={**image,'models':sorted(model_paths),'bindingSources':sorted(binding_sources[name]),
            'faces':len(rects),'uniqueRectangles':len({tuple(r['bounds']) for r in rects}),
            'uvBounds':bounds,'usedPixels':used,'occupancyPercent':round(used/(width*height)*100,3),
            'unusedRGBABytes':4*(width*height-used),'boundingCropSize':crop,
            'boundingCropRGBABytesSaved':4*(width*height-crop[0]*crop[1]) if crop else 0,
            'outsideUVs':outside,'packingBlockers':sorted(set(issues)),
            'eligibleForModelPacking':bool(rects) and not issues}
        atlases.append(atlas)
    def duplicates(key):
        groups=defaultdict(list)
        for name,info in pngs.items():groups[info[key]].append(name)
        result=[]
        for digest,names in groups.items():
            if len(names)<2:continue
            result.append({'hash':digest,'paths':names,'fileBytes':sum(pngs[n]['fileBytes'] for n in names),
                'dedupFileBytesSaved':sum(pngs[n]['fileBytes'] for n in names)-min(pngs[n]['fileBytes'] for n in names),
                'dedupRGBABytesSaved':sum(pngs[n]['decodedRGBABytes'] for n in names[1:]),
                'nonmodelConsumers':{n:sorted(nonmodel[n]) for n in names if nonmodel[n]}})
        return sorted(result,key=lambda g:g['dedupFileBytesSaved'],reverse=True)
    duplicate_bytes=duplicates('sha256');duplicate_pixels=duplicates('pixelSha256')
    groups=Counter()
    for name,image in pngs.items():groups[name.split('/')[0]]+=image['decodedRGBABytes']
    bound_models={model for paths in bindings.values() for model in paths}
    model_usage=[]
    for model,rects in sorted(model_rects.items()):
        textures=sorted(texture for texture,models in bindings.items() if model in models)
        usage=[]
        for texture in textures:
            image=pngs[texture]
            used,bounds,outside=occupancy(rects,image['width'],image['height'])
            usage.append({'texture':texture,'uvBounds':bounds,'usedPixels':used,
                'occupancyPercent':round(used/(image['width']*image['height'])*100,3),
                'outsideUVCount':len(outside)})
        bounds=[min(r['bounds'][0] for r in rects),min(r['bounds'][1] for r in rects),
                max(r['bounds'][2] for r in rects),max(r['bounds'][3] for r in rects)] if rects else None
        model_usage.append({'model':model,'faces':len(rects),'uvBounds':bounds,
            'textureUsage':usage,'animations':sorted(animations[model]),'issues':model_problems[model]})
    variants=[{'model':model,'textures':sorted(texture for texture,models in bindings.items() if model in models)} for model in sorted(bound_models)]
    variants=[entry for entry in variants if len(entry['textures'])>1]
    report={'method':'Read only. Native signed UV origins, mirrors and quarter turns; union of all face texels including hidden faces. Model binding follows explicit nested context including block states. External/native model dependencies are blockers. Potential rectangle repacking savings are upper bounds, before padding and packing fragmentation.',
        'summary':{'pngCount':len(pngs),'pngFileBytes':sum(p['fileBytes'] for p in pngs.values()),
            'decodedRGBABytes':sum(p['decodedRGBABytes'] for p in pngs.values()),'modelCount':len(model_data),
            'boundAtlasCount':len(atlases),'eligibleAtlasCount':sum(a['eligibleForModelPacking'] for a in atlases),
            'exactDuplicateGroups':len(duplicate_bytes),'exactDedupFileBytesSaved':sum(g['dedupFileBytesSaved'] for g in duplicate_bytes),
            'pixelDuplicateGroups':len(duplicate_pixels),'pixelDedupFileBytesSaved':sum(g['dedupFileBytesSaved'] for g in duplicate_pixels),
            'decodedRGBABytesByNamespace':dict(groups)},
        'atlases':sorted(atlases,key=lambda a:a['unusedRGBABytes'],reverse=True),
        'exactDuplicatePNGGroups':duplicate_bytes,'identicalDecodedPixelGroups':duplicate_pixels,
        'modelUVUsage':model_usage,'sharedModelTextureVariants':variants,
        'unboundLocalModels':sorted(set(model_data)-bound_models),'animatedUVOffsets':animated_uv,
        'unreferencedPNGs':sorted(set(pngs)-set(refs)),
        'nonmodelTextures':[dict(pngs[n],packingBlockers=sorted(reasons),references=refs[n],particleContracts=particle_contracts[n]) for n,reasons in sorted(nonmodel.items()) if reasons],
        'pngReferences':dict(refs),'pngInventory':list(pngs.values())}
    output=ROOT/'build/texture-audit.json';output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(report['summary'],indent=2))
    print('Largest eligible UV gaps:')
    for a in [a for a in report['atlases'] if a['eligibleForModelPacking']][:15]:
        print(a['path'],str((a['width'],a['height'])),str(a['occupancyPercent'])+'%',str(a['unusedRGBABytes'])+' unused RGBA bytes','crop',a['boundingCropSize'])
    print('Exact duplicates:')
    for g in duplicate_bytes: print(g['paths'],g['dedupFileBytesSaved'])

if __name__=='__main__':main()
