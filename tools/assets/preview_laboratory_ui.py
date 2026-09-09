"""Authored static layout previews with shipped icons. Not a native UI renderer.

Requires Pillow only. Geometry for named controls is read from the actual UI files.
"""
from pathlib import Path
import importlib.util
import json
import re
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('layout', ROOT / 'tools/validate_laboratory_ui.py')
layout = importlib.util.module_from_spec(spec)
spec.loader.exec_module(layout)
UI = layout.UI
ICONS = ROOT / 'src/main/resources/Common/Icons/ItemsGenerated'
OUT = ROOT / 'docs/art'


class Preview:
    def __init__(self, width, height, title):
        self.image = Image.new('RGB', (width + 80, height + 130), '#070d18')
        self.draw = ImageDraw.Draw(self.image)
        self.label((40, 15, width, 33), title, 19, '#e1eafa', True)
        self.label((40, 47, width, 20), 'Static layout preview. Not a native client screenshot.', 12, '#98adc9')
        self.origin = (40, 88)

    def label(self, rect, text, size=14, color='#d8e2f1', bold=False, align='Start', wrap=True):
        x, y, w, h = rect
        path = Path('C:/Windows/Fonts') / ('segoeuib.ttf' if bold else 'segoeui.ttf')
        font = ImageFont.truetype(str(path), size)
        lines = []
        for paragraph in text.split('\n'):
            current = ''
            for word in paragraph.split():
                candidate = (current + ' ' + word).strip()
                if wrap and current and self.draw.textlength(candidate, font=font) > w:
                    lines.append(current)
                    current = word
                else:
                    current = candidate
            lines.append(current)
        for line in lines:
            if y + size > rect[1] + h:
                break
            dx = 0 if align == 'Start' else (w - self.draw.textlength(line, font=font)) / (2 if align == 'Center' else 1)
            self.draw.text((x + dx, y), line, font=font, fill=color)
            y += size + 4

    def box(self, rect, color):
        x, y, w, h = rect
        self.draw.rectangle((x, y, x + w - 1, y + h - 1), fill=color)

    def icon(self, rect, item):
        path = ICONS / (item + '.png')
        if not path.exists():
            path = ICONS / 'SM_Research_Notes.png'
        icon = Image.open(path).convert('RGBA')
        icon.thumbnail((int(rect[2]), int(rect[3])), Image.Resampling.LANCZOS)
        self.image.paste(icon, (int(rect[0] + (rect[2] - icon.width) / 2), int(rect[1] + (rect[3] - icon.height) / 2)), icon)

    def element(self, source, selector, text=None, item=None, color=None, origin=None, parent=(1140, 754)):
        body = layout.block(source, selector)
        x1, y1, x2, y2 = layout.rect(body, *parent)
        ox, oy = origin or self.origin
        rect = (ox + x1, oy + y1, x2 - x1, y2 - y1)
        if item:
            self.icon(rect, item)
        elif text is not None:
            font = re.search(r'FontSize:\s*(\d+)', body)
            tint = re.search(r'TextColor:\s*(#[\da-fA-F]{6})', body)
            alignment = re.search(r'HorizontalAlignment:\s*(\w+)', body)
            self.label(rect, text, int(font[1]) if font else 12, color or (tint[1] if tint else '#87eef7'),
                       'RenderBold: true' in body, alignment[1] if alignment else 'Start')
        else:
            tint = re.search(r'Background:\s*(#[\da-fA-F]{6})', body)
            self.box(rect, color or (tint[1] if tint else '#1c2a43'))
        return rect

    def button(self, source, selector, text, origin=None, parent=(1140, 754)):
        rect = self.element(source, selector, origin=origin, parent=parent, color='#1c2a43')
        self.label((rect[0], rect[1] + (rect[3] - 16) / 2, rect[2], 20), text, 12, '#87eef7', True, 'Center')


