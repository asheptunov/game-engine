# P9: surface-area BVH construction

P9 changes the preparation of the bounding-volume hierarchies (BVHs) used by `DirectRgbTracer`. `BvhBuilder` places object or primitive centers into sixteen bins on each axis, then chooses the partition with the smallest child surface-area-times-item-count cost. This surface-area heuristic (SAH) estimates ray traversal work. It replaces median splitting as the default, with four objects or eight primitives per leaf.

Prepared trees remain immutable and cached across camera changes. Original object/primitive identities, conservative bounds padding, surface tie rules, transport and sampling remain unchanged. Object traversal retains P8's near-first order; mesh primitive traversal retains escape links. Degenerate centroid distributions or unusable partitions fall back to a stable median split. A construction depth cap of thirty emits a complete leaf, possibly larger than the configured size, and preserves all entries. This bounds recursion and keeps the two 32-entry worker cursors safe without adding per-ray allocation. Their array payload remains 512 bytes per worker.

Implementation and automatic checks are complete. Native-window input, focus, painting and smoothness acceptance remain pending.

## Construction, memory and repeated tracing

Measured October 11, 2026 on the i5-13600KF, 20 logical processors, OpenJDK 23.0.1+11-39. The final [pinned comparisons](pinned/) run each scene in its own JVM, restricted to logical CPU 0; no performance/efficiency core classification is assumed. Five rounds alternate median 4/4, SAH 4/4 and SAH 4/8 object/primitive leaf configurations. Each gets 100 warmup and 200 measured complete one-sample passes at 48x48, one worker, seed one and the same camera, geometry and transport. Block scenes use depth two and sun/sky; mesh scenes replace the four mesh-room instances with one shared spherical mesh at details 4, 12 or 32, retaining preset depth eight and materials.

The table reports medians of five run medians, not pooled pass times. Positive reduction means less time. Blocks have no primitive BVH, so both SAH configurations perform identical work there; their small timing differences show residual measurement variation.

| Scene | Median 4/4 trace (ms) | Retained SAH 4/8 trace (ms) | Time reduction | SAH 4/4 trace (ms) |
| --- | ---: | ---: | ---: | ---: |
| landscape | 2.3377 | 1.5542 | 33.5% | 1.5427 |
| blocks-1024 | 0.6350 | 0.6351 | -0.0% | 0.6418 |
| mesh-4 | 3.7699 | 3.4655 | 8.1% | 3.4395 |
| mesh-12 | 4.7767 | 4.4478 | 6.9% | 4.4067 |
| mesh-32 | 6.1239 | 5.7476 | 6.1% | 5.6482 |

Hierarchy-only construction is timed separately on already computed world-space bounds: five warmups, then the median of 21 builds per round. The following values are again medians across five rounds. They exclude mesh generation, bounds preparation and transport compilation. Build bytes are allocated bytes on the constructing thread. Retained bytes use a Java instrumentation agent to sum the owned BVH object, node/order arrays and each node/bounds object, including JVM headers and padding; shared geometry, source bounds, temporary build scratch, materials, worker cursors and publication buffers are excluded. `-1` means the agent is absent, not zero memory. Depth counts edges from root depth zero.

| Scene | Build ms, median / SAH | Build bytes, median / SAH | Retained bytes, median / SAH | Nodes, median / SAH | Depth, median / SAH |
| --- | ---: | ---: | ---: | ---: | ---: |
| landscape | 0.0913 / 0.1884 | 44,000 / 40,592 | 19,632 / 15,528 | 241 / 187 | 7 / 8 |
| blocks-1024 | 0.2335 / 0.4893 | 156,336 / 120,368 | 42,992 / 42,992 | 511 / 511 | 8 / 8 |
| mesh-4 | 0.0379 / 0.0491 | 17,376 / 22,496 | 10,432 / 5,568 | 124 / 60 | 4 / 4 |
| mesh-12 | 1.0793 / 1.0301 | 320,992 / 260,512 | 95,936 / 61,888 | 1148 / 700 | 8 / 7 |
| mesh-32 | 14.7727 / 10.9192 | 2,099,808 / 1,977,152 | 686,016 / 450,112 | 8188 / 5084 | 10 / 10 |

The landscape saves about 0.784 ms per measured pass for about 0.097 ms extra construction, so one such pass more than repays the build cost. Mesh detail four saves 0.304 ms for 0.011 ms extra construction; larger measured meshes reduce both trace and build time. These are workload-specific amortization checks, not predictions for arbitrary grids or scenes. The synthetic 1,024-block view gains no useful trace speed and adds about 0.256 ms of construction. No larger-world scaling improvement is claimed. Median construction remains available for comparisons and short-lived workloads that cannot repay preparation; there is no automatic lifetime predictor.

