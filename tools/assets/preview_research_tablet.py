"""Static previews of native tablet geometry and production Java router output.

Requires Pillow. Run ResearchTreeLayoutVerification with tools/assets/research-tree-layout.json
as its output argument after changing the Java layout. This does not render the native client.
"""
import json
from PIL import Image, ImageDraw
from preview_laboratory_ui import Preview, ROOT, UI, OUT, layout

POINTS = [('Cognition', 'Insight', 20), ('Energy', 'Energetic', 30), ('Gravity', 'Gravitic', 18), ('Shadow', 'Shade', 15), ('Space', 'Spatial', 22), ('Time', 'Chrono', 12)]
SPECIAL = {'research': 'Research_Notes', 'anomaly_types': 'Research_Tablet', 'anomaly_shards': 'Gravitic_Shard', 'gravity_anomalies': 'Gravitic_Shard', 'temporal_anomalies': 'Chrono_Shard', 'spatial_anomalies': 'Spatial_Shard', 'energy_anomalies': 'Energetic_Shard', 'shadow_anomalies': 'Shade_Shard', 'cognitive_anomalies': 'Insight_Shard', 'resonite': 'Raw_Resonite', 'resonant_energy': 'Resonant_Burner', 'reality_forge_category': 'Reality_Forge', 'containment_basics': 'Echo_Vacuum'}
SPECIAL.update({'gravitic_transport': 'Gravitic_Tube', 'resonant_separation': 'Resonant_Separator',
                'flux_smelting': 'Flux_Furnace', 'pattern_assembly': 'Pattern_Assembler'})
DISCIPLINES={'cognitive_anomalies':'cognition','energy_anomalies':'energy','gravity_anomalies':'gravity','shadow_anomalies':'shadow','spatial_anomalies':'space','temporal_anomalies':'time'}
PLAN = ROOT / 'tools/assets/research-tree-layout.json'


def icon(node):
    return 'SM_' + SPECIAL.get(node, '_'.join(s.capitalize() for s in node.split('_')))


def graph_image(plan, node_ui, known, selected=None):
    """Draw exact exported routes and native node geometry on a separate bounded canvas."""
    graph_ui = layout.block((UI / 'ResearchTablet.ui').read_text(), 'Graph')
    width = layout.anchor(graph_ui)['Width']
    node_size = layout.anchor(layout.block(node_ui, 'Node'))
    size = (node_size['Width'], node_size['Height'])
    graph = Preview.__new__(Preview)
    graph.image = Image.new('RGB', (width, plan['height']), '#091e26')
    graph.draw = ImageDraw.Draw(graph.image)
    graph.origin = (0, 0)
    for line in plan['traces']:
        color = '#45998d' if line['child'] in known else '#655779' if line['parent'] in known else '#263e4a'
        graph.box((line['x'], line['y'], line['width'], line['height']), color)
    for node in plan['nodes']:
        data = node['research']
        is_known, ready = data['id'] in known, set(data['prerequisites']) <= known
        color = '#70dfc3' if is_known else '#b39ade' if ready else '#516575'
        at = (node['x'], node['y'])
        graph.element(node_ui, 'NodeOutline', color='#8cffff' if data['id'] == selected else color, origin=at, parent=size)
        graph.element(node_ui, 'NodeFace', color='#194550' if data['id'] == selected else '#15383e' if is_known else '#102932', origin=at, parent=size)
        if data['id'] in DISCIPLINES:
            graph.element(node_ui, 'NodeDisciplineIcon', texture=UI/'Disciplines'/(DISCIPLINES[data['id']]+'.png'), origin=at, parent=size)
        else:
            item = icon(data['id'])
            if not (ROOT / 'src/main/resources/Common/Icons/ItemsGenerated' / (item + '.png')).is_file():
                raise FileNotFoundError(f'Missing research preview icon for {data["id"]}: {item}')
            graph.element(node_ui, 'NodeIcon', item=item, origin=at, parent=size)
        graph.element(node_ui, 'NodeLamp', color=color, origin=at, parent=size)
        graph.element(node_ui, 'NodeCaption', origin=at, parent=size)
        graph.element(node_ui, 'NodeName', data['name'], color='#a7ffff' if data['id'] == selected else '#a3bac8' if ready or is_known else '#617988', origin=at, parent=size)
    return graph.image


