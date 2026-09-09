# Research conversion

The research system uses Hytale's native `InteractiveCustomUIPage` API. It does not require client-side Java, Noesis, or a separate client mod. `ResearchTabletPage` presents the original 26-node graph as browsable categories with names, descriptions, prerequisite states, exact six-currency costs, and purchased research notes. The Reality Forge category appears after its gateway unlocks; the proxy node does not charge the player twice.

`ResearchMachinePage` shows all six instruments together. A note activates only the disciplines in that node's cost map. All instruments continue running simultaneously. Physics and drift run on the server in batches of four original 20 Hz ticks, with visual updates up to 5 Hz on the owning world thread. An outstanding visual acknowledgement pauses simulation until that frame arrives, so a slow connection cannot skip memory cues or accumulate unseen instability. Player messages can operate named, bounded controls; they cannot claim success or set instability.

| Discipline | Original interaction retained | Native Hytale presentation |
| --- | --- | --- |
| Cognition | Recall a random sequence of unique symbols in a 3 by 3 grid; three symbols by default; 50 ticks per symbol, replay after 100 ticks, replace after 600 stable ticks | Nine numbered laboratory channels flash in sequence and accept the same ordered clicks. Numbered channels avoid relying on client font support for Minecraft's runic glyphs. |
| Energy | Adjust amplitude and period independently in 0.05 steps; both within 0.1 of the target for 100 ticks; drift after 600 further ticks | Purple reference and cyan live sine waves, four physical-looking adjustment buttons, stability hold readout |
| Gravity | Choose counterforce from -5 through +5 to balance a random nonzero gravity; damped cube physics, center tolerance 0.1, hold 100 ticks; new force after 1000 further ticks | A suspension tube, moving cube, purple center markers and eleven force detents |
| Shadow | Move a light through -60 to +60 degrees in 15-degree steps and change distance from 20 to 50; match shadow direction and length, tolerance 10; drift after 200 ticks | Movable light, physical cube, purple target shadow and cyan projected shadow |
| Space | Reduce image distortion with the original plus/minus 0.05 warp adjustment; stable below 0.1; random drift after 100 stable ticks | An animated, twisted calibration image made from 49 lattice points, progressively restored to a regular image |
| Time | Adjust clock speed by 0.1, match the reference within 0.15, snap within 0.05; reference drift after 200 stable ticks | A twelve-marker chronometer with cyan and purple moving hands and the original periodic timing lamp |

Instability starts at 50%. It falls by 0.004 per original tick only when every active instrument is stable; otherwise it rises by 0.001 divided by the number of active instruments. Research succeeds at 5% and fails at 95%. These original rates, control increments, drift delays, cognition difficulty and the global minigame toggle are configurable in the generated `research-config.json`.

Research observations, scan deduplication, unlocks, point spending and minted-note tokens are saved in an atomically replaced `research.properties` ledger under the plugin data directory. Research notes are actual `SM_Research_Notes` inventory items carrying a server-minted token. A full inventory does not spend points. The note remains in the inventory while the machine reserves an experiment, and is consumed only on successful completion. This preserves the original note-return-on-failure behavior and avoids losing a note when a player disconnects, closes the page, changes worlds, walks away, or the machine is removed. Removing or transferring the reserved note cancels the current experiment. Only one player can reserve a given machine, and only one experiment can reserve a researcher at a time.

Root integration:

```java
ResearchService research = new ResearchService(pluginDataDirectory);
research.openTablet(playerRef, store);
research.openMachine(playerRef, store, blockPosition); // org.joml.Vector3i
research.scan(playerUuid, naturalAnomalyUuid.toString(), ResearchType.ENERGY, 10);
research.hasUnlocked(playerUuid, "reality_forge");
research.close(); // plugin shutdown
```

The scanner caller is responsible for verifying hold duration, line of sight, anomaly range and natural origin before calling `scan`. The service makes observation rewards once-per-player-per-stable-anomaly-ID and persists that protection across restarts. Capsule-generated anomalies should never call the award method.

