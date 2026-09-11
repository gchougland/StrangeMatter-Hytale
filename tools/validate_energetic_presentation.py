"""Stdlib checks for the targeted energetic rift and stabilizer visual revision."""
from pathlib import Path
import argparse, hashlib, json, struct, sys, zipfile

ROOT=Path(__file__).resolve().parents[1];RES=ROOT/'src/main/resources';COMMON=RES/'Common'
sys.path.insert(0,str(ROOT/'tools/assets'))
from test_held_light_fix import geometry
from terrain_revision_policy import authorized_terrain_edit

def read(path):return json.loads(path.read_text(encoding='utf-8-sig'))
def walk(nodes):
    for n in nodes:
        yield n
        yield from walk(n.get('children',[]))

def validate(audit_preservation=False):
    report=read(ROOT/'tools/assets/energetic-revision.json')
    core=read(COMMON/'Items/StrangeMatter/anomaly_energetic_rift.blockymodel')
    shell=read(COMMON/'Items/StrangeMatter/anomaly_energetic_shell.blockymodel')
    asset=read(RES/'Server/Models/StrangeMatter/SM_Energetic_Rift_Core.json')
    assert shell['nodes'][0]['name']=='RiftShell' and shell['nodes'][0]['shape']['settings']['isPiece']
    assert any(n['name']=='RiftShell' and n['shape']['type']=='none' for n in core['nodes'])
    assert asset['DefaultAttachments']==[{'Model':'Items/StrangeMatter/anomaly_energetic_shell.blockymodel',
        'Texture':'Items/StrangeMatter/anomaly_energetic_shell.png'}]
    for attachment in asset['DefaultAttachments']:
        assert attachment['Texture'].startswith(('Characters/','NPC/','Items/','Cosmetics/','Resources/'))
    # The shell began as a byte copy of the forge atlas. These are independent
    # editable textures now; current shell UV and atlas checks remain below.
    if audit_preservation:
        assert (COMMON/'Items/StrangeMatter/anomaly_energetic_shell.png').read_bytes()==(COMMON/'Blocks/StrangeMatter/reality_forge.png').read_bytes()
    faces=0
    for model,texture in [(core,asset['Texture']),(shell,asset['DefaultAttachments'][0]['Texture'])]:
        width,height=struct.unpack('>II',(COMMON/texture).read_bytes()[16:24])
        assert min(width,height)>=32 and width%32==height%32==0
        ids=[]
        for node in walk(model['nodes']):
            ids.append(node['id']);shape=node['shape']
            if shape['type']!='box':continue
            assert abs(sum(v*v for v in node['orientation'].values())-1)<1e-6
            size=shape['settings']['size']
            for side,layout in shape['textureLayout'].items():
                w,h=(size['x'],size['z']) if side in ('top','bottom') else (size['z'],size['y']) if side in ('left','right') else (size['x'],size['y'])
                if layout['angle']%180:w,h=h,w
                assert 0<=layout['offset']['x']<=width-w and 0<=layout['offset']['y']<=height-h,(node['name'],side)
                faces+=1
        assert len(ids)==len(set(ids))
    points,_=geometry({'nodes':core['nodes']+shell['nodes']})
    for i,k in enumerate('XYZ'):
        assert min(p[i] for p in points)/32>=asset['HitBox']['Min'][k]
        assert max(p[i] for p in points)/32<=asset['HitBox']['Max'][k]
    folder=RES/'Server/Particles/StrangeMatter';total=0
    rift=read(folder/'SM_Energetic_Rift.particlesystem')
    assert len(rift['Spawners'])==13 and 0<rift['LifeSpan']<=2.25
    for group in rift['Spawners']:
        sp=read(folder/'Spawners'/(group['SpawnerId']+'.particlespawner'))
        total+=sp['TotalParticles']['Max']
        assert 0<sp['LifeSpan']<=.92 and sp['ParticleLifeSpan']['Max']<=1.2
        assert sp['MaxConcurrentParticles']==sp['TotalParticles']['Max']<=22
        assert (COMMON/sp['Particle']['Texture']).exists()
    assert total==64 and report['particles']['conservativeConcurrentLimit']==128
    for suffix in ('Filament','Glow'):
        sp=read(folder/'Spawners'/('SM_Stabilizer_Link_'+suffix+'.particlespawner'))
        assert sp['ParticleRotationInfluence']=='BillboardVelocity' and not sp['ParticleRotateWithSpawner']
        assert sp['InitialVelocity']=={'Speed':{'Min':.01,'Max':.01},'Yaw':{'Min':0,'Max':0},'Pitch':{'Min':0,'Max':0}}
        assert sp['Particle']['InitialAnimationFrame']['Scale']['Y']['Min']*4==1.45
        assert sp['TotalParticles']=={'Min':1,'Max':1} and sp['LifeSpan']<=.05
    old=ROOT/report['snapshot']/'Resources.zip'
    preservation_audited=audit_preservation and old.exists()
    preserved=0 if preservation_audited else None
    later_terrain_repairs=0 if preservation_audited else None
    if preservation_audited:
        with zipfile.ZipFile(old) as archive:
            prefix='src/main/resources/'
            old_core=json.loads(archive.read(prefix+'Common/Items/StrangeMatter/anomaly_energetic_rift.blockymodel'))
            assert core['nodes'][:len(old_core['nodes'])]==old_core['nodes'],'Original core geometry or UVs changed'
            for name in archive.namelist():
                rel=name.removeprefix(prefix)
                # Preserve every original bitmap and unrelated emitter. The two
                # later requested terrain border repairs need exact hash pairs.
                particle=rel.startswith('Server/Particles/') and not any(s in rel for s in ('SM_Energetic_','SM_Stabilizer_Link'))
                if rel.endswith('.png') or particle:
                    before=archive.read(name);after=(RES/rel).read_bytes()
                    assert after==before or authorized_terrain_edit(rel,before,after),'Unexpected visual change: '+rel
                    preserved+=int(before==after);later_terrain_repairs+=int(before!=after)
    print(json.dumps({'result':'PASS','mappedModelFaces':faces,'riftLayers':13,'particlesPerPulse':64,
        'maximumRiftOverlap':128,'linkVelocityAligned':True,'preservedSnapshotFiles':preserved,
        'laterAuthorizedTerrainRepairs':later_terrain_repairs,
        'historicalPreservationAudited':preservation_audited,
        'sharedAtlasCopyAudited':audit_preservation}))

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--audit-preservation',action='store_true',
        help='Compare this revision with its historical snapshot, including unrelated art and the original forge atlas copy.')
    validate(parser.parse_args().audit_preservation)