def full_forge():
    """An enlarged complete circuit for route review, including nodes below the scroll fold."""
    plan = json.loads(PLAN.read_text())['reality_forge']
    node_ui = (UI / 'ResearchTreeNode.ui').read_text()
    known = {node['research']['id'] for node in plan['nodes']}
    # Show the complete circuit unlocked so every wire is readable at the same contrast.
    graph = graph_image(plan, node_ui, known | {'reality_forge'}, 'gravitic_transport')
    zoom = 2
    enlarged = graph.resize((graph.width * zoom, graph.height * zoom), Image.Resampling.LANCZOS)
    page = Preview(enlarged.width, enlarged.height + 48, 'Reality Forge | Complete research circuit')
    page.image.paste(enlarged, page.origin)
    edges = {(line.get('source', line['parent']), line['child']) for line in plan['traces']}
    page.label((page.origin[0], page.origin[1] + enlarged.height + 12, enlarged.width, 28),
               f'{len(plan["nodes"])} topics   {len(edges)} connections   Full {graph.width} by {graph.height} canvas at 2x   All topics shown unlocked',
               14, '#a5bfcc')
    path = OUT / 'research-tablet-reality_forge-full-circuit-preview.png'
    page.image.save(path)
    print(path)
    return path


def render(category, selected):
    source = (UI / 'ResearchTablet.ui').read_text()
    node_ui = (UI / 'ResearchTreeNode.ui').read_text()
    point_ui = (UI / 'ResearchPoint.ui').read_text()
    all_plans = json.loads(PLAN.read_text())
    plan = all_plans[category]
    names = {n['research']['id']: n['research']['name'] for p in all_plans.values() for n in p['nodes']}
    known = {'research', 'field_scanner', 'anomaly_shards', 'anomaly_types', 'resonite', 'resonant_energy', 'gravity_anomalies', 'temporal_anomalies'}
    if category == 'reality_forge':
        known |= {'reality_forge', 'reality_forge_category', 'containment_basics', 'levitation_pad'}
    page = Preview(1232, 776, 'Research Tablet | ' + ('Foundations and anomalies' if category == 'general' else 'Reality Forge'))
    ox, oy = page.origin
    for x, y, w, h, color in [(0, 0, 1232, 776, '#26394a'), (3, 3, 1226, 770, '#111c2b'), (7, 29, 5, 682, '#2e224d'), (1220, 29, 5, 682, '#483466'), (27, 23, 42, 3, '#a59c55'), (76, 23, 8, 3, '#54b9ba'), (1199, 24, 4, 4, '#44b9bd'), (24, 31, 1184, 708, '#385057'), (26, 33, 1180, 704, '#071820'), (592, 752, 48, 3, '#375862')]:
        page.box((ox + x, oy + y, w, h), color)
    page.label((ox + 46, oy + 45, 395, 27), 'RESEARCH TABLET', 23, '#71e7e5', True)
    page.element(source, 'Scanned', '37 field observations recorded', parent=(1232, 776))
    for i, (name, shard, count) in enumerate(POINTS):
        origin = (ox + 478 + i * 119, oy + 44)
        page.element(point_ui, 'PointIcon', texture=UI/'Disciplines'/(name.lower()+'.png'), origin=origin, parent=(116, 43))
        page.element(point_ui, 'PointName', name, origin=origin, parent=(116, 43))
        page.element(point_ui, 'PointCount', str(count), origin=origin, parent=(116, 43))
    for name, text, x, width in [('General', 'FOUNDATIONS AND ANOMALIES', 46, 248), ('Forge', 'REALITY FORGE', 304, 222)]:
        page.box((ox + x, oy + 104, width, 35), '#6bd8d4' if name == 'General' and category == 'general' else '#b394df' if name == 'Forge' and category != 'general' else '#393752')
        page.button(source, name, text, origin=(ox + x, oy + 104), parent=(width, 35))
    page.label((ox + 546, oy + 112, 352, 18), 'Hover for details. Select a node to continue.', 11, '#8ea5b3')
    page.button(source, 'Journal', 'ACHIEVEMENTS', parent=(1232, 776))
    page.button(source, 'Close', 'CLOSE', parent=(1232, 776))
    page.box((ox + 46, oy + 153, 764, 542), '#304a51')
    page.box((ox + 47, oy + 154, 762, 540), '#091e26')
    page.element(source, 'CategoryTitle', 'FOUNDATIONS AND ANOMALIES' if category == 'general' else 'REALITY FORGE', origin=(ox + 47, oy + 154), parent=(762, 540))
    page.element(source, 'NodeCount', f"{sum(n['research']['id'] in known for n in plan['nodes'])} / {len(plan['nodes'])} unlocked", origin=(ox + 47, oy + 154), parent=(762, 540))
    bounds = layout.anchor(layout.block(source, 'GraphScroll'))
    graph_at = (ox + 47 + bounds['Left'], oy + 154 + bounds['Top'])
    graph = graph_image(plan, node_ui, known, selected)
    # Native TopScrolling clips its children. Never paint lower graph rows onto the frame or legend.
    visible = graph.crop((0, 0, min(graph.width, bounds['Width']), min(graph.height, bounds['Height'])))
    page.image.paste(visible, graph_at)
    if graph.height > bounds['Height']:
        track_x = graph_at[0] + bounds['Width'] - 7
        page.box((track_x, graph_at[1], 4, bounds['Height']), '#213b45')
        page.box((track_x, graph_at[1], 4, max(24, round(bounds['Height'] ** 2 / graph.height))), '#506788')
    page.element(source, 'Details', parent=(1232, 776))
    detail = (ox + 830, oy + 153)
    data = next(n['research'] for n in plan['nodes'] if n['research']['id'] == selected)
    page.element(source, 'DetailIcon', item=icon(selected), origin=detail, parent=(364, 542))
    page.element(source, 'NodeTitle', data['name'], origin=detail, parent=(364, 542))
    page.element(source, 'NodeStatus', 'READY TO RESEARCH', color='#b39ade', origin=detail, parent=(364, 542))
    page.element(source, 'Description', data['description'], origin=detail, parent=(364, 542))
    page.element(source, 'Prerequisites', 'Requires: ' + ', '.join(names[n] for n in data['prerequisites']), origin=detail, parent=(364, 542))
    balances = {name.upper(): count for name, shard, count in POINTS}
    cost_ui=(UI/'ResearchDisciplineCost.ui').read_text();index=0
    for name,shard,count in POINTS:
        if name.upper() not in data['costs']:continue
        at=(detail[0]+18,detail[1]+278+index*19);index+=1
        page.element(cost_ui,'CostIcon',texture=UI/'Disciplines'/(name.lower()+'.png'),origin=at,parent=(328,19))
        page.element(cost_ui,'CostLabel',f'{name}  {balances[name.upper()]} / {data["costs"][name.upper()]}',origin=at,parent=(328,19))
    page.button(source, 'Purchase', 'CREATE RESEARCH NOTE', origin=detail, parent=(364, 542))
    page.element(source, 'Message', 'Create a note, then complete its experiment at a Research Machine.', origin=detail, parent=(364, 542))
    for x, color, text, width in [(48, '#70dfc3', 'UNLOCKED', 108), (185, '#a992db', 'READY TO RESEARCH', 145), (359, '#516575', 'PREREQUISITE NEEDED', 180)]:
        page.box((ox + x, oy + 714, 7, 7), color)
        page.label((ox + x + 15, oy + 709, width, 19), text, 10, '#8ab1b6')
    path = OUT / f'research-tablet-{category}-preview.png'
    page.image.save(path)
    print(path)


if __name__ == '__main__':
    OUT.mkdir(parents=True, exist_ok=True)
    render('general', 'reality_forge')
    render('reality_forge', 'hoverboard')
    full_forge()
