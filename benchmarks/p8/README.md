# P8: near-first object traversal

P8 changes how `DirectRgbTracer` walks the bounding-volume hierarchy (BVH) over scene objects. Each internal node tests both children, visits the child with the nearer forward bounds entry first, and saves the other child in worker-local scratch. A closer surface hit can discard deferred nodes using their cached entry distances. Opaque shadow queries still stop at the first blocker; glass and volume visibility retain their ordered nearest-boundary queries.

The retained default applies to the object hierarchy introduced by P7, active at 32 objects. Primitive hierarchies inside meshes retain escape traversal: a first near-first mesh experiment reduced bounds/primitive work but increased median time from 8.22 to 8.92 ms. Follow-up timings were variable, so no mesh speedup is claimed. Tree construction, leaf sizes, geometry, surface identities, sampling, lighting and application scheduling remain unchanged.

Implementation and automatic checks are complete. Native-window input and smoothness acceptance remain pending.

## Frozen game route

Measured October 10–11, 2026 on the development i5-13600KF, 20 logical processors, OpenJDK 23.0.1+11-39, 14 workers and 16-pixel tiles. The landscape has 313 blocks. Requested grid is 320×200 for a 960×600 window reference; depth two, seed one, one sample per batch, and temporal reconstruction off. Fixed runs use that exact grid. Adaptive runs retain the existing resolution policy.

Each configuration runs a 30-second warmup and three 30-second measured replays of `benchmarks/game/camera-route.csv`: 25 seconds moving, five stationary. Before runs use compiled P2.1 commit `e74867c`; final runs use the source identified by [final-source-sha256.txt](final-source-sha256.txt). Measured configurations ran sequentially, without another build or benchmark in those intervals. The route SHA256 is `a705beafb16265242207dcbc9ab9d53c5e9955ce90131f1546be36ceb01b5254`. Route metadata says CPU unspecified because these direct invocations omitted its descriptive JVM property; every run used this same machine.

Fresh images are copied software publications, not monitor FPS. Interval p95 includes initial/final holds. Age and pickup p95 pool measured moving publications only. Age starts at snapshot capture; pickup starts at completed publication. Full-grid recovery is seconds after movement stops; final spp means accumulated samples per pixel at replay end.

| Configuration | Fresh images/s | Interval p95 by replay (ms) | Age p95 (ms) | Pickup p95 (ms) | Full-grid recovery (s) | Final spp by replay |
| --- | ---: | --- | ---: | ---: | --- | --- |
| before-fixed | 51.5 | 37.883, 36.821, 36.229 | 38.21 | 11.21 | 0.020–0.033 | 282, 294, 279 |
| final-fixed | 65.8 | 28.330, 31.429, 27.606 | 31.14 | 9.23 | 0.020–0.048 | 345, 314, 399 |
| before-adaptive | 87.4 | 19.214, 20.006, 18.494 | 20.09 | 8.62 | 0.025–0.383 | 302, 308, 234 |
| final-adaptive | 88.9 | 19.646, 19.635, 19.139 | 20.14 | 8.00 | 0.020–0.385 | 341, 357, 308 |

Adaptive moving grids:

- `before-adaptive`: 320x200: 3076 publications, 240x150: 2126 publications, 160x100: 1354 publications.
- `final-adaptive`: 320x200: 3196 publications, 240x150: 1554 publications, 160x100: 1861 publications, 120x75: 59 publications.

All twelve before/final replays pass the historical 200 ms p95 threshold and recover the matching final camera at full resolution with further refinement. Builds and measurements exclude actual input delivery, Swing painting and monitor scanout. These sequential route timings vary substantially with host state: the newly measured fixed baseline differs from the earlier P2.1 report. Do not interpret the full route-level improvement as a guaranteed isolated traversal speedup. Static matched work below provides a separate check.

An earlier all-near-first candidate also passed three fixed replays at 67.0 fresh images/s, with interval p95 28.476, 27.199 and 27.683 ms. Its [after-fixed](after-fixed/) evidence is retained separately; final results above use the retained configuration. The game blocks have fewer than 16 primitives each and therefore no primitive BVH.

## Matched tracing work and memory

[BvhTraversalBenchmark](../../tst/engine/BvhTraversalBenchmark.java) alternates escape/retained traversal in five rounds in one JVM. Each scene/configuration gets 100 warmup passes and 200 measured passes, 64×64 pixels, one worker, one sample per pass, seed one and identical geometry/camera/transport. Landscape and the synthetic 32×32 block grid use depth two and game sun/sky; mesh-room uses its preset optics and depth. Median values below are medians of the five run medians, not pooled per-pass timings.

