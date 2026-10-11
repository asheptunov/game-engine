# Sample-game profiling — October 10, 2026

Status: profiling and priority reassessment, not an optimization delivery. The current action order is maintained in [PerformanceRequirements.md](../../PerformanceRequirements.md). The game remains a CPU path tracer; this review does not change its rendering quality or world representation.

## Workload and method

The subject is the default `./game.ps1` landscape: 313 textured diffuse cube instances, Noon sun and sky, depth 2, seed 1, one sample per batch, temporal reconstruction/budget off, 14 workers and 32-pixel tiles. The requested grid is 320×200 for a 960×600 window reference. Hardware is an Intel i5-13600KF with 20 logical processors, using OpenJDK 23.0.1. All runs were sequential and window-free.

Each benchmark runs a 30-second route warmup and three 30-second measured replays of the frozen [camera route](camera-route.csv). Movement occupies the first 25 seconds; the last five hold the final camera for full-grid restoration/refinement. Java Flight Recorder (JFR) starts approximately 30 seconds after JVM startup using its `profile` configuration. Its execution samples cover the measured route and stationary holds; they are not motion-only samples. The publication timing analysis below excludes warmup and stationary holds.

The actual `GameRenderer` session/update/copy path is measured. A fresh image is a newly copied engine publication, not a repaint. Image age ends after copying and begins at the engine's captured-job timestamp. Pickup delay runs from engine completion to copy start. Copy time includes allocation, tone mapping, sRGB encoding and row flipping into the immutable AWT image. Trace time is the engine's elapsed trace-call time, including worker scheduling, not aggregate CPU time. CPU time is separately summed across the coordinator and active workers and is coarse on this host.

The engine accessor named `publicationNanos()` currently returns the captured-job timestamp; the new pickup measurement deliberately uses `finishedNanos()`. Neither measurement includes Swing painting, physical input delivery or monitor scanout. Ages describe captured render jobs, not how long a physical input waited to be captured.

## Results

The final fixed-grid profile produced 630 moving publications across 75 measured seconds, or 8.40 fresh images/second at 320×200. Its three p95 fresh intervals were 187.190, 187.118 and 189.679 ms. The maximum interval was 231.604 ms. During the final hold, matching full-grid images appeared after 0.143–0.180 seconds and refined to 46–52 samples per pixel. The adaptive result below is faster at a much smaller grid, not an equal-quality speedup.

The adaptive profile produced 3,125 moving publications across 75 measured seconds, or 41.67 fresh images/second. Every moving image was 102×64, only 10.2% of the requested pixel count. Its three p95 fresh intervals were 35.571, 36.426 and 35.926 ms. Full-grid images returned 0.442–0.469 seconds after stopping and refined to 54–57 samples per pixel.

| Moving-publication metric | Fixed median / p95 | Adaptive median / p95 |
| --- | ---: | ---: |
| Trace elapsed time | 86.820 / 177.733 ms | 14.717 / 28.825 ms |
| Completed-image pickup delay | 15.237 / 29.749 ms | 6.130 / 16.834 ms |
| Image allocation/conversion/copy | 1.020 / 1.431 ms | 0.082 / 0.169 ms |
| Captured-image age at copy completion | 96.525 / 188.659 ms | 17.816 / 35.795 ms |

Quantiles are calculated independently; adding medians or p95 values does not reconstruct another column. The adaptive means are 16.487 ms tracing, 7.426 ms pickup, 0.100 ms copying, and 24.067 ms image age. Pickup is therefore material in the small-grid path. The 16 ms fixed-delay update timer in `GameRenderer` is consistent with this delay, but a scheduling change still requires a before/after experiment.

| JFR category | Fixed: 18,545 samples | Adaptive: 21,092 samples |
| --- | ---: | ---: |
| Nearest-hit query | 70.70% | 72.31% |
| Shadow-occlusion query | 26.48% | 25.84% |
| All other sampled execution | 2.82% | 1.85% |
| Total recorded GC pauses | 3.318 ms | 2.411 ms |

These query categories are mutually exclusive in the summarizer. They describe sampled executing stacks, not exclusive wall time, machine CPU utilization, or time waiting for worker completion. Nearest-hit queries include both primary and continuation rays; this profile does not separate their CPU shares. In both modes ray queries dominate. Fixed-grid pickup delay also exceeds one nominal tick at p95, so the timer is not a hard upper bound; scheduling contention can add delay.