Eight-primitive leaves retain 35-47% less hierarchy memory than median/four in the three mesh cases, while tracing 6.2-8.1% faster. SAH/four traces slightly faster than SAH/eight, but raises retained memory above median at details 12 and 32. The retained eight-leaf default accepts this small speed tradeoff for substantially less memory and cheaper builds. One- and two-item leaves were also evaluated in [the initial sweep](sweep.csv); their extra nodes/memory did not justify making them defaults.

Landscape totals over each 200-pass run: node visits fall from 9,087,020 to 5,396,329; hierarchy bounds tests from 16,501,556 to 10,106,350; primary primitive tests from 3,265,368 to 3,042,552; continuation tests from 1,279,692 to 1,221,780; shadow tests from 254,801 to 253,166. Larger mesh leaves reduce hierarchy work while increasing some primitive tests: at detail twelve, SAH/eight performs 7,322,022 primary tests versus 7,169,518 for median/four. Fewer node visits alone therefore do not establish the speedup. These hierarchy counters exclude per-object bounds checks and medium-containment bounds. Every measured worker tracing allocation total is zero.

The unpinned [confirmation](confirmation.csv) and `steady-*.csv` files are retained as experiments. They include a misleading mesh-detail-twelve slowdown and substantial variation even between identical block work. CPU affinity was added to reduce migration/scheduling variation before choosing the default. Pinned timings still contain outliers, including landscape median build rounds; inspect the raw files rather than inferring a universal speedup.

## Frozen game route

The game uses the same 313-block landscape and 30-second `benchmarks/game/camera-route.csv`: 25 seconds moving and five stationary. Grid is requested 320x200 for a 960x600 window reference, fourteen workers, sixteen-pixel tiles, depth two, seed one, one sample per batch and temporal reconstruction off. Each configuration warms for thirty seconds and measures three replays. Source identity is recorded in [final-source-sha256.txt](final-source-sha256.txt); the separate before runs use compiled P8 commit `f48b71b`, fingerprinted in [baseline-classes-sha256.txt](baseline-classes-sha256.txt). Route SHA256 is `a705beafb16265242207dcbc9ab9d53c5e9955ce90131f1546be36ceb01b5254`. Direct runs omit the descriptive CPU property and say unspecified in metadata; they used the same host named above.

Fresh images/s counts copied software publications over the moving 75 seconds. Interval p95 includes initial/final holds. Age and pickup p95 pool moving publications from the three measured replays: age starts at snapshot capture; pickup starts at completed publication. Recovery is seconds after motion stops until the matching final camera appears at full grid. Final spp is accumulated samples per pixel at replay end. These metrics exclude actual input delivery, Swing painting and monitor scanout.

| Configuration | Fresh images/s | Interval p95 per replay (ms) | Age p95 (ms) | Pickup p95 (ms) | Full-grid recovery (s) | Final spp per replay |
| --- | ---: | --- | ---: | ---: | --- | --- |
| before-fixed | 79.9 | 24.247, 24.469, 24.335 | 25.06 | 7.49 | 0.016-0.021 | 466, 446, 454 |
| after-fixed | 76.9 | 31.087, 30.740, 30.705 | 31.76 | 12.87 | 0.020-0.030 | 486, 488, 498 |
| before-adaptive | 76.9 | 21.405, 21.851, 20.108 | 22.30 | 12.04 | 0.028-0.395 | 294, 353, 372 |
| after-adaptive | 85.5 | 19.978, 20.475, 19.930 | 20.88 | 12.78 | 0.015-0.030 | 489, 486, 482 |
| paired median | 53.5 | 31.209, 31.183, 31.218 | 32.13 | 11.92 | 0.036-0.045 | 323, 323, 323 |
| paired SAH | 78.9 | 30.483, 30.609, 30.689 | 31.59 | 12.59 | 0.017-0.025 | 488, 490, 484 |

The separate before/after JVMs give mixed results: fixed-grid throughput declines slightly and interval tails worsen, while adaptive delivery improves. This prompted an alternating comparison in one JVM, with only one game session active at a time. Both policies use the same current classes, shared worker pool and fixed grid; median uses four-item leaves at both levels to reproduce P8 construction, while SAH uses the retained four/eight defaults. Each scene's prepared tree remains cached while its session is suspended. The three measured rounds alternate policy order after a separate warmup for each. This comparison gives a consistent gain in copied-image count and stationary refinement, with little change in p95 interval. The route's periodic input/camera updates can still shape delivery intervals; average images/s is not a smoothness guarantee.

