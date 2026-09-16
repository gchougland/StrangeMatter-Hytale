"""Approximate gate-state particle composite using current native sprites/model.

This is an orthographic asset review, not a client screenshot. Samples the actual
JSON at mid-pulse, averages fixed/random ranges, and composites the four stationary
Open layers (three Closed layers) over the existing unit-scale core model. Infall
sparks, world lighting, client bloom, and depth sorting are deliberately omitted.
No source textures, models, systems, or spawners are written.
"""
import argparse
import hashlib
from pathlib import Path

import numpy as np
from PIL import Image, ImageColor, ImageDraw, ImageFont

import render_current_icons as icons
from build_warp_gate_states import ROOT, PARTICLES, SPAWNERS, CHANNELS, read, check_assets

COMMON = ROOT / 'src/main/resources/Common'
MODELS = ROOT / 'src/main/resources/Server/Models/StrangeMatter'
PIXELS_PER_BLOCK = 56
UNITS_PER_BLOCK = 32
PANEL = (300, 352)


def font(size, bold=False):
    for name in (('C:/Windows/Fonts/segoeuib.ttf' if bold else 'C:/Windows/Fonts/segoeui.ttf'),
                 ('DejaVuSans-Bold.ttf' if bold else 'DejaVuSans.ttf')):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            pass
    return ImageFont.load_default(size=size)


def number(value):
    return (value['Min'] + value['Max']) / 2 if isinstance(value, dict) else value


def interpolate(left, right, fraction):
    if isinstance(left, str):
        return np.array(ImageColor.getrgb(left)) * (1 - fraction) + np.array(ImageColor.getrgb(right)) * fraction
    if isinstance(left, dict) and 'Min' not in left:
        return {axis: interpolate(left[axis], right[axis], fraction) for axis in left}
    return number(left) * (1 - fraction) + number(right) * fraction


def sample(animation, field, phase, default):
    keys = sorted((int(time), frame[field]) for time, frame in animation.items() if field in frame)
    if not keys:
        return interpolate(default, default, 0)
    if keys[0][0] > 0:
        keys.insert(0, (0, default))
    for (t0, left), (t1, right) in zip(keys, keys[1:]):
        if phase <= t1:
            return interpolate(left, right, max(0, (phase - t0) / (t1 - t0)))
    return interpolate(keys[-1][1], keys[-1][1], 0)


def sprite(emitter, phase):
    particle = emitter['Particle']
    initial = particle['InitialAnimationFrame']
    animation = particle['Animation']
    frame = particle['FrameSize']
    texture = Image.open(COMMON / particle['Texture']).convert('RGBA')
    # These gate sprites all use a single 128px frame, one native pixel=1/32 block.
    assert texture.size == (frame['Width'], frame['Height'])
    initial_scale = initial.get('Scale', {'X': 1, 'Y': 1})
    animated_scale = sample(animation, 'Scale', phase, {'X': 1, 'Y': 1})
    size = tuple(max(1, round(frame[dimension] / UNITS_PER_BLOCK * PIXELS_PER_BLOCK
                             * number(initial_scale[axis]) * animated_scale[axis]))
                 for axis, dimension in (('X', 'Width'), ('Y', 'Height')))
    texture = texture.resize(size, Image.Resampling.LANCZOS)
    pixels = np.asarray(texture).astype(float)
    tint = np.array(ImageColor.getrgb(initial.get('Color', '#ffffff'))) / 255
    tint *= sample(animation, 'Color', phase, '#ffffff') / 255
    opacity = number(initial.get('Opacity', 1)) * sample(animation, 'Opacity', phase, 1)
    pixels[:, :, :3] *= tint
    pixels[:, :, 3] *= opacity
    texture = Image.fromarray(np.clip(pixels, 0, 255).astype(np.uint8))
    rotation = sample(animation, 'Rotation', phase, {'Z': 0}).get('Z', 0)
    return texture.rotate(-rotation, Image.Resampling.BICUBIC, expand=True)


def composite(canvas, image, center, additive=False):
    layer = Image.new('RGBA', canvas.size)
    layer.alpha_composite(image, (round(center[0] - image.width / 2), round(center[1] - image.height / 2)))
    if not additive:
        return Image.alpha_composite(canvas, layer)
    background = np.asarray(canvas).astype(float)
    foreground = np.asarray(layer).astype(float)
    background[:, :, :3] += foreground[:, :, :3] * foreground[:, :, 3:4] / 255
    return Image.fromarray(np.clip(background, 0, 255).astype(np.uint8))


