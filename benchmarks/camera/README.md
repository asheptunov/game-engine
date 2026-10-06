# Camera implementation verification

C1–C5 of [CameraRequirements.md](../../CameraRequirements.md) are implemented. Automated checks and preview inspection completed on 2026-10-06; the user reported that both deliveries looked good and approved committing and pushing the work. The manual checks below remain available for regression testing.

## Try it in the viewport

Rebuild and launch from the repository root using Java 23 preview. F12 switches from the startup texture editor to the viewport; `/` opens its console. Use these commands in order:

```text
view preset playground
view resolution 400 250
view camera focus distance 7
view camera mode orthographic
view camera height 7
view camera mode lens
view camera aperture 0.5
view camera focus distance 7
view camera focus distance 11
view camera aperture 0
view camera mode perspective
view camera fov 60
view camera status
view camera reset
```

In orthographic mode, check equal sizes across depth and forward movement without magnification. The floor is parallel to horizontal orthographic rays, so it can disappear until you look down. In lens mode, distance 7 favors the nearer coral sphere/blue box; 11 favors the distant gold sphere. Increasing aperture strengthens defocus; aperture zero exactly restores pinhole rendering. WASD/Space/Ctrl and mouse look should preserve optics; opening the console, switching scenes, reset and focus loss should clear held input. Switching back to orthographic should restore its height.

Try `view camera focus center` after aiming at a surface. It selects the first surface, including glass, and leaves focus unchanged on a miss. Focus distance is measured along camera forward, rather than along the slanted ray. The focus plane moves with the camera; there is no automatic subject tracking.

Try `view temporal on`, then open/close the aperture while F3 is visible. Finite aperture should immediately display raw output with the fallback reason. Requested temporal and motion-budget settings remain remembered; returning to zero aperture starts fresh compatible history. P4 `view interactive on` remains usable in every camera mode.

Use `view camera help` for group help, and `view camera focus center help` for leaf help. Equivalent root help routing is preserved. `view camera reset` restores the current preset camera and optics, retaining edited objects/materials and tracing resolution. It also preserves the special `glass-inside` viewpoint.

## Automated checks

Run `./build`, then `./test <suite>` from WSL/Bash with JDK 23. On this host the default WSL distribution had no Bash, so verification used the same compiler flags directly from PowerShell:

```powershell
$cameraSources = @(rg --files src tst -g '*.java')
Set-Content out/cli/.sources $cameraSources
& "$env:USERPROFILE\.jdks\openjdk-23.0.1\bin\javac.exe" -d out/cli --enable-preview --release 23 '@out/cli/.sources'
& "$env:USERPROFILE\.jdks\openjdk-23.0.1\bin\java.exe" --enable-preview -cp out/cli engine.CameraModelTest
```

All eighteen affected suites completed with no logged failures: `engine.CameraCompatibilityTest`, `CameraModelTest`, `CameraLifecycleTest`, `CameraSamplingTest`, `CameraControlsTest`, `ProgressivePathTest`, `DielectricPathTest`, `VolumePathTest`, `RoughLightingTest`, `MeshAccelerationTest`, `ParallelTraceTest`, `ResponsiveTraceTest`, `InteractiveResolutionTest`, `TemporalReconstructionTest`, `TemporalBudgetTest`, plus `ViewportKeyBindingsTest`, `ProfilingIntegrationTest`, and `ui.console.ConsoleInteractionTest`. Unqualified camera/rendering suites in this list are in `engine`. AWT-dependent suites used `'-Djava.awt.headless=false'` without opening windows. Inspect logs: the harness does not reliably return failing exit codes.

`CameraCompatibilityTest` contains five raw RGB float-bit hashes captured before extraction: triangle depth 0, bounce-room depth 3, glass-inside depth 8, volume-room depth 12, and a skewed/off-center bounce-room camera. All match after extraction. New tests cover orthographic origins/directions/scale, world-image round trips, guide depth/footprint/cuts/reuse, mode memory, validation/no-ops, lens focus convergence, uniform disk statistics, defocus growth, normalized brightness, depth-zero aperture sampling, axial first-surface focus/misses/staleness, varying-origin glass media, deterministic workers/tiles/batches/cancellation, exact captured asynchronous previews, settling, P4 optics and temporal-budget restoration. Camera ray sampling allocates no per-ray objects.

## Reproducible previews

```text
./test engine.CameraPreview perspective 7 0.5
./test engine.CameraPreview orthographic 7 0.5
./test engine.CameraPreview lens 7 0.5
./test engine.CameraPreview lens 11 0.5
```

