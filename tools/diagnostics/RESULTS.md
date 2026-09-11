# Measured results for 0.9.6

Measured September 11, 2026 using Java 25.0.1 and the installed Hytale server on this Windows computer. Three fresh isolated server processes tested the exact release code and resources. Each workload has 400 samples after warmup. These are server component timings with no connected clients; they do not predict FPS or total server capacity.

| Workload | Median range across 3 runs | p95 range across 3 runs | Allocation per operation |
| :--- | ---: | ---: | ---: |
| power_routes_32 | 3.14 to 4.61 us | 3.61 to 8.76 us | 18,408 to 18,408 bytes |
| power_routes_128 | 13.14 to 13.59 us | 14.04 to 15.68 us | 72,200 to 72,200 bytes |
| ingredients_36 | 5.75 to 6.09 us | 6.51 to 9.32 us | 24,552 to 24,680 bytes |
| ingredients_108 | 10.91 to 11.31 us | 12.38 to 13.18 us | 28,296 to 28,424 bytes |
| research_profile | 4.28 to 4.72 us | 5.45 to 5.95 us | 16,144 to 16,240 bytes |
| tube_plan_storage | 34.25 to 37.50 us | 39.62 to 51.45 us | 65,720 to 66,592 bytes |
| tube_plan_fuel | 30.25 to 32.52 us | 38.08 to 40.15 us | 62,712 to 63,432 bytes |
| machine_tick_16 | 24.00 to 30.00 us | 29.90 to 61.30 us | 14,469 to 14,968 bytes |
| machine_tick_64 | 85.60 to 98.00 us | 120.00 to 129.30 us | 55,223 to 56,170 bytes |
| tube_tick_128_idle | 0.10 to 0.10 us | 5.20 to 5.70 us | 7,753 to 7,794 bytes |

The 128 node power route originally allocated 620,200 bytes per walk. Storing predecessor links reduced that to 72,200 bytes, an 88.36 percent reduction. Its original single run measured 165.16 us median and 226.24 us p95; the optimized three runs measured 13.14 to 13.59 us median and 14.04 to 15.68 us p95. Timing comparisons are observational and can include host scheduling and JIT effects; the allocation improvement directly matches the removed path copies. Twelve thousand randomized equivalence cases plus explicit graph boundary cases preserve the previous routing behavior.

All three current runs pass the conservative comparison thresholds against the saved representative baseline. That baseline is the run with the middle p95 for the 128 node routing workload; no samples are synthesized or combined. `reference-reports.zip` preserves all three reports and the original measurement.

The loaded native atlas audit fits all six reported atlases within 8192. Model/entity uses 8192 x 8192; blocks, particles and UI use 8192 x 4096; item icons use 8192 x 2048; world map uses 2048 x 256. Strange Matter contributes 828,416 padded pixels to models, 1,967,104 to blocks, 471,040 to icons, 287,300 to particles and 428,892 to UI. Block and particle packing are estimates. Fluid and block overlay atlases are outside the upstream audit. These results cover Hytale plus Strange Matter; other mod packs require a combined audit.

Release SHA256: `66be2d0a1339e01575c78b8c7060f1c0554701cb8147471406737b8c0cdb5b31`.

See README.md for commands, workload definitions, limitations and how to compare future changes.
