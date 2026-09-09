# Strange Matter 0.3.0

This revision addresses the second Hytale playtest report while preserving current manually edited art. Replace the previous mod JAR with build/libs/StrangeMatter-0.3.0.jar. The Aetherhaven-style Gradle asset-sync workflow remains available.

## Laboratory and progression

- **Resonant Burner:** accepts Hytale's Fuel resource, the same filter used by the native furnace. Positive FuelQuality alone no longer qualifies an item: native items default to 1 even when they are tools. One fuel item is consumed atomically; duration follows its resource quantity and fuel quality. Magazine capacity is checked before removal.
- **Old burner queues:** unburned non-fuel items are quarantined and returned through **Collect**, preserving metadata. Items already burned cannot be reconstructed because the old save no longer identifies them.
- **Laboratory Bench:** shard conversions, crystals, lamps and lanterns have a separate crafting category. Native recipe knowledge and the research ledger gate crafting. Existing completed research migrates into Hytale's known recipes on joining.
- **Reality Forge:** material cards display actual icons, inventory counts, required counts and exact deficits. Missing research and materials are listed together. Wood costs use resource:Wood_Planks, accept mixed native plank families, and display an available plank variant.
- **Crafting animation:** orbiting energy, scanning light, a floating output icon and a progress bar animate the coalescence chamber. The committed recipe remains visible while other recipes are browsed. Materials remain reserved until completion. Updates are coalesced while awaiting native ACKs; input continues through the existing page-specific transport. Recipe selection and transaction binding change together.
- **Research journal:** all 56 original teaching pages across 26 topics are restored. Open an unlocked topic to read its guide, current recipes and Hytale control notes. Back returns to the selected topic. Successful minigames display **0%** with an empty instability bar, without changing the gameplay threshold.
- **Commands:** native command collections and typed arguments provide standard help and autocomplete. Administrative unlocks persist, include prerequisites by default, and update native recipe knowledge.

| Command | Result |
|---|---|
| /sm | Native command help |
| /sm help | Progression guidance and command usage |
| /sm research unlock hoverboard | Unlock a topic and its prerequisites |
| /sm research unlock all | Unlock every topic for yourself |
| /sm research unlock all --player PlayerName | Unlock for another player |
| /sm research unlock hoverboard --strict | Reject missing prerequisites instead of granting them |

The shorter /sm unlock is also available. Administrative operations require strangematter.admin. Console commands must specify an online name or offline UUID.

## Anomalies, equipment and scientist

- Active Rift Stabilizers receive jagged electrical links from their energetic rift. Powered Resonance Condensers draw moving particles from each anomaly into the machine, with a distinct color per discipline. Effects are finite and emitted at a bounded cadence.
- Rifts use a declared native elemental damage cause. **Creative mode, worn Tinfoil Hats, native invulnerability and nearby enabled grounding still protect against rifts.** Disabled stabilizers no longer protect merely by existing. The enabled grounding callback reads an immutable machine snapshot to avoid cross-world lock inversion.
- The Graviton Hammer's primary mining strength stays at the Thorium baseline. Charged block damage scales to 2x/3x/4x. The charged forward shock also deals 20/30/40 base native entity damage, respecting armor, damage permissions, invulnerability and line of sight.
- Hoverboard entity size and mount anchor are doubled. Its movement configuration inherits native horse Mount settings, including steering and maximum-speed values previously omitted by the partial configuration. It uses the walking mount controller and no longer has its horizontal velocity erased every tick.
- New anomaly deposits include resonite and the anomaly's own shard ore where suitable stone exists. The generator recognizes more native geology and searches below deeper sediment. A minimum vein prevents unlucky rolls from omitting either enabled resource; an explicitly zero chance still disables it. **Only newly generated terrain receives these deposits. Existing explored chunks are not retrofitted.**
- All 15 scientist trades use **Life Essence**. The Klops scientist wears a lab coat, instrument belt, cyan monocle and twin specimen tanks. Existing ordinary scientist models migrate to the outfit while temporary appearance overrides are respected.

## Art and inventory

- **77 item icons at 64×64** were regenerated from current files. The renderer handles inherited shape offsets, UV mirrors/rotations, visibility, and native stone hosts for CubeWithModel ores.
- Six lamps are approximately two-block standing fixtures. Lanterns retain their hanging design.
- Raw resonite has a rough stone matrix with exposed cyan mineral fragments.
- Shade materials are spectral blue/teal, distinct from Gravitic violet. Related shards, crystals, ore, lights, icons and condenser streams follow this palette.
- The worn hat again has an empty native Head attachment. Edited brim/crown geometry remains as children, fitted just above the scalp. The preview uses the full native player rig.
- Materials and blocks stack to 100; fixtures, machines, blank notes and empty capsules to 25. Tools, worn equipment and filled capsules carrying individual anomaly identities remain single items.
- The creative tab uses a native root plus **All** child and dedicated flask artwork at native category dimensions. Item membership follows the child category. This repairs the structural discrepancy associated with the client null-reference crash; a connected-client click remains the final confirmation.

[Updated assets](art/targeted-playtest-revisions.png) · [Current item icons](art/current-item-icons.png) · [Scientist outfit](art/scientist-wardrobe.png) · [Hat fitting](art/tinfoil-player-fitting.png)

The pre-edit resource snapshot is build/backups/pre-0.3.0-resources-20260909-003438.zip, with a matching SHA256 manifest. The art audit found 319 of 416 original Common files unchanged and 97 changes confined to requested assets, category artwork and regenerated icons. The icons-only pass independently verified that none of its 274 model/texture/animation inputs changed. See tools/assets/manual-art-preservation.json and the safe workflow in [art-direction.md](art-direction.md).

## Verification

The final Java25/Gradle build passed. Hytale0.6.4 loaded the production JAR and native assets without Strange Matter warnings/errors. The isolated native-world run passed recipe knowledge migration, native crafting rejection/acceptance, valid UpdateRecipes/UpdateKnownRecipes wire packets, all429 usable furnace fuel assets, precise inventory consumption, recovery, Forge requirements, enabled grounding, rift health loss, invulnerability, all six mixed ore deposits, horse movement settings and doubled mount, charged damage and native damage protections, conduits, capsules, portals and temporal expiry.

Headless regressions include 20,000 energy scenarios, 1,050 seeded research experiments, 1,200 inputs through delayed native ACKs, targeted administrative unlocks and all56 teaching pages. Presentation, UI markup, recipes, particle/audio graphs, icon renderer and manual-art preservation checks passed. The packaged resources were compared byte-for-byte against the current source resources, with no test fixtures included in the release. Exact JAR size/hash and provenance are in build/release-verification-0.3.0.json; the native run is build/native-world-run/20260909-010113-630-f8ed13cc.

Headless/native-server tests cannot prove connected-client visual presentation, animated worn attachments, creative-tab clicks or keyboard-controlled riding. These remain explicit playtest checks rather than claimed live-client results. The isolated world harness also logs Hytale's existing bare-mode listener/shutdown errors; its explicit verification marker is required, and the separate production asset-validation run exits successfully.
