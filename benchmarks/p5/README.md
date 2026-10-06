# P5 temporal reuse measurements

P5 adds optional diffuse temporal reconstruction with `view temporal on`. Raw samples remain independent, and `view temporal off` immediately restores them. History reduces one-spp noise in the tested rooms, but collecting and validating it costs time and memory. Keep it off by default: the equal-time benefit depends on the scene, and the initial native cost check showed slower motion processing with it enabled.

## Implementation and controls

`view temporal on/off` controls live presentation. `view temporal help` explains the scope; `view status` reports the setting. P4 interactive resolution remains independent. Changing its actual grid discards temporal history. The blocking reference renderer always displays raw output.

The existing trace workers capture a center ray and up to four corner rays per pixel when a camera/grid needs new guides. Guides store normalized-ray distance, a camera-facing normal and a stable prepared primitive ID. Sampled primary rays can invalidate a guide when their subpixel surface differs. Guide queries do not consume the sampler stream; their rays/tests have separate tracer counters. Their CPU/allocation cost is included in tracing statistics.

Only nonemissive opaque diffuse surfaces are eligible. Misses, mirror/glass surfaces, inside-media cameras and mixed corner/center hits reject history. Scattering anywhere in a scene disables it for the whole image. Neighboring pixels must agree on the primitive in both cameras; conservative checks also reject silhouettes and mesh face seams. These finite guards detect the tested thin strips and mixtures, but cannot prove that every microscopic subpixel surface has been found.

The coordinator reconstructs the current center hit in world space and projects it into the previous captured camera. Nearest-pixel correspondence must match identity and normals, remain within 1.5 pixel footprints, and lie within 0.1 footprint plus 0.0001 world units of the same surface plane. Normal agreement must exceed 0.995. History rejects transport/geometry/light/material/seed/depth/restart/grid changes. Camera cuts include translation over one quarter of the pinhole distance, approximately 11 degree orientation changes, and focal/sensor-size changes over one percent. Images more than 500 ms apart cannot reuse history.

Current radiance bounds from a 3×3 neighborhood clamp the historical color. Blending uses current raw spp and at most four nominal history samples. Each moving blend has at least 20% fresh radiance, so an older contribution fades by a factor of at most 0.8 at each valid camera transition. These are reconstruction weights, not independently traced samples. At an unchanged camera, raw refinement keeps the prior camera frozen and never feeds the current estimate into itself. Raw accumulation remains unbiased by this feature; reconstructed presentation can blur or introduce bias through correspondence and clamping.

Reconstruction splits rows across the same bounded process-wide pool used for tracing. Each task owns its projection scratch; rows have disjoint output. Workers join before publication, including on failure/interruption. Hard cancellation is checked between rows and discards partial history. Camera-only movement still lets the captured pass/reconstruction publish; it never queues cameras or cancels every mouse event. Pause/target/suspend/enable changes clear history conservatively.

Raw and reconstructed RGB are copied into the existing three leased publication slots. A retained publication cannot be overwritten. Disabling restores raw conversion immediately; the next coordinator job releases reconstruction storage. A separate mode revision prevents a quick off/on edit from reviving an old completed history, even if the coordinator never observed the intermediate off setting. No image-sized allocation, tracing or reconstruction occurs under the input monitor.

## Camera dependent grain follow up

The first implementation reused the same pixel/sample-zero random stream after every camera change. This made raw noise appear attached to screen coordinates; reprojecting that correlated grain could make history look like dragging compression artifacts. Camera paths now scramble the configured seed with a deterministic hash of the captured eye, sensor origin and sensor edges. The hash is computed once per trace call and is independent of time, workers and camera visit order. Camera motion changes grain; a repeated identical view reproduces it. Stationary sample numbers still advance and average normally. Cached redraws do not add artificial grain. The depth-zero point-light diagnostic and explicit ray-level numeric adapters retain their existing behavior.

The follow-up used the same resolution, scene path, reference quality and soft budget as the original run. `camera-grain-motion-results.log` records the camera-dependent stream before the continuous-fading fix below; the original logs and table below remain historical measurements of the previous stream.

| Scene | One-spp raw MSE | One-spp temporal MSE | Reduction | Equal-budget raw MSE | Equal-budget temporal MSE |
| --- | ---: | ---: | ---: | ---: | ---: |
| Diffuse area-light room | 0.0741720 | 0.0598254 | 19% | 0.00779557 | 0.00807264 |
| Bounce room | 0.925028 | 0.322637 | 65% | 0.0691215 | 0.0568291 |
| Glass | 0.0630792 | 0.0469828 | 26% | 0.0100687 | 0.00924603 |
| Volume room | 0.0818850 | 0.0818850 | 0% | 0.0379782 | 0.0344749 |

