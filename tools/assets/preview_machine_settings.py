"""Static settings previews using actual UI anchors, shared patches and item icons.

Requires Pillow. This is an offline layout review, not a native client screenshot.
"""
from pathlib import Path
import io
import re
import zipfile
from PIL import Image
from preview_laboratory_ui import Preview, layout, UI, OUT, ROOT
from build_shared_buttons import nine_slice

NATIVE = Path('C:/Users/gchou/AppData/Roaming/Hytale/install/release/package/game/latest/Assets.zip')


class SettingsPreview(Preview):
    def icon(self, rect, item):
        relative = 'Common/Icons/ItemsGenerated/' + item + '.png'
        local = ROOT / 'src/main/resources' / relative
        if local.exists():
            source = local
        else:
            with zipfile.ZipFile(NATIVE) as assets:
                source = io.BytesIO(assets.read(relative))
        icon = Image.open(source).convert('RGBA')
        icon.thumbnail((int(rect[2]), int(rect[3])), Image.Resampling.LANCZOS)
        self.image.paste(icon, (int(rect[0] + (rect[2] - icon.width) / 2), int(rect[1] + (rect[3] - icon.height) / 2)), icon)

    def button(self, source, selector, text, origin=None, parent=(1120, 820), theme='Secondary'):
        body = layout.block(source, selector)
        x, y, right, bottom = layout.rect(body, *parent)
        ox, oy = origin or self.origin
        patch = nine_slice(Image.open(UI / 'Buttons' / (theme + '_Default.png')).convert('RGBA'), right-x, bottom-y)
        self.image.paste(patch, (int(ox+x), int(oy+y)), patch)
        self.label((ox+x+8, oy+y+(bottom-y-18)/2, right-x-16, 24), text, 14, '#d8f0f4', True, 'Center')


def tube():
    source = (UI / 'TubeConfig.ui').read_text()
    page = SettingsPreview(1120, 820, 'Gravitic Tube connection settings')
    ox, oy = page.origin
    page.box((ox, oy, 1120, 820), '#0b1528')
    page.element(source, 'Title', 'GRAVITIC TUBE', parent=(1120, 820))
    page.button(source, 'Close', 'CLOSE', theme='Cancel')
    page.label((ox+26, oy+66, 1068, 24), 'Connected inventories start on SEND. Choose TAKE on the source. Tubes join automatically.', 13, '#a9bbd5')
    for i, name in enumerate(['East TAKE', 'West SEND', 'Up OFF', 'Down OFF', 'South OFF', 'North OFF']):
        page.button(source, f'Face{i}', ('> ' if i==0 else '') + name, theme='Action' if i==0 else 'Secondary')
    left = layout.block(source, 'ConnectionPanel')
    right = layout.block(source, 'FilterPanel')
    l = page.element(source, 'ConnectionPanel', parent=(1120, 820))
    r = page.element(source, 'FilterPanel', parent=(1120, 820))
    for selector, value in [('Selected', 'East connection'), ('Neighbor', 'Storage'), ('ModeHelp', 'TAKE pulls items from this container into the tube. Its connection glows cyan.'), ('HeldName', 'Resonite Ingot')]:
        page.element(left, selector, value, origin=l[:2], parent=(340,570))
    for selector, value, theme in [('Off','OFF','Cancel'),('Take','> TAKE','Action'),('Send','SEND','Secondary'),('Section','Storage','Secondary')]:
        page.button(left,selector,value,origin=l[:2],parent=(340,570),theme=theme)
    page.element(left,'HeldIcon',item='SM_Resonite_Ingot',origin=l[:2],parent=(340,570))
    for y, text in [(272,'CONTAINER SECTION'),(388,'YOUR HELD ITEM')]:
        page.label((l[0]+18,l[1]+y,304,22),text,13,'#93a9c6',True)
    page.label((l[0]+18,l[1]+488,304,58),'Samples copy the item you are holding. Your item is never consumed.',13,'#a9bbd5')
    page.box((l[0]+18,l[1]+370,304,1),'#36516b')
    page.label((r[0]+16,r[1]+18,680,24),'ITEM FILTER',18,'#bf9ff4',True)
    page.button(right,'Match','Same item',origin=r[:2],parent=(712,570))
    page.button(right,'Filter','Allow matching items',origin=r[:2],parent=(712,570))
    page.element(right,'FilterHelp','Click a sample to copy your held item. No samples means any item.',origin=r[:2],parent=(712,570))
    samples = layout.block(right,'SamplesGroup')
    origin = (r[0]+16,r[1]+148)
    for i in range(5):
        body = layout.block(samples,f'Sample{i}')
        a = layout.anchor(body)
        at=(origin[0]+a['Left'],origin[1])
        patch=nine_slice(Image.open(UI/'Buttons/Secondary_Default.png').convert('RGBA'),128,104)
        page.image.paste(patch,(int(at[0]),int(at[1])),patch)
        if i<2:
            item=['SM_Resonite_Ingot','SM_Resonant_Circuit'][i]
            page.element(body,f'Icon{i}',item=item,origin=at,parent=(128,104))
            name=['Resonite Ingot','Resonant Circuit'][i]
        else:
            page.element(body,f'SamplePrompt{i}','COPY',origin=at,parent=(128,104))
            name='Held item'
        page.element(body,f'SampleLabel{i}',name,origin=at,parent=(128,104))
        page.button(samples,f'Clear{i}','CLEAR',origin=origin,parent=(680,152))
    page.box((r[0]+16,r[1]+318,680,1),'#36516b')
    page.label((r[0]+16,r[1]+332,360,24),'TRANSFER LIMITS',18,'#bf9ff4',True)
    page.button(right,'Step','Stock step: 1',origin=r[:2],parent=(712,570))
    for i,(key,label,value) in enumerate([('Leave','Leave in source','0'),('Fill','Fill destination to','Unlimited'),('Priority','Destination priority','0'),('Batch','Items per batch','5')]):
        page.label((r[0]+16,r[1]+386+i*44,290,22),label,14,'#bdd0e4')
        page.button(right,key+'Minus','LESS',origin=r[:2],parent=(712,570))
        page.button(right,key+'Plus','MORE',origin=r[:2],parent=(712,570))
        x,y,x2,y2=layout.rect(layout.block(right,key+'Value'),712,570)
        page.label((r[0]+x,r[1]+y+10,x2-x,24),value,17,'#aeeeff',align='Center')
    page.element(source,'Message','Saved East connection. Item samples were not consumed.',parent=(1120,820))
    page.element(source,'Status','Ready to move items',parent=(1120,820))
    page.button(source,'Apply','APPLY CONNECTION',theme='Action')
    path=OUT/'tube-settings-preview.png';page.image.save(path);print(path)


