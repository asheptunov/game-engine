# Gradual center-detail transitions

October 28, 2026. Follow-up to the [initial foveation experiment](../README.md).
Audience: players evaluating the experimental control and renderer maintainers.

## Try it

Relaunch `./game.ps1 -Backend gpu`. In Escape → Render, select 1080p, Fixed
resolution and Full atmosphere, then enable **Center detail (experimental)**.
The same control now uses gradual density transitions. It remains off by default.
Look at dark pillars and shadows while moving, turning and standing still. The
center should remain sharp; changes in noise size should be less conspicuous.
Turn the option off to restore uniform sampling.

The player reported a noticeable performance gain with the first version, but
visible rings in low-light, noisy surfaces. Their input capture is
`out/game/captures/2026-10-28T23-02-50.137238100Z-cda17c85-0df9-4815-8468-de81c266aba8`.
It contains a displayed 1920×1080, one-sample GPU image with depth 8, Full
atmosphere, fixed resolution and center detail enabled.

## What changed

`src/gpu/GpuFoveation.java` now assigns a density to each 4×4 screen-space tile
instead of switching all 8×8 tiles at two sharp radial boundaries. Normalize
each screen axis to −1..1 and measure elliptical radius at the tile center:

| Radius | Sampling |
| --- | --- |
| Up to 0.5 | One independent path per pixel |
| 0.5–0.7 | Gradually mix 1×1 and 2×2 footprints |
| 0.7–0.75 | One path per 2×2 footprint |
| 0.75–0.95 | Gradually mix 2×2 and 4×4 footprints |
| Beyond 0.95 | One path per 4×4 footprint |

A deterministic integer hash selects each tile's density using a smoothstep
probability curve. The pattern is fixed in screen coordinates at a given
resolution, independent of camera pose, frame number, sampling seed and sample
count. This prevents density reshuffling from corrupting progressive means or
adding frame-to-frame changes in footprint size. Ordinary tracing noise remains.
Changing internal resolution rebuilds the pattern and resets accumulation as before.

Each footprint still shares one radiance estimate across its pixels. This is a
gradual change in average density, not fractional-size footprints, image blurring
or denoising. It can leave irregular patches and chunky peripheral noise. The
central ellipse retains approximately 960×540 pixels of full density at 1080p.
The HUD and uniform mode are unchanged. Full atmosphere integration remains
forced while center detail is enabled; requested Half/Quarter settings return
when it is disabled. Adaptive resolution can still scale the whole grid.

The compact GPU location list remains the production dispatch path. The sparse
diagnostic path in `assets/gpu/diffuse.comp` evaluates the same pattern independently;
tests require its full image to equal the compact output exactly.

## Captured-scene comparison

The same displayed state was rendered fresh at one sample before and after the
change. These are rendered comparisons, not modified screenshots:

- [Hard boundaries](before.png)
- [Gradual transitions](after.png)

The central 400×300 crop is byte-identical between the two replays.
Visual inspection shows softer transitions across the pillars, with residual
patches and coarse noise. Human acceptance during motion remains pending. A
static replay cannot establish motion quality or restore the original scheduler
and temporal history.

## Cost

At 1080p the primary-path count rises from 655,680 (31.62% of uniform) to 793,167
(38.25%). This passes the provisional limit of 42%. Continuation and shadow-ray
counts depend on the scene; these percentages are not FPS predictions.

`gpu.FoveatedCost` uses the existing static ruins view at hour 6.396, depth 8,
Full atmosphere, 1920×1080, one sample, packed readback and no Swing presentation.
Each mode warms up for 30 calls, then records 100. It alternates uniform, sparse
and compact ordering over three rounds. Hardware: RTX 3080 12 GB, Windows,
Java 23. Hard-band measurements preceded the rebuild; gradual measurements
followed it. No agent builds/tests ran concurrently with timing; other desktop
activity was uncontrolled. Compare the raw logs as well as these summaries.

| Production mode | Median of three median GPU times | Median of three median host times |
| --- | ---: | ---: |
| Original hard bands | 4.53 ms | 6.45 ms |
| Gradual transitions | 5.25 ms | 7.38 ms |
| Uniform, in the gradual run | 12.26 ms | 14.62 ms |

The measured GPU increase versus hard bands is about 16%, while gradual sampling
still uses about 57% less GPU execution time than uniform in this workload.
The earlier baseline's uniform GPU median was 12.95 ms, showing some run-to-run
variation. These are isolated timings, not live fresh-frame rates or minimum FPS.
The original 46→71 fresh-paints/sec result is historical; live fixed/adaptive FPS
has not been remeasured for this follow-up.

Raw data: [hard-band timing](before-cost.csv), [gradual timing](after-cost.csv).

## Verification and reproduction

`tools/gpu/FoveatedCheck.java` checks protected center pixels against uniform
rendering, smooth density proportions in radial bins, the path-count limit,
complete footprints at odd/thin grids and 1080p, compact/sparse equality,
progressive accumulation, finite aperture, fog fallback, toggles, raw/packed
agreement, retained leases and close. Required gates are `./verify.ps1` and
`./gpu-check.ps1 -NoBuild` after `./gpu-build.ps1`. Both gates passed on the final source. Results accompany this
report; manual movement quality remains a separate acceptance step.

From the repository root in PowerShell, replay the captured displayed state:

```powershell
./capture.ps1 -Capture out/game/captures/2026-10-28T23-02-50.137238100Z-cda17c85-0df9-4815-8468-de81c266aba8 -Action render -Backend gpu -View displayed -Samples 1 -Output out/performance/falloff-after.png
```

For the isolated cost comparison on the current implementation:

```powershell
./gpu-build.ps1
$falloffJdk = 'C:/Users/andri/.jdks/openjdk-23.0.1/bin'
$falloffClasspath = 'out/gpu/classes;out/game/classes;out/gpu/lib/*'
& "$falloffJdk/javac.exe" --enable-preview --release 23 -cp $falloffClasspath -d out/gpu/classes tools/gpu/FoveatedCost.java
& "$falloffJdk/java.exe" --enable-preview '-Djava.awt.headless=true' -cp $falloffClasspath gpu.FoveatedCost
```

Current code generates gradual mode only; the saved baseline logs and images
preserve the earlier experiment. Source hashes identify the follow-up files;
the original report's hashes identify the baseline. Edge-aware reconstruction
and adaptive-budget tuning remain deferred pending player feedback.