At the soft equal budget, bounce-room improved about 18%, glass about 8%, and the diffuse room worsened about 4%. One-spp cut frames and rejected guide pixels still match raw exactly. Volume remains raw fallback; timing changes its completed pass count. Run-to-run scheduling/timing also changes spp, so compare modes within each run rather than attributing all differences between runs to seeding. These numeric checks do not establish that the visible history drag is gone: nearest-pixel reprojection and clamping are unchanged. Current comparison PNGs reflect the most recent run.

Twelve suites were rechecked after the sampling change, including all transport families, parallel/batch agreement, temporal reconstruction, async previews/cancellation, P4 adaptation, input and F3 integration. The new `CameraSamplingTest` verifies different sample-zero streams for changed eye/origin/edges, reproduction across camera visit orders/workers/batches, and the unchanged point-light depth-zero diagnostic.

## Continuous fading follow up

The initial eight-transition age cutoff made valid wall pixels reset together roughly every nine fresh images. At 23–27 fresh FPS, this corresponds to the reported 2.5–3 Hz pulse. That cutoff and its age arrays have been removed. The existing capped blend now continuously fades older information, retaining at least 20% fresh radiance per moving update. Surface mismatch, cuts, scene edits, stale-image rejection and stationary raw refinement remain intact.

Regression tests run 96 forward/backward updates at 40 ms intervals, requiring sustained reuse and stable blend confidence beyond the former reset boundaries. A separate paired-history test follows one old radiance difference for 32 transitions: it stays nonzero at transition nine, then decays smoothly to below 0.001. Five affected suites pass (TemporalReconstructionTest, ResponsiveTraceTest, InteractiveResolutionTest, CameraSamplingTest and ProfilingIntegrationTest). These establish removal of the periodic age reset; visible motion still needs the user's appraisal. The earlier quality/cost tables predate this change and are retained as labeled historical measurements.

## Original quality and equal time

Measured on Windows/OpenJDK 23.0.1, 20 logical CPUs, 14 workers and 32-pixel tiles. Baseline commit is `ec6b24c38520618d2e5b21a31c69aab43dce1a48`. Both modes run the P5 source; disabling eliminates guides/reconstruction. Resolution is 160×100, seed 1, preset depth (diffuse-room/glass 8, bounce-room 3, volume-room 12), one raw spp per tracing call, 12 warmup and 18 measured camera snapshots. Diffuse-room converts rough-room's nonemissive objects to diffuse materials. A translation, direction reversal and one-unit camera cut exercise correspondence. Each snapshot has a 128-spp raw reference.

| Scene | One-spp raw MSE | One-spp temporal MSE | Reduction | Equal-budget raw MSE | Equal-budget temporal MSE |
| --- | ---: | ---: | ---: | ---: | ---: |
| Diffuse area-light room | 0.0710542 | 0.0660896 | 7% | 0.0103059 | 0.0128178 |
| Bounce room | 0.942384 | 0.514203 | 45% | 0.111646 | 0.0864507 |
| Glass | 0.0644451 | 0.0556697 | 14% | 0.00853255 | 0.00935016 |
| Volume room | 0.0797900 | 0.0797900 | 0% | 0.0293604 | 0.0278848 |

MSE is mean squared linear RGB error against the finite reference, over the whole image including unsupported surfaces. The soft 16.67 ms budget allows additional complete raw passes when predicted cost fits; final passes can overshoot. Mean elapsed time was about 15 ms for the surface rooms. Raw/temporal mean spp were 7.00/5.44 (diffuse), 7.83/7.28 (bounce), and 6.94/5.83 (glass). Bounce-room error fell about 23% at this budget; diffuse and glass error rose about 24% and 10%. Volume mode is exact raw fallback; its equal-budget difference comes from timing changing the completed pass count, not history.

For one spp, direction-reversal MSE fell 0.983378→0.500581 in bounce-room and 0.0665484→0.0560762 in glass. Camera-cut frame error matched raw exactly in every scene. The maximum reconstructed-versus-raw difference on rejected guide pixels was exactly zero. Separate tests check newly visible identities, depth/normal mismatch and thin geometry. These checks bound tested trails and resets; they do not establish absence of ghosting in all scenes. Nearest correspondence can shift detail, and clamping can suppress rare bright samples.

The benchmark writes comparison PNGs to `out/cli/p5-<preset>-comparison.png`: raw / temporal / 128 spp, before reversal and at the camera cut. Inspection showed reduced diffuse noise, unchanged mirror/glass edges and exact raw fallback at the cut. The F3 composed preview is `out/cli/p5-f3-preview.png`; history metadata fits on a separate line from grid and raw spp.

## Original quarter and native processing cost

The cost run uses 18 warmup and 18 measured moving snapshots, one raw spp, the same depth/seed/workers/tile settings, and 360×225 or 1440×900. Timings include guide generation, tracing and reconstruction; they exclude display conversion, leased publication copies, UI, scheduler pacing and AWT presentation. They measure processing cost, not displayed FPS.

