"""Surgical shared button migration. Factory and Tube pages remain owned by their layout builders."""
from pathlib import Path
import re

ROOT=Path(__file__).resolve().parents[2]
UI=ROOT/'src/main/resources/Common/UI/Custom/StrangeMatter'
EXCLUDED={'Factory.ui','AssemblerRecipeSelector.ui','FactoryRecipeSelector.ui','ForgeRecipeRow.ui','TubeConfig.ui','SharedButtons.ui'}


def migrate(text):
    revised=re.sub(r'\$C\.@(TextButton|CancelTextButton|SecondaryTextButton|Button|IconButton)\b',r'$B.@\1',text)
    if revised!=text and '$B = "SharedButtons.ui";' not in revised:
        revised='$B = "SharedButtons.ui";\n'+revised
    return revised


def apply():
    changed=[]
    for path in UI.glob('*.ui'):
        if path.name in EXCLUDED:continue
        text=path.read_text(encoding='utf-8-sig');new=migrate(text)
        if path.name=='ResearchStyle.ui':
            new='''$C = "../Common.ui";
$B = "SharedButtons.ui";

@Label = LabelStyle(FontSize: 14, TextColor: #d3ddf3, Wrap: true);
@Caption = LabelStyle(FontSize: 11, TextColor: #8197b9, Wrap: true);
@ControlLabel = $B.@SmallLabel;
@ControlStyle = $B.@ControlStyle;
@Control = TextButton { Style: @ControlStyle; Padding: (Horizontal: 5); TextTooltipStyle: $C.@DefaultTextTooltipStyle; };
@RowStyle = $B.@RowStyle;
'''
        if path.name=='ResearchTreeNode.ui':
            new=re.sub(r'@NodeStyle = (?:ButtonStyle\([^\n]+\)|\$B\.@\w+);',
                       '@NodeStyle = ButtonStyle(...$B.@TreeNodeStyle, Default: (Background: #000000(0)));',new)
            if '$B = "SharedButtons.ui";' not in new:new='$B = "SharedButtons.ui";\n'+new
        if new!=text:path.write_text(new,encoding='utf-8');changed.append(path.name)
    # This generator owns only the legacy forge document, not the new Factory page.
    generator=ROOT/'tools/assets/build_forge_ui.py'
    text=generator.read_text(encoding='utf-8-sig')
    new=re.sub(r'\$C\.@(TextButton|CancelTextButton)\b',r'$B.@\1',text)
    if '$B = "SharedButtons.ui";' not in new:new=new.replace('layout = \'\'\'$C = "../Common.ui";','layout = \'\'\'$B = "SharedButtons.ui";\n$C = "../Common.ui";')
    if new!=text:generator.write_text(new,encoding='utf-8');changed.append(generator.name)
    print('Shared button migrations:',', '.join(changed))


if __name__=='__main__':apply()