def forge():
    source = (UI / 'RealityForge.ui').read_text()
    row = (UI / 'ForgeRecipeRow.ui').read_text()
    page = Preview(1140, 754, 'Reality Forge selection and crafting')
    ox, oy = page.origin
    page.box((ox, oy, 1140, 754), '#101a2a')
    page.box((ox, oy, 4, 754), '#7651ad')
    page.element(source, 'Title', 'REALITY FORGE')
    page.label((ox + 26, oy + 61, 720, 19), 'Create equipment from materials and anomaly shards', 13, '#aa8fce')
    page.element(source, 'State', 'CRAFTING')
    page.box((ox + 26, oy + 88, 1088, 1), '#36516b')
    page.label((ox + 26, oy + 105, 265, 24), 'RECIPES', 16, '#72edf2', True)
    page.element(source, 'RecipeCount', '8 available   11 total')
    page.element(source, 'Recipes')
    recipes = [r for r in json.loads((ROOT / 'src/main/resources/Server/StrangeMatter/recipes.json').read_text()) if r['station'] == 'forge']
    # This view is scrolled down so the selected Hoverboard and locked alternatives are visible.
    for i, recipe in enumerate(recipes[4:10]):
        origin = (ox + 31, oy + 167 + i * 78)
        selected, locked = recipe['id'] == 'hoverboard', recipe['id'] in ('rift_stabilizer', 'stasis_projector')
        page.box((*origin, 247, 73), '#152138')
        page.element(row, 'RecipeAccent', color='#72edf2' if selected else '#354360', origin=origin, parent=(247, 73))
        page.element(row, 'RecipeIcon', item=recipe['output'], origin=origin, parent=(247, 73))
        name = recipe['output'][3:].replace('_', ' ')
        page.element(row, 'RecipeName', name, color='#72edf2' if selected else '#a0b0c9' if locked else '#d6e2f2', origin=origin, parent=(247, 73))
        page.element(row, 'RecipeState', 'SELECTED' if selected else 'RESEARCH NEEDED' if locked else 'AVAILABLE', color='#dfa9be' if locked else '#9bdbd7', origin=origin, parent=(247, 73))
    page.box((ox + 284, oy + 169, 4, 501), '#1e2a42')
    page.box((ox + 284, oy + 314, 4, 257), '#506788')
    page.element(source, 'Recipe', 'Hoverboard')
    page.element(source, 'RecipeIndex', '6 / 11')
    page.element(source, 'Research', 'RESEARCH VERIFIED  /  Hoverboard', color='#a5dccc')
    materials = [('SM_Resonite_Ingot', 'Resonite Ingot', 12, 4), ('SM_Resonant_Circuit', 'Resonant Circuit', 4, 1), ('SM_Containment_Capsule_Gravity', 'Containment Capsule Gravity', 1, 1), ('SM_Gravitic_Shard', 'Gravitic Shard', 7, 2), ('SM_Energetic_Shard', 'Energetic Shard', 8, 2)]
    for i, (item, name, have, need) in enumerate(materials):
        m = layout.block(source, 'Material' + str(i))
        page.element(source, 'Material' + str(i), origin=(ox + 318, oy + 185), parent=(346, 330))
        origin = (ox + 318, oy + 185 + 41 * i)
        page.element(m, 'MaterialAccent' + str(i), origin=origin, parent=(346, 37))
        page.element(m, 'MaterialIcon' + str(i), item=item, origin=origin, parent=(346, 37))
        page.element(m, 'MaterialName' + str(i), name, origin=origin, parent=(346, 37))
        page.element(m, 'MaterialCount' + str(i), f'{have} / {need}', origin=origin, parent=(346, 37))
    page.box((ox + 684, oy + 185, 430, 330), '#30445f')
    page.box((ox + 685, oy + 186, 428, 328), '#0b1423')
    chamber = (ox + 698, oy + 198)
    for x, y, w, h, color in [(46, 24, 310, 202, '#29405c'), (47, 25, 308, 200, '#0d192b'), (89, 44, 224, 162, '#473769'), (90, 45, 222, 160, '#142038'), (120, 207, 162, 3, '#507d90'), (183, 25, 2, 199, '#56aebc')]:
        page.box((chamber[0] + x, chamber[1] + y, w, h), color)
    for i, (x, y) in enumerate([(100, 71), (137, 170), (210, 50), (309, 82), (335, 187), (58, 153), (264, 193), (301, 213)]):
        page.box((chamber[0] + x, chamber[1] + y, 7, 7), '#a88fe3' if i % 2 else '#67e8ef')
    page.element(source, 'ForgePreview', item='SM_Hoverboard', origin=chamber, parent=(402, 250))
    page.element(source, 'ForgeQuantity', '1 x OUTPUT', origin=chamber, parent=(402, 250))
    page.element(source, 'Progress', 'CRAFTING   43%', origin=(ox + 685, oy + 186), parent=(428, 328))
    page.box((ox + 703, oy + 494, 392, 5), '#2c3551')
    page.box((ox + 703, oy + 494, 168, 5), '#67e8ef')
    page.element(source, 'Requirements', 'Materials reserved for Hoverboard. The chamber will finish this recipe even while you browse.', color='#a7dfe7')
    page.element(source, 'Message', 'Materials come from your inventory. Any wood plank type is accepted; capsules reserved for flight cannot be spent.')
    for selector, text in [('Toggle', 'PAUSE'), ('Craft', 'CRAFT'), ('Collect', 'COLLECT OUTPUT'), ('Close', 'CLOSE INSTRUMENT')]:
        page.button(source, selector, text)
    page.image.save(OUT / 'reality-forge-selection-preview.png')


