"""Create the shared Strange Matter UI skin only. No model or existing world texture is regenerated."""
from pathlib import Path
import argparse
import hashlib
import json
import math
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
COMMON = ROOT / 'src/main/resources/Common'
UI = COMMON / 'UI/Custom/StrangeMatter'
OUT = UI / 'Buttons'
THEMES = {'Action': (71, 212, 224), 'Secondary': (159, 125, 220), 'Cancel': (122, 157, 186)}
STATES = ('Default', 'Hovered', 'Pressed', 'Disabled')


def paint(theme, state):
    """A painted bevel and corner circuits stay inside the native eight pixel border."""
    image = Image.new('RGBA', (64, 32))
    pixels = image.load()
    accent = THEMES[theme]
    if state == 'Disabled':
        accent = (62, 81, 102)
    bright = {'Default': 1, 'Hovered': 1.35, 'Pressed': .84, 'Disabled': .58}[state]
    for y in range(32):
        for x in range(64):
            corner = min(x, 63-x) + min(y, 31-y)
            if corner < 3:
                continue
            shade = (2 if y < 14 else -2) + round(math.cos(y*.2)*1.5)
            base = (20+shade, 33+shade, 52+shade)
            if x in (0,63) or y in (0,31):
                base = (5, 12, 23)
            elif x in (1,62) or y in (1,30):
                base = tuple(round(c*.47) for c in accent)
            elif y == 2:
                base = (65, 91, 117)
            elif y >= 28:
                base = (11, 20, 35)
            elif x in (2,61):
                base = (39, 57, 79)
            if state == 'Hovered' and 4 <= y <= 27:
                base = (base[0]+6, base[1]+10, base[2]+13)
            if state == 'Pressed':
                base = tuple(max(0, c-4) for c in base)
            pixels[x,y] = (*base,255)
    draw = ImageDraw.Draw(image)
    color = tuple(min(255,round(c*bright)) for c in accent)+(255,)
    # Deliberately keep ornament out of the stretched center region.
    draw.line([(4,10),(4,5),(10,5)], fill=color, width=1)
    draw.line([(53,26),(59,26),(59,21)], fill=color, width=1)
    draw.point((6,7),fill=(180,210,221,255) if state!='Disabled' else color)
    draw.point((57,24),fill=color)
    if state=='Hovered':
        draw.line((12,3,51,3),fill=tuple(round(c*.72) for c in color[:3])+(255,))
    if state=='Pressed':
        draw.line((8,3,55,3),fill=(9,18,29,255))
    return image


def library():
    lines = ['$C = "../Common.ui";', '',
      '@Label = LabelStyle(FontSize: 14, TextColor: #d8f0f4, RenderBold: true, HorizontalAlignment: Center, VerticalAlignment: Center);',
      '@SmallLabel = LabelStyle(FontSize: 13, TextColor: #d8f0f4, RenderBold: true, HorizontalAlignment: Center, VerticalAlignment: Center);',
      '@DisabledLabel = LabelStyle(FontSize: 14, TextColor: #73889e, RenderBold: true, HorizontalAlignment: Center, VerticalAlignment: Center);']
    for theme in THEMES:
        for state in STATES:
            lines.append(f'@{theme}{state} = PatchStyle(TexturePath: "Buttons/{theme}_{state}.png", Border: 8);')
    for name, theme, label in [('TextButtonStyle','Action','Label'),('SecondaryTextButtonStyle','Secondary','Label'),('CancelTextButtonStyle','Cancel','Label'),('ControlStyle','Action','SmallLabel')]:
        lines += [f'@{name} = TextButtonStyle(']
        for state in STATES:
            style = '(FontSize: 13, TextColor: #73889e, RenderBold: true, HorizontalAlignment: Center, VerticalAlignment: Center)' if label=='SmallLabel' and state=='Disabled' else '@DisabledLabel' if state=='Disabled' else '@'+label
            lines += [f'  {state}: (Background: @{theme}{state}, LabelStyle: {style}),']
        lines += ['  Sounds: $C.@ButtonSounds', ');']
    for name,theme in [('RowStyle','Secondary'),('IconButtonStyle','Action')]:
        lines += [f'@{name} = ButtonStyle(']
        lines += [f'  {state}: (Background: @{theme}{state}),' for state in STATES]
        lines += ['  Sounds: $C.@ButtonSounds', ');']
    lines += ['// Circuit connections pass beneath the node hit target. Only the icon and title are backed.',
              '@TreeNodeStyle = ButtonStyle(',
              '  Default: (Background: #000000(0)),',
              '  Hovered: (Background: #71d7ee(0.07)),',
              '  Pressed: (Background: #9e7de0(0.11)),',
              '  Disabled: (Background: #000000(0)),',
              '  Sounds: $C.@ButtonSounds', ');']
    for name, style in [('TextButton','TextButtonStyle'),('SecondaryTextButton','SecondaryTextButtonStyle'),('CancelTextButton','CancelTextButtonStyle')]:
        lines += [f'@{name} = TextButton {{', f'  Style: @{style};',
                  '  Anchor: (Height: 40);', '  Padding: (Horizontal: 10);',
                  '  TextTooltipStyle: $C.@DefaultTextTooltipStyle;', '  Text: @Text;', '};']
    lines += ['@Control = TextButton { Style: @ControlStyle; Padding: (Horizontal: 5); TextTooltipStyle: $C.@DefaultTextTooltipStyle; };',
              '@IconButton = Button { Style: @IconButtonStyle; Anchor: (Width: 48, Height: 40); Padding: (Full: 6); TextTooltipStyle: $C.@DefaultTextTooltipStyle; };',
              '@Button = Button { Style: @IconButtonStyle; Anchor: (Height: 40); TextTooltipStyle: $C.@DefaultTextTooltipStyle; };']
    return '\n'.join(lines)+'\n'


