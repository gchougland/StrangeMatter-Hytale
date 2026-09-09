"""Import exact source criteria and requirement groups; never infer intent from display text."""
import json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
SOURCE=Path(r'C:/Users/gchou/Documents/Projects/StrangeMatter-1.20.1/strange-matter/src/main/resources')
language=json.loads((SOURCE/'assets/strangematter/lang/en_us.json').read_text())
types={'gravity_anomaly':'gravity','temporal_bloom':'time','warp_gate_anomaly':'space','energetic_rift':'energy','echoing_shadow':'shadow','thoughtwell':'cognition'}
def item(name):return 'SM_'+'_'.join(p.title() for p in name.split(':')[-1].split('_'))
out=[]
for path in sorted((SOURCE/'data/strangematter/advancements').glob('*.json')):
    data=json.loads(path.read_text());display=data['display'];criteria={}
    for name,c in data['criteria'].items():
        trigger=c['trigger'].split(':')[-1];condition=c.get('conditions',{});values=[]
        if trigger=='impossible':trigger='joined'
        elif trigger=='inventory_changed':values=[item(i) for predicate in condition['items'] for i in predicate['items']]
        elif trigger=='scan_anomaly':values=[types[condition['anomaly_type'].split(':')[-1]]]
        elif trigger=='complete_research_category':values=[condition['research_category']]
        elif trigger!='anomaly_effect_applied':raise ValueError(trigger)
        criteria[name]={'trigger':trigger,'values':values}
    out.append({'id':path.stem,'parent':data.get('parent','').split(':')[-1],
                'title':language[display['title']['translate']],'description':language[display['description']['translate']],
                'icon':item(display['icon']['item']),'toast':display.get('show_toast',True),'chat':display.get('announce_to_chat',True),
                'criteria':criteria,'requirements':data.get('requirements',[[name] for name in criteria])})
dest=ROOT/'src/main/resources/Server/StrangeMatter/Progression/advancements.json';dest.parent.mkdir(parents=True,exist_ok=True)
dest.write_text(json.dumps(out,indent=2)+'\n',encoding='utf8')
print(f'Converted {len(out)} advancements; no source rewards present.')