def research():
    source = (UI / 'ResearchMachine.ui').read_text()
    row = (UI / 'ResearchNoteRow.ui').read_text()
    page = Preview(1032, 760, 'Research Machine note review')
    ox, oy = page.origin
    page.box((ox, oy, 1032, 760), '#2b3858')
    page.box((ox + 2, oy + 2, 1028, 756), '#0b1323')
    origin = (ox + 26, oy + 26)
    page.element(source, 'ResearchName', 'RESEARCH MACHINE / AWAITING NOTE', origin=origin, parent=(980, 708))
    page.button(source, 'Close', 'CLOSE', origin=origin, parent=(980, 708))
    page.element(source, 'Instability', 'INSTABILITY 50%', origin=origin, parent=(980, 708))
    page.box((origin[0], origin[1] + 65, 960, 9), '#26314a')
    page.box((origin[0], origin[1] + 65, 480, 9), '#51dce3')
    page.element(source, 'NotePicker', origin=origin, parent=(980, 708))
    picker = (origin[0], origin[1] + 92)
    page.label((picker[0] + 20, picker[1] + 15, 720, 28), 'CHOOSE A RESEARCH NOTE', 21, '#bba2ed', True)
    page.label((picker[0] + 20, picker[1] + 49, 718, 24), 'Select a note from your inventory, review its instruments, then insert it.', 13, '#9db0cb')
    page.button(source, 'RefreshNotes', 'REFRESH NOTES', origin=picker, parent=(960, 508))
    page.element(source, 'NoteCount', '5 unfinished notes in your inventory', origin=picker, parent=(960, 508))
    page.element(source, 'Notes', origin=picker, parent=(960, 508))
    for i, (name, item, state) in enumerate([('Gravity Anomalies', 'SM_Gravitic_Shard', '1 active instruments'), ('Energy Anomalies', 'SM_Energetic_Shard', '1 active instruments'), ('Hoverboard', 'SM_Hoverboard', 'SELECTED'), ('Resonance Condenser', 'SM_Resonance_Condenser', '2 active instruments')]):
        r = (picker[0] + 25, picker[1] + 114 + i * 81)
        page.box((*r, 317, 76), '#152138')
        page.element(row, 'NoteAccent', color='#78dfe9' if i == 2 else '#485070', origin=r, parent=(317, 76))
        page.element(row, 'NoteRowIcon', item=item, origin=r, parent=(317, 76))
        page.element(row, 'NoteName', name, color='#78dfe9' if i == 2 else '#d8deef', origin=r, parent=(317, 76))
        page.element(row, 'NoteState', state, origin=r, parent=(317, 76))
    page.box((picker[0] + 348, picker[1] + 117, 4, 363), '#1e2a42')
    page.box((picker[0] + 348, picker[1] + 117, 4, 308), '#506788')
    page.element(source, 'NoteDetails', origin=picker, parent=(960, 508))
    detail = (picker[0] + 378, picker[1] + 109)
    page.element(source, 'NoteIcon', item='SM_Hoverboard', origin=detail, parent=(562, 379))
    page.element(source, 'NoteTitle', 'Hoverboard', origin=detail, parent=(562, 379))
    page.element(source, 'NoteDescription', 'A personal transportation device that hovers above the ground using anti-gravity technology.', origin=detail, parent=(562, 379))
    page.label((detail[0] + 20, detail[1] + 183, 522, 21), 'ACTIVE INSTRUMENTS', 12, '#ac93d5', True)
    page.element(source, 'NoteDisciplines', 'Energy   |   Gravity', origin=detail, parent=(562, 379))
    page.element(source, 'NotePrerequisites', 'Ready to insert. Your note is used only when the experiment succeeds.', origin=detail, parent=(562, 379))
    page.button(source, 'InsertNote', 'INSERT SELECTED NOTE', origin=detail, parent=(562, 379))
    page.element(source, 'Message', 'Choose a research note from your inventory. Keep its active instruments stable to complete the experiment.', origin=origin, parent=(980, 708))
    page.image.save(OUT / 'research-note-selection-preview.png')


if __name__ == '__main__':
    OUT.mkdir(parents=True, exist_ok=True)
    forge()
    research()
    print(OUT / 'reality-forge-selection-preview.png')
    print(OUT / 'research-note-selection-preview.png')
