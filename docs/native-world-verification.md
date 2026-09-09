# Native world smoke test

Run `./tools/verify-native-world.ps1` from the project directory. It builds the release jar and test classes, packages a separate test mod, seeds the test permission file, runs the installed Hytale server/assets and requires a passing result marker. Use `-SkipBuild` only after building the latest sources. Each run receives a new directory under `build/native-world-run`; the most recent result and log are also copied to that parent directory.

`src/test/java/com/hexvane/strangematter/equipment/NativeWorldVerification.java` is a test-only JavaPlugin entry point. It is excluded from the normal release jar. A separate test jar can combine the production jar and this compiled test class, replacing only its manifest's Main entry with `com.hexvane.strangematter.equipment.NativeWorldVerification` and declaring `Hytale:Universe` as a dependency. Keep `Group: Hexvane`, `Name: StrangeMatter` and `IncludesAssetPack: true` so production asset references load unchanged.

The test server runs in `build/native-world-run`, with only the test jar in its `mods` directory. `--bare` prevents default-world loading and network-port binding. The harness creates its own uniquely named flat world, disables NPC spawns, loads one chunk and dispatches its assertions on the world's own thread. A 90-second watchdog exits a stalled run. It writes `native-world-result.txt` and a `NATIVE_WORLD_VERIFICATION_PASSED` or `NATIVE_WORLD_VERIFICATION_FAILED` log marker, then shuts the server down.

On the supplied September 2026 server, first-run bare-mode permission loading fails because the engine closes its temporary writer before flushing it. Pre-create `build/native-world-run/permissions.json` containing `{"users":{},"groups":{}}` to avoid that unrelated engine initialization bug. The test does not edit permissions or worlds outside its isolated build directory.

The verified run used Java 25, the installed Hytale 0.6.4 server jar and its matching Assets.zip, with these server flags:

```text
--bare --assets <installed-game>/Assets.zip --disable-sentry --disable-file-watcher --auth-mode offline
```

On 2026-09-09 UTC, the harness passed actual native custom-block placement, native gathering item drops, 400 resonant energy from 20 fueled burner ticks, the complete chrono blob lifecycle, and MachineState JSON roundtrips preserving filled capsule nonces, nested BSON metadata, item quantities, fuel metadata and damaged-tool durability. It also consumed mixed hardwood/goldenwood plank ingredients, rejected reserved capsule ingredients, and completed a forge recipe using a real captured anomaly. The forge saved its output together with a spent-capsule receipt, retired that anomaly identity, and rejected stale copies after save/reload. Evidence is in `build/native-world-run/native-world.log` and `build/native-world-run/native-world-result.txt`.

These assertions exercise the world/ECS and native assets without a client. They do not validate UI rendering, mounted movement, client input, sounds as heard by a player, multiplayer contention, entity attacks inside stasis, portal traversal, or appearance synchronization.

The 0.1.1 harness additionally validates the initial `UpdateRecipes` packet after native wire serialization and decoding: all 48 Strange Matter recipes must have concrete primary outputs, valid output membership and resolvable item/resource ingredients. This regression failed on the 0.1.0 recipe data and passes on the corrected data; see [recipe-packet-fix.md](recipe-packet-fix.md).


## Revision 0.2.0 coverage

The wrapper now also packages `NativeEquipmentVerification` and `ResearchNoteVerification`. It verifies all64 conduit masks after real neighbor replacement, machine-state identity, fully positional sound packets, endpoint color persistence, actual MountNPC wire output, nine downward stones requiring two Thorium-strength hits, exact inventory reservation (including rejection of a stale quantity), capsule save gating/native impact/nonce retirement and the creative no-save path. Research note names, descriptions and token metadata survive the native inventory packet round trip and legacy-note migration. All49 native recipes survive a589,983-byte UpdateRecipes round trip.

The supplied server additionally logs a bare-mode ServerManager error because it skips binding listeners and then asserts a listener exists. The isolated world assertions execute to completion despite this engine issue, and the harness requires their explicit passing marker. This is not a claim of a clean connected server startup. The production asset-loader run completes normally.
