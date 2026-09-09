"""Write a self-contained interactive HTML preview; never creates/edits bitmaps."""
from pathlib import Path
import base64,json,sys
sys.path.insert(0,str(Path(__file__).resolve().parent))
from test_held_light_fix import I,add,mv,mm,qm,v
from build_energetic_revision import ROOT,RES,COMMON

VERTS={'front':[(-1,-1,1),(1,-1,1),(1,1,1),(-1,1,1)],'back':[(1,-1,-1),(-1,-1,-1),(-1,1,-1),(1,1,-1)],
 'right':[(1,-1,1),(1,-1,-1),(1,1,-1),(1,1,1)],'left':[(-1,-1,-1),(-1,-1,1),(-1,1,1),(-1,1,-1)],
 'top':[(-1,1,1),(1,1,1),(1,1,-1),(-1,1,-1)],'bottom':[(-1,-1,-1),(1,-1,-1),(1,-1,1),(-1,-1,1)]}

def mesh(model,tex):
    faces=[]
    def walk(nodes,pr=I,pp=(0,0,0)):
        for n in nodes:
            shape=n['shape'];r=mm(pr,qm(n['orientation']));p=add(add(pp,mv(pr,v(n['position']))),mv(r,v(shape.get('offset',{}))))
            if shape['type']=='box':
                size=shape['settings']['size'];half=[size[k]*shape.get('stretch',{}).get(k,1)/2 for k in 'xyz']
                for side,verts in VERTS.items():
                    pts=[tuple(a/32 for a in add(p,mv(r,tuple(t[i]*half[i] for i in range(3))))) for t in verts]
                    uv=[];w,h=(size['x'],size['z']) if side in ('top','bottom') else (size['z'],size['y']) if side in ('left','right') else (size['x'],size['y'])
                    layout=shape['textureLayout'][side]
                    for a,b in [(0,1),(1,1),(1,0),(0,0)]:
                        if layout['mirror'].get('x'):a=1-a
                        if layout['mirror'].get('y'):b=1-b
                        for _ in range(layout['angle']//90):a,b=1-b,a
                        uv.append([layout['offset']['x']+a*(w-.01),layout['offset']['y']+b*(h-.01)])
                    faces.append({'p':pts,'uv':uv,'tex':tex,'glow':shape['shadingMode']=='fullbright'})
            walk(n.get('children',[]),r,p)
    walk(model['nodes']);return faces

def main():
    faces=[];images={}
    for stem,texture,key in [('anomaly_energetic_rift','Items/StrangeMatter/anomaly_energetic_rift.png','core'),
            ('anomaly_energetic_shell','Items/StrangeMatter/anomaly_energetic_shell.png','shell')]:
        faces+=mesh(json.loads((COMMON/f'Items/StrangeMatter/{stem}.blockymodel').read_text()),key)
        images[key]='data:image/png;base64,'+base64.b64encode((COMMON/texture).read_bytes()).decode()
    for key in ('fissure','spark','ring','halo','star','debris'):
        images[key]='data:image/png;base64,'+base64.b64encode((COMMON/f'Particles/StrangeMatter/{key}.png').read_bytes()).decode()
    template=Path(__file__).with_name('energetic_preview_template.html').read_text()
    link_styles=[]
    for suffix in ('Glow','Filament'):
        sp=json.loads((RES/f'Server/Particles/StrangeMatter/Spawners/SM_Stabilizer_Link_{suffix}.particlespawner').read_text())
        initial=sp['Particle']['InitialAnimationFrame'];anim=sp['Particle']['Animation']['0']
        link_styles.append({'width':initial['Scale']['X']['Min']*4,'height':initial['Scale']['Y']['Min']*4,
                            'color':anim['Color'],'opacity':anim['Opacity']})
    result=template.replace('__DATA__',json.dumps({'faces':faces,'images':images,'linkStyles':link_styles}))
    path=ROOT/'docs/art/energetic-rift-preview.html';path.parent.mkdir(parents=True,exist_ok=True);path.write_text(result,encoding='utf-8')
    print(path)

if __name__=='__main__':main()
