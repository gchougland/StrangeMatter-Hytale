# Build verification - Strange Matter 0.2.0

Verified September 9, 2026 UTC with Java 25 and Hytale **0.6.4**, server revision `108a26d534805e68dcf0d00638914cb0db82555b`, and matching installed assets.

Release: `build/libs/StrangeMatter-0.2.0.jar` (2,610,907 bytes).

SHA-256: `00dac7a66b3bda586210f43be7d8915749a0286d5f0a672e49b5bcc87398eb3c`.

Test fixtures and sync probes are excluded from the distributable. Model saves that appeared during packaging were preserved and included in the final snapshot. Native world assertions ran on the final Java revision; the subsequent model-only updates passed production asset loading.

| Check | Result |
| --- | --- |
| Gradle build | PASS: Java, release/source archives, gameplay, recipes, UI, presentation and effects |
| Asset-editor workflow | PASS: real syncAssets round trip, editable resource classpath, finalizer, NoSync isolation, manifest exclusion and Windows JVM arguments |
| Presentation | PASS: 77 items, 86 exact 64px icons, 84 native-sized atlases, 118 particle spawners, 64 conduit masks, creative membership, recipe stations and keybinding hints |
| Presentation regressions | PASS: six cases including rejection of old 128px icons and 256 x 16 atlases |
| Effects/audio | PASS: 41 finite systems, 44 mono clips, valid graphs and fully spatial sound events |
| Recipes | PASS: 49 native recipes, 59 audited original recipes, 337 material references and exact six-shard resource membership |
| Recipe packet | PASS: 49 recipes in an actual 589,983-byte UpdateRecipes wire round trip |
| Research | PASS: 1,050 seeded experiments, original 26 nodes, costs, prerequisites, persistence and input validation |
| Native UI input | PASS: wire-decoded Insert/Close through PacketAdapters, extra fields, encoded action identity and raw fallback; 1,200 controls at simulated 1.2-second RTT and real cognition under pending ACK |
| HUD/UI lifecycle | PASS: keyed native icon/meter/error packets, unchanged-state suppression, other-mod HUD preservation, bounded/stale input and teardown |
| UI syntax | PASS: 11 templates, 354 selectors and five native-string regressions |
| Networks | PASS: 20,000 scenarios, conservation, shared capacity, cyclic/detour routes and all eleven forge gates |
| Native conduits | PASS: all 64 states through actual neighbor replacement, machine-family connection and persistent machine identity |
| Native notes | PASS: actual purchase, tooltip title/description, inventory packet round trip, legacy migration and token/unrelated metadata preservation |
| Equipment geometry | PASS: six hammer orientations, charged depths, unique 3x3 cells, precision mode, finite muzzle origins and depleted-imprinter restoration |
| Native hoverboard | PASS: owned-holder NPC lifecycle emits correct MountNPC wire packet |
| Native hammer | PASS: first Thorium-strength hit damages all nine downward stones, second hit breaks them |
| Native capsules | PASS: exact inventory reservation, stale-quantity rejection, full payload ledger, controlled inventory-save completion, moving block impact, nonce retirement and creative no-save path |
| Other gameplay | PASS: 1,000 chrono volumes, temporal expiry, anomaly persistence, scientist trades and original advancement logic |
| Native production assets | PASS: no Strange Matter asset/role warnings and normal validation shutdown |
| Isolated native world | PASS: all extended assertions plus gathering, 400 RE burner generation, forge completion and reserved metadata |

Evidence: `build/revision-build.log`, `build/asset-sync-verification.log`, `build/validation-run/asset-validation.log`, `build/native-world-run/native-world.log`, `build/native-world-run/native-world-result.txt`, and `build/reports/release-0.2.0.json`.

Existing user saves were not loaded. The isolated world uses `--bare` and opens no game port. The installed engine logs an unrelated ServerManager empty-listener assertion in that mode; world assertions still run to completion and the wrapper requires their explicit passing marker. Production asset validation completes normally. The capsule fixture controls the inventory-save completion future, rather than using an actual connected player's asynchronous disk save.

No connected Hytale client was driven. First-placement door/trapdoor appearance, native UI layout, audible falloff while moving, particle motion, mounted controls and replicated disguises still need the next client acceptance pass. The art sheets are offline renders. Native mounting and distance attenuation are supported; the setup has been corrected.

See [revision details](REVISION-0.2.0.md), [research](research-port.md), [equipment](equipment-port.md), [mobility](mobility-port.md), and [native world harness](native-world-verification.md).

Source edits continued after this snapshot; those files are listed in `build/reports/release-0.2.0.json` and remain preserved in source for the next build.