| Scene | Escape median (ms) | Retained median (ms) | Change |
| --- | ---: | ---: | ---: |
| Landscape, 313 blocks | 3.8783 | 3.7554 | 3.2% less time |
| Synthetic 1,024 blocks | 1.0975 | 1.0380 | 5.4% less time |
| Mesh-room | 8.1190 | 8.0515 | Same traversal/work; timing variation |

Landscape totals over each 200-pass run: hierarchy bounds tests fall from 29,951,130 to 29,346,854; primary primitive tests from 6,142,896 to 5,809,872; continuation tests from 3,354,684 to 2,279,316; shadow tests from 470,742 to 453,136. Node visits fall from 29,951,130 to 16,163,958. A visit counts an escape node processed or a stack node popped, including cached-distance pruning; child bounds tests count separately. These counters exclude the additional per-object bound check and medium-containment bounds. Fewer visits therefore do not imply the same proportional CPU saving.

Synthetic-grid primary tests fall from 4,952,448 to 3,980,952; bounds tests fall from 6,782,772 to 6,350,686. Its fixed camera and predominantly primary work are a limited scaling diagnostic, not a moving larger-world gameplay claim. Mesh work is identical with the retained escape default. Every measured worker tracing allocation total is zero. Tail times and some rounds vary; inspect [paired-final.csv](paired-final.csv), including the 2.32 ms synthetic near-first outlier, rather than relying only on median values.

Each cursor reserves 32 integer node indices and 32 float entry distances; two separate cursors per worker total 512 bytes of array payload, 7,168 bytes for fourteen workers, plus Java headers/cursor fields. Allocation happens when workers are created, never per ray. Median splitting with leaves of four bounds keeps an int-sized primitive/object range within this capacity. Each BVH node gains one integer right-child index; actual retained heap overhead, including padding, was not measured. Escape links remain available. This capacity argument depends on the current median builder and must be revisited when P9 changes construction.

The initial all-near-first measurements are [escape.csv](escape.csv) and [near.csv](near.csv). [paired.csv](paired.csv) records a rejected slab early-exit variant with unstable timing. Neither experiment sets the retained mesh default.

## Correctness and reproduction

[verify-final.log](verify-final.log) records a passing `./verify.ps1`, including object acceleration, mesh acceleration, parallel tracing, glass, volume, temporal reconstruction and responsive publication checks. [game-check-final.log](game-check-final.log) records passing game, texture, sun/sky, lighting and landscape suites. The new cursor test proves reverse-direction near-leaf selection, shrinking-distance pruning, scratch reset, inside-bound and parallel-axis behavior. Existing reference tests cover 2,500 touching-block rays, cross-object ties, tiny/shared mesh seams, seeded advanced-transport images, source skipping and cache edits. Equal-distance nodes are not pruned; original object/primitive IDs break surface ties. The existing Caps-on synthetic-console skip remains.

The refactor reduces two accepted method complexity ceilings and removes three obsolete style finding groups. `./style-check.ps1 -PruneBaseline` ran; no baseline finding or ceiling was expanded. The JDK compiler initially failed inside the sandbox with `Cannot close compiler resources`; final verification and game checks succeeded using the installed JDK outside that restriction.

From the repository root in PowerShell:

```powershell
./verify.ps1
./game-check.ps1
. ./tools/style/common.ps1
Invoke-StyleJava @('--enable-preview', '-Djava.awt.headless=true', '-cp', 'out/scene-check/base/classes', 'engine.BvhTraversalBenchmark') 'out/p8-paired.csv'
./game-benchmark.ps1 -NoBuild -NoAdaptive -OutputDirectory out/p8-fixed
./game-benchmark.ps1 -NoBuild -OutputDirectory out/p8-adaptive
```

Require zero from `Invoke-StyleJava`; it launches without a child console window. For a route comparison using the same new classes with escape traversal, invoke `game.GameRouteBenchmark` directly and add `-Dengine.bvhEscapeReference=true` before `-cp`. To investigate near-first primitive traversal, add `-Dengine.bvhPrimitiveNearFirst=true`; this internal benchmark switch is captured when tracer workers are created. The benchmark alternates the escape-reference property itself. Brute mode remains the numerical reference.

Native acceptance: run `./game.ps1`, move/look continuously, stop to check full-grid refinement, lose/regain focus, release/recapture the pointer, and close while tracing. Automatic software counters do not establish monitor smoothness or physical input latency.

Next milestone is P9: evaluate better BVH construction and leaf sizes against median splitting, including preparation cost, retained memory, primitive work and repeated tracing time. Primitive near-first traversal remains conditional on a reliable mesh benefit. P10 storage and P15 analytic boxes still require residual profiling evidence.
