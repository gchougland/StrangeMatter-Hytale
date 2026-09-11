"""Static previews using shipped UI anchors and icons. Does not emulate the native client."""
from pathlib import Path
import importlib.util
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))
from preview_laboratory_ui import Preview, layout, UI, OUT


def inventory(page, origin, note=False):
    source = (UI / 'MachineInventoryPanel.ui').read_text(encoding='utf-8')
    ox, oy = origin
    page.box((ox, oy, 512, 278), '#091522')
    page.box((ox + 540, oy, 438, 278), '#091522')
    page.label((ox + 10, oy + 7, 234, 22), 'RESEARCH NOTE' if note else 'INGREDIENTS', 12, '#89edf5', True)
    if not note:
        page.label((ox + 268, oy + 7, 234, 22), 'FINISHED ITEMS', 12, '#b9a3ee', True)
    page.label((ox + 550, oy + 7, 414, 22), 'YOUR INVENTORY', 12, '#a8c6d8', True)
    page.label((ox + 550, oy + 219, 414, 16), 'HOTBAR', 10, '#7d9fb5', True)
    samples = ['SM_Resonite_Ingot', 'SM_Resonant_Circuit', 'SM_Gravitic_Shard', 'SM_Energetic_Shard']
    for selector, size, cols in [('MachineInputGrid', 1 if note else 15, 5), ('MachineOutputGrid', 0 if note else 5, 5), ('PlayerStorageGrid', 36, 9), ('PlayerHotbarGrid', 9, 9)]:
        a = layout.anchor(layout.block(source, selector))
        for n in range(size):
            x, y = ox + (540 if selector.startswith('Player') else 0) + a['Left'] + n % cols * 46, oy + a['Top'] + n // cols * 46
            page.box((x, y, 42, 42), '#334c62')
            page.box((x + 1, y + 1, 40, 40), '#142333')
            item = None
            if selector == 'MachineInputGrid' and n < 4:
                item = 'SM_Research_Notes' if note else samples[n]
            elif selector == 'MachineOutputGrid' and n == 0:
                item = 'SM_Resonant_Separator'
            elif selector == 'PlayerHotbarGrid' and n < 3:
                item = ['SM_Field_Scanner', 'SM_Research_Tablet', 'SM_Warp_Gun'][n]
            elif selector == 'PlayerStorageGrid' and n in (2, 6, 12, 13):
                item = samples[n % 4]
            if item:
                page.icon((x + 3, y + 3, 36, 36), item)
                if selector in ('MachineInputGrid', 'PlayerStorageGrid') and not note:
                    page.label((x + 22, y + 27, 18, 14), '15', 10, '#e1eafa')
    page.label((ox + 10, oy + 286, 968, 18), 'Move items between the machine, your inventory and your hotbar.', 11, '#809dad')


