# 0.1.1: world loading and UpdateRecipes

The 0.1.0 client failed while handling packet 60, `UpdateRecipes`. The client log at September 8, 2026, 21:52:15 local time records a `NullReferenceException` during recipe asset synchronization, before the world finishes loading.

Two conversion defects reached the actual packet:

- Three standalone recipes (`SM_Raw_Resonite`, `SM_Resonite_Ingot_From_Block`, `SM_Resonite_Ingot_From_Nuggets`) supplied `Output` without `PrimaryOutput`. Hytale fills that field automatically for embedded item recipes, but its standalone recipe codec does not infer it from `Output`. These three packets consequently contained a null primary output. All 403 supplied native standalone recipe assets specify that field.
- Four embedded recipes (Anomaly Resonator, Field Scanner, Reality Forge and Stabilized Core) treated Minecraft's `strangematter:anomaly_shards` tag as a literal item named `SM_Anomaly_Shards`. That item does not exist. The tag means any of the six actual anomaly shard items.

Version 0.1.1 gives standalone recipes explicit primary outputs and maps source ingredient tags to native resource families. `SM_Anomaly_Shards` is now a resource type with exactly the six original shard members and an icon under the required `Icons/ResourceTypes/` path. Recipe quantities remain unchanged. Wood plank tags also retain their native `Wood_Planks` resource-family meaning in the audited forge catalog.

## Regression evidence

`NativeWorldVerification` now constructs the real initial `UpdateRecipes` packet from Hytale's loaded recipe registry, serializes it with the native protocol, decodes the resulting bytes and inspects every Strange Matter recipe. It validates primary outputs, output membership, ingredient identifiers and resource references.

- Before the fix: 48 recipes in a 589,369-byte packet; exactly three null primary outputs and four missing item references. The test failed. Preserved log: `build/native-world-run/20260908-215638-597-cfbdc658/native-world.log`.
- After the fix: all 48 recipes passed in a 589,474-byte packet. The rest of the native-world harness also passed. Log: `build/native-world-run/20260908-220228-543-5e43dd77/native-world.log`.
- The normal Gradle `build` now runs `tools/validate_recipes.py`, checking all 48 native recipes, 59 audited recipes and 332 material references, including bench categories and exact six-shard tag semantics. Running this check against the old JAR fails; corrected resources pass.

The earlier asset-loader and world checks did not inspect recipe synchronization packets. This regression closes that gap. A successful client reconnect has not been observed by this automated test.

Replace `StrangeMatter-0.1.0.jar` with `StrangeMatter-0.1.1.jar` in the same mods directory, leaving only one version installed. Preserve the generated mod data directory and the world save.
