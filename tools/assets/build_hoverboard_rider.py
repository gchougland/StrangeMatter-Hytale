"""Author native player surfing loops and preview the actual unchanged player rig.

Bone transforms and foot targets are authored in native character model units.
The fitting preview uses the supplied player model and texture; it is not a
screenshot or a verification of the closed client renderer's world scale.
"""
from pathlib import Path
import copy, json, math
import numpy as np
from PIL import Image, ImageDraw, ImageFont
import render_current_icons as render

ROOT = render.ROOT
COMMON = ROOT / 'src/main/resources/Common'
NATIVE = render.NATIVE
DEST = COMMON / 'Characters/Animations/StrangeMatter/Hoverboard'
PLAYER_ANIMATIONS = ROOT / 'src/main/resources/Server/Item/Animations/StrangeMatter/SM_Hoverboard_Rider.json'
RIG = render.read(NATIVE / 'Characters/Player_With_Face.blockymodel')


def nodes(model):
    def walk(seq):
        for n in seq:
            yield n
            yield from walk(n.get('children', []))
    return {n['name']: n for n in walk(model['nodes'])}


BONES = nodes(RIG)
def qmul(a, b):
    ax, ay, az, aw = a; bx, by, bz, bw = b
    return np.array([aw*bx+ax*bw+ay*bz-az*by, aw*by-ax*bz+ay*bw+az*bx,
                     aw*bz+ax*by-ay*bx+az*bw, aw*bw-ax*bx-ay*by-az*bz])


def inv(q): return np.array([-q[0], -q[1], -q[2], q[3]])
def axis(index, degrees):
    value = np.zeros(4); angle = math.radians(degrees)/2
    value[index] = math.sin(angle); value[3] = math.cos(angle)
    return value
def xyz(x=0, y=0, z=0): return qmul(qmul(axis(1, y), axis(0, x)), axis(2, z))
def matrix(q): return render.art.qmatrix(dict(zip('xyzw', q)))
def base(name): return np.array([BONES[name].get('orientation', {}).get(k, 1 if k == 'w' else 0) for k in 'xyzw'])
def vec(name, field): return render.vec(BONES[name].get(field, {}))
def qbetween(a, b):
    a = a/np.linalg.norm(a); b = b/np.linalg.norm(b)
    q = np.r_[np.cross(a, b), 1+np.dot(a, b)]
    return q/np.linalg.norm(q)


def pose(phase, mode):
    """Two joint leg solve keeps both shoes flat and planted through each loop."""
    wave = math.sin(phase)
    intensity = {'Idle': .8, 'Glide': 1.25, 'Boost': 1.7}[mode]
    pelvis_y = {'Idle': 41.0, 'Glide': 39.6, 'Boost': 37.8}[mode] + .65*wave
    pelvis_q = xyz(y=82, z=.7*wave)
    pelvis_p = np.array([.45*math.cos(phase)*intensity, pelvis_y, -.25*wave])
    rotations = {'Pelvis': pelvis_q,
                 'Belly': xyz(x=4+intensity, y=-14, z=1.2*wave),
                 'Chest': xyz(x=3+intensity, y=-15, z=-.8*wave),
                 'Head': xyz(x=-5-intensity, y=-35, z=-.6*wave),
                 'R-Arm': xyz(x=-18-2*wave, y=-18, z=-60-2*wave),
                 'L-Arm': xyz(x=-10+2*wave, y=12, z=55+2*wave),
                 'R-Forearm': xyz(x=-24, y=-8, z=-9),
                 'L-Forearm': xyz(x=-27, y=7, z=7),
                 'R-Hand': xyz(x=4, y=-8, z=-5),
                 'L-Hand': xyz(x=3, y=7, z=5)}
    positions = {'Pelvis': pelvis_p-vec('Pelvis', 'position')}
    # Native leg chain pivots include the parent shape offset.
    for side, sign in (('R', -1), ('L', 1)):
        thigh, calf, foot = (side+'-'+part for part in ('Thigh', 'Calf', 'Foot'))
        hip = vec(thigh, 'position')
        a = render.vec(BONES[thigh]['shape']['offset']) + vec(calf, 'position')
        b = render.vec(BONES[calf]['shape']['offset']) + vec(foot, 'position')
        # Wide surf stance runs along the board's Z axis, with toes across the deck.
        foot_q = xyz(y=82+sign*7)
        # Center the entire shoe on the board, not just its rear ankle pivot.
        target_world = np.array([0., 6.8, -sign*23.0])-matrix(foot_q)@np.array([0., 0., 6.])
        target = matrix(pelvis_q).T @ (target_world-pelvis_p)
        reach = target-hip; distance = np.linalg.norm(reach); direction = reach/distance
        length_a, length_b = np.linalg.norm(a), np.linalg.norm(b)
        assert abs(length_a-length_b) < distance < length_a+length_b
        along = (length_a**2-length_b**2+distance**2)/(2*distance)
        forward = np.array([0., 0., 1.]); bend = forward-direction*np.dot(forward, direction)
        bend /= np.linalg.norm(bend)
        knee = hip+direction*along+bend*math.sqrt(length_a**2-along**2)
        thigh_q = qbetween(a, knee-hip)
        calf_global = qbetween(b, target-knee)
        rotations[thigh] = thigh_q
        rotations[calf] = qmul(inv(thigh_q), calf_global)
        # Cancel all knee/hip pitch at the shoe; only a slight stance toe-out remains.
        rotations[foot] = qmul(inv(qmul(pelvis_q, calf_global)), foot_q)
    deltas = {name: qmul(inv(base(name)), q) for name, q in rotations.items()}
    return deltas, positions


