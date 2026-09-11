"""Build the native Forge layout; geometry and ItemIcon widgets need no raster regeneration."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

def render():
    slots = []
    for i in range(8):
        x, y = 0, i * 41
        slots.append(f'''Group #Material{i} {{ Anchor: (Left: {x}, Top: {y}, Width: 346, Height: 37); Background: #18263b;
      Group #MaterialAccent{i} {{ Anchor: (Left: 0, Top: 0, Width: 3, Bottom: 0); Background: #67e8ef; }}
      ItemIcon #MaterialIcon{i} {{ Anchor: (Left: 8, Top: 2, Width: 32, Height: 32); ItemId: "SM_Resonite_Ingot"; }}
      Label #MaterialName{i} {{ Anchor: (Left: 48, Top: 2, Width: 182, Height: 33); Style: (FontSize: 12, TextColor: #d0ddec, Wrap: true, VerticalAlignment: Center); }}
      Label #MaterialCount{i} {{ Anchor: (Left: 235, Top: 2, Width: 103, Height: 33); Style: (FontSize: 12, TextColor: #67e8ef, RenderBold: true, HorizontalAlignment: End, VerticalAlignment: Center); }}
    }}''')
    motes = '\n'.join(f'Group #ForgeMote{i} {{ Anchor: (Left: 0, Top: 0, Width: 7, Height: 7); Background: #67e8ef; HitTestVisible: false; }}' for i in range(8))
    layout = '''$B = "SharedButtons.ui";
$C = "../Common.ui";
$C.@PageOverlay {}
Group {
  Anchor: (Width: 1140, Height: 754); Background: #101a2a;
  Group { Anchor: (Left: 0, Top: 0, Width: 4, Bottom: 0); Background: #7651ad; }
  Label #Title { Anchor: (Left: 26, Top: 22, Width: 790, Height: 35); Text: "REALITY FORGE"; Style: (FontSize: 26, TextColor: #72edf2, RenderBold: true); }
  Label { Anchor: (Left: 26, Top: 61, Width: 720, Height: 19); Text: "Create equipment from materials and anomaly shards"; Style: (FontSize: 13, TextColor: #aa8fce); }
  Label #State { Anchor: (Right: 26, Top: 28, Width: 200, Height: 28); Style: (FontSize: 14, TextColor: #bf9be9, HorizontalAlignment: End, RenderBold: true); }
  Group { Anchor: (Left: 26, Top: 88, Width: 1088, Height: 1); Background: #36516b; }
  Label { Anchor: (Left: 26, Top: 105, Width: 265, Height: 24); Text: "RECIPES"; Style: (FontSize: 16, TextColor: #72edf2, RenderBold: true); }
  Label #RecipeCount { Anchor: (Left: 26, Top: 134, Width: 265, Height: 20); Style: (FontSize: 12, TextColor: #93a9c6); }
  Group #Recipes { Anchor: (Left: 26, Top: 162, Width: 268, Height: 517); Background: #0b1423; Padding: (Full: 5); LayoutMode: TopScrolling; ScrollbarStyle: $C.@DefaultScrollbarStyle; }
  Label #NoRecipes { Anchor: (Left: 44, Top: 194, Width: 225, Height: 90); Text: "Your discoveries will appear here."; Style: (FontSize: 15, TextColor: #93a9c6, Wrap: true, HorizontalAlignment: Center); HitTestVisible: false; Visible: false; }
  Label #Recipe { Anchor: (Left: 318, Top: 104, Width: 670, Height: 31); Style: (FontSize: 23, TextColor: #e1eafa, RenderBold: true); }
  Label #RecipeIndex { Anchor: (Right: 26, Top: 111, Width: 118, Height: 20); Style: (FontSize: 13, TextColor: #93a9c6, HorizontalAlignment: End); }
  Label #Research { Anchor: (Left: 318, Top: 143, Width: 796, Height: 31); Style: (FontSize: 13, TextColor: #ba9bef, Wrap: true); }
  Group { Anchor: (Left: 318, Top: 185, Width: 346, Height: 330);
    SLOTS
  }
  Group { Anchor: (Left: 684, Top: 185, Width: 430, Height: 330); Background: #30445f; Padding: (Full: 1);
    Group { Background: #0b1423;
      Group #ForgeChamber { Anchor: (Left: 13, Top: 12, Width: 402, Height: 250); HitTestVisible: false;
        Group { Anchor: (Left: 46, Top: 24, Width: 310, Height: 202); Background: #29405c; Padding: (Full: 1);
          Group { Background: #0d192b; }
        }
        Group { Anchor: (Left: 89, Top: 44, Width: 224, Height: 162); Background: #473769; Padding: (Full: 1);
          Group { Background: #142038; }
        }
        Group { Anchor: (Left: 120, Top: 207, Width: 162, Height: 3); Background: #507d90; }
        Group #ForgeScan { Anchor: (Left: 48, Top: 25, Width: 2, Height: 199); Background: #56aebc; Visible: false; }
        MOTES
        ItemIcon #ForgePreview { Anchor: (Left: 153, Top: 67, Width: 96, Height: 96); ItemId: "SM_Resonite_Ingot"; }
        Label #ForgeQuantity { Anchor: (Left: 140, Top: 168, Width: 122, Height: 25); Style: (FontSize: 14, TextColor: #d2e5f5, HorizontalAlignment: Center, RenderBold: true); }
      }
      Label #Progress { Anchor: (Left: 18, Top: 270, Width: 392, Height: 22); Style: (FontSize: 13, TextColor: #a7dfe7, HorizontalAlignment: Center); }
      Group { Anchor: (Left: 18, Top: 308, Width: 392, Height: 5); Background: #2c3551;
        Group #ForgeFill { Anchor: (Left: 0, Top: 0, Width: 1, Height: 5); Background: #67e8ef; }
      }
    }
  }
  Label #Requirements { Anchor: (Left: 318, Top: 532, Width: 796, Height: 58); Style: (FontSize: 13, TextColor: #f0a6bd, Wrap: true); }
  Label #Message { Anchor: (Left: 318, Top: 591, Width: 796, Height: 37); Style: (FontSize: 13, TextColor: #96acc7, Wrap: true); }
  $B.@TextButton #Toggle { @Text = "PAUSE"; Anchor: (Left: 318, Top: 637, Width: 122, Height: 42); }
  $B.@TextButton #Craft { @Text = "CRAFT"; Anchor: (Left: 452, Top: 637, Width: 320, Height: 42); }
  $B.@TextButton #Collect { @Text = "COLLECT OUTPUT"; Anchor: (Left: 784, Top: 637, Width: 330, Height: 42); }
  $B.@CancelTextButton #Close { @Text = "CLOSE INSTRUMENT"; Anchor: (Left: 26, Top: 695, Width: 1088, Height: 36); }
}
'''.replace('SLOTS', '\n'.join(slots)).replace('MOTES', motes)
    return layout

def main():
    (ROOT/'src/main/resources/Common/UI/Custom/StrangeMatter/RealityForge.ui').write_text(render(),encoding='utf8')

if __name__ == '__main__':
    main()
