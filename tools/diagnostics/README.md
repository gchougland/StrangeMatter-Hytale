# Performance and texture diagnostics

The regular build checks source texture footprint budgets. Every isolated native world verification also runs the atlas audit against the actual loaded Hytale and Strange Matter assets. Neither the upstream audit nor the benchmark classes are included in the release jar.

See `RESULTS.md` for the measured 0.9.6 results. `performance-baseline.json` retains a representative measured run for this computer; `reference-reports.zip` keeps all three fresh runs plus the measurement before the power routing optimization. `atlas-baseline.json` records the loaded native atlas report used to set the reviewed budgets.

## Commands

```powershell
./tools/build.ps1 -Tasks @('build')
./tools/verify-native-world.ps1 -SkipBuild
./tools/benchmark.ps1 -SkipBuild
```

The benchmark defaults to three fresh server processes. Use `-Repetitions 1` for a quick measurement. Each process starts a disposable flat world, warms each workload for at least 500 ms and 200 operations, then records 400 samples. Read the operation descriptions: some small operations are batched, and their percentiles are per operation averages of each batch. Native machine and tube ticks use one operation per sample. No user worlds or installed mods are modified.

Reports are under `build/performance-runs/<run>/performance-report.json` and `.md`. They include median, p95, p99, maximum, thread CPU time, allocation rate, individual samples, runtime/CPU information, and hashes of the tested archive and server. A raw sample maximum can include GC, JIT compilation or OS scheduling. These are component benchmarks with no connected clients, not FPS measurements or whole server capacity tests. Active tube persistence throughput, player anomaly physics, network delivery and client rendering need separate profiling in a representative live world.

Machine measurements include native block/component reads, factory inventories, periodic saves and presentation reconciliation. They exercise a mix of burning generators, idle condensers and conduits. The separate routing benchmarks exercise connected networks; the machine fixtures are spatially separated. Tube benchmarks cover occupied chest/furnace transfer planning and the idle 128 tube discovery/refresh loop, not a sustained durable transport pipeline.

## Comparing changes

```powershell
./tools/benchmark.ps1 -SkipBuild -Baseline 'tools/diagnostics/performance-baseline.json' -FailOnRegression
```

Comparisons require matching benchmark definitions, CPU/OS/JDK/heap and server hash. Run on an otherwise idle computer; compare all three fresh processes before diagnosing a change. The optional failure threshold requires p95 to grow by both 50 percent and 50 microseconds, or allocation to grow by both 20 percent and 1 KiB per operation. These conservative alerts are regression signals, not hardware independent performance guarantees. Ordinary builds do not fail on timing noise.

## Texture budgets

`budgets.json` contains explicit reviewed pixel budgets. `sourceUniquePixelLimits` is a fast PNG content hash deduplication check, grouped by source folder. It reports duplication and growth but is not an atlas packing simulation. Its reference measurements and reports keep physical file count, raw pixel count and unique pixel count separate.

`nativePaddedPixelLimits` limits Strange Matter's contribution in each loaded native atlas. The native report also fails incomplete asset reads and combined atlas dimensions above 8192. Model/entity, UI, world map and item icon membership use the upstream auditor; block and particle packing are estimates. Fluid and block overlay atlases are not audited. A successful vanilla plus Strange Matter run does not guarantee room alongside every other mod pack. Reports list all loaded packs and their contributions so combined growth is visible.

There is also a 3 MPx ceiling per atlas. A configured budget above that ceiling is rejected, so raising a budget cannot silently remove this limit. Existing reviewed limits remain tighter. MPx means one million padded pixels as reported by the upstream auditor; totals from separate atlases do not occupy one shared texture.

Inspect growth and the largest texture list before raising a budget. Do not blindly regenerate a baseline after a failure. Source pixel budgets have approximately 15 percent headroom from the reviewed assets; native budgets have separately reviewed headroom. Normal fixture reports live in `build/native-world-run/<run>/atlas-audit-report.txt` and `.json`; benchmark runs include the same reports.

## Upstream attribution

The unmodified `src/test/java/dev/zero/atlasaudit/AtlasAudit.java` is from ZeroErrors' **hytale-atlas-audit**, supplied in the sibling repository, commit `cce5eb6a65ef0267e6c50cd54d5b2748cde7486c`.

SHA256: `0acdb4d176a06c317cb9912abafe24fe73ecc1d423e8f5862e13c97c32e3074a`.

It is used under the MIT license in `atlas-audit-LICENSE.txt`, also embedded in the isolated test jar. That third party license applies to this source even though Strange Matter itself is All Rights Reserved. The bridge adds reporting and validation; the upstream packing and asset discovery code is unchanged. Update this source deliberately when upstream or Hytale changes its atlas rules, retain its license, and review the new reports before updating budgets.