These render playground at 400×250, depth 0, seed 1, 128 spp, display aspect 1.6 and default exposure. Output is in `out/cli/camera-<mode>-focus-<distance>.png`. All four images were inspected; foreground/background sharpness changes as expected without changed framing. Outputs are generated artifacts, not checked-in binary fixtures.

## Pinhole cost check

Before and after used the same Windows 11 host, Intel Family 6 Model 183, 20 logical CPUs, OpenJDK 23.0.1, one worker, tile 32 and exact camera/scene settings:

```text
./test ViewportBenchmark bounce-room depth=3 size=200 samples=1 seed=1 workers=1 warmup=20 frames=40 output=out/cli/camera-after.csv
```

| Metric | Before extraction | After extraction |
| --- | ---: | ---: |
| Median TRACE | 22.53 ms | 22.41 ms |
| p95 TRACE | 40.05 ms | 39.22 ms |
| Median full frame | 44.51 ms | 45.17 ms |
| Mean trace allocated bytes/job | 1,140 | 1,124 |
| Mean full frame allocated bytes | 11,667 | 16,412 |

This short window-free check supports comparable pinhole trace cost; it is not a controlled performance claim or an actual input/display latency measurement. Fixed per-job allocation covers captured/compiled camera data and worker setup; ray scratch is reused. Full-pipeline allocation increased by about 4.7 KiB/frame in this measurement, outside the trace allocation. Full-frame median changed by about 1.5%; timing and warm-up variation limit precision. Exact transport counts agree (40,000 primaries and 99,118 continuations in the final measured image). CSVs and settings sidecars are generated under `out/cli`.

`ViewportBenchmark` now also accepts `camera=perspective|orthographic|lens`, `fov=<degrees>`, `height=<units>`, `focus=<units>`, and `aperture=<radius>`. Set focus before entering orthographic through console commands when first-entry matching is desired; benchmark mode is configured before dependent optics, so `height` gives its explicit span. Lens timing includes disk sampling and origin containment; compare matching optics/scene/sample settings.

## Delivery limits

Finite-aperture temporal reconstruction is deliberately unavailable. Aperture radiance is averaged with normalized exposure, with no physical shutter/f-number/vignetting model. Real lens assemblies, diffraction, distortion, aberration, subject tracking and persistence remain deferred. Headless and synthetic-event tests verify renderer/input logic, but human GUI checks above are still required for physical interaction and display behavior.

## C4–C5 follow-up verification (2026-10-06)

Both projections now accept a finite aperture. `view camera projection perspective/orthographic`
preserves effective aperture; legacy `mode perspective/orthographic` closes it, while `mode lens`
restores perspective with the remembered radius. A deliberate radius of zero is remembered too.
Orthographic aperture samples translate with each reference image point; mean framing stays parallel,
with perpendicular-plane blur radius `aperture * abs(1 - axialDepth/focusDistance)`.

The original five pinhole fixtures remain exact. Before C4, two additional raw float-bit hashes were
captured on playground, 64×67, depth 3, seed 812, one worker, three spp, focus 7:

| Camera | Raw FNV-1a digest |
| --- | ---: |
| Perspective lens, aperture 0.2 | 2544894998670422241 |
| Orthographic, aperture 0 | -5477149993092030197 |

`CameraFollowupCompatibilityTest` retains both exactly. `CameraOpticsTest` checks analytic translated
bundles, disk coverage, focus-plane convergence, mean magnification/no breathing, uniform-emitter
brightness, depth-zero sampling, nested starting media and worker/tile/batch/cancellation agreement.
`CameraLifecycleTest` now also covers finite orthographic previews and aperture-history toggles.

`FocusControllerTest` uses a fake monotonic clock for reciprocal-distance linear/smooth progression,
exact endpoints, retargeting, no-op targets, policy edits, pause/resume, moving-subject dwell,
cumulative tolerance, loss/reacquisition and invalid distances. It covers axial off-center queries,
source coordinates, center aliases, mesh-face subject identity, cached preparation/BVH traversal,
bounded pose lag and a fake alternate target provider. This is the extension boundary for future
timeline/world/subject selection; those providers are not shipped.

`FocusLifecycleTest` uses latches to hold a query while selection/manual control changes. Thirty
selection edits still issue only one in-flight query; late results are discarded. It checks cadence,
suspension, strict one-shot rejection and zero-aperture accumulation/history equivalence. Scripted
finite-aperture autofocus in both projections publishes coherent captured-camera previews that
match independent reference traces, settles exactly, restores the requested grid, and reaches
the sample target. `ProfilingIntegrationTest` uses the actual asynchronous resolver and live viewport
to verify acquisition after target completion and captured-optics F3 metadata.

