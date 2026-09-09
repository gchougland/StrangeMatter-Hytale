# Playtest revision 0.2.0

Implemented changes from the in-game playtest. Server-side and packet-level checks are complete; client-only limitations are described below.

- [x] Aetherhaven-style development server, syncAssets finalizer, runServerNoSync; round-trip verified.
- [x] All item icons 64 × 64; creative tab with icon and every mod item.
- [x] Positional anomaly audio with distance attenuation and spatial mono source clips.
- [x] Native finite particle effects and spatial sounds for all gadgets; cyan/violet warp endpoints.
- [x] Anomalous grass matches native terrain; no tilling action or hoe prompt.
- [x] Ores embedded in native stone, central shard blooms without platforms.
- [x] Filled capsule launch, durable inventory transaction and single-use identity.
- [x] Visible energetic-rift arc and body-scale damage impact.
- [x] Laboratory crafting bench; its recipe in Workbench, mod recipes at Laboratory.
- [x] Golden full-block time field; stasis pad with suspended item and holding beam.
- [x] Imprinter revert; working native mounted hoverboard lifecycle.
- [x] Keyed visual gadget HUD, reduced routine chat.
- [x] Levitation direction and full-height effects; toggles between ascent/descent.
- [x] Six-face conduit geometry matches actual network neighbors, including after reload.
- [x] Hammer all-plane 3 × 3 footprint and native Thorium-equivalent block damage/cadence.
- [x] Specific native interaction prompts; ordinary machine GUIs open.
- [x] Door/trapdoor initial state matches closed state and native rotation.
- [x] Research Close/Insert work through native events without animation ACK gating.
- [x] Research notes show topic and insert into machine; full-area minigame shutters.
- [x] Build, gameplay verification, asset validation, native world and recipe packet checks.

Client-only visual and audio behavior requires a Hytale playtest; server validation alone does not establish it.

## Using this revision

- Replace the old mod jar with `build/libs/StrangeMatter-0.2.0.jar`, keeping `mods/Hexvane_StrangeMatter` and its research, inventory and anomaly saves.
- Start `runServer` for the Aetherhaven-style asset-editor workflow. Changes in `build/resources/main` copy to `src/main/resources` when the server stops. Use `runServerNoSync` to omit the copy, or `syncAssets` to copy manually. The manifest is excluded.
- Craft the Laboratory Bench in the vanilla Workbench: one Workbench, four iron bars, two copper bars and six planks. Ordinary mod recipes now live there; furnace recipes and the research-gated Reality Forge retain their stations.
- Find every mod item in the Strange Matter creative category. All item icons are exactly 64 × 64.
- Echoform Imprinter: secondary or use restores normal form immediately, even when depleted. Crouch-primary also restores.
- Warp Gun: primary creates cyan endpoint A, secondary creates violet endpoint B; use clears the pair.
- Levitation Pad: open its controls to switch up/down; directional chevrons and end rings show the usable shaft.
- Gadgets display their icon, status and progress in a keyed native HUD layer. Routine feedback stays out of chat, and other mods' HUD layers remain intact.
- Research notes carry their experiment title and description. Use a note at a Research Machine to select its experiment, or select and insert it from the machine page.

## Audio and visual changes

Hytale exposes native distance attenuation; it is not an engine limitation. All six anomaly clips were stereo and used the partially diffuse default. The exported clips are now mono, `SpatialBlend` is 1, and attenuation begins at 1.5 blocks and reaches zero at 18 blocks. Gadget sound events are also positional. The conversion includes 44 original sound clips.

The revised native particle graph contains 41 finite systems: separate handheld flashes, beam traces, impacts, directional levitation markers, stasis beam/halo, and cyan/violet portal layers. Rift damage draws an arc to the victim's torso and a body-sized lightning corona. Muzzle effects spawn in front of the camera while authoritative aim remains at the eye.

The grass uses native terrain rendering without tilling, ores use Hytale's `CubeWithModel` stone host, shard tips bloom from a shared center, stasis uses a short pad, and time dilation is an opaque luminous golden force-field cube. Conduits choose only the connected arms and retain their machine identity across native state changes.

## Verification scope

Build checks cover 1,050 seeded experiments, 20,000 energy scenarios, all hammer orientations/depths, durable capsule ledgers, UI syntax, icons/atlases, creative membership, recipe stations, particle references and mono/spatial audio. Native packet regressions exercise Insert/Close through PacketAdapters and fallback decoding while ACKs are pending; 1,200 ordered inputs at simulated 1.2-second RTT pass without clearing the engine gate.

An isolated loaded world verifies all 64 conduit states through actual neighbor changes, 49 recipes through native UpdateRecipes serialization, note tooltip metadata round trips, a real owned NPC mount's MountNPC packet, Thorium-strength nine-block damage, and capsule reservation/save-gate/impact/replay behavior. The capsule fixture controls the inventory-save completion future; it does not claim to test a real connected player's disk save.

The actual Gradle sync task passed a source-to-build editor-change round trip with copying restricted to an owned temporary probe. Native asset loading passed without Strange Matter asset warnings.

No connected Hytale client was driven in this revision. The first-placement door/trapdoor pose, HUD/minigame layout, moving-listener sound falloff, particle appearance, mounted movement and disguise appearance still need the next in-game acceptance pass. Native mounting is supported and its server lifecycle is now verified; exact Minecraft water-hover physics remain an adaptation.