| Scene | Grid | Raw mean ms | Temporal mean ms | Reconstruction mean ms |
| --- | --- | ---: | ---: | ---: |
| Bounce room | 360×225 | 8.19 | 22.55 | 8.24 |
| Bounce room | 1440×900 | 115.58 | 228.15 | 44.60 |
| Glass | 360×225 | 7.82 | 13.93 | 2.07 |
| Glass | 1440×900 | 110.53 | 221.29 | 32.46 |

Native overhead remains substantial even after parallel reconstruction. Five visibility checks per eligible pixel account for much of the remaining guide cost. History expires across very slow frames rather than retaining unsuitable old imagery. This implementation earns an optional quality experiment, not a default performance improvement. Prefer the raw mode for maximum fresh-image throughput; the next intersection phases can reduce work shared by both modes.

A separate baseline/disabled/disabled/baseline check used the committed P4 tracer as a classpath overlay, with native glass, depth 8, one spp, seed 1, 14 workers, tile 32, 30 warmup and 40 measured stationary full-pipeline frames. Baseline median frame times were 107.86/88.70 ms; disabled P5 medians were 107.44/86.36 ms. Transport counters matched. This shows no consistent disabled-mode slowdown in the check; p95 and convergence times varied with system load. The four logs retain that variability rather than treating it as a throughput gain.

## Memory and diagnostics

Two reconstruction histories now retain 72 bytes of pixel payload per sensor pixel: each has guide data (20), RGB (12), and weight (4). The tracer's current guide adds 20; up to three reconstructed publication buffers add 36. Total additional maximum payload is 128 bytes/pixel, plus Java headers/rows/task scratch. It is bounded by configured grid limits and publication count, not motion duration. Earlier cost logs include the removed age arrays (74/130 bytes per pixel).

| Grid | Two histories MiB | Maximum additional payload MiB |
| --- | ---: | ---: |
| 360×225 | 5.56 | 9.89 |
| 1440×900 | 88.99 | 158.20 |
| 1600×1600 limit | 175.78 | 312.50 |

F3 reports reused/eligible pixels, mean blend confidence over eligible pixels, reconstruction wall time, and the **two-history payload only**. Blend is a presentation weight, not a probability of correctness or raw spp. Trace wall/CPU/allocated-byte statistics include guides but exclude reconstruction; the separate history timing and fresh-image intervals expose its cost. Unsupported volume scenes retain no temporal buffers. Turning off frees history and guide storage on the next job; leased older publications remain valid until released.

## Verification and reproduction

Fourteen suites pass: TemporalReconstructionTest, ResponsiveTraceTest, ParallelTraceTest, InteractiveResolutionTest, ProgressivePathTest, DielectricPathTest, RoughLightingTest, MeshAccelerationTest, VolumePathTest, FrameProfilerTest, ProfilingIntegrationTest, ViewportKeyBindingsTest, ConsoleInteractionTest and ViewportFooterTest. Tests cover exact raw streams/counters, stationary refinement, bounded age/storage, cuts/edits/expiration, unsupported geometry/media, lease immutability, pause/toggle/restart, partial cancellation, scalar/parallel equality, immediate raw raster restoration and F3 metadata. Automated runs open no window. Physical GUI motion/scanout remains a manual check.

After compiling all sources/tests with Java 23 preview:

```powershell
& "$env:USERPROFILE\.jdks\openjdk-23.0.1\bin\java.exe" --enable-preview -cp out/cli engine.TemporalReconstructionTest
& "$env:USERPROFILE\.jdks\openjdk-23.0.1\bin\java.exe" --enable-preview -cp out/cli engine.TemporalReuseBenchmark
& "$env:USERPROFILE\.jdks\openjdk-23.0.1\bin\java.exe" --enable-preview -cp out/cli engine.TemporalReuseBenchmark cost
& "$env:USERPROFILE\.jdks\openjdk-23.0.1\bin\java.exe" --enable-preview '-Djava.awt.headless=false' -cp out/cli ProfilingIntegrationTest
```

The benchmark logs archive timing, spp, error, cut/reversal and memory columns. Test logs must be inspected for `[ERROR]`: the harness does not signal failures through its exit code. `source-hashes-original-stream.txt` identifies the original P5 implementation; `source-hashes-camera-grain.txt` identifies the camera-dependent stream before the age fix; `source-hashes.txt` identifies continuous fading. The original motion-quality timing run precedes the camera-facing normal correction (all its eligible diffuse surfaces face the camera), formatting, exact-counter tests and quick-toggle revisions. The camera-grain quality run uses the current camera-dependent sampler with the earlier age cutoff. Continuous-fading validation is in the `temporal-fade-*Test.log` files.