The native Java 23 compiler succeeds, and all 23 affected suites report results without failures:
the 19 viewport suites from `CameraCompatibilityTest` through `TemporalBudgetTest` (including the
four new follow-up suites), plus `ViewportKeyBindingsTest`, `ProfilingIntegrationTest`,
`ui.console.ConsoleInteractionTest` and `profiling.FrameProfilerTest`. Logs are
`out/cli/followup-<suite>.log`. Verification opens no windows. The harness exit code alone is
insufficient; logs were inspected for failures and actual result summaries.

One repeated `ViewportKeyBindingsTest` attempt transiently failed its existing synthetic-console
resolution assertion; an immediate unchanged rerun passed. The test reads native keyboard lock
state, but the cause of this attempt was not established. The failed attempt is preserved in
`out/cli/viewport-keybindings-transient-first-attempt.log`; the latest suite log contains the
passing rerun. The focus-specific controller/lifecycle/live integration checks passed throughout.

Two finite orthographic previews hold height 7 constant while changing focus:

```text
./test engine.CameraPreview orthographic 7 0.5 7
./test engine.CameraPreview orthographic 11 0.5 7
```

Outputs are `out/cli/camera-orthographic-focus-<distance>-aperture-0.5-height-7.0.png`.
Both were inspected: near sphere/box details sharpen at 7, and the distant gold sphere sharpens
at 11 with unchanged framing. F3 composition preview: `out/cli/focus-f3-preview.png`.

## Query cost

`./test engine.FocusQueryBenchmark` warms 100 queries and measures 1,000 selected-point
queries per preset with unchanged default geometry on OpenJDK 23.0.1, the same Windows host as
the earlier camera check. Normalized points span u=0.25..0.75, v=0.5. Time includes cached lookup,
reference-ray generation, bounds/BVH intersection, focus validation and measurement construction;
initial preparation is warmed separately. There is no tracing, reconstruction or AWT presentation.

| Preset | Median | p95 | Prepared instances | Additional preparations | Primitive tests | Capture ages >100ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| playground | 0.0025ms | 0.0101ms | 5 | 0 | 6,085 | 0 / 1,000 |
| mesh-room | 0.0026ms | 0.0059ms | 18 | 0 | 6,638 | 0 / 1,000 |

Output: `out/cli/focus-query-benchmark.log`. These are warm query costs for these small presets,
not general autofocus latency or display performance claims. Freshness replay rejects observations
after >100ms and after >2% projected displacement, while accepting a small translation. The latched
overload case holds focus and rejects old control/scene/source results without a queue. Live stale
rates depend on geometry, CPU load and camera motion; the 20Hz limit does not promise a minimum rate.

## Human follow-up test

Launch the rebuilt app, switch to the viewport with F12, then open `/` and run:

```text
view preset playground
view resolution 400 250
view camera aperture 0.5
view camera focus transition duration 700
view camera focus transition curve smooth
view camera focus auto delay 150
view camera focus auto tolerance 1
view camera focus mode auto
```

Close the console and aim the center at foreground/background surfaces. Focus should pull smoothly,
settle and resume refinement. Brief foreground interruptions should wait for the acquisition delay;
a miss holds the last realized distance. F3 shows actual/target focus and the shown image's optics.
The geometric query selects the first glass surface, not the object seen through refraction.

Try another selection and deliberately pull manually:

```text
view camera focus source screen 0.65 0.5
view camera focus mode manual
view camera focus pull distance 7
view camera focus pull distance 11
view camera focus transition curve linear
view camera focus transition duration 1200
view camera focus pull center
view camera focus pull source
view camera projection orthographic
view camera height 7
view camera focus mode auto
```

`v=0` is bottom, independent of resolution. `center` always means (0.5,0.5), even after a source edit.
Live surface commands acknowledge a queued query; `view camera status` shows its eventual outcome.
Miss/stale queries preserve the prior policy/pull. Compare fast/slow and linear/smooth pulls; verify
manual freeze while reframing, orthographic blur without magnification changes, pause/resume,
F12 away/back, window focus loss/return, and autofocus continuing while the console is open.
Set aperture 0 and verify autofocus remains useful for preparing focus while stationary accumulation
continues; reopening the aperture applies that realized focus. Camera reset restores all focus defaults.