def animation(mode):
    duration = {'Idle': 120, 'Glide': 90, 'Boost': 72}[mode]
    result = {'formatVersion': 1, 'duration': duration, 'holdLastKeyframe': False, 'nodeAnimations': {}}
    for i in range(9):
        # The client deserializes frame indices as System.Int32: even JSON 0.0 fails.
        # Keep the authored cycle/poses and quantize intermediate Glide keys by <= .5 frame.
        time = (duration*i+4)//8
        rotations, positions = pose(0 if i == 8 else math.tau*i/8, mode)
        for name in rotations.keys() | positions.keys():
            track = result['nodeAnimations'].setdefault(name, {key: [] for key in
                ('position', 'orientation', 'shapeStretch', 'shapeVisible', 'shapeUvOffset')})
            for kind, values in (('orientation', rotations), ('position', positions)):
                if name not in values: continue
                delta = {k: round(float(v), 7) for k, v in zip('xyzw' if kind == 'orientation' else 'xyz', values[name])}
                track[kind].append({'time': time, 'delta': delta, 'interpolationType': 'smooth'})
    return result


def validate_client_frame_numbers(anim):
    """Check numeric JSON token types, not merely mathematical integrality (0.0 != 0)."""
    def frame(value, path):
        if type(value) is not int or not 0 <= value <= 2**31-1:
            raise ValueError(f'{path} must be a nonnegative Int32 JSON integer token; got {value!r}')
    frame(anim['duration'], '$.duration')
    count = 0
    for bone, tracks in anim['nodeAnimations'].items():
        for kind, keys in tracks.items():
            previous = -1
            for index, key in enumerate(keys):
                path = f'$.nodeAnimations.{bone}.{kind}[{index}].time'
                frame(key['time'], path)
                if not previous < key['time'] <= anim['duration']:
                    raise ValueError(f'{path} must increase within the animation duration')
                previous = key['time']; count += 1
    return count


def player_animation_config():
    """Native PlayAnimation selects this item-animation family without replacing the player model."""
    # Default includes first-person-only movement entries. Inheriting them makes
    # the client warn about 50 missing third-person clips for this action family.
    # Preserve its non-animation settings explicitly; only these three actions
    # belong in the rider map, and KeepPreviousFirstPersonAnimation retains hands.
    return {'Camera': {
        axis: {'AngleRange': {'Max': 45, 'Min': -45}, 'TargetNodes': ['Head']}
        for axis in ('Pitch', 'Yaw')
    }, 'WiggleWeights': {
        'Pitch': 2, 'PitchDeceleration': .1, 'Roll': .1, 'RollDeceleration': .1,
        'X': 3, 'XDeceleration': .1, 'Y': .1, 'YDeceleration': .1,
        'Z': .1, 'ZDeceleration': .1
    }, 'Animations': {
        f'Surf{mode}': {
            'ThirdPerson': f'Characters/Animations/StrangeMatter/Hoverboard/Surf_{mode}.blockyanim',
            'ThirdPersonMoving': f'Characters/Animations/StrangeMatter/Hoverboard/Surf_{mode}.blockyanim',
            'Looping': True, 'Speed': 1, 'BlendingDuration': .18,
            'KeepPreviousFirstPersonAnimation': True
        } for mode in ('Idle', 'Glide', 'Boost')
    }}


