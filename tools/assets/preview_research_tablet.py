"""Static previews of native tablet geometry and production Java router output.

Requires Pillow. Run ResearchTreeLayoutVerification with tools/assets/research-tree-layout.json
as its output argument after changing the Java layout. This does not render the native client.
"""
from pathlib import Path
import json
import re
from preview_laboratory_ui import Preview, ROOT, UI, OUT, layout

POINTS = [('Cognition', 'Insight', 20), ('Energy', 'Energetic', 30), ('Gravity', 'Gravitic', 18), ('Shadow', 'Shade', 15), ('Space', 'Spatial', 22), ('Time', 'Chrono', 12)]
SPECIAL = {'research': 'Research_Notes', 'anomaly_types': 'Research_Tablet', 'anomaly_shards': 'Gravitic_Shard', 'gravity_anomalies': 'Gravitic_Shard', 'temporal_anomalies': 'Chrono_Shard', 'spatial_anomalies': 'Spatial_Shard', 'energy_anomalies': 'Energetic_Shard', 'shadow_anomalies': 'Shade_Shard', 'cognitive_anomalies': 'Insight_Shard', 'resonite': 'Raw_Resonite', 'resonant_energy': 'Resonant_Burner', 'reality_forge_category': 'Reality_Forge', 'containment_basics': 'Echo_Vacuum'}
DISCIPLINES={'cognitive_anomalies':'cognition','energy_anomalies':'energy','gravity_anomalies':'gravity','shadow_anomalies':'shadow','spatial_anomalies':'space','temporal_anomalies':'time'}


def icon(node):
    return 'SM_' + SPECIAL.get(node, '_'.join(s.capitalize() for s in node.split('_')))


def render(category, selected):
    source = (UI / 'ResearchTablet.ui').read_text()
    node_ui = (UI / 'ResearchTreeNode.ui').read_text()
    point_ui = (UI / 'ResearchPoint.ui').read_text()
    plan = json.loads((ROOT / 'tools/assets/research-tree-layout.json').read_text())[category]
    all_plans = json.loads((ROOT / 'tools/assets/research-tree-layout.json').read_text())
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
    page.button(source, 'Journal', 'FIELD JOURNAL', parent=(1232, 776))
    page.button(source, 'Close', 'CLOSE', parent=(1232, 776))
    page.box((ox + 46, oy + 153, 764, 542), '#304a51')
    page.box((ox + 47, oy + 154, 762, 540), '#091e26')
    page.element(source, 'CategoryTitle', 'FOUNDATIONS AND ANOMALIES' if category == 'general' else 'REALITY FORGE', origin=(ox + 47, oy + 154), parent=(762, 540))
    page.element(source, 'NodeCount', f"{sum(n['research']['id'] in known for n in plan['nodes'])} / {len(plan['nodes'])} unlocked", origin=(ox + 47, oy + 154), parent=(762, 540))
    graph = (ox + 59, oy + 199)
    for line in plan['traces']:
        color = '#45998d' if line['child'] in known else '#655779' if line['parent'] in known else '#263e4a'
        page.box((graph[0] + line['x'], graph[1] + line['y'], line['width'], line['height']), color)
    for node in plan['nodes']:
        data = node['research']
        is_known, ready = data['id'] in known, set(data['prerequisites']) <= known
        color = '#70dfc3' if is_known else '#b39ade' if ready else '#516575'
        at = (graph[0] + node['x'], graph[1] + node['y'])
        page.element(node_ui, 'NodeOutline', color='#8cffff' if data['id'] == selected else color, origin=at, parent=(128, 68))
        page.element(node_ui, 'NodeFace', color='#194550' if data['id'] == selected else '#15383e' if is_known else '#102932', origin=at, parent=(128, 68))
        if data['id'] in DISCIPLINES:
            page.element(node_ui,'NodeDisciplineIcon',texture=UI/'Disciplines'/(DISCIPLINES[data['id']]+'.png'),origin=at,parent=(128,68))
        else:
            page.element(node_ui, 'NodeIcon', item=icon(data['id']), origin=at, parent=(128, 68))
        page.element(node_ui, 'NodeLamp', color=color, origin=at, parent=(128, 68))
        page.element(node_ui,'NodeCaption',origin=at,parent=(128,68))
        page.element(node_ui, 'NodeName', data['name'], color='#a7ffff' if data['id'] == selected else '#a3bac8' if ready or is_known else '#617988', origin=at, parent=(128, 68))
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
    render('general', 'reality_forge')
    render('reality_forge', 'hoverboard')
