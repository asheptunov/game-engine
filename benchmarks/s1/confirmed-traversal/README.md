# GPU triangle confirmation: before and after

October 29, 2026. Results for the sample game on an NVIDIA RTX 3080 12 GB, driver 616.92, Windows, OpenJDK 23.0.1 with preview features.

Confirming accepted triangle intersections approximately halves GPU rendering time in the captured forest. It is enabled by default. The fixed-1080p moving combat benchmark improves from 19.4 to 34.0 fresh offscreen paints per second. This is useful progress toward the game's 120 FPS goal, but does not achieve or establish 120 real presentations per second at 1080p.

## Change and compatibility

[diffuse.comp](../../../assets/gpu/diffuse.comp) previously found the nearest triangle entirely in shader code without confirming any candidate to Vulkan. It now calls `rayQueryConfirmIntersectionEXT` after accepting a closer triangle. Confirmation allows traversal to shorten the remaining ray interval and avoid farther geometry. Source-face rejection, the near-distance cutoff, shadow early exit, shading, path depth, and sampling remain unchanged. The relevant behavior is described in the [Vulkan traversal specification](https://docs.vulkan.org/spec/latest/chapters/raytraversal.html).

[GpuKernel.java](../../../src/gpu/GpuKernel.java) selects the old path when the JVM property `-Dgpu.referenceTraversal=true` is set before kernel construction. The choice is a shader specialization constant; it introduces no per-ray runtime setting lookup. All kernel variants receive the same choice. The default is the confirmed path.

The shader still chooses scene-order ties among candidates returned by traversal. Hardware may prune equal-distance candidates, so identical tie selection across drivers is no longer guaranteed in the default path. Use the reference property when the original all-candidate ordering is required. Opaque-only traversal and deferring hit attributes until traversal finishes remain separate experiments.

## Fixed-camera results

All rows use a 1920×1080 grid, depth 8, seed 1, one fresh sample per dispatch, Full atmosphere, and automatic resolution disabled. “Smooth” means the existing center-detail sampling and spatial reconstruction; its settings are held constant within each before/after pair. Uniform samples the entire grid.

| Captured scene | Sampling | GPU before → after | GPU time reduction | Host before → after |
| --- | --- | --- | --- | --- |
| Forest | Uniform | 61.83 → 30.02 ms | 51.4% | 63.49 → 31.75 ms |
| Forest | Smooth | 38.84 → 19.27 ms | 50.4% | 40.64 → 20.88 ms |
| Older ruins | Uniform | 10.45 → 9.43 ms | 9.8% | 12.55 → 11.46 ms |
| Older ruins | Smooth | 6.59 → 6.19 ms | 6.1% | 8.20 → 7.76 ms |

Each number is the median of three round medians. Each round has 30 warmup dispatches and 100 measured dispatches per sampling mode; mode order alternates. Dispatches restart at sample zero to represent fresh images. GPU timestamps include tracing, atmosphere, reconstruction when enabled, and packed display conversion, including the captured bloom mode. Host wall time additionally includes preparation, submission/wait and readback; it is not added to the GPU column. Compilation, initial scene upload, gameplay, window submission and scanout are excluded from these warm measurements.

The forest capture retains aperture 0.02, focus 14.61, Optical bloom, and smooth settings `centerFraction=0.22`, `edgeSpacing=2`. The older ruins capture has zero aperture, bloom off, and smooth settings `0.5`, `6`. These are two different scenes and quality configurations; only matched before/after rows isolate this change.

The original shader was benchmarked before editing. A later forest run with the compiled reference switch reproduced the old cost: uniform round medians 61.07, 61.72 and 62.10 ms. Its saved 384×216 raw arrays matched all four original baseline arrays byte for byte. Smooth timings showed desktop variability in the third control round, so small differences should not be treated as precise universal gains. Raw timings, per-round summaries and settings are in the adjacent `*-samples.csv`, `before-*.log`, `after-*.log`, and [reference-forest.log](reference-forest.log).

## Moving game and resolution budget

The existing [PresentationBenchmark](../../../tools/gpu/PresentationBenchmark.java) now accepts `traversal` to alternate the two implementations across three paired rounds. Each run warms for two seconds and measures eight seconds on its time-driven camera route, with the current adventure world, moving combat objects, Full atmosphere and uniform sampling. Rendering feeds a 3840×2160 offscreen Swing surface. It counts fresh image paints, excluding cached redraws; it does not measure a visible window, native presentation or monitor scanout.

At fixed 1920×1080, the median run rate is **19.375 → 34.000 fresh paints/s**, a **75.5% increase**. The median of the runs' 95th-percentile fresh intervals falls from 57.04 to 33.96 ms. See [route.log](route.log).

With the game's **120 FPS adaptive budget**, the median of run mean internal widths rises from **480 → 676.8 pixels**, at 16:9. The improved path typically selects widths from 480 to 720. The first run in each mode includes a transitional 1920-wide frame; later runs establish the sustained range. Median fresh paint rate is 195.875 → 186.250/s: the controller spends the saved time on more detail, rather than retaining the smallest grid. These are offscreen rates, and the FPS budget is a resolution target, not a frame limiter. See [adaptive-route.log](adaptive-route.log). The internal resolution remains well below 1080p.

## Image checks and verification

[TraversalCheck](../../../tools/gpu/TraversalCheck.java), added to `gpu-check.ps1`, compares eight accumulated samples in seven synthetic scenes. It covers hidden layers in both scene orders, diffuse continuations and same-object shadows, coincident colored instances, multiple glass layers, an inside-glass camera and finite aperture. All comparisons are exact on this device; direct-light cases also pass the CPU comparison.

[TraversalCaptureComparison](../../../tools/gpu/TraversalCaptureComparison.java) compares full-resolution raw radiance and tone-mapped bytes at 1 and 16 samples. Each image has 6,220,800 RGB channels. Tone mapping uses capture exposure 1 and excludes bloom. The outputs are not universally bit-identical:

| Scene / sampling | Changed display channels, 1 sample | Maximum byte difference, 1 sample | Changed display channels, 16 samples | Maximum byte difference, 16 samples |
| --- | --- | --- | --- | --- |
| Forest / uniform | 6 | 21/255 | 12 | 3/255 |
| Forest / smooth | 4 | 3/255 | 18 | 3/255 |
| Ruins / uniform | 0 | 0 | 8 | 2/255 |
| Ruins / smooth | 0 | 0 | 4 | 1/255 |

All values are finite. Raw root-mean-square error ranges up to 9.43×10⁻⁶, but that aggregate does not describe every changed pixel: the largest single raw channel difference is 0.0173. The exact cause of these rare path differences was not isolated. They are compatible with changed traversal/tie or floating-point behavior, not proof of either. Inspection of the generated forest/uniform and ruins/smooth 16-sample image pairs found no visible structural differences. Logs retain all raw and encoded error statistics: [forest](forest-1080p-quality.log), [ruins](ruins-1080p-quality.log).

At benchmark time, `verify.ps1` and `game-check.ps1` passed, as did the existing 53 core GPU checks and all seven new traversal cases. The full GPU gate failed two existing fog approximation checks: `FogResolutionCheck` and `FogGridCheck`, both at depth 0, hour 7, divisor 4, with one pixel outside their error budget. Re-running each with `gpu.referenceTraversal=true` reproduced identical failure metrics. The remaining GPU suites were run after the first failure; all passed except that second fog check, including thin-leaf transport, adaptive resolution, image ownership, smooth sampling, bloom, and hidden native presentation. The original evidence remains in [gpu-gate.log](gpu-gate.log), [remaining-gpu-checks.log](remaining-gpu-checks.log), and the two `fog-*-reference-check.log` files.

Before publishing the separate feature commits, the forest commit `8a3be4c` tightened the coarse fog reconstruction fallback in `assets/gpu/diffuse.comp`. Dense foliage exposed narrow light shafts that neighboring coarse samples missed. The shader now requires a smaller radiance spread before reusing those samples and otherwise integrates the pixel at full resolution. This trades some Half/Quarter atmosphere speedup for accuracy; the benchmark runs above used Full atmosphere and did not exercise this fallback. Test error tolerances were unchanged. Both complete fog suites and the full GPU gate now pass; see [gpu-post-split.log](gpu-post-split.log). Visible-window play and input latency remain unmeasured.

## Reproduction and retained evidence

Run from the repository root in PowerShell. These measurements used game/GPU changes that were uncommitted at benchmark time and are now included in the feature commits preceding this report. Checking out only the original base commit does not reproduce that snapshot. [metadata.json](metadata.json) preserves that original base, source hashes, hardware and capture checksums. The capture folders below are local, ignored inputs and are not included with this report. Fixed-camera reproduction requires those captures or equivalent new captures; the moving benchmark generates its scene and needs no saved capture.

```powershell
./gpu-build.ps1
$jdk = "$env:USERPROFILE/.jdks/openjdk-23.0.1/bin"
$cp = 'out/gpu/classes;out/game/classes;out/gpu/lib/*'
& "$jdk/javac.exe" --enable-preview --release 23 -cp $cp -d out/gpu/classes tools/gpu/GpuCheck.java tools/gpu/TraversalCheck.java tools/gpu/TraversalBenchmark.java tools/gpu/TraversalCaptureComparison.java tools/gpu/PresentationBenchmark.java

$forest = 'out/game/captures/2026-10-29T13-51-40.663364200Z-0c27bbc2-e5b3-472e-b5ac-0ab6028da370'
$ruins = 'out/game/captures/2026-10-28T23-02-50.137238100Z-cda17c85-0df9-4815-8468-de81c266aba8'
& "$jdk/java.exe" --enable-preview '-Djava.awt.headless=true' '-Dgpu.referenceTraversal=true' -cp $cp gpu.TraversalBenchmark $forest out/traversal/repeat-reference
& "$jdk/java.exe" --enable-preview '-Djava.awt.headless=true' -cp $cp gpu.TraversalBenchmark $forest out/traversal/repeat-confirmed
& "$jdk/java.exe" --enable-preview '-Djava.awt.headless=true' -cp $cp gpu.TraversalCaptureComparison $forest traversal-forest

& "$jdk/java.exe" --enable-preview '-Djava.awt.headless=true' '-Dgame.benchmarkSeconds=8' -cp $cp game.PresentationBenchmark traversal
& "$jdk/java.exe" --enable-preview '-Djava.awt.headless=true' '-Dgame.benchmarkSeconds=8' '-Dgame.benchmarkBudget=120' -cp $cp game.PresentationBenchmark traversal
& "$jdk/java.exe" --enable-preview '-Djava.awt.headless=true' -cp $cp gpu.TraversalCheck
```

Repeat the capture commands with `$ruins` and distinct output names for the second scene. Run timing commands serially with other GPU applications idle. `TraversalBenchmark` writes per-dispatch CSV and 384×216 big-endian float RGB arrays at 1 and 16 samples. The comparison tool writes full-resolution 16-sample PNG pairs under `out/gpu/`. The archived CSV and logs are the measured evidence; generated images and raw arrays remain local.

The next performance work should use this default as the baseline. The forest still takes about 19 ms of GPU work even with its captured smooth sampling, so additional lighting/reconstruction and frame-pipeline work is needed for the 8.33 ms target. A visible-window route is required to evaluate the final 120 FPS goal.