def posed_rig(anim, frame):
    result = copy.deepcopy(RIG)
    for name, n in nodes(result).items():
        # The software preview's winding test does not account for mirrored scales.
        # Draw both windings on these native mirrored pieces; depth still hides backs.
        if any(v < 0 for v in n.get('shape', {}).get('stretch', {}).values()):
            n['shape']['doubleSided'] = True
        track = anim['nodeAnimations'].get(name, {})
        if track.get('position'):
            n['position'] = dict(zip('xyz', render.vec(n['position'])+render.vec(track['position'][frame]['delta'])))
        if track.get('orientation'):
            delta = track['orientation'][frame]['delta']
            n['orientation'] = dict(zip('xyzw', qmul(base(name), np.array([delta[k] for k in 'xyzw']))))
    return result


def shoe_bounds(model, side):
    # Isolate the shoe while retaining its actual parent chain for the fit measurement.
    copy_model = copy.deepcopy(model)
    for name, n in nodes(copy_model).items():
        if name != side+'-Foot': n['shape']['type'] = 'none'
    faces = render.model_faces(copy_model, Image.open(NATIVE/'Characters/Player_Textures/Player_Greyscale.png'))
    pts = np.concatenate([face[0] for face in faces]); return pts.min(0), pts.max(0)


def previews(animations):
    texture = Image.open(NATIVE/'Characters/Player_Textures/Player_Greyscale.png')
    entries=[]; max_drift=0
    for mode, anim in animations.items():
        first=[]
        for frame in range(9):
            rig = posed_rig(anim, frame)
            for j, side in enumerate(('R', 'L')):
                low, high = shoe_bounds(rig, side)
                assert abs(low[1]) < .001, (mode, frame, side, low)
                center = (low+high)/2
                if frame == 0: first.append(center)
                else: max_drift=max(max_drift,float(np.linalg.norm(center-first[j])))
            if frame in (0,2,6):
                faces=render.model_faces(rig, texture)
                entries.append((mode+' balance '+str(frame), faces))
    render.contact(entries, ROOT/'docs/art/hoverboard-surfing-rig.png', cols=3, cell=340)
    # A rig-only turntable avoids assuming an undocumented client avatar/world scale.
    sheet=Image.new('RGBA',(1500,610),(17,25,42,255)); draw=ImageDraw.Draw(sheet)
    font=ImageFont.truetype('C:/Windows/Fonts/consola.ttf',16)
    rig=posed_rig(animations['Glide'],0); faces=render.model_faces(rig,texture)
    for i,yaw in enumerate((0,45,90)):
        sheet.alpha_composite(render.render(faces,500,yaw=yaw,pitch=8),(i*500,28))
        draw.text((i*500+250,545),f'Native player rig   view {yaw}',font=font,anchor='mm',fill=(210,232,235))
    draw.text((750,583),'Surf stance with planted feet and subtle balance motion',font=font,anchor='mm',fill=(126,209,223))
    sheet.convert('RGB').save(ROOT/'docs/art/hoverboard-surfing-stance.png')
    frames=[]
    for index in list(range(8))*2:
        rig=posed_rig(animations['Glide'],index); faces=render.model_faces(rig,texture)
        background=Image.new('RGBA',(420,450),(17,25,42,255))
        background.alpha_composite(render.render(faces,410,yaw=42,pitch=8),(5,5))
        frames.append(background.convert('RGB'))
    frames[0].save(ROOT/'docs/art/hoverboard-surfing-loop.gif',save_all=True,append_images=frames[1:],duration=375,loop=0)
    return max_drift


def main():
    DEST.mkdir(parents=True,exist_ok=True)
    animations={name:animation(name) for name in ('Idle','Glide','Boost')}
    for name, value in animations.items():
        validate_client_frame_numbers(value)
        (DEST/f'Surf_{name}.blockyanim').write_text(json.dumps(value,indent=2)+'\n',encoding='utf-8')
    PLAYER_ANIMATIONS.parent.mkdir(parents=True,exist_ok=True)
    PLAYER_ANIMATIONS.write_text(json.dumps(player_animation_config(),indent=2)+'\n',encoding='utf-8')
    # Never reintroduce the obsolete model-animation wrapper: native PlayAnimation uses the
    # dedicated ItemPlayerAnimations family while the real player's model/skin remain intact.
    max_drift=previews(animations)
    report={'status':'PASS','animationFiles':3,'playerAnimationStates':3,'nativeBones':len(animations['Idle']['nodeAnimations']),
            'integerFrameKeys':sum(validate_client_frame_numbers(a) for a in animations.values()),
            'maxKeyTimeQuantizationFrames':.5,
            'maxFootCenterDriftNativeUnits':max_drift,'footBottomNativeUnits':0,'stanceWidthNativeUnits':46,
            'preview':'Native player rig fitting. No claim of closed client rendering or avatar world scale.'}
    (ROOT/'tools/assets/hoverboard-rider-validation.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(report,indent=2))


if __name__ == '__main__': main()
