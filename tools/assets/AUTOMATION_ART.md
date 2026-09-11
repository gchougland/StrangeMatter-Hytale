# Automation artwork

The current models and textures are authoritative. The new set is separate from all previously edited artwork. Its first snapshot is `build/art-preservation/automation-20260911T032005134924Z/Resources.zip`; all 510 prior Common files were byte identical after authoring and visual review.

`automation-set.json` lists the 15 item IDs, their names and descriptions, tier models, texture bindings, working effects, local particle origins and interaction hints. Runtime integration owns recipes, inventories, processing and persistence. Three machine items use `BlockType.BlockEntity.Components.SM_Factory`. Gravitic Tubes use `SM_GraviticTube`.

All machine tiers share the 512 by 128 `Automation/automation.png` and `automation_working.png` atlases. Native signed UVs include mirrored backs, integer face sizes, fractional geometry stretch and painted swatches with padding. The eleven concentrates share `metal_concentrate.blockymodel`, with eleven 64 by 64 metal atlases. Every new inventory icon is 64 by 64.

States are `Working`, `Tier2`, `Tier2Working`, `Tier3`, and `Tier3Working`, as applicable. Each machine animation is 120 integer frames, or four seconds. Native working states own the animation and quiet positional hum. Completion events are separate, with one cue per completed batch. Sounds are original mono Vorbis, with four second hum loops and 0.38 second completion cues. They attenuate within seven blocks.

Tube state names are `Connection00` through `Connection63`, with bits in the order positive X, negative X, positive Y, negative Y, positive Z, negative Z. Tube geometry is world aligned. Unconnected faces have visible closed collars. Connected arms and central passages use opaque frames, without transparent shell textures. `SM_Tube_Field` and `SM_Tube_Transfer` provide finite translucent accents. Runtime owns protected item carrier displays.

The particle systems are finite 0.9 second pulses, with 3 to 9 particles per pulse. Emit only while work or transfer occurs, with an independent runtime budget. Machine particle origins are model-relative block offsets and must follow placement rotation, especially the two block wide Pattern Assembler.

## Current art workflow

Use an interpreter with Pillow and numpy for previews and icons. Standard validation needs Python only.

```powershell
python tools/assets/build_automation_set.py --preview
python tools/assets/build_automation_set.py --icons
python tools/validate_automation_art.py
python tools/test_automation_art.py
```

The preview and icons options read current saved models and textures. They do not run model generation. Previews are `docs/art/automation-machines.png`, `automation-concentrates.png`, and `automation-working-poses.png`. The pose preview illustrates sampled transforms; final interpolation and appearance require a client check.

`--create` refuses to overwrite an existing Automation model set. `--reset-new-set` is an explicit replacement of this set's models, atlases, items and presentation definitions, and takes a resource snapshot first. It is not a normal build step and must not be used after manual edits merely to update icons. It also replaces the set's item definitions, so preserve later runtime content changes independently. Audio generation accepts an ffmpeg build with libvorbis through `--ffmpeg`, or a native libsndfile DLL with Vorbis through `--sndfile`.

Normal validation checks current UVs, geometry, moving bounds, native animation targets, all tube masks, opacity, components, tier states, icons, finite particle budgets and positional audio. Historical byte comparison is optional through `--audit-art path/to/Common-before.json`, never part of routine builds.