Validation performed: Java 25 compilation against the installed September 2026 Hytale server API, followed by `ResearchVerification`. It passed 1,050 seeded solvability simulations covering every purchased node and all six simultaneous instruments, maximum cognition difficulty, disabled minigames, unattended failure, malformed control rejection, exact key costs/prerequisites, unique sequences, scan deduplication, independent player awards and save/reload behavior. The native acknowledgement regression below also runs in the normal build.

The user opened the previous research page in a live client and reported nonresponsive Insert/Close controls and incomplete shutters. The playtest revision below addresses those reports; the revised client interaction and visual behavior still need an in-game acceptance pass. Research data has no KubeJS runtime dependency; Minecraft KubeJS extension scripts are not interpreted by this implementation.

## Animated UI acknowledgement handling

The supplied server's `PageManager.updateCustomPage` increments an acknowledgement counter for every frame. `PageManager.handleEvent` silently discards a `Data` event while that counter is nonzero. Merely slowing animation, sending one frame at a time, or setting `locksInterface=false` does not remove that server gate. Resetting the counter would make late acknowledgements throw an exception.

`LivePageTransport` addresses both sides without modifying the engine counter:

- Every Research Machine and ordinary machine button is bound with `locksInterface=false` and a random per-opening `SMPageNonce`. The same nonce is also carried inside the native `Action` string, so page identity remains available if a client omits extra custom fields. Note rows keep their original token bindings; animation never clears, re-appends or renumbers them.
- A scoped native inbound packet adapter intercepts only reserved nonced `Data`. It queues input to the owning world and rechecks the exact active page, entity reference and store before dispatch. The page then rechecks machine range, existence, inventory token, prerequisites and bounded control values. Native `Acknowledge` and `Dismiss` packets pass unchanged. A native fallback call cannot bypass the validated dispatcher.
- Input dispatch is independent of acknowledgement state. Closing, dismissing, disconnecting, changing world or replacing the page invalidates queued input; a new page receives a new nonce. JSON is limited to 1,024 characters with bounded string fields, the queue holds at most 128 events, one world callback drains at most 32, and a token bucket allows an 80-event burst then 40 events/second.
- Control debounce has its own monotonic 20 Hz clock, advanced before every live input and pulse. Its original one-tick or five-tick cooldown therefore stays approximately 50 ms or 250 ms while visual simulation is paused. Delayed visual acknowledgements cannot stretch a human's three-symbol memory entry into multi-second input lockouts. Standalone deterministic simulations still use their normal tick clock.
- An observational acknowledgement window follows initial/update `CustomPage` packets and `SetPage` transitions that close a custom page, including preceding page traffic. Visual pulses wait for that window to drain, coalescing state before building the next patch. Research advances its simulation only when that displayed frame has been acknowledged. The ordinary machine page uses the same transport at up to 2 Hz; its Craft binding carries the displayed recipe ID, so a stale click cannot craft a newly selected, unseen recipe.
- Hytale itself clears its acknowledgement counter immediately before sending `JoinWorld` during a world transition. The transport observes that packet, invalidates the old lease and discards its old observational window. An abandoned old-world frame therefore cannot freeze the next world's page. The mod never invokes the engine's counter-reset method.
- All frames still go through `PageManager.updateCustomPage`, synchronously on the correct world thread. There are no raw frame writes, fabricated acknowledgements, counter resets, or reflection in production. `ResearchService.close()` removes the packet adapters and invalidates leases.
- If a stopped world rejects a timer callback, the page cancels its pulse and releases both the machine and researcher reservations without touching the stopped ECS. A dismissed or disposed page cannot enqueue more simulation work.

`LivePageVerification` uses the actual native `PageManager`. Test-only reflection seeds its counter and a minimal page, reproduces the dropped-input bug, and then routes 1,200 ordered controls through 100 frames at a simulated 1.2-second RTT. Every normal acknowledgement drains without underflow, input never changes the counter, and only one frame remains outstanding. A separate test routes a real `ResearchSession` cognition sequence at 250 ms spacing while its frame remains unacknowledged: all three symbols succeed, immediate duplicates remain rejected, and unseen memory/instability/physics clocks remain paused. It also verifies previous-page/close/initial acknowledgement bursts, world-transfer reset boundaries, stale nonce rejection, queued input after replacement/dismissal, unrelated text that mentions the nonce field, payload limits, and bounded input flooding. `ResearchVerification` verifies that a rejected world executor and normal dismissal each release real service reservations. These prove the server gate is bypassed safely; a live client still must confirm layout and control feel.

