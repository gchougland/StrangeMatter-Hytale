# Scientist laboratories and original advancements

The authoritative Minecraft inputs are `AnomalyScientistTrades.java`, `VillageStructureAddition.java`, `anomaly_scientist_lab.nbt`, the thirteen advancement JSON files, their English translations, and the research machine's completed-category triggers. Original files are read only. The two scripts in `tools/worldgen` reproduce the converted prefab and advancement catalogue directly from those inputs; `scientist-lab-source.json` records the complete decoded original NBT for comparison.

## Laboratory

The source laboratory is **9 × 8 × 8**, with no loot chest or hidden research rewards. Its one scientist is a novice with zero merchant experience. The source positions are retained: Research Machine `(4,1,1)`, Stasis Projector `(6,2,1)`, two-cell bed beginning `(6,1,4)`, scientist `(5.832658,1,2.335622)`. Floors, columns, roof stairs/slabs, doors, the planter and twenty cyan panes preserve the source block layout. Six custom cyan lamps replace sea lanterns. The source jigsaw connector becomes its original final-state Resonite Pillar.

`Server/Prefabs/StrangeMatter/Anomaly_Scientist_Lab.prefab.json` is a native Hytale prefab. Custom glass and lamp models were made specifically for the laboratory, with original painted atlases and valid UVs. Native Emerald Temple wall torches replace Minecraft soul torches; their fire is green. The source cyan bed becomes the compact native Crude Bed: larger decorative native beds would collide with the original wall. The open trapdoor preserves its source direction, using the mod's native animated hinge. Native doors, furniture and tall machines receive their actual rotated filler cells during generated placement. A collision with another source block aborts the entire placement before any terrain mutation.

The source stasis block's stale captured-entity UUID and the villager's stale absolute-position memories are deliberately omitted. The NBT contains no corresponding anomaly entity. These are saved editor-world references, not portable laboratory content. The source scientist is represented by a custom `SM_Klops_Scientist` appearance on the native Klops rig and idle/watch behavior, with the Anomaly Scientist name and source-equivalent health fraction. It has no invented loot drops. Its custom split lab coat, cyclops safety goggle/magnifier, utility belt, badge and cyan/violet reagent tanks are bound to the native animation bones. Existing saved scientists using the old Klops appearance migrate on load; temporary disguises and unrelated cosmetic overrides are left alone.

Minecraft inserts this house into plains, desert, taiga, savanna and snowy village jigsaw pools with configurable **weight 6**. Hytale does not expose those Minecraft pools. The native adaptation therefore recognizes Kweebec or Village furniture in **newly generated chunks only**, tries a nearby flat, natural, dry, empty plot wholly within that chunk, and keeps laboratories at least roughly 128 blocks apart. It never plants wilderness ruins or edits existing chunks. The initial native placement lottery uses laboratory weight 6 and ordinary-plot weight 10, followed by up to 48 safe-plot attempts; this is explicitly a Hytale sampling choice and is **not numerically equivalent** to Minecraft village pool frequency. Custom generator settlements without those markers will not receive automatic labs. `setPlotWeights(int,int)` and `setGenerationEnabled(boolean)` allow integration tuning.

Register `ScientistService.onChunkPreLoad` at **EventPriority.FIRST**, before Hytale's EARLY block-entity preprocessor. The service modifies only the raw new-chunk section holders, fills collision cells and updates height maps. Native block entity creation then initializes the bed and other component-backed blocks normally. `tick(World,double)` subsequently spawns the one persistent scientist when that chunk is loaded. A saved entity UUID prevents duplicate scientists on chunk reload; death does not reset the merchant or respawn it. `spawnScientist(World,Vector3d)` is an explicit operator/integrator API; it places no building. Pasting only the prefab through an unrelated editor does not automatically enroll a scientist in this service.

## Trading

The native interaction hint is handled by `StateSupport.consumeInteraction`, and opens `ScientistPage`. The server rechecks merchant identity, unlocked offers, stock, inventory capacity, payment and six-block distance on every transaction. Players share the scientist's stock and training. The ordinary native barter UI cannot preserve source tier progression, so this page uses Hytale Custom UI with authoritative Java transactions.

Every pool entry retains its source counts, maximum uses and merchant experience. Each scientist selects two offers per unlocked tier, preserving its earlier selections. Selection is stable by scientist UUID. Merchant experience thresholds are 0, 10, 70, 150 and 250. The requested Hytale currency is native Life Essence (`Ingredient_Life_Essence`), replacing Minecraft emeralds for both buying and selling. Counts remain unchanged even when payment spans several native stacks. Offer IDs and existing stock/experience saves are unchanged.

