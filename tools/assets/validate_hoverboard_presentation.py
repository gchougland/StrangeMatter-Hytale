"""Validate the actual rig-to-deck fit and bounded riding feedback. Optional authored preview."""
from pathlib import Path
import argparse, copy, hashlib, json, re, struct
import numpy as np
from PIL import Image, ImageDraw, ImageFont
import build_hoverboard_rider as rig
import render_current_icons as render
import prepare_hoverboard_mount as mount

ROOT=mount.ROOT;RES=ROOT/'src/main/resources';COMMON=RES/'Common'

def read(path):return json.loads(path.read_text(encoding='utf8'))

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--preview',action='store_true');args=parser.parse_args()
    source=read(mount.SOURCE);variant=read(mount.TARGET);report=read(ROOT/'tools/assets/hoverboard-mount-model.json')
    expected=mount.translated(source,report['rootShiftYModelUnits'])
    assert variant==expected,'Mount must preserve every original model/UV field apart from its root Y translation'
    # The historical hashes describe the original height repair. Later lossless
    # atlas relocation is valid when both current models still match exactly here.
    code=(ROOT/'src/main/java/com/hexvane/strangematter/equipment/MobilityTools.java').read_text()
    found=re.search(r'BOARD_SCALE\s*=\s*([\d.]+),\s*BOARD_ANCHOR_Y\s*=\s*([\d.]+)f',code);assert found
    scale,anchor=map(float,found.groups());assert scale==mount.ENTITY_SCALE and anchor==mount.ANCHOR_Y
    assert report['entityArtUnitsPerBlock']==64 and abs(report['visualOriginWorldY']-1.374375)<1e-9
    grips=[node for node in variant['nodes'] if node['name']=='foot_grip']
    tops=[(n['position']['y']+n['shape']['settings']['size']['y']/2)*scale/64 for n in grips]
    assert len(tops)==2 and all(abs(top-anchor)<1e-9 for top in tops)
    assert .8<report['legacyGapWorldBlocks']<.9,'Regression fixture must reproduce the reported visible gap'
    contacts=0
    for mode in ('Idle','Glide','Boost'):
        anim=read(rig.DEST/f'Surf_{mode}.blockyanim');assert rig.validate_client_frame_numbers(anim)==153
        for frame in range(9):
            body=rig.posed_rig(anim,frame)
            for side in ('R','L'):
                low,high=rig.shoe_bounds(body,side);center=(low+high)/2
                assert abs(anchor+low[1]/64-tops[0])<2e-6,f'{mode} frame{frame} shoe floats above the actual grip'
                assert low[0]>-13.01 and high[0]<13.01 and low[2]>-37.01 and high[2]<37.01,'Actual shoe remains above the full original deck'
                assert min(abs(center[2]/64-n['position']['z']*scale/64) for n in grips)<.017
                contacts+=1
    event=read(RES/'Server/Audio/SoundEvents/StrangeMatter/SM_Hoverboard_Riding_Hum.json')
    assert event['SpatialBlend']==1 and event['MaxDistance']==8 and event['Volume']<=-16
    assert len(event['Layers'])==1 and event['Layers'][0]['Looping'] is True
    clip=COMMON/event['Layers'][0]['Files'][0];raw=clip.read_bytes();header=raw.index(b'\x01vorbis')
    assert raw[header+11]==1 and struct.unpack_from('<I',raw,header+12)[0]==48000,'Riding audio must remain a mono48kHz positional source'
    sound_report=read(ROOT/'tools/assets/hoverboard-ride-effects.json')
    assert sound_report['seamDelta']<=sound_report['largestAdjacentSampleDelta']*1.01
    assert sound_report['durationSeconds']==8 and sound_report['peak']<=.36
    assert hashlib.sha256(raw).hexdigest()==sound_report['files']['Common/'+event['Layers'][0]['Files'][0]]
    for id in ('SM_Hoverboard_Glide','SM_Hoverboard_Idle_Lift'):
        effect=read(RES/f'Server/Particles/StrangeMatter/{id}.particlesystem');total=0
        assert effect['LifeSpan']<=.65 and effect['CullDistance']==24
        for layer in effect['Spawners']:
            emitter=read(RES/f"Server/Particles/StrangeMatter/Spawners/{layer['SpawnerId']}.particlespawner")
            total+=emitter['TotalParticles']['Max']
            assert emitter['ParticleLifeSpan']['Max']<=.5 and emitter['LifeSpan']<=.1
            assert (COMMON/emitter['Particle']['Texture']).is_file()
        assert total<=4,'Two moving coils must emit no more than eight particles per pulse'
    result={'status':'PASS','actualShoeContacts':contacts,'previousGapBlocks':report['legacyGapWorldBlocks'],
            'gripWorldY':tops[0],'riderAnchorY':anchor,'mountGeometryAndUvsMatchHeldModel':True,
            'loop':'mono48kHz8s','particleMaxPerMovingPulse':8}
    (ROOT/'tools/assets/hoverboard-presentation-validation.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf8')
    if args.preview:preview(source,variant,anchor,scale)
    print('Hoverboard presentation PASS: '+json.dumps(result))

def preview(source,variant,anchor,scale):
    body=rig.posed_rig(read(rig.DEST/'Surf_Idle.blockyanim'),0)
    skin=Image.open(rig.NATIVE/'Characters/Player_Textures/Player_Greyscale.png')
    body_faces=render.model_faces(body,skin)
    shifted_body=[(points+np.array([0,anchor*64,0]),*rest) for points,*rest in body_faces]
    texture=Image.open(COMMON/'Items/StrangeMatter/hoverboard.png')
    sheet=Image.new('RGB',(1280,760),(12,19,34));draw=ImageDraw.Draw(sheet)
    heading=ImageFont.truetype('C:/Windows/Fonts/segoeuib.ttf',25);font=ImageFont.truetype('C:/Windows/Fonts/segoeui.ttf',17)
    old=mount.translated(source,mount.LEGACY_SHIFT)
    for index,(model,title,note) in enumerate(((old,'Previous visual placement','Grip plane sits 0.824 blocks below the feet'),(variant,'Corrected riding fit','Same model, texture, collider and surfing pose'))):
        board_faces=[(points*scale,*rest) for points,*rest in render.model_faces(model,texture)]
        picture=render.render(shifted_body+board_faces,620,yaw=29,pitch=10)
        x=index*640;sheet.paste(picture,(x+10,65),picture)
        draw.text((x+320,24),title,font=heading,anchor='mt',fill=(167,230,245))
        draw.text((x+320,680),note,font=font,anchor='mt',fill=(190,202,220))
    draw.text((640,724),'Authored rig fit using native entity art scale. This is not a captured game frame.',font=font,anchor='mt',fill=(119,146,167))
    path=ROOT/'docs/art/hoverboard-height-fit.png';path.parent.mkdir(parents=True,exist_ok=True);sheet.save(path)

if __name__=='__main__':main()