## Playtest control, note and instrument feedback revision

The previous event decoder required exactly three string fields, and the inherited native raw handler could decode an event only to have the typed handler reject it outside a bridge dispatch. The decoder now reads the owned fields from a native event envelope, tolerates unrelated envelope fields and empty optional values, and accepts the embedded page identity in `Action`. Both animated pages override the raw native handler and re-enter the same bounded, nonce-validated world queue. This fallback does not bypass any page, machine or inventory validation. No raw packet capture was supplied from the failed client session, so these identified silent-drop paths are not presented as a captured diagnosis of that session.

The expanded regression uses an actual `GamePacketHandler`, real `PacketAdapters`, real `PageManager` and wire-serialized/deserialized `CustomPageEvent`. Insert preserves its note token, and Close emits the native `SetPage` even with a prior frame acknowledgement still pending. Ordinary acknowledgements then drain normally. It also exercises the native raw fallback and keeps the delayed-connection tests above. Constructor bypass and field reflection exist only in the isolated test fixture; production uses native APIs.

Each unused discipline now has an opaque shutter spanning its entire instrument panel, including the title and status. Active panels retain their original controls and simulation. Original positional sounds play when the machine opens, accepts/rejects a note, begins research, succeeds or fails; visual refreshes never play sounds.

Research notes use Hytale's `ItemDisplayMetadata` name/description overrides together with the working native `TranslationProperties` metadata path used by Aetherhaven. Their inventory tooltip names the actual experiment and explains machine insertion. Existing minted notes acquire this display metadata without changing their identity or other metadata. Using notes while aiming within range at a Research Machine selects that exact note; elsewhere, use displays a brief instrument hint and leaves the archive closed. Notes still consume observations when purchased and consume the actual note only after successful research. `ResearchNoteVerification` checks native display resolution, the transmitted inventory metadata and legacy-note token preservation after native item assets load.

`GadgetHudService` uses the native keyed `SM_Gadget_Hud` layer. A dark navy panel displays the held item's native icon, a cyan/purple instrument state, a real acquisition progress meter, contextual controls and pink error states. Routine refreshes expire when the gadget is no longer held, while brief result notices remain visible long enough to read. Identical readouts produce no extra packet. The HUD adds/removes only its own key and never resets another mod's HUD. `GadgetHudVerification` round-trips the actual native HUD packet and checks icon, meter, error color, update deduplication and preservation of another HUD layer.

## Research catalog customization

An optional `research-catalog.json` beside `research.properties` adds research nodes or partially overrides existing nodes on startup. The original 26 entries remain present. Each service builds a complete immutable catalog before accepting it; a malformed cost, unknown prerequisite, duplicate node, unsupported discipline/category or prerequisite cycle rejects the configuration without partially changing the existing catalog. Omitted fields preserve the original values. Costs are integer observations from 0 to 10,000; a zero removes that discipline, and an empty cost map means the node starts unlocked. Supported tablet categories are `general` and `reality_forge`.

```json
{
  "nodes": [
    { "id": "hoverboard", "costs": { "gravity": 12, "energy": 15 } },
    {
      "id": "advanced_containment",
      "category": "reality_forge",
      "name": "Advanced Containment",
      "description": "A pack-specific extension of the containment curriculum.",
      "costs": { "space": 20, "shadow": 10 },
      "prerequisites": ["containment_basics"]
    }
  ]
}
```

This supplies a declarative replacement for the original custom research-node registry. It does not execute KubeJS scripts or add new research disciplines, category layouts, custom game engines, crafting recipes or runtime JavaScript callbacks. Other Hytale plugins can use `service.node(id)`, `service.nodes()` and `service.hasUnlocked(playerUuid, id)` to integrate configured nodes.

`setScanHook(BiConsumer<UUID, ResearchType>)` runs once after a fresh scan commits. `setCompletionHook(BiConsumer<UUID, ResearchNode>)` runs after a successful experiment consumes its note and persists the unlock. The completion node exposes its costs for discipline-specific milestones. Hook failures are logged without undoing the already committed research transaction. The headless verification also covers extension overrides, instance isolation, invalid-catalog rejection and scan-hook deduplication.