def core(channel):
    model = read(MODELS / ('SM_Warp_Gate' + channel + '_Core.json'))
    assert model['MinScale'] == model['MaxScale'] == 1
    faces = icons.item_faces(model)
    points = np.concatenate([face[0] for face in faces])
    span = max(points[:, :2].max(0) - points[:, :2].min(0))
    # render() reserves 16px padding and otherwise fits the model's exact bounds.
    size = round(span / UNITS_PER_BLOCK * PIXELS_PER_BLOCK) + 16
    return icons.render(faces, size=size, yaw=0, pitch=0)


def render_preview(output, phase=50):
    check_assets()
    source_paths = [path for path in COMMON.rglob('*') if path.is_file()
                    and path.suffix in ('.png', '.blockymodel') and 'warp' in path.name]
    source_paths += list((COMMON / 'Particles/StrangeMatter').glob('*.png'))
    hashes = {path: hashlib.sha256(path.read_bytes()).digest() for path in source_paths}
    sheet = Image.new('RGBA', (936, 856), '#090d18')
    draw = ImageDraw.Draw(sheet)
    draw.text((22, 18), 'WARP GATE  /  PERSONAL COOLDOWN', font=font(23, True), fill='#eef0ff')
    draw.text((22, 51), 'Approximate particle composite from current native assets - not a client screenshot',
              font=font(14), fill='#9aa9c4')
    for row, state in enumerate(('Open', 'Closed')):
        for column, channel in enumerate(CHANNELS):
            panel = Image.new('RGBA', PANEL, '#111725')
            painter = ImageDraw.Draw(panel)
            label = ('NATURAL', 'CYAN', 'PURPLE')[column]
            painter.text((15, 12), label + '  /  ' + state.upper(), font=font(15, True), fill='#dde6fb')
            painter.text((15, 35), 'Ready to travel' if state == 'Open' else '5-second personal cooldown',
                         font=font(12), fill='#93a4c1')
            center = (PANEL[0] / 2, 196)
            # A faint scale cross behind the center helps show that the closed
            # aperture has no broad opaque/swirling interior. It is preview-only.
            painter.line((44, 196, 256, 196), fill='#1b2539')
            painter.line((150, 74, 150, 318), fill='#1b2539')
            panel = composite(panel, core(channel), center)
            system = read(PARTICLES / ('SM_Warp_Gate' + channel + '_' + state + '.particlesystem'))
            rendered = 0
            for layer in system['Spawners']:
                if 'Infall' in layer['SpawnerId']:
                    continue
                emitter = read(SPAWNERS / (layer['SpawnerId'] + '.particlespawner'))
                assert emitter['InitialVelocity']['Speed']['Max'] == 0
                panel = composite(panel, sprite(emitter, phase), center, emitter['RenderMode'] == 'BlendAdd')
                rendered += 1
            painter = ImageDraw.Draw(panel)
            painter.text((15, 329), f'{rendered} aperture layers + unchanged ring core', font=font(12), fill='#8f9cb8')
            sheet.alpha_composite(panel, (12 + column * 306, 83 + row * 360))
    draw = ImageDraw.Draw(sheet)
    draw.text((22, 813), f'At {phase:g}% of a 220ms pulse. Native color, opacity, dimensions and model UVs.',
              font=font(13), fill='#bdc8dc')
    draw.text((22, 835), 'Orthographic face-on view. Infall sparks, bloom, world lighting and client depth sorting omitted.',
              font=font(12), fill='#8f9cb8')
    output.parent.mkdir(parents=True, exist_ok=True)
    sheet.convert('RGB').save(output)
    for path, expected in hashes.items():
        assert hashlib.sha256(path.read_bytes()).digest() == expected, 'Preview modified source ' + str(path)
    print('WARP_GATE_PREVIEW PASS:', output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / 'docs/art/warp-gate-states.png')
    parser.add_argument('--phase', type=float, default=50, help='Sampled particle lifetime percentage (0..100).')
    args = parser.parse_args()
    if not 0 <= args.phase <= 100:
        parser.error('--phase must be between 0 and 100')
    render_preview(args.output, args.phase)


if __name__ == '__main__':
    main()