def upgrade():
    source = layout.block((UI / 'Factory.ui').read_text(),'SettingsGroup')
    row = (UI / 'FactoryUpgradeCost.ui').read_text()
    page = SettingsPreview(1088,404,'Pattern Assembler upgrade costs')
    ox,oy=page.origin
    page.box((ox,oy,1088,404),'#152238')
    page.box((ox+362,oy+20,1,364),'#36516b')
    page.label((ox+20,oy+20,320,26),'SHARED ACCESS',18,'#72edf2',True)
    page.label((ox+20,oy+62,320,80),"Allow a player by name or UUID. Recipes still use the owner's discoveries.",14,'#adc0d5')
    page.element(source,'AccessName',origin=page.origin,parent=(1088,404),color='#081523')
    page.label((ox+30,oy+170,300,24),'Player name or UUID',14,'#718ca5')
    page.button(source,'Grant','ALLOW',parent=(1088,404),theme='Action')
    page.button(source,'Revoke','REMOVE',parent=(1088,404))
    page.label((ox+20,oy+280,320,88),'Upgrade materials come from your inventory and hotbar. The machine needs power while upgrading.',13,'#91a9bd')
    page.element(source,'UpgradeTitle','UPGRADE TO TIER 2',parent=(1088,404))
    rows=page.element(source,'UpgradeCosts',parent=(1088,404))
    for i,(item,name,have,need) in enumerate([('Ingredient_Bar_Copper','Copper Ingot',18,30),('Ingredient_Bar_Iron','Iron Ingot',20,20),('Ingredient_Fabric_Scrap_Linen','Linen Scraps',20,20),('SM_Resonant_Circuit','Resonant Circuit',1,2),('SM_Resonant_Coil','Resonant Coil',2,2)]):
        at=(rows[0]+6,rows[1]+6+i*48)
        page.box((*at,660,44),'#162438')
        page.element(row,'UpgradeIcon',item=item,origin=at,parent=(660,44))
        page.element(row,'UpgradeName',name,origin=at,parent=(660,44))
        page.element(row,'UpgradeCount',f'{have} / {need}',origin=at,parent=(660,44),color='#f0a6bd' if have<need else '#79f4ed')
        page.element(row,'UpgradeMissing',f'Need {need-have} more' if have<need else 'Ready',origin=at,parent=(660,44),color='#f0a6bd' if have<need else '#91c8bc')
    page.element(source,'UpgradeHelp','Gather the missing materials shown above.',parent=(1088,404))
    page.button(source,'Upgrade','UPGRADE',parent=(1088,404),theme='Action')
    path=OUT/'factory-upgrade-preview.png';page.image.save(path);print(path)


if __name__=='__main__':
    OUT.mkdir(parents=True,exist_ok=True)
    tube()
    upgrade()
