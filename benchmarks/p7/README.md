# P7: object-level acceleration



P7 adds a bounding-volume hierarchy (BVH) over prepared objects in `DirectRgbTracer`. The hierarchy skips groups of objects for nearest-hit and shadow queries, including ordered volume visibility. It activates at 32 objects; smaller scenes retain the existing bounded scan. The sample game's 313-block landscape uses it automatically. The geometry, textures, lighting, depth, sampling, and resolution settings are unchanged.



Implementation and automated verification are complete. Native-window playthrough remains pending: these measurements exclude actual input delivery, Swing painting, and monitor scanout.



## Fixed-grid game results



Measured October 10, 2026 on the development i5-13600KF (20 logical processors), OpenJDK 23.0.1, 14 workers and 32-pixel tiles. The frozen game route runs for 30 seconds: 25 seconds moving, then five seconds stationary. Each configuration receives one warmup replay and three measured replays. Requested and actual grids are 320×200; depth is two, one sample per batch, seed one, temporal reconstruction and adaptive resolution off. Benchmarks and builds were run sequentially.



| Configuration | Fresh images during 75 seconds of motion | Fresh images/second | Per-replay p95 fresh interval (ms) | Full-grid return after stopping (s) | Final samples/pixel |

| --- | ---: | ---: | --- | --- | --- |

| Preserved pre-P7 build | 551 | 7.35 | 202.895 / 217.435 / 216.162 | .145 / .138 / .244 | 45 / 44 / 44 |

| P7 | 2,198 | 29.31 | 61.853 / 62.227 / 63.073 | .035 / .057 / .044 | 160 / 158 / 160 |



This is about four times as many fresh images at the same fixed grid in these runs. The old build missed the historical 200 ms p95 threshold; its failure and 1.13-second maximum stall are retained in the evidence. P7 passed all three replays. Earlier pre-P7 measurements in [the profiling report](../game/PROFILE.md) were faster than this fresh baseline, so four times is an observed run comparison, not a universal speedup. Host conditions and run order were not controlled independently.



Across moving publications, median trace time fell from 100.60 to 14.21 ms and median captured-image age from 124.45 to 32.26 ms. Median pickup delay was 13.82 ms before and 16.65 ms after; copy time was 1.21 and 1.08 ms. Pickup is the time from engine completion until game image-copy start. Faster tracing has made this periodic game-owner delay a substantial remaining cost. Trace and pickup overlap other activity; their medians need not sum to the median age. The elapsed-time camera route is identical, but faster rendering captures more camera positions, so these are not identical per-ray workloads.



Evidence: [before summary](before-fixed/summary.csv), [after summary](after-fixed/summary.csv), per-publication CSVs and `moving-summary.json` in the same directories. The baseline used the preserved pre-P7 `out/game-check/classes` copied to `out/p7/baseline-classes` before rebuilding. Its source context is the earlier profiling changes on revision `0133bb9660753e2e881295a4e0ba36c8a1ba63f5`; the new build's exact Java source fingerprints are in [the manifest](after-fixed/source-sha256.txt). Both runs use the same route SHA-256 recorded in metadata.



## Adaptive behavior and remaining CPU work



A separate P7 adaptive run used Java Flight Recorder (JFR) after warmup. Its three replays produced 820 / 817 / 818 moving publications, with p95 fresh intervals of 32.098 / 32.104 / 32.106 ms. All 2,455 moving images were still 102×64. Full 320×200 images returned .429 / .423 / .432 seconds after stopping, then refined to 147 samples per pixel. The historical regression checks passed.



This is 32.73 fresh images/second, below the earlier pre-P7 adaptive profile's 41.67. It is not an adaptive frame-rate improvement. The runs were not interleaved and profiler/host effects are not isolated. Median trace time is now 6.53 ms, while median completion-to-copy pickup is 24.38 ms and median captured-image age is 31.24 ms; copying takes .13 ms. The existing fixed-delay owner loop is a strong next investigation, including its interaction with resolution adaptation. Faster tracing alone did not restore moving detail or translate into faster adaptive publications in this run.



JFR recorded 1,741 execution samples: 70.1% include nearest-hit queries, 4.9% shadow queries, .9% game conversion, and 24.1% other work. `ObjectTraversal.next` was the leaf in 472 samples (27.1% of all samples), while the triangle distance routine was the leaf in 135 (7.8%). These are sampled executing stacks, not exclusive wall time or time spent waiting. Inlining limits method-level cost attribution. The capture also includes stationary holds; the publication timing summaries above include only motion. Total recorded GC pause time was 8.36 ms.



Keep P2.1 scheduling/adaptation and worker/tile measurements first. For the next tracing experiment, P8/P9 traversal and tree construction now have more direct evidence than assuming polygon intersection is the dominant remaining cost. P15 analytic boxes remains conditional; P10 storage changes still require further evidence. No additional optimization is included in this P7 delivery.