## Native recipe knowledge and administration

Native crafting now requires recipe knowledge for Strange Matter crafting recipes. `ResearchRecipeBridge` reconciles Hytale's persisted `PlayerConfigData.KnownRecipes` with the authoritative research ledger on player readiness, successful research and online administrative unlocks. This also migrates existing completed research when an older save is first joined. It records both the primary output item ID used by native `CraftingManager` and the recipe ID used by native diagram windows. It generates the client update through `CraftingPlugin.sendKnownRecipes`; unrelated vanilla/mod knowledge is preserved. Native processing/furnace recipes do not support `KnowledgeRequired` and retain their normal processing rules.

Foundation researches grant the tablet, laboratory, scanner, resonite building/material, basic resonant-energy and shard decoration recipes. Named advanced instruments require their corresponding research. The custom Reality Forge uses the same named requirement lookup, with containment basics covering the vacuum and empty capsule. `ResearchCraftGate` cancels native pre-craft events when the research ledger lacks the required topic, even if someone separately grants native recipe knowledge. This prevents stale knowledge or a generic recipe-learning command from bypassing the research requirement.

`/sm` is a native command tree with subcommand suggestions, typed arguments and native `--help`. Public commands are `/sm journal` (alias `tablet`), `status`, `milestones` and `help`. Administrative commands require `strangematter.admin`:

- `/sm research unlock hoverboard` unlocks that topic and any missing prerequisites for yourself.
- `/sm research unlock all --player PlayerName` unlocks the complete configured catalog for an online player.
- `/sm research unlock warp_gun --player <UUID> --strict` targets a stored/offline UUID and requires its prerequisites to be complete. Without `--strict`, prerequisites are included atomically. Offline native recipe discovery synchronizes on next join.
- `/sm unlock ...` is a shorter alias for the same administrative operation.
- `/sm points energy 25 --player PlayerName` grants observations; omitting the target selects yourself. Console invocations must supply a target.
- `/sm spawn GRAVITY`, `/sm locate --type GRAVITY`, `/sm kit`, `/sm scientist` and `/sm save` retain the laboratory utilities.

Unlocks persist before completion hooks run, do not spend or grant observations, and are idempotent. The native target argument suggests online names and also accepts explicit offline UUIDs. The node argument suggests the current configurable catalog plus `all` and rejects unknown IDs before execution. Public recipe integrations can call `requiredResearchForItem(itemId)`, `researchName(nodeId)`, `unlock(uuid,nodeOrAll,includePrerequisites)` and `syncRecipes(playerRef,store)`.

## Restored teaching pages and completion display

Selecting an unlocked topic now opens its field guide; the archive's selected unlocked topic also offers **READ FIELD GUIDE**. All 56 built-in pages across the 26 topics are imported from the original `ResearchNodeInfoScreen` and English teaching text. Hard line breaks from the narrow Minecraft panel are reflowed while retaining paragraphs and lists. The Hytale page has previous/next controls, a scrolling body, native output icons and current Hytale recipe ingredients/output quantities. A separate Hytale controls note explains adaptations such as scanner charging, capsule use and native mounts. Original Minecraft screenshots are not presented as Hytale screenshots. `tools/export_research_pages.py` reproducibly imports the text from the supplied original source without modifying it.

Successful experiments display an empty instability meter and **0%**. The simulation still succeeds at its original internal threshold below 5%; no cooldown, difficulty, instability rate or success/failure balance changed. Native recipe update failures after completion cannot misreport the committed research as a failed save; the ledger remains authoritative and the next join retries recipe discovery.

`ResearchProgressionVerification` passes prerequisite rejection/atomic unlock, individual/all targeting, idempotent hooks, save/reload, all 56 teaching pages, native command metadata/suggestions and a solved minigame with unchanged internal threshold and zero-percent presentation. `ResearchUnlockVerification` runs in the native world harness and checks actual `CraftingManager` rejection/acceptance, native pre-craft cancellation, both knowledge ID forms, migration and the real wire-encoded `UpdateKnownRecipes` packet. The supplied test fixtures bypass only the absent socket/player setup; production uses no reflection.
