# Strange Matter 0.4.0

This revision addresses the latest playtest report. The distributable is
`build/libs/StrangeMatter-0.4.0.jar`. The Aetherhaven-style asset synchronization
workflow remains available.

## Changes

- **Lighting:** all six lamp and lantern families emit their crystal's color.
- **Shared models:** 24 item/block references now use six shared models with their
  family textures. Distinct manually edited lamp and lantern variants remain distinct.
  The mappings are in `tools/assets/shared-models.json`.
- **Held equipment:** scanner rotated 180 degrees; hammer rotated 90 degrees.
  Geometry and UVs are preserved. Only those two existing icons were regenerated.
- **Graviton Hammer:** primary uses Hytale's pickaxe Mine animation, swing audio,
  and a 0.7-second cadence enforced on both client interaction and server action.
  Thorium-strength primary damage and stronger charged attacks are preserved.
  Charging plays its sound immediately and stops it on release/cancel; the release
  pulse uses a separate heavy swing sound.
- **Reality Forge:** remembers each player's selected recipe at each forge using
  stable recipe IDs, including after server restart. See `machine-controls.md`.
- **Resonant Burner:** capacity is 32,000 remaining fuel ticks (26m40s), including
  active and queued fuel. Load One and Load Max accept real furnace fuels; bulk
  loading takes whole items that fit. The panel shows capacity and a fill meter.
  Existing excess fuel is retained and drains normally.
- **Hoverboard:** native rider movement owns downhill motion; NPC gravity no
  longer competes with it. Deployment consumes the actual board after its saved
  inventory receipt is secure. Dismount removes the entity and returns that item,
  preserving its metadata and durability. A full inventory delays the return;
  disconnect/restart recovery retains the receipt and rejects stale item copies.
- **Tooltips:** 78 specific descriptions replace the generic material/equipment
  text, covering uses and controls with concise flavor text.
- **Terrain:** all unshaped native soil and rock families can host anomaly terrain
  and ore deposits, including desert sand and sandstone. Surface soil becomes
  anomalous grass; buried soil becomes anomalous dirt. Original ore density is
  preserved. Existing chunks are not regenerated, so explore newly generated
  terrain to see the change.
- **Filled capsules:** use a native Throw animation and a visible tumbling capsule
  on a ballistic path. Impact releases the captured anomaly's original identity.
  Point-blank throws cannot skip a wall through the hand offset.

## Capsule crash fix

The reported NullPointerException came from cloning the live Player during the
required inventory save: the installed server has no legacy entity clone codec
for Player. Saves now use the same shallow component holder as Hytale's native
player-saving system. Native storage serializes that holder synchronously and
orders the asynchronous disk writes. A successful inventory save still precedes
flight, preventing identity duplication. Queued throws from the previous version
reconcile when their owner is available. Storage failures retain the queue and
retry with throttled diagnostics.

## Verification

The Java25 build and gameplay, recipe, UI, particle/audio and presentation checks
passed. Hytale0.6.4 loaded the packaged assets successfully. The native-world
harness exercised real player creation, actual disk save/decode/reattach, capsule
consumption and visible flight/impact, Forge selection persistence, bulk fuel
capacity and sequential burning, hoverboard consumption/return and five downhill
positions through native movement and NPC steering, desert soil strata, sandstone
ores, protected blocks and fresh-chunk boundaries. Existing research/UI ACK,
combat, anomaly, conduit and crafting regressions also passed.

Art preservation checks confirm 220 original model/texture files and 84 existing
icons remain byte-identical; the two held model changes affect root orientation
only. The full 899-file source snapshot from before this revision is in
`build/art-preservation/20260909T083748312884Z`.

These are server and static asset tests. First-person orientation, lighting,
animation feel and multiplayer motion still need the normal in-game playtest.
Release hash, archive/resource comparison and final native run provenance are
recorded in `build/release-verification-0.4.0.json`.
