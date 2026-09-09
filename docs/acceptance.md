# Hytale acceptance pass

Use a new local test world with only Strange Matter enabled first. Record the Hytale build, mod jar hash and any server/client errors. Repeat the multiplayer cases with two accounts before distributing a release.

| Area | Action and expected result |
| --- | --- |
| Asset loading | Load the world, search creative inventory for Strange Matter, place each machine and inspect all sides. Every texture must appear; doors, stairs, slab placement and hat attachment must align. |
| Native UI | Open `/sm journal`, then a Research Machine and Reality Forge. Check readable wrapping and scrolling at different UI scales. Every instrument must stay within its panel. |
| Delayed acknowledgements | Introduce high latency and click controls during moving frames, including ordered cognition inputs. Inputs must arrive in order while simulation pauses for unseen frames. Dismiss/reopen and change worlds while frames are pending: the next page must respond normally. |
| Stale recipe controls | Rapidly select Next/Previous and Craft before the new recipe frame appears. An outdated click must never craft the newly selected recipe; it must request a fresh click. |
| Scanning | Hold on each of six natural anomaly types for two seconds. Gain exactly 10 matching points once. Break aim, release early, swap tools and restart the server: no partial or repeated award. |
| Research | Purchase a note with exact points, check prerequisites, then fail, dismiss and complete its experiment. Failure/closing leaves the note; success consumes it once and unlocks the topic. |
| All instruments | Start a topic requiring several disciplines. Leave one unstable while balancing the others. Instability only falls when every active instrument is stable. Check moving waves, cube, shadows, distortion lattice, clock and flashing memory sequence. |
| Research concurrency | Two players use one machine and one note is moved between inventories. Only one valid experiment may complete; no duplicate unlock or consumed foreign note. |
| Power | Connect burner → one conduit → condenser within ten blocks of a field. Feed charcoal. Confirm 20 RE/t generation, 2 RE/t active consumption and one shard every 75 active seconds. Disconnect a conduit and verify transfer stops. |
| Forge | Test insufficient shards, missing research, full output/inventory and successful crafting. Every input plus additional shard cost is paid once; output survives restart. |
| Capture | Hold vacuum with an empty capsule; break aim and retry. Exactly one empty capsule becomes filled. Throw the filled capsule and observe flight/impact. Releasing it cannot produce new scanner rewards. |
| Capsule recovery | Interrupt the server during launch, flight, impact and refund; reconnect with a full inventory. The same anomaly identity must release once or return as one intact capsule, with pending recovery retained until inventory space is available. |
| Gravity/time | Observe levitation, crop advancement/regression and juvenile/adult livestock roles. Leave and return; natural aging and entity identity must persist. |
| Rift | Observe discharges with and without tinfoil shielding and grounding. Place four stabilizers: no more than three should draw power from one rift. |
| Warp | Walk into both natural and gun-created gates. Check cooldown, return pairing, entities of different sizes, and blocked/fluid-filled destinations. No arrival may embed an entity in solid terrain. |
| Thoughtwell/shadow | Compare effects with/without hat, leave radius, and restart. Disguises/effects restore; shadow creatures stay bounded and clean up. |
| Hammer | Primary mines a 3×3 face; crouching mines one block; charges at 1/2/3 seconds mine depths 3/6/9. Drops respect native gathering and protected machines remain intact. |
| Chrono/stasis | Fire at air/terrain; temporal blocks allow passage and expire. Restart during their lifetime. Specimens release when stasis is disabled; player movement is not permanently changed. |
| Mobility | Mount/dismount a Hoverboard, rise/descend, disconnect and change worlds. Imprint a creature, restore form, die/reconnect. No orphan mount or permanent model alteration. |
| World generation | Explore new chunks and locate each field/deposit. Return to already edited terrain after restart: no new deposit generation may overwrite it. |
| Scientists and milestones | Inspect a generated laboratory, trade through five ranks and restock near its Research Machine. Two players must share stock without duplicate purchases. Verify all thirteen journal milestones and persistence. |
| Performance | Run several active laboratories and six nearby fields with two players. Observe server tick time, particle visibility/culling and UI latency. |

This checklist records work still requiring a real client. Passing Java tests or native asset loading alone does not mark these cases passed.