| Tier | Input | Output | Uses | Merchant XP |
|---|---|---|---:|---:|
| Novice | 8 Life Essence | 4 Raw Resonite | 12 | 2 |
| Novice | 6 Raw Resonite | 1 Life Essence | 16 | 2 |
| Apprentice | 12 Life Essence | 3 Resonite Ingot | 12 | 5 |
| Apprentice | 2 Resonite Ingot | 1 Life Essence | 16 | 5 |
| Apprentice | 5 Life Essence | 9 Resonite Nugget | 16 | 5 |
| Journeyman | 20 Life Essence | 1 Resonant Coil | 8 | 10 |
| Journeyman | 16 Life Essence | 1 Gravitic Shard | 4 | 10 |
| Journeyman | 16 Life Essence | 1 Spatial Shard | 4 | 10 |
| Expert | 24 Life Essence | 1 Stabilized Core | 6 | 15 |
| Expert | 18 Life Essence | 1 Chrono Shard | 4 | 15 |
| Expert | 18 Life Essence | 1 Energetic Shard | 4 | 15 |
| Master | 30 Life Essence | 1 Resonant Circuit | 4 | 30 |
| Master | 20 Life Essence | 1 Shade Shard | 3 | 30 |
| Master | 20 Life Essence | 1 Insight Shard | 3 | 30 |
| Master | 48 Life Essence | 1 Resonite Block | 2 | 30 |

Restocking is twice per native game day while the living scientist is within six blocks of its laboratory Research Machine. Morning and afternoon time slots replace Minecraft's villager work schedule. The source demand rule `demand += uses - (maxUses - uses)` and price multiplier `0.05` apply on restock; base price is the floor and 64 the cap. The service does not fabricate Minecraft reputation, gossip, cure discounts or player experience orbs in Hytale. Rank changes are immediate rather than running Minecraft's villager promotion animation.

Hytale NPCs do not implement Minecraft's villager profession/POI reassignment. An ordinary Hytale villager therefore does not automatically become an anomaly scientist when a player places a Research Machine. Scientists are supplied through laboratories or the explicit native spawn API; this is a remaining profession-system adaptation.

## Advancements and journal

`ProgressionService` imports actual trigger predicates and nested requirement groups; translated descriptions do not override those predicates. It persists criterion progress and completed milestones in `advancements.properties`. Native notifications and world chat announce completion; `open(PlayerRef,Store)` presents the field journal. `snapshot(UUID)` exposes all thirteen milestone records for other UI integrations.

Callbacks are `joined(UUID)`, `firstContact(UUID)`, `scanned(UUID,ResearchType)`, `completedResearch(UUID,ResearchNode)` and `inventoryChanged(UUID,Collection<String>)`. `tick(World,double)` observes inventory once per second and handles the original survival root award. The normal welcome service owns the once-per-player Research Tablet; this service does not issue another tablet. Completion callbacks iterate the research node's paid disciplines, just as the original research machine does. Source advancement parents are presentation relationships, not extra earning prerequisites.

Notable source behavior preserved:

- **First Contact** is an advancement only. It awards no research currency.
- **Anomaly Collector** says “scan all six” but has one OR group containing all six criteria. Its actual source behavior completes after any one anomaly scan. This source bug is retained, and the remaining individual scan criteria are still recorded.
- **Knowledge Seeker** has six separate AND groups and requires completed research in every discipline.
- **The Researcher**, **Research Master**, **Portal Master** and the other equipment milestones check actual item acquisition, even where their descriptions mention research, crafting or use. Receiving the item also counts, matching the source predicates.
- **Anomaly Tamer** accepts any one of the six filled capsule types.
- None of the thirteen source advancement files specifies items, experience or research-point rewards. Exploration rewards come from finding the real laboratory equipment and its scientist's material trade progression.

## Verification and limits

`ScientistVerification` checks that all fifteen offers use native Life Essence, source stock limits and progression, stable offer selection, demand changes, restock rewind resistance, the source OR/AND milestone behavior, player isolation and persistence. It runs with the main gameplay verification suite. `validate_scientist_lab.py` independently checks native block IDs, source machine/window/lamp counts, rotated bounding boxes and filler collisions. Java compiles against the supplied installed Hytale server API.

Server asset loading and deterministic tests do not substitute for a connected client playtest. Native UI layout, NPC interaction response, actual settlement frequency and the complete furnished building still require validation in the target game build. The conservative generator can skip settlements when no safe plot fits; it never flattens occupied ground to force a laboratory into place.