Both queries contain the flat object loop in `src/engine/DirectRgbTracer.java`. Object bounds reject many face tests, but there is no hierarchy that rejects groups of objects. JIT inlining and sampled line attribution do not reliably split that cost into bounds tests, loop traversal, and polygon intersections. In particular, a hot loop line is not proof that its one source statement consumes the reported percentage. Object-level acceleration is the strongest supported next experiment, not an already measured speedup.

The adaptive trace allocation median is 4,952 bytes per published job, excluding the game image allocation. The game allocates an integer pixel array per copied image: 26,112 bytes at 102×64 and 256,000 at 320×200, plus object overhead. Allocation and conversion are real costs, but the measured conversion time and very small GC pause totals do not support prioritizing image pooling over query work. This conclusion is limited to these grids.

## Decisions enabled by the evidence

1. Prioritize P7 for both nearest-hit and sun-shadow queries. Preserve equal-distance ties, touching block faces, texture attachment, deterministic samples, and existing glass/volume regressions.
2. Add P2.1 for game completion-to-copy scheduling, remeasured after P7. Include a worker/tile sweep: the moving grid has only eight 32-pixel tiles, fewer than the configured fourteen workers. This establishes limited task availability, not the benefit of any particular tile size.
3. Keep P15 box intersections conditional on the residual profile after object pruning. Do not claim a measured bounds-versus-face split from JFR.
4. Defer broad storage rewrites, image pooling, additional temporal work and denoising as performance fixes for this workload. No cache-miss measurements were collected. Depth-two diffuse paths do not establish a case for roulette. Chunks and hidden-face removal need a larger-world scaling experiment before selecting a world architecture.

The original 200 ms p95 check is retained for regression continuity. It does not establish smooth gameplay or acceptable moving detail. Future optimization comparisons must retain the same fixed resolution, transport and seed, report actual adaptive grids and image age, and add native-window input/painting checks. Larger worlds and higher fixed grids remain unmeasured here. No new hard gameplay FPS or minimum-resolution target is approved by this report.

## Reproduction and retained evidence

From the repository root, with Java 23 configured as described in the [game guide](README.md):

```powershell
./game-benchmark.ps1 -NoAdaptive -Profile -OutputDirectory out/game-profile-fixed
./game-benchmark.ps1 -NoBuild -Profile -OutputDirectory out/game-profile-adaptive
# Repeat without -Profile for timing controls; keep the output directories distinct.
./game-benchmark.ps1 -NoBuild -NoAdaptive -OutputDirectory out/game-control-fixed
./game-benchmark.ps1 -NoBuild -OutputDirectory out/game-control-adaptive
python tools/game/summarize_profile.py out/game-profile-fixed
python tools/game/summarize_profile.py out/game-profile-adaptive
```

Python 3 is optional for summarizing CSVs; use the path to an installed Python if `python` is not on PATH. `-Profile` adds `game.jfr` and `jfr-summary.csv`. The Java summarizer source is `tools/game/SummarizeJfr.java`; the launcher runs it without a visible console after recording completes. CSV output also contains primitive-test counts and aggregate trace CPU/allocation. Primitive counts do not include object-bound tests. Sampled allocation weights in JFR are estimates, not exact allocation totals. The ordinary route output retains its metadata, route hash, warmup and three publication CSVs, and summary. The launcher now also records Java source SHA-256 hashes and the Git revision; source hashes identify uncommitted profiling changes that a revision alone misses.

Compact evidence is retained under [profile-20261010](profile-20261010/fixed/summary.csv), with separate [adaptive results](profile-20261010/adaptive/summary.csv). Full recordings remain locally at `out/game-profile-fixed-final-20261010/game.jfr` and `out/game-profile-adaptive-20261010/game.jfr`; they can be regenerated using the commands above. Profiled timing must not be presented as normal unprofiled performance. Earlier unprofiled controls used the same route/settings: October 9 adaptive p95 was 34.258–34.733 ms at 102×64; October 10 fixed p95 was 154.678–156.251 ms at 320×200. An initial fixed-grid exploratory recording also gave 152–156 ms p95 and approximately 99% of execution samples in ray queries. The slower final fixed recording does not isolate a cause: host load and profiling perturbation were not controlled independently. The bottleneck attribution is consistent, but these runs do not establish JFR overhead or any optimization speedup. Repeated unprofiled A/B runs are required for an implementation decision.