def snapshot():
    path=ROOT/'build/shared-ui-refresh/before-common.json'
    if not path.exists():
        path.parent.mkdir(parents=True,exist_ok=True)
        path.write_text(json.dumps({p.relative_to(COMMON).as_posix():hashlib.sha256(p.read_bytes()).hexdigest()
                                   for p in COMMON.rglob('*') if p.is_file()},indent=2)+'\n')


def write():
    snapshot(); OUT.mkdir(parents=True,exist_ok=True)
    for theme in THEMES:
        for state in STATES:
            paint(theme,state).save(OUT/f'{theme}_{state}.png')
    (UI/'SharedButtons.ui').write_text(library(),encoding='utf-8')


def nine_slice(image,width,height,border=8):
    result=Image.new('RGBA',(width,height))
    sx=(0,border,image.width-border,image.width); sy=(0,border,image.height-border,image.height)
    dx=(0,border,width-border,width); dy=(0,border,height-border,height)
    for x in range(3):
        for y in range(3):
            part=image.crop((sx[x],sy[y],sx[x+1],sy[y+1]))
            part=part.resize((dx[x+1]-dx[x],dy[y+1]-dy[y]),Image.Resampling.NEAREST)
            result.alpha_composite(part,(dx[x],dy[y]))
    return result


def font(size,bold=False):
    return ImageFont.truetype('C:/Windows/Fonts/'+('segoeuib.ttf' if bold else 'segoeui.ttf'),size)


def preview():
    image=Image.new('RGBA',(1080,730),(8,18,31,255));draw=ImageDraw.Draw(image)
    draw.text((36,24),'STRANGE MATTER   /   SHARED CONTROLS',font=font(25,True),fill='#b7f6ff')
    draw.text((36,64),'Current button artwork at native nine slice sizes. Not a native client screenshot.',font=font(15),fill='#829bad')
    for column,state in enumerate(STATES):
        x=36+column*260
        draw.text((x,114),state.upper(),font=font(14,True),fill='#9bacd0')
        for row,(theme,text) in enumerate([('Action','CRAFT ONCE'),('Secondary','SELECT PATTERN'),('Cancel','CLOSE INSTRUMENT')]):
            y=152+row*82
            image.alpha_composite(nine_slice(Image.open(OUT/f'{theme}_{state}.png').convert('RGBA'),232,48),(x,y))
            color='#73889e' if state=='Disabled' else '#d8f0f4'
            draw.text((x+116,y+24),text,font=font(14,True),fill=color,anchor='mm')
    draw.text((36,420),'ONE SKIN AT DIFFERENT SIZES',font=font(15,True),fill='#9bacd0')
    for x,y,w,h,text,theme in [(36,457,48,40,'+', 'Action'),(102,457,142,40,'SEND','Action'),(262,457,240,40,'SHOW RECOVERED ITEMS','Secondary'),(520,457,522,40,'RETURN TO THE LABORATORY','Cancel'),(36,522,306,64,'SELECT ASSEMBLER PATTERN','Secondary')]:
        image.alpha_composite(nine_slice(Image.open(OUT/f'{theme}_Default.png').convert('RGBA'),w,h),(x,y))
        draw.text((x+w/2,y+h/2),text,font=font(14,True),fill='#d8f0f4',anchor='mm')
    draw.text((36,640),'Opaque navy panels  /  Cyan actions  /  Violet selections  /  Quiet slate close controls',font=font(16),fill='#a0b6c8')
    draw.text((36,675),'Corners and edge details stay fixed as the center stretches. Existing hit targets and bindings are preserved.',font=font(14),fill='#7c98ab')
    path=ROOT/'docs/art/shared-buttons-preview.png';path.parent.mkdir(parents=True,exist_ok=True);image.convert('RGB').save(path)
    print(path)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--write',action='store_true');parser.add_argument('--preview',action='store_true');args=parser.parse_args()
    if args.write: write()
    if args.preview: preview()
