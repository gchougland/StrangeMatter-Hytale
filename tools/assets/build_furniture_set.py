"""Build only the Resonite furniture set, with a shared painted material atlas.

Uses the existing repo native box/UV format and current model renderer. No prior
art is read as an input or overwritten. A repeat build requires --rebuild and
backs up this set's existing output first. --preview reads current art only.
Requires Pillow and numpy, available in the bundled Codex Python runtime.
"""
from pathlib import Path
import argparse
import hashlib
import json
import math
import random
import zipfile
from datetime import datetime, timezone
import numpy as np
from PIL import Image, ImageDraw
import build_assets as art
import render_current_icons as render
import repair_furniture_0_8_7 as repair

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / 'src/main/resources'
COMMON = RES / 'Common'
FOLDER = 'Blocks/StrangeMatter/Furniture'
TEXTURE = FOLDER + '/resonite_furniture.png'
REPORT = ROOT / 'tools/assets/furniture-set.json'
PIECES = ('Chair', 'Stool', 'Sofa', 'Table', 'Desk', 'Bed', 'Chest', 'Wardrobe',
          'Bookshelf', 'Wall_Shelf', 'Cabinet', 'Wall_Monitor', 'Ceiling_Vent', 'Ladder', 'Window', 'Sign', 'Chest_Large')
COLORS = {
    'navy': (29, 43, 67), 'edge': (65, 87, 113), 'dark': (13, 23, 38), 'steel': (118, 148, 165),
    'cyan': (54, 218, 234), 'purple': (161, 95, 232), 'teal': (33, 83, 94), 'plum': (78, 51, 105),
    'paper': (206, 202, 173), 'wood': (90, 61, 53), 'copper': (180, 111, 66), 'cloth': (182, 204, 207),
    'screen': (12, 44, 61), 'vent': (18, 28, 43), 'amber': (240, 179, 78), 'glass': (76, 185, 206),
}
MATERIALS = list(COLORS)
TILE = 128


def save_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')