Evidence: [adaptive summary](after-adaptive-profile/summary.csv), [moving timings](after-adaptive-profile/moving-summary.json), [JFR summary](after-adaptive-profile/jfr-summary.csv). The full recording remains at `out/p7/after-adaptive-profile/game.jfr`, outside the checked-in evidence.



## Cutoff and scaling



`engine.ObjectAccelerationBenchmark` compares a forced object hierarchy with the old bounded flat scan in the same build. Both retain per-object primitive BVHs. It uses one worker, 64×64 pixels, matching seeds and sample indices, 40 warmup passes and 60 measured passes per case. Three rounds alternate order. Values below are the median of the three run medians; no performance assertions depend on noisy timing results.



| Scene | Objects | Flat median (ms) | Tree median (ms) | Interpretation |

| --- | ---: | ---: | ---: | --- |

| Synthetic touching block grid | 4 | .385 | .442 | Tree can lose on tiny scenes |

| Synthetic touching block grid | 16 | .996 | .348 | A small spatial grid can benefit |

| Synthetic touching block grid | 32 | 1.780 | .474 | About 3.8× faster |

| Synthetic touching block grid | 128 | 8.859 | 1.176 | About 7.5× faster |

| Synthetic touching block grid | 1,024 | 83.819 | 1.917 | About 43.7× faster on this static grid |

| Textured game landscape | 313 | 31.168 | 4.586 | About 6.8× faster |

| Glass room | 13 | 3.096 | 3.733 | Retain flat scan |

| Volume room | 16 | 8.094 | 8.609 | Retain flat scan |



The 32-object cutoff is deliberately conservative: room geometry overlap and optical paths differ from a simple block grid. The playground and mesh-room forced-tree runs also showed no benefit. The synthetic grid is a static workload, not a larger playable-world benchmark. It does not establish a frame-rate promise for arbitrary worlds.



Incremental tree construction over already prepared bounds took a median .299 ms for the 313-object landscape (241 nodes) and .579 ms for 1,024 objects (511 nodes), across 101 constructions. This excludes common primitive preparation, JVM startup and transport. The tree retains one object-index array and bounded node/bounds objects, shared across workers; no heap-size claim is made. Raw timings and primitive counts are in [scaling.csv](scaling.csv). Reducing object scans need not reduce primitive tests: traversal order changes which candidate surfaces are checked before finding the nearest hit.



## Implementation and correctness



`PreparedObject` supplies conservative world-space bounds. The existing median-split builder in `PrimitiveBvh` now also accepts those bounds; nodes have at most four entries per leaf and stackless escape links. `DirectRgbTracer` retains the original object array and stores a hierarchy alongside its prepared snapshot. Each worker owns a reusable `ObjectTraversal` cursor. Queries allocate no per-ray traversal state. Equal-distance hits prefer the original scene index, then original primitive index, regardless of tree order.



Camera moves reuse the hierarchy. Instance, material, or legacy-geometry edits rebuild the prepared data. Brute mode bypasses both hierarchy levels. Source-face skipping, emitter endpoint exclusion, medium containment and boundary crossing rules are unchanged. `SpatialQuery`, the separate scene query service, keeps its existing implementation. Near-first traversal remains P8; these results use fixed preorder traversal.



`ObjectAccelerationTest` covers randomized nearest-hit identity and bounded occlusion, parallel and inside rays, touching-block seams, cross-object ties, camera reuse, scene edits, removal and empty/small-scene transitions. It also compares exact seeded raw images with brute force for glass, inside-glass, volume, area-light, mesh and textured sun/sky scenes. The game suite compares the landscape's displayed output with brute force and checks movement, restoration and refinement through the actual asynchronous renderer. Existing scene/engine suites cover leases, cancellation, media, mesh seams and worker agreement.



## Reproduction and remaining acceptance



From the repository root, use JDK 23 with preview features:



```powershell

./verify.ps1

./game-benchmark.ps1 -NoAdaptive -OutputDirectory out/p7-fixed-repeat

./game-benchmark.ps1 -NoBuild -Profile -OutputDirectory out/p7-adaptive-repeat

. ./tools/style/common.ps1

Invoke-StyleJava @('--enable-preview', '-Djava.awt.headless=true', '-cp', 'out/scene-check/base/classes', 'engine.ObjectAccelerationBenchmark') 'out/p7-scaling-repeat.csv'

```



The scaling benchmark has package-local threshold overrides for comparison; the game and normal engine use the fixed 32-object cutoff. To reproduce the historical game baseline, use a pre-P7 build with the profiling instrumentation, not `acceleration=false`: brute mode also removes per-object bounds and primitive trees, so it is a different comparison.



For native acceptance, launch `./game.ps1`, fly through the landscape with adaptation on and off, look across touching block seams, stop to confirm full-grid refinement, then check pointer release/capture, focus loss, resizing and close. Record actual moving grids and fresh-image timing alongside perceived smoothness. No new minimum acceptable moving resolution or hard smooth-gameplay target is implied by the historical 200 ms threshold.