def factory():
    source = (UI / 'Factory.ui').read_text(encoding='utf-8')
    page = Preview(1140, 860, 'Reality Forge with native inventory slots')
    ox, oy = page.origin
    page.box((ox, oy, 1140, 860), '#101a2a')
    for selector, value in [('Title', 'REALITY FORGE'), ('Owner', 'Workshop owner: Explorer'), ('Tier', 'TIER 1'), ('RecipeCount', '8 discovered recipes'), ('Selected', 'Resonant Separator'), ('RecipeAccess', 'Research complete'), ('Stock', 'Keep 5 finished items'), ('Status', 'Working'), ('Progress', '43% complete'), ('Message', 'The finished machine will appear in the output slots.')]:
        page.element(source, selector, value, parent=(1140, 860))
    for selector, value in [('Search', 'Find a recipe'), ('Category', 'All'), ('Settings', 'SETTINGS'), ('Craft', 'CRAFT ONCE'), ('Stop', 'STOP JOB'), ('Repeat', 'Repeat off'), ('Toggle', 'Pause'), ('StockDown', 'LESS'), ('StockUp', 'MORE'), ('Close', 'CLOSE')]:
        page.button(source, selector, value, parent=(1140, 860))
    r = page.element(source, 'Recipes', parent=(1140, 860))
    for i, (name, item) in enumerate([('Resonant Separator', 'SM_Resonant_Separator'), ('Flux Furnace', 'SM_Flux_Furnace'), ('Pattern Assembler', 'SM_Pattern_Assembler')]):
        at = (r[0] + 5, r[1] + 5 + i * 68)
        page.box((*at, 262, 63), '#1a3046' if i == 0 else '#142238')
        page.icon((at[0] + 5, at[1] + 7, 48, 48), item)
        page.label((at[0] + 63, at[1] + 7, 194, 36), name, 14, '#d2e6f3', True)
        page.label((at[0] + 63, at[1] + 43, 194, 16), 'Selected' if i == 0 else 'Available', 11, '#83cdd3')
    for i, (item, label) in enumerate([('SM_Resonite_Ingot', 'Resonite Ingot    15 / 10'), ('SM_Resonant_Circuit', 'Resonant Circuit    5 / 2'), ('SM_Gravitic_Shard', 'Gravitic Shard    5 / 2')]):
        page.icon((ox + 337, oy + 162 + i * 38, 32, 32), item)
        page.label((ox + 378, oy + 172 + i * 38, 310, 24), label, 13, '#b8d8df')
    page.box((ox + 716, oy + 157, 398, 132), '#0b1728')
    page.element(source, 'Preview', item='SM_Resonant_Separator', parent=(1140, 860))
    page.box((ox + 899, oy + 173, 2, 98), '#67e8ef')
    pm = page.element(source, 'PowerMeter', color='#0a1422', parent=(1140, 860))
    page.label((pm[0] + 12, pm[1] + 8, 100, 23), 'CHARGE', 12, '#69ebf4', True)
    page.label((pm[0] + 105, pm[1] + 8, 400, 23), '6,500 / 10,000 RE', 12, '#d3edf5')
    page.box((pm[0] + 12, pm[1] + 31, 762, 12), '#31495f')
    for i in range(10):
        page.box((pm[0] + 14 + i * 76, pm[1] + 33, 72, 8), '#59d7e6' if i < 6 else '#142436')
    page.label((pm[0] + 12, pm[1] + 46, 600, 19), 'Working', 11, '#a1bdce')
    page.box((ox + 328, oy + 464, 786, 7), '#263551')
    page.box((ox + 328, oy + 464, 338, 7), '#67e8ef')
    inventory(page, (ox + 78, oy + 475))
    page.image.save(OUT / 'reality-forge-selection-preview.png')


def research():
    source = (UI / 'ResearchMachine.ui').read_text(encoding='utf-8')
    page = Preview(1036, 850, 'Research Machine with saved note slot')
    ox, oy = page.origin
    page.box((ox, oy, 1036, 850), '#2b3858')
    page.box((ox + 2, oy + 2, 1032, 846), '#0b1323')
    origin = (ox + 26, oy + 26)
    for selector, value in [('ResearchName', 'RESEARCH MACHINE'), ('Instability', 'PREPARE AN EXPERIMENT'), ('Message', 'Review the note, then keep its instruments stable to complete the research.')]:
        page.element(source, selector, value, origin=origin, parent=(984, 798))
    page.button(source, 'Close', 'CLOSE', origin=origin, parent=(984, 798))
    p = page.element(source, 'NotePicker', origin=origin, parent=(984, 798))
    page.label((p[0] + 20, p[1] + 15, 930, 28), 'PREPARE YOUR EXPERIMENT', 21, '#bba2ed', True)
    page.label((p[0] + 20, p[1] + 49, 930, 24), 'Place a note in the machine slot. Your note stays here until you take it back or finish its research.', 13, '#9db0cb')
    d = page.element(source, 'NoteDetails', origin=p[:2], parent=(984, 610))
    for selector, value in [('NoteTitle', 'Hoverboard'), ('NoteDescription', 'Ride above the ground with a field of strange gravity.'), ('NotePrerequisites', 'Ready to begin. Your note is used when this research succeeds.')]:
        page.element(source, selector, value, origin=d[:2], parent=(964, 196))
    page.element(source, 'NoteIcon', item='SM_Hoverboard', origin=d[:2], parent=(964, 196))
    page.label((d[0] + 488, d[1] + 14, 456, 21), 'ACTIVE INSTRUMENTS', 12, '#ac93d5', True)
    for i, name in enumerate(['Energy', 'Gravity']):
        page.label((d[0] + 520 + i * 152, d[1] + 45, 114, 24), name, 12, '#bcaad9')
        from PIL import Image
        im = Image.open(UI / 'Disciplines' / (name.lower() + '.png')).convert('RGBA').resize((24, 24))
        page.image.paste(im, (int(d[0] + 490 + i * 152), int(d[1] + 40)), im)
    page.button(source, 'ViewInstruments', 'REVIEW EXPERIMENT', origin=d[:2], parent=(964, 196))
    inventory(page, (p[0], p[1] + 294), note=True)
    page.image.save(OUT / 'research-note-selection-preview.png')


if __name__ == '__main__':
    OUT.mkdir(parents=True, exist_ok=True)
    factory()
    research()
    print(OUT / 'reality-forge-selection-preview.png')
    print(OUT / 'research-note-selection-preview.png')