def atlas():
    image = Image.new('RGBA', (512, 512))
    for index, (name, base) in enumerate(COLORS.items()):
        tile = Image.new('RGBA', (TILE, TILE)); rng = random.Random('resonite-furniture/' + name)
        for y in range(TILE):
            for x in range(TILE):
                broad = 3 * math.sin(x * .12 + y * .025) + 2 * math.sin(y * .14)
                if name in ('teal', 'plum', 'cloth'): broad += 2 * ((x + y) % 2)
                light = broad + rng.choice((-2, -1, 0, 0, 0, 1, 2))
                color = tuple(max(0, min(255, int(v + light))) for v in base)
                tile.putpixel((x, y), color + ((62 if name == 'glass' else 255),))
        d = ImageDraw.Draw(tile)
        if name in ('navy', 'edge', 'steel', 'copper', 'dark'):
            # Broad painted strokes and sparse wear, with no hard tile border.
            for i in range(42):
                x, y = rng.randrange(128), rng.randrange(128)
                delta = rng.choice((-9, -5, 5, 9)); color = tuple(max(0, min(255, v + delta)) for v in base)
                d.line((x, y, min(127, x + rng.randrange(2, 14)), y), fill=color, width=1)
        if name in ('teal', 'plum'):
            for x in range(-128, 256, 24):
                c = tuple(max(0, v - 13) for v in base)
                d.line((x, 0, x + 128, 128), fill=c, width=1)
                d.line((x, 128, x + 128, 0), fill=c, width=1)
            for x in range(12, 128, 24):
                for y in range(12, 128, 24): d.rectangle((x, y, x+1, y+1), fill=tuple(v+12 for v in base))
        if name == 'cloth':
            for y in range(2, 128, 8): d.line((0, y, 127, y), fill=(168, 191, 196, 255))
        if name == 'paper':
            for y in range(5, 128, 4): d.line((0, y, 127, y), fill=(169, 173, 159))
        if name == 'wood':
            for y in range(0, 128, 4):
                d.line([(x, y + int(2*math.sin(x*.07+y))) for x in range(128)], fill=(75, 49, 46))
        if name in ('cyan', 'purple', 'amber'):
            for y in range(128):
                mul = .76 + .24 * ((math.sin(y * .11) + 1) / 2)
                d.line((0, y, 127, y), fill=tuple(round(v*mul) for v in base))
        if name == 'screen':
            for x in range(0, 128, 6): d.line((x, 0, x, 127), fill=(16, 63, 76))
            for y in range(0, 128, 6): d.line((0, y, 127, y), fill=(16, 63, 76))
            for start in range(5, 128, 12):
                d.line([(x, start+round(3*math.sin(x*.8))) for x in range(128)], fill=(66, 227, 231), width=1)
                d.line((2, start+8, 8, start+8), fill=(181, 121, 239))
        if name == 'vent':
            for y in range(4, 128, 5):
                d.line((0, y, 127, y), fill=(67, 87, 108), width=1)
                d.line((0, y+1, 127, y+1), fill=(8, 15, 27), width=2)
        if name == 'glass':
            for x in range(-128, 256, 64):
                d.line((x, 0, x+128, 128), fill=(164, 245, 249, 95), width=2)
        image.alpha_composite(tile, ((index % 4)*TILE, (index // 4)*TILE))
    return image


class Model:
    def __init__(self, piece):
        self.piece = piece; self.nodes = []; self.sequence = 0

    def group(self, name, position, parent=None):
        self.sequence += 1
        n = {'id': str(self.sequence), 'name': name, 'position': art.V(position),
             'orientation': art.quat(), 'children': []}
        (self.nodes if parent is None else parent['children']).append(n)
        return n

    def box(self, name, position, size, material='navy', rotation=(0, 0, 0), parent=None, glow=False):
        self.sequence += 1
        dims = [max(1, min(96, math.ceil(v))) for v in size]
        tile = MATERIALS.index(material); ox, oy = (tile % 4)*TILE, (tile // 4)*TILE
        layout = {}
        for face in ('front', 'back', 'left', 'right', 'top', 'bottom'):
            # All native face rectangles remain in a single material tile, with
            # at least eight pixels of gutter and reproducible surface variation.
            rng = random.Random(f'{self.piece}/{name}/{self.sequence}/{face}')
            layout[face] = {'offset': {'x': ox+8+rng.randrange(8), 'y': oy+8+rng.randrange(8)},
                            'mirror': {'x': face == 'back', 'y': False}, 'angle': 0}
            if face == 'back': layout[face]['offset']['x'] += dims[0]
        n = {'id': str(self.sequence), 'name': name, 'position': art.V(position), 'orientation': art.quat(rotation),
             'shape': {'type': 'box', 'offset': art.V((0, 0, 0)), 'stretch': art.V([v/d for v, d in zip(size, dims)]),
                       'settings': {'isPiece': False, 'size': art.V(dims), 'isStaticBox': parent is None},
                       'textureLayout': layout, 'unwrapMode': 'custom', 'visible': True,
                       'doubleSided': material == 'glass', 'shadingMode': 'fullbright' if glow else 'standard'}}
        (self.nodes if parent is None else parent['children']).append(n)
        return n

    def rivets(self, xs, ys, z, parent=None):
        for x in xs:
            for y in ys: self.box('Fastener', (x, y, z), (1.2, 1.2, .8), 'steel', parent=parent)

    def panel(self, position, width, height, material='screen', parent=None):
        x, y, z = position
        self.box('Inset_Frame', (x, y, z), (width+3, height+3, 1.6), 'dark', parent=parent)
        self.box('Inset_Surface', (x, y, z+.9), (width, height, .4), material, parent=parent, glow=material == 'screen')
        self.box('Bezel_Highlight', (x, y+height/2+1, z+1), (width+3, .7, .5), 'steel', parent=parent)

    def feet(self, xs, zs, height=13):
        for x in xs:
            for z in zs:
                self.box('Rubber_Foot', (x, 1, z), (4, 2, 4), 'dark')
                self.box('Braced_Leg', (x, 1+height/2, z), (2.5, height, 2.5), 'edge')
                self.box('Foot_Collar', (x, 3, z), (3.5, 1.4, 3.5), 'steel')

    def trim(self, center, width, depth, material='steel'):
        x, y, z = center
        for dz in (-depth/2, depth/2): self.box('Edge_Trim', (x, y, z+dz), (width, .8, .8), material)
        for dx in (-width/2, width/2): self.box('Edge_Trim', (x+dx, y, z), (.8, .8, depth), material)

    def flask(self, position, liquid='cyan', scale=1):
        x, y, z = position
        self.box('Sample_Base', (x, y+2*scale, z), (4*scale, 4*scale, 4*scale), 'edge')
        self.box('Sample_Fluid', (x, y+3*scale, z), (3*scale, 3*scale, 3*scale), liquid, glow=True)
        self.box('Sample_Shoulder', (x, y+4.5*scale, z), (3.5*scale, 1*scale, 3.5*scale), 'steel')
        self.box('Sample_Neck', (x, y+6*scale, z), (1.5*scale, 2*scale, 1.5*scale), liquid)
        self.box('Sample_Stopper', (x, y+7.1*scale, z), (2.2*scale, .8*scale, 2.2*scale), 'copper')

    def books(self, x, y, z, count=5):
        mats = ('plum', 'teal', 'wood', 'navy', 'copper')
        for i in range(count):
            h = (10, 13, 11, 14, 12)[i % 5]
            self.box('Research_Volume', (x+i*3.3, y+h/2, z), (2.8, h, 6), mats[i % 5])
            self.box('Book_Label', (x+i*3.3, y+h-3, z+3.2), (1.8, 2, .3), 'paper')
            for dy in (1.5, h-1): self.box('Book_Binding', (x+i*3.3, y+dy, z+3.2), (3, .5, .4), 'steel')

    def data(self):
        data={'nodes':self.nodes,'format':'prop'}
        repair.visible_groups(data)
        return data


def make(piece):
    if piece == 'Chest_Large':
        m=make('Chest');repair.resize_axis(m.data(),'x',2,-16);m.piece=piece;return m
    m = Model(piece); b = m.box
    if piece in ('Chair', 'Stool', 'Sofa'):
        sofa = piece == 'Sofa'; stool = piece == 'Stool'; center = 16 if sofa else 0; width = 61 if sofa else 25
        m.feet((center-width/2+3, center+width/2-3), (-9, 9), 11)
        b('Seat_Chassis', (center, 12, 0), (width, 3, 24), 'navy'); m.trim((center, 13.8, 0), width-1, 23)
        centers = (0, 32) if sofa else (0,)
        for x in centers:
            b('Cushion_Piping', (x, 14, 1), (25, 2, 21), 'edge')
            b('Stitched_Cushion', (x, 15.2, 1), (23, 1.6, 19), 'teal' if not sofa else 'plum')
            b('Cyan_Seat_Accent', (x, 11.5, 12.3), (14, 1, .6), 'cyan', glow=True)
        if not stool:
            for x in (center-width/2+2, center+width/2-2):
                b('Back_Support', (x, 26, -10), (2.5, 26, 3), 'edge', (-6, 0, 0))
                b('Arm_Riser', (x, 18, 7), (2.5, 10, 2.5), 'steel')
                b('Padded_Arm', (x, 23, 2), (4, 3, 21), 'navy')
                b('Arm_Accent', (x, 24.7, 2), (2, .6, 14), 'purple', glow=True)
            for x in centers:
                b('Back_Armor', (x, 29, -11), (25, 20, 3), 'navy', (-6, 0, 0))
                b('Back_Cushion', (x, 29, -8.7), (21, 15, 2), 'plum' if not sofa else 'teal', (-6, 0, 0))
                b('Cushion_Central_Seam', (x, 29, -7.5), (.65, 14, .5), 'edge')
            m.rivets((center-width/2+2, center+width/2-2), (19, 36), -7)
        else:
            for z in (-8, 8): b('Foot_Rest', (0, 6, z), (20, 1.8, 1.8), 'copper')
    elif piece in ('Table', 'Desk'):
        m.feet((-11, 43), (-10, 10), 25)
        b('Worktop_Subframe', (16, 25, 0), (60, 2.5, 28), 'edge')
        b('Worktop', (16, 27, 0), (62, 2, 29), 'navy'); m.trim((16, 28.1, 0), 60, 27)
        b('Soft_Work_Surface', (16, 28.3, 0), (54, .5, 23), 'teal')
        for x in (-11, 43): b('Leg_Brace', (x, 10, 0), (2, 2, 22), 'steel')
        b('Cross_Brace', (16, 7, -10), (52, 2, 2), 'edge')
        for x in (-8, 40): b('Worktop_End_Marker', (x, 28.8, 0), (.8, .5, 19), 'cyan', glow=True)
        if piece == 'Desk':
            b('Drawer_Enclosure', (37, 15, 0), (18, 20, 23), 'navy')
            for y in (9, 16, 23):
                b('Drawer_Face', (37, y, 12), (16, 5.7, 1.4), 'edge')
                b('Drawer_Handle', (37, y, 13.3), (6, 1, 1.2), 'steel')
            b('Screen_Stem', (34, 33, -7), (3, 9, 3), 'steel')
            m.panel((34, 39, -6), 18, 13)
            b('Screen_Upper_Rail', (34, 47, -5), (23, 1.5, 3), 'navy')
            b('Tablet_Sheet', (1, 29, 3), (14, .8, 12), 'paper', (0, -10, 0))
            b('Writing_Stylus', (10, 30, 8), (1, 1, 9), 'copper', (0, -20, 0))
            m.flask((-8, 29, -6), 'purple', .8)
    elif piece == 'Bed':
        m.feet((-12, 12), (-11, 43), 15)
        b('Bed_Chassis', (0, 16, 16), (30, 5, 60), 'navy'); m.trim((0, 18.5, 16), 29, 59)
        b('Mattress_Piping', (0, 20.2, 16), (28, 4, 57), 'edge')
        b('Padded_Mattress', (0, 22.5, 16), (27, 3, 56), 'cloth')
        b('Violet_Quilt', (0, 24, 23), (27.5, 1, 40), 'plum')
        for x in (-11.5, 11.5): b('Quilt_Trim', (x, 24.65, 23), (1, .4, 38), 'teal')
        b('Folded_Quilt_Edge', (0, 25, 4), (28, 1.6, 4), 'teal')
        b('Pillow', (0, 25.5, -6), (22, 3, 11), 'cloth')
        b('Pillow_Seam', (0, 27, -6), (21, .3, .5), 'steel')
        b('Headboard_Frame', (0, 26, -14), (31, 28, 3), 'navy')
        b('Headboard_Pad', (0, 32, -11.9), (25, 12, 1.3), 'teal')
        for x in (-12, 12): b('Headboard_Light', (x, 33, -11), (1, 12, 1), 'cyan', glow=True)
        b('Footboard_Frame', (0, 20, 46), (31, 14, 3), 'navy')
        m.panel((0, 19, 47), 13, 5, 'vent')
        for z in (-5, 14, 34): b('Side_Service_Light', (15.2, 17, z), (.7, 1.5, 8), 'purple', glow=True)
        m.rivets((-12, 12), (15, 24), 47.5)
    elif piece in ('Chest', 'Cabinet', 'Wardrobe'):
        chest = piece == 'Chest'; wardrobe = piece == 'Wardrobe'; h = 23 if chest else 62 if wardrobe else 30
        m.feet((-11, 11), (-10, 10), 3)
        b('Container_Floor', (0, 4, 0), (29, 3, 25), 'edge')
        wall_top = 21 if chest else h-2
        for x in (-13, 13): b('Container_Side', (x, (wall_top+5.5)/2, 0), (3, wall_top-5.5, 22), 'navy')
        b('Container_Back', (0, (wall_top+5.5)/2, -11), (23, wall_top-5.5, 2), 'dark')
        b('Container_Interior', (0, 6, 0), (23, 1, 20), 'teal')
        for x in (-14.75, 14.75): b('Corner_Rail', (x, (wall_top+5.5)/2, 0), (.5, wall_top-5.5, 24), 'edge')
        if chest:
            b('Chest_Front', (0, 13.25, 11.5), (23, 15.5, 2), 'navy'); m.panel((0, 12, 12.5), 13, 7, 'vent')
            lid = m.group('Lid', (0, 21, -12))
            b('Chest_Lid', (0, 2, 12), (29, 4, 26), 'navy', parent=lid)
            b('Lid_Inset', (0, 4.2, 12), (22, .5, 20), 'teal', parent=lid)
            for x in (-11, 11):
                b('Lid_Binding', (x, 2.5, 12), (2, 4.5, 27), 'steel', parent=lid)
                b('Front_Binding', (x, 12, 13), (2, 14, 1), 'steel')
            b('Latch_Outer', (0, 0, 26), (6, 7, 2), 'dark', parent=lid)
            b('Latch_Emitter', (0, 0, 27.2), (3, 3, .5), 'cyan', parent=lid, glow=True)
            for x in (-15, 15): b('Carry_Handle', (x, 12, 0), (1, 3, 9), 'copper')
        else:
            b('Top_Cornice', (0, h-1, 0), (30, 2, 26), 'edge')
            for y in ([20, 37, 53] if wardrobe else [16]): b('Interior_Shelf', (0, y, 0), (24, 1, 21), 'steel')
            for side, name in ((-1, 'Door_Left'), (1, 'Door_Right')):
                pivot = m.group(name, (side*12, 5, 12))
                x = -side*5.6
                b('Door_Panel', (x, (h-8)/2, 0), (11.5, h-8, 2), 'navy', parent=pivot)
                b('Door_Inset', (x, (h-8)/2, 1.2), (8.5, h-14, .8), 'teal' if wardrobe else 'edge', parent=pivot)
                b('Vertical_Pull', (-side*9.5, (h-8)/2, 2.4), (1.2, 7, 1.5), 'steel', parent=pivot)
                b('Door_Circuit', (x, h-13, 1.9), (6, 1.2, .5), 'purple' if side == 1 else 'cyan', parent=pivot, glow=True)
            m.rivets((-13, 13), (6, h-4), 13.2)
            if wardrobe:
                b('Upper_Pressure_Gauge', (0, 59, 13.5), (3, 2, .8), 'amber')
                m.panel((0, 9, -12.5), 16, 5, 'vent')
    elif piece == 'Bookshelf':
        m.feet((-12, 12), (-6, 6), 3)
        b('Bookcase_Back', (0, 33, -8), (28, 58, 2), 'dark')
        for x in (-14, 14): b('Bookcase_Side', (x, 33, 0), (3, 58, 18), 'navy')
        for y in (4, 23, 42, 62):
            b('Bookcase_Shelf', (0, y, 0), (29, 2, 19), 'edge')
            b('Shelf_Edge', (0, y, 9.7), (25, .7, .6), 'cyan' if y in (23, 62) else 'steel', glow=y in (23, 62))
        m.books(-10, 5, 3, 6); m.books(-10, 24, 3, 4); m.books(-3, 43, 3, 5)
        m.flask((8, 24, 2), 'purple', 1); m.flask((-9, 43, 1), 'cyan', 1.1)
        m.rivets((-13, 13), (9, 57), 10)
    elif piece == 'Wall_Shelf':
        for x in (-12, 12):
            b('Wall_Bracket', (x, 15, -14), (3, 27, 3), 'navy')
            b('Shelf_Gusset', (x, 8, -8), (2, 12, 2), 'steel', (37, 0, 0))
        b('Display_Shelf', (0, 13, -5), (31, 2, 20), 'edge')
        b('Shelf_Lip', (0, 14.2, 5), (29, 1.5, 1.5), 'navy')
        b('Shelf_Light', (0, 12, 4.7), (21, .7, .7), 'cyan', glow=True)
        m.books(-9, 14, -1, 4); m.flask((9, 14, -2), 'purple', 1)
        m.rivets((-12, 12), (4, 27), -11.8)
    elif piece == 'Wall_Monitor':
        b('Wall_Mount', (0, 16, -14), (12, 17, 3), 'dark')
        b('Monitor_Shell', (0, 17, -10), (30, 24, 5), 'navy')
        m.panel((0, 19, -7.1), 24, 15)
        b('Monitor_Cap', (0, 30, -10), (31, 2, 6), 'edge')
        for x, mat in ((-9, 'cyan'), (-3, 'purple'), (3, 'amber')):
            b('Status_Button', (x, 7, -6.7), (2.5, 1.5, 1), mat, glow=True)
        b('Tuning_Dial', (10, 7, -6), (4, 4, 2), 'steel', (0, 0, 45))
        for x in (-13, 13): b('Cooling_Rail', (x, 17, -6.7), (1, 12, .7), 'copper')
    elif piece == 'Ceiling_Vent':
        b('Vent_Mount', (0, 30.5, 0), (31, 3, 31), 'navy')
        b('Recessed_Air_Duct', (0, 28.8, 0), (25, .6, 25), 'dark')
        for z in range(-10, 11, 4): b('Vent_Louvre', (0, 28, z), (23, 1, 2), 'steel', (15, 0, 0))
        for x in (-13.5, 13.5): b('Vent_Status_Rail', (x, 28.8, 0), (.8, 1, 22), 'cyan', glow=True)
        for x in (-13, 13):
            for z in (-13, 13): b('Vent_Bolt', (x, 28.6, z), (1.5, .6, 1.5), 'copper')
    elif piece == 'Ladder':
        for x in (-11, 11):
            b('Ladder_Rail', (x, 16, -11), (3, 32, 4), 'navy')
            b('Rail_Highlight', (x-1, 16, -8.8), (.7, 32, .6), 'steel')
            for y in (4, 28): b('Wall_Standoff', (x, y, -14), (5, 4, 4), 'edge')
        for y in (4, 12, 20, 28):
            b('Ladder_Rung', (0, y, -10), (23, 2.8, 4), 'steel')
            b('Rung_Grip', (0, y+1.6, -10), (15, .5, 4.2), 'teal')
            for x in (-8, 8): b('Rung_Marker', (x, y, -7.7), (1.5, 1, .5), 'cyan', glow=True)
    elif piece == 'Window':
        for x in (-14, 14): b('Window_Frame', (x, 16, 0), (4, 32, 6), 'navy')
        for y in (2, 30): b('Window_Frame', (0, y, 0), (24, 4, 6), 'navy')
        b('Blue_Glass', (0, 16, 0), (24, 24, .4), 'glass')
        for x in (-11, 11): b('Inner_Seal', (x, 16, 2.3), (1, 24, 1), 'steel')
        for y in (5, 27): b('Inner_Seal', (0, y, 2.3), (22, 1, 1), 'steel')
        b('Window_Mullion', (0, 16, 0), (2, 24, 4), 'edge')
        b('Window_Crossbar', (0, 16, 0), (24, 2, 4), 'edge')
        for x in (-14, 14): b('Window_Status_Inlay', (x, 16, 3.2), (.7, 17, .6), 'cyan', glow=True)
        m.rivets((-14, 14), (3, 29), 3.2)
    elif piece == 'Sign':
        b('Plaque_Wall_Mount', (0, 18, -14), (22, 13, 3), 'dark')
        b('Plaque_Frame', (0, 18, -11), (30, 20, 3), 'navy')
        b('Plaque_Enamel', (0, 18, -9.3), (26, 16, .7), 'teal')
        # A readable geometric laboratory flask rather than fake editable text.
        for x in (-2, 2): b('Flask_Neck', (x, 22, -8.6), (1, 6, .7), 'cyan', glow=True)
        b('Flask_Lip', (0, 25, -8.6), (7, 1, .7), 'cyan', glow=True)
        for x, angle in ((-4, -28), (4, 28)): b('Flask_Shoulder', (x, 16.9, -8.6), (1, 7, .7), 'cyan', (0, 0, angle), glow=True)
        b('Flask_Base', (0, 13.7, -8.6), (11, 1, .7), 'cyan', glow=True)
        b('Reagent_Level', (0, 16, -8.5), (6, 1.8, .5), 'purple', glow=True)
        m.rivets((-13, 13), (11, 25), -8.8)
    if piece == 'Table':repair.resize_axis(m.data(),'z',2,16)
    return m


def box_bounds(low, high):
    return {'Min': dict(zip('XYZ', low)), 'Max': dict(zip('XYZ', high))}


def hitboxes(piece):
    if piece == 'Chest_Large':
        data=hitboxes('Chest')
        for box in data['Boxes']:
            box['Min']['X']=box['Min']['X']*2-1;box['Max']['X']=box['Max']['X']*2-1
        return data
    # Coordinates follow native block models: x/32+.5, y/32, z/32+.5.
    # Deliberate whole cell silhouettes avoid decorative fractional filler cells.
    bounds = {
        'Chair': [((.05,0,.05),(.95,.5,.92)), ((.06,.5,.04),(.94,1.27,.31))],
        'Stool': [((.05,0,.08),(.95,.5,.92))],
        'Sofa': [((.02,0,.05),(1.98,.5,.92)), ((.02,.5,.04),(1.98,1.27,.31))],
        'Table': [((0,0,.03),(2,.90,.97))],
        'Desk': [((0,0,.03),(2,.90,.97)), ((1.16,.9,.17),(1.94,1.49,.42))],
        'Bed': [((0,0,0),(1,.77,2)), ((0,.77,0),(1,1.27,.14)), ((0,.77,1.87),(1,.85,2))],
        'Chest': [((0,0,.06),(1,.80,.98))],
        'Cabinet': [((.02,0,.06),(.98,.94,1))],
        'Wardrobe': [((.02,0,.06),(.98,1.94,1))],
        'Bookshelf': [((.02,0,.17),(.98,1.97,.84))],
        'Wall_Shelf': [((.01,.04,0),(.99,.96,.69))],
        'Wall_Monitor': [((.01,.14,0),(.99,1,.36))],
        'Ceiling_Vent': [((.01,.84,.01),(.99,1,.99))],
        'Ladder': [((.08,0,0),(.92,1,.30))],
        'Window': [((0,0,.39),(1,1,.61))],
        'Sign': [((.03,.24,.01),(.97,.88,.25))],
    }
    data={'Boxes': [box_bounds(*b) for b in bounds[piece]]}
    if piece == 'Table':
        for box in data['Boxes']:
            box['Min']['Z']*=2;box['Max']['Z']*=2
    return data


DESCRIPTIONS = {
    'Chair': 'Take a seat between experiments. A padded chair with a sturdy resonite frame.',
    'Stool': 'A compact padded seat for your laboratory. Place it beside a work table.',
    'Sofa': 'Sit with a fellow scientist. Two padded seats share a resonite frame.',
    'Table': 'A large work table with a soft surface and reinforced legs. Occupies a square of four blocks.',
    'Desk': 'Organize your next experiment at a desk with a monitor, notes and sample flask. Decorative furniture.',
    'Bed': 'Rest after a long experiment and set your respawn point using the normal bed controls.',
    'Chest': 'Store 18 stacks of supplies. Place two matching chests side by side to combine them into one large chest.',
    'Chest_Large': 'Store 36 stacks of supplies in a joined chest. Breaking it returns two regular chests and its contents.',
    'Wardrobe': 'Store 36 stacks of clothing and equipment behind a pair of sturdy cabinet doors.',
    'Bookshelf': 'Keep research books and sealed samples on display. Decorative furniture.',
    'Wall_Shelf': 'Mount books and a glowing sample flask on your laboratory wall. Decorative furniture.',
    'Cabinet': 'Store 18 stacks of supplies in a compact cabinet with opening doors.',
    'Wall_Monitor': 'A glowing display of unusual readings for your laboratory wall. Decorative furniture.',
    'Ceiling_Vent': 'Finish your laboratory ceiling with metal louvers and small cyan status lights. Decorative furniture.',
    'Ladder': 'Climb between laboratory floors. Stack sections against a wall to extend the ladder.',
    'Window': 'Look through pale cyan glass held in a strong resonite frame.',
    'Sign': 'Mark your laboratory with a glowing flask plaque. This decorative sign cannot be edited.',
}


def recipe(piece):
    ingots = {'Chair':3,'Stool':2,'Sofa':5,'Table':5,'Desk':6,'Bed':6,'Chest':4,'Wardrobe':6,
              'Bookshelf':5,'Wall_Shelf':2,'Cabinet':4,'Wall_Monitor':3,'Ceiling_Vent':2,'Ladder':2,'Window':2,'Sign':2}[piece]
    inputs = [{'ItemId':'SM_Resonite_Ingot','Quantity':ingots}]
    if piece in ('Chair','Stool','Sofa','Bed'):
        inputs.append({'ItemId':'Ingredient_Fabric_Scrap_Linen','Quantity':{'Chair':3,'Stool':2,'Sofa':5,'Bed':8}[piece]})
    elif piece in ('Table','Desk','Chest','Wardrobe','Bookshelf','Wall_Shelf','Cabinet'):
        inputs.append({'ResourceTypeId':'Wood_Planks','Quantity':4 if piece in ('Table','Desk','Wardrobe','Bookshelf') else 2})
    if piece in ('Desk','Wall_Monitor'): inputs.append({'ItemId':'SM_Resonant_Circuit','Quantity':1})
    if piece == 'Window': inputs.append({'ItemId':'Ingredient_Crystal_Cyan','Quantity':2})
    if piece in ('Bookshelf','Wall_Shelf','Sign'): inputs.append({'ItemId':'SM_Resonite_Nugget','Quantity':3})
    return {'Input':inputs,'OutputQuantity':1,'TimeSeconds':3,
            'BenchRequirement':[{'Type':'Crafting','Id':'SM_Laboratory','Categories':['SM_Laboratory_Furniture']}],
            'KnowledgeRequired':True}


def item(piece):
    if piece == 'Chest_Large':
        value=item('Chest');value.pop('Recipe');value['Variant']=True
        value['TranslationProperties']={key:'server.items.SM_Resonite_Chest_Large.'+key.lower() for key in ('Name','Description')}
        value['Icon']='Icons/ItemsGenerated/SM_Resonite_Chest_Large.png'
        block=value['BlockType'];block.pop('ConnectedBlockRuleSet')
        block['CustomModel']=FOLDER+'/chest_large.blockymodel';block['HitboxType']='SM_Resonite_Chest_Large'
        block['InteractionHint']='server.interactionHints.SM_Resonite_Chest_Large'
        block['BlockEntity']['Components']['ItemContainerBlock']['Capacity']=36
        block['Gathering']['Breaking']['DropList']={'Container':{'Type':'Single','Item':{'ItemId':'SM_Resonite_Chest','QuantityMin':2,'QuantityMax':2}}}
        return value
    id = 'SM_Resonite_'+piece
    category = 'Furniture.Beds' if piece == 'Bed' else 'Furniture.Containers' if piece in ('Chest','Wardrobe','Cabinet') else 'Furniture.Signs' if piece == 'Sign' else 'Furniture.Doors' if piece in ('Ladder','Window') else 'Furniture.Furniture'
    block = {'Material':'Solid','DrawType':'Model','Opacity':'Transparent','CustomModel':FOLDER+'/'+piece.lower()+'.blockymodel',
             'CustomModelTexture':[{'Texture':TEXTURE,'Weight':1}], 'HitboxType':id,'VariantRotation':'NESW',
             'Gathering':{'Breaking':{'GatherType':'Benches'}},'BlockParticleSetId':'Stone','ParticleColor':'#263a54',
             'BlockSoundSetId':'Stone','PhysicalMaterialId':'Stone','Support':{'Down':[{'FaceType':'Full'}]}}
    if piece in ('Chair','Stool','Sofa'):
        block['Seats'] = [{'Offset':{'X':x,'Y':0,'Z':.2 if piece != 'Stool' else .12},'Yaw':0} for x in ([0,1] if piece == 'Sofa' else [0])]
        block['Interactions'] = {'Use':'Block_Seat'}
        block['InteractionHint'] = 'server.interactionHints.'+id
    if piece == 'Bed':
        block['Beds'] = [{'Offset':{'X':0,'Y':.25,'Z':.5},'Yaw':0}]
        block['Interactions'] = {'Use':{'Interactions':[{'Type':'Bed'}]},'Primary':'Check_Can_Break_Respawn'}
        block['BlockEntity'] = {'Components':{'RespawnBlock':{}}}
        block['InteractionHint'] = 'server.interactionHints.'+id
    if piece in ('Chest','Wardrobe','Cabinet'):
        block['CustomModelAnimation']=FOLDER+'/Animations/'+piece.lower()+'_closed.blockyanim'
        block['BlockEntity'] = {'Components':{'ItemContainerBlock':{'Capacity':36 if piece == 'Wardrobe' else 18}}}
        block['Interactions'] = {'Use':'Open_Container'}
        block['InteractionHint'] = 'server.interactionHints.'+id
        block['State'] = {'Definitions':{state:{'CustomModelAnimation':FOLDER+'/Animations/'+piece.lower()+'_'+action+'.blockyanim',
                                               'InteractionSoundEventId':'SFX_Chest_Wooden_'+action.capitalize()}
                                        for state,action in (('OpenWindow','open'),('CloseWindow','close'))}}
    if piece == 'Chest':
        block['ConnectedBlockRuleSet']={'Type':'CustomTemplate','TemplateShapeAssetId':'ChestConnectedBlockTemplate',
            'TemplateShapeBlockPatterns':{'Default':'SM_Resonite_Chest','Double':'SM_Resonite_Chest_Large'}}
    if piece in ('Wall_Shelf','Wall_Monitor','Sign','Ladder'):
        block['Support'] = {'North':[{'FaceType':'Full'}]}
        block['PlacementSettings'] = {'RotationMode':'BlockNormal'}
    if piece == 'Ladder': block['MovementSettings'] = {'IsClimbable':True}
    if piece == 'Ceiling_Vent': block['Support'] = {'Up':[{'FaceType':'Full'}]}
    if piece == 'Window':
        block.pop('Support',None); block['VariantRotation']='Wall'
        block['Supporting']={'Up':[{'FaceType':'Window'}],'Down':[{'FaceType':'Window'}]}
    return {'TranslationProperties':{'Name':'server.items.'+id+'.name','Description':'server.items.'+id+'.description'},
            'Icon':'Icons/ItemsGenerated/'+id+'.png','MaxStack':25,'Categories':[category,'SM_StrangeMatter.All'],
            'PlayerAnimationsId':'Block','ItemSoundSetId':'ISS_Blocks_Stone','Quality':'Uncommon',
            'Tags':{'Type':['Furniture','StrangeMatter'],'Family':['Resonite']},'BlockType':block,
            'Interactions':{'Primary':'Block_Primary','Secondary':'Block_Secondary'},'Recipe':recipe(piece)}


def animation(piece, opened):
    rotations = {'Lid':(-88,0,0)} if piece == 'Chest' else {'Door_Left':(0,-100,0),'Door_Right':(0,100,0)}
    nodes = {}
    for node, rotation in rotations.items():
        initial, final = (art.quat(),art.quat(rotation)) if opened else (art.quat(rotation),art.quat())
        nodes[node]={'position':[], 'orientation':[{'time':0,'delta':initial,'interpolationType':'smooth'},
                                                   {'time':14,'delta':final,'interpolationType':'smooth'}],
                     'shapeStretch':[],'shapeVisible':[],'shapeUvOffset':[]}
    return {'formatVersion':1,'duration':16,'holdLastKeyframe':True,'nodeAnimations':nodes}


def preview(entries):
    views=[]
    for entry in entries:
        resource=render.read(RES/entry['itemFile']); faces=render.item_faces(resource)
        views.append((entry['name'],faces))
    # Ceiling furniture is seen from below during play; use its visible face.
    contact_path=ROOT/'docs/art/resonite-furniture-contact.png'
    render.contact(views,contact_path,cols=4,cell=300)
    contact_image=Image.open(contact_path).convert('RGBA')
    vent_index=PIECES.index('Ceiling_Vent');x=(vent_index%4)*300;y=(vent_index//4)*340
    ImageDraw.Draw(contact_image).rectangle((x,y,x+299,y+299),fill=(17,25,42,255))
    vent=render.render(views[vent_index][1],286,pitch=-25)
    contact_image.alpha_composite(vent,(x+7,y+3));contact_image.convert('RGB').save(contact_path)
    selected = ('Chair','Bed','Chest','Wardrobe','Bookshelf','Desk')
    sheet=Image.new('RGB',(1200,len(selected)*330),(17,25,42));draw=ImageDraw.Draw(sheet)
    for row,piece in enumerate(selected):
        resource=item(piece); model=render.read(COMMON/resource['BlockType']['CustomModel']); tex=Image.open(COMMON/TEXTURE)
        for col,yaw in enumerate((35,155,-65)):
            faces=render.model_faces(model,tex);pic=render.render(faces,310,yaw=yaw,pitch=20)
            sheet.paste(pic,(col*400+45,row*330),pic)
        draw.text((12,row*330+310),piece+'   front   rear   side',fill=(201,224,226))
    sheet.save(ROOT/'docs/art/resonite-furniture-views.png')
    # Open state review applies the actual exported animation endpoint to roots.
    animated=[]
    for piece in ('Chest','Wardrobe','Cabinet'):
        model=render.read(COMMON/FOLDER/(piece.lower()+'.blockymodel'))
        endpoints=animation(piece,True)['nodeAnimations']
        for n in model['nodes']:
            if n['name'] in endpoints:n['orientation']=endpoints[n['name']]['orientation'][-1]['delta']
        animated.append(('Open '+piece,render.model_faces(model,Image.open(COMMON/TEXTURE))))
    render.contact(animated,ROOT/'docs/art/resonite-furniture-open.png',cols=3,cell=340)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--rebuild',action='store_true',help='Back up and replace only this generated furniture set')
    parser.add_argument('--preview',action='store_true',help='Render current furniture without changing any game resources')
    args=parser.parse_args()
    if args.preview:
        preview(render.read(REPORT)['items']); return
    existing=list((COMMON/FOLDER).rglob('*')) if (COMMON/FOLDER).exists() else []
    if any(p.is_file() for p in existing):
        if not args.rebuild:parser.error('Furniture already exists. Use --preview to inspect current edits, or --rebuild for an intentional reset of this set.')
        stamp=datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
        backup=ROOT/'build/art-preservation'/('furniture-before-rebuild-'+stamp+'.zip');backup.parent.mkdir(parents=True,exist_ok=True)
        with zipfile.ZipFile(backup,'w',zipfile.ZIP_DEFLATED) as archive:
            for p in existing:
                if p.is_file():archive.write(p,p.relative_to(RES).as_posix())
            for piece in PIECES:
                for p in (RES/'Server/Item/Items/StrangeMatter'/('SM_Resonite_'+piece+'.json'),COMMON/'Icons/ItemsGenerated'/('SM_Resonite_'+piece+'.png')):
                    if p.exists():archive.write(p,p.relative_to(RES).as_posix())
    (COMMON/FOLDER).mkdir(parents=True,exist_ok=True);atlas().save(COMMON/TEXTURE)
    entries=[]
    for piece in PIECES:
        model=make(piece).data(); definition=item(piece); id='SM_Resonite_'+piece
        modelpath=COMMON/definition['BlockType']['CustomModel'];save_json(modelpath,model)
        itempath=RES/'Server/Item/Items/StrangeMatter'/(id+'.json');save_json(itempath,definition)
        hitboxpath=RES/'Server/Item/Block/Hitboxes/StrangeMatter'/(id+'.json');save_json(hitboxpath,hitboxes(piece))
        if piece in ('Chest','Wardrobe','Cabinet'):
            for action in ('open','close'):save_json(COMMON/FOLDER/'Animations'/(piece.lower()+'_'+action+'.blockyanim'),animation(piece,action=='open'))
            save_json(COMMON/FOLDER/'Animations'/(piece.lower()+'_closed.blockyanim'),repair.closed_animation(repair.visible_groups(model)))
        faces=render.item_faces(definition);points=np.concatenate([face[0] for face in faces])
        icon=render.render(faces,256,pitch=-25 if piece=='Ceiling_Vent' else 23).resize((64,64),Image.Resampling.LANCZOS);icon.save(COMMON/definition['Icon'])
        entries.append({'id':id,'name':'Large Resonite Chest' if piece=='Chest_Large' else 'Resonite '+piece.replace('_',' '),'description':DESCRIPTIONS[piece],'research':'resonite',
                        'itemFile':itempath.relative_to(RES).as_posix(),'model':definition['BlockType']['CustomModel'],'texture':TEXTURE,
                        'icon':definition['Icon'],'recipe':definition.get('Recipe'),'hitbox':id,
                        'boundsModelUnits':{'min':points.min(0).round(5).tolist(),'max':points.max(0).round(5).tolist()},
                        'capacity':36 if piece in ('Wardrobe','Chest_Large') else 18 if piece in ('Chest','Cabinet') else None,
                        'seats':len(definition['BlockType'].get('Seats',[])),
                        'interactionHint':('Press [{key}] to '+('sleep' if piece=='Bed' else 'sit' if piece in ('Chair','Stool','Sofa') else 'open the '+('large chest' if piece=='Chest_Large' else piece.lower()))) if piece in ('Chair','Stool','Sofa','Bed','Chest','Wardrobe','Cabinet','Chest_Large') else None})
    save_json(REPORT,{'items':entries,'modelUnitsPerBlock':32,'atlas':[512,512],'materials':MATERIALS,
                      'interactionHints':{'interactionHints.'+entry['id']:entry['interactionHint'] for entry in entries if entry['interactionHint']},
                      'nativeBehaviorSources':['Furniture_Village_Chair','Furniture_Crude_Stool','Furniture_Village_Bench','Furniture_Village_Bed','Furniture_Village_Chest_Small','Furniture_Village_Ladder','Furniture_Village_Window','Furniture_Village_Sign'],
                      'signEditable':False,'notes':'Sign editing is not present in the supplied native furniture schema. Storage uses standard native container grids.'})
    preview(entries)
    print(json.dumps({'pieces':len(entries),'atlas':TEXTURE,'report':str(REPORT),'preview':'docs/art/resonite-furniture-contact.png'}))


if __name__=='__main__':main()