All eighteen unprofiled measured replays pass the historical 200 ms p95 threshold and recover/refine at the final full-grid camera. Adaptive runs retain existing policy and actual grid records in publication CSVs. Builds and benchmarks ran sequentially. Host state still varies; neither the alternating result nor the scalar benchmark proves a guaranteed native-gameplay gain.

## Residual profile and priorities

A separate fixed-grid JFR run uses the retained SAH configuration, a thirty-second warmup and three measured replays. Its [sample summary](profile/jfr-summary.csv) is diagnostic and is excluded from unprofiled timing comparisons. The recording contains 15,300 execution samples: nearest-query stacks account for 68.8%, shadow-query stacks 17.8%, game image conversion 4.5%, and other stacks 8.9%. The hottest leaf frame is `PrimitiveBvh.Bounds.entryDistance` at 47.8%; `MollerTrumbore.distance` accounts for 10.6%. JIT inlining and sampling prevent treating these shares as exclusive wall time or an exact bounds-versus-face cost split. They do not measure cache misses.

P10 remains next as a conditional experiment: compare compact immutable node/bounds storage and its intersection path with the existing layout, using fixed-grid delivery, matched trace work, memory and reference transport checks. Retain it only if the measured benefit repays its complexity. P15 analytic boxes remains the next alternative; polygon intersections are visible, but the current profile favors investigating the bounds/traversal path first. No layout gain or cache-stall cause is assumed.

JFR estimates 2.74 GB of integer-array allocation weight over the measured recording; this is sampled allocation weight across the application, not exact bytes allocated by tracing workers. `GameRenderer.copyImage` creates immutable copied image storage per publication. This is a future presentation-allocation investigation, but its 4.5% sampled execution share and 10.35 ms total recorded GC pause do not place it ahead of tracing in this run. The binary recording remains at `out/p9/profile/game.jfr`; the source, summary, settings and publication records are archived here.

## Verification and reproduction

[verify-final.log](verify-final.log), [game-check-final.log](game-check-final.log), [construction-test-final.log](construction-test-final.log) and [style-test.log](style-test.log) pass. The new construction suite covers coincident centroids, skewed distributions, capped depth, retained indices, multiple leaf sizes and randomized nearest queries with parallel axes, inside origins and finite limits. Existing selected suites cover exact seeded/reference images, touching blocks, object ties, tiny/shared mesh seams, glass/volumes, worker agreement, cancellation, cache reuse and leases. The existing Caps-on synthetic-console skip remains. The readability baseline removes obsolete median-builder findings without expanding accepted debt. The completion gate runs selected suites, not the entire project.

From the repository root in PowerShell:

```powershell
./verify.ps1
./game-check.ps1
./style-test.ps1
./tools/game/bvh-benchmark.ps1 -NoBuild -OutputDirectory out/p9-recheck-pinned
. ./tools/style/common.ps1
Invoke-StyleJava @('--enable-preview', '-Djava.awt.headless=true', '-cp', 'out/scene-check/base/classes', 'engine.BvhConstructionTest') 'out/p9-recheck-test.log'
Invoke-StyleJava @('--enable-preview', '-Djava.awt.headless=true', '-cp', 'out/game-check/classes', 'game.GameBvhComparisonBenchmark', 'out/p9-recheck-paired') 'out/p9-recheck-paired.log'
./game-benchmark.ps1 -NoBuild -NoAdaptive -OutputDirectory out/p9-recheck-fixed
./game-benchmark.ps1 -NoBuild -OutputDirectory out/p9-recheck-adaptive
./game-benchmark.ps1 -NoBuild -NoAdaptive -Profile -OutputDirectory out/p9-recheck-profile
```

Require zero exit status from each `Invoke-StyleJava` call. Launchers suppress child consoles and redirect output. The pinned launcher verifies affinity mask one for each scalar JVM and packages the memory agent from verified classes; it changes no other process or scheduled task. Its timings are not directly comparable with unpinned multicore route results.

Internal JVM controls `-Dengine.bvhMedianReference=true`, `-Dengine.bvhObjectLeafSize=4` and `-Dengine.bvhPrimitiveLeafSize=4` reproduce median/four construction when passed before `-cp` to a fresh process. Leaf controls accept 1..16. Defaults are SAH/four objects/eight primitives. Values are captured when a tree is prepared, so changing properties does not rebuild existing cached trees; these are benchmark controls, not live console settings. `BvhConstructionBenchmark sweep` tests equal leaf sizes at both levels; `confirm <scene>` tests median 4/4, SAH 4/4 and SAH 4/8. Retained-byte measurement requires the memory agent packaged by the pinned launcher.

Native acceptance remains: run `./game.ps1`, move/look continuously, stop to confirm full-grid refinement, lose/regain focus, release/recapture the pointer and close during tracing. This requires human inspection; software publication counters do not establish monitor smoothness or physical input latency.
