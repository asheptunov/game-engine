# Continuous-spacing center detail experiment

October 28, 2026. Audience: players comparing the GPU options and renderer maintainers.
This follows the [gradual block-density experiment](../falloff/README.md), which the
player liked but could still distinguish by its stringy transitions.

## Try the comparison

Relaunch `./game.ps1 -Backend gpu`. In Escape → Render, choose 1080p, Fixed resolution
and Full atmosphere. Enable **Center detail (experimental)** and then **Smooth center
detail (experimental)**. Both switches default to off. Smooth has no effect unless
Center detail is enabled. CPU rendering stays uniform.

Toggle only Smooth to compare continuous reconstruction with the previous gradual
block-density version. Toggle Center detail off to compare with uniform rendering.
Inspect the ruins pillars, trees, weapon silhouette and shadows while moving and
standing still. The question is whether smoother changes in noise outweigh extra
softness and rendering cost. This is an optional trial, not a replacement of the
previous mode or a promise that transitions will be invisible.

## Sampling and reconstruction

`src/gpu/GpuSmoothFoveation.java` builds a symmetric sample lattice whose spacing
increases continuously along each screen axis. The central half of each axis retains
ordinary pixel centers; spacing then rises from one toward six pixels through a
smoothstep curve. The protected center is approximately a 960×540 rectangle at 1080p.
This is horizontal/vertical falloff, not circular or elliptical rings. Edges are
covered with clamped endpoint samples. The layout and inverse coordinate tables
are cached by internal resolution and built on the GPU owner thread.

`assets/gpu/diffuse.comp` traces one jittered path per lattice location and accumulates
its own radiance mean. A second compute pass reconstructs every output pixel from
nearby samples using a positive tent filter. Its support has the same width in both
screen directions, avoiding the stretched noise produced by simple interpolation
where sample spacing differs between axes. The protected full-density interior
remains exact; the peripheral filter intentionally softens detail.

The nearest lattice sample supplies a surface reference. Neighboring samples must
have the same object identity, normal dot product above 0.9, and depth difference
below the larger of 0.02 scene units or 3% of the larger depth. Misses can blend with
other misses. These guides come from the existing primary path; there is no extra
full-resolution visibility pass. This prevents many cross-surface blends but cannot
recover thin objects that were never sampled. Silhouette errors, jitter, residual
coarse noise and softened texture detail remain possible. Guides describe the current
pass, whereas radiance means may include earlier passes at that same captured view.

The GPU barrier between tracing and reconstruction makes completed means and guides
visible before output is written. Raw captures and packed presentation use this same
reconstructed output. The new setting participates in render invalidation and adaptive
cost resets. Held image leases remain stable. Captures retain both flags; older files
without the Smooth flag default to the previous mode. Full primary atmosphere/fog
integration is retained, and requested Half/Quarter settings return when Center detail
is disabled. Adaptive resolution may still reduce the whole grid; no new temporal
history or adaptive-policy tuning is included.

The two-dimensional guide array adds about 31.3 MiB at 1080p, plus small coordinate
tables. Buffers are reused at the same grid, replaced on resize after prior work
completes, and owned by the device until release. Disabling the option retains its
bounded cached allocation. Full-size output and readback costs remain.

## Captured-scene evidence

Input: the local game capture
`out/game/captures/2026-10-28T23-02-50.137238100Z-cda17c85-0df9-4815-8468-de81c266aba8`.
The displayed state is fixed at 1920×1080, depth 8, Full atmosphere and exposure 1.
The input capture is not copied into this report; the reproduction command requires
that local folder. The images below are fresh renders of its displayed camera and
world, with only the sampling mode changed. They do not recreate original scheduling.

| Mode | One sample per lattice location | 32 samples per lattice location |
| --- | --- | --- |
| Uniform | [Image](uniform-1.png) | [Image](uniform-32.png) |
| Previous gradual blocks | [Image](discrete-1.png) | [Image](discrete-32.png) |
| Continuous spacing | [Image](smooth-1.png) | [Image](smooth-32.png) |

Visual inspection of the continuous images shows smoother transitions and softer
peripheral noise, with residual edge artifacts. An initial interpolation-only attempt
stretched noise into strings; the equal-width filter above addresses that effect.
The central 400×300 crop is byte-identical to uniform in the one- and 32-sample images.
These are equal-pass comparisons, not equal-time quality comparisons. Human movement
quality acceptance is pending.

## Measured cost

`tools/gpu/SmoothFoveationCapture.java` measures all three modes against the same
captured state using packed readback, with no Swing window, combat progression or
physical presentation. Each mode warms up for 30 calls, then measures 100 fresh
sample-zero calls. Order alternates over three rounds. The reported values are the
median of each round's median. Hardware: RTX 3080 12 GB, Windows, Java 23. No agent
builds or tests ran during timing; other desktop activity was uncontrolled.

| Mode | Primary paths at 1080p | Median GPU time | Median host completion |
| --- | ---: | ---: | ---: |
| Uniform | 2,073,600 (100%) | 13.02 ms | 15.42 ms |
| Previous gradual blocks | 793,167 (38.25%) | 6.92 ms | 9.14 ms |
| Continuous spacing | 1,026,000 (49.48%) | 8.65 ms | 10.93 ms |

GPU time includes reconstruction and display encoding. Host completion additionally
includes preparation, submission/waiting and readback. Continuous mode costs about
25% more GPU time than the previous mode in this capture, while remaining about 34%
below uniform. It passes the provisional 55% primary-ray ceiling. It has not been
shown to improve live FPS or meet a minimum 60/120 FPS target. Results from earlier
scenes or sessions are not directly comparable. See [all timing rows](timing.csv).

## Checks and reproduction

Required gates: `./verify.ps1`, `./gpu-check.ps1 -NoBuild` after `./gpu-build.ps1`,
and `./game-check.ps1`. All three passed on the final implementation. Gate logs accompany this report. `SmoothFoveationCheck` checks
coordinate symmetry, monotonicity and coverage; exact protected-center pixels on
odd/thin grids and 1080p; deterministic restart and accumulation; finite aperture;
Full atmosphere/fog fallback; no red-surface/blue-sky color leakage in a constructed
boundary scene; raw/packed agreement; toggles; fallback restoration; leases; and
owner retirement. The game capture test checks setting preservation and old files.
Existing GPU checks continue to exercise the previous mode and uniform rendering.

From the repository root in PowerShell:

```powershell
./gpu-build.ps1
$smoothJdk = 'C:/Users/andri/.jdks/openjdk-23.0.1/bin'
$smoothClasspath = 'out/gpu/classes;out/game/classes;out/gpu/lib/*'
& "$smoothJdk/javac.exe" --enable-preview --release 23 -cp $smoothClasspath -d out/gpu/classes tools/gpu/GpuCheck.java tools/gpu/SmoothFoveationCheck.java tools/gpu/SmoothFoveationCapture.java
& "$smoothJdk/java.exe" --enable-preview '-Djava.awt.headless=true' -cp $smoothClasspath gpu.SmoothFoveationCheck
& "$smoothJdk/java.exe" --enable-preview '-Djava.awt.headless=true' -cp $smoothClasspath gpu.SmoothFoveationCapture out/game/captures/2026-10-28T23-02-50.137238100Z-cda17c85-0df9-4815-8468-de81c266aba8
```

The comparison command prints CSV timing rows and writes `out/gpu/smooth-<mode>-<samples>.png`.
Passing a second argument such as `preview` skips timing and generates only images.
Source hashes accompany the results. The implementation remains an optional experiment
pending human feedback; advanced denoising, a radial sampling lattice and adaptive
budget retuning are separate future work.
