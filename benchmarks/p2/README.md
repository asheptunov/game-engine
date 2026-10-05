# Responsive renderer P2 results

P2 removes the full-frame state lock from live rendering. Synthetic AWT events
remain responsive during native glass tracing and image conversion. Fresh image
latency is still much longer than a 60 Hz interval; this phase does not establish
60 fresh samples per second.

## Continuous camera motion

The first P2 policy cancelled every changed camera snapshot and accepted only an
exact match to the latest camera. Continuous mouse movement could therefore
prevent any pass from publishing while display FPS stayed stable. Camera-only
edits now let the active pass finish as a complete preview of its captured camera,
stop further passes in that batch, then capture the latest camera for the next job.
Scene/resolution/restart edits, pause and suspension still cancel between tiles.
Previews keep their own generation and never contribute samples to a different
camera's accumulation. Image age now measures time since snapshot capture.

With 100 synthetic AWT mouse events in native glass, fourteen workers and 32-pixel
tiles, restoring the old camera-cancellation policy displayed **zero** new camera
images during motion. The preview policy displayed **15** before movement stopped.
P95 handler time was 0.328 ms before and 0.271 ms after; display-work p95 was
29.79 ms before and 34.52 ms after. Completing useful passes increases CPU work
compared with repeatedly cancelling them. The final exact camera image arrived
after 140.29 ms in the revised run. These are headless raster results, excluding
AWT presentation; display FPS alone does not measure this improvement.

The comparison uses the same current renderer and benchmark, with an isolated
coordinator copy restoring cancellation on every camera edit for the before run.
It isolates the scheduling policy rather than comparing different transport code.
Raw summaries and the revised sources' hashes are in
[motion-fix-results.txt](motion-fix-results.txt).
The current command below also reports new camera images displayed during motion.

`ResponsiveTraceTest` drives camera edits continuously until three distinct
complete previews arrive, checks a preview's pixels against its captured camera,
checks that the latest camera still has zero accumulated spp, and verifies exact
convergence after motion stops. The related scheduling, input/focus, profiling,
and progressive sampling suites pass.

## Initial P2 measurements

The following measurements precede the continuous-motion correction above.

Measurements use the same Windows i5-13600KF host, 20 logical processors,
OpenJDK 23.0.1, fourteen trace workers and 32-pixel tiles. Runs were sequential.
The working-tree changes are based on `bb694ac`; the blocking benchmark settings
files include the measured source fingerprint. Measurements precede final footer
layout and test/documentation edits; transport and scheduling are unchanged.

## Input and image latency

`ResponsiveViewportBenchmark` runs native 1440×900 glass while posting 100
synthetic mouse events through the AWT event queue, with 16 ms sleeps between
completed deliveries. It first obtains a stationary image and warms up ten
renders, then renders concurrently with input. The slow baseline therefore takes
longer to deliver the same number of events. This compares handling under render
load; it is not an equal-duration camera-motion throughput comparison.

The baseline's `legacy-lock` mode wraps the current blocking pipeline in the old
full-frame state monitor. It emulates P1's locking behavior using P2 transport
code; it is not a rebuilt P1 binary. AWT presentation, physical device latency,
profiling overlays, and scheduler cadence are excluded. Neither mode opens a window.

| Measurement | Emulated P1 lock | P2 | P2 repeat |
| --- | ---: | ---: | ---: |
| Handler median / p95 ms | 95.156 / 103.825 | 0.116 / 0.212 | 0.119 / 0.281 |
| Posted event through handler median / p95 ms | 95.297 / 103.934 | 0.240 / 0.421 | 0.233 / 0.716 |
| Display work median / p95 ms | 110.893 / 118.312 | 19.389 / 24.444 | 19.487 / 24.718 |
| Final generation first image in raster ms | unmeasured | 127.22 | 119.33 |
| Cancelled jobs | unmeasured | 81 | 79 |
| Obsolete primary paths | unmeasured | 6,804,480 | 5,989,376 |
| Maximum individual tile wall time ms | unmeasured | 29.30 | 32.80 |

The final-generation latency ends after image conversion into the raster; an
actual visible AWT frame occurs later. Display work during motion often repeats
the previous complete image. Its FPS is not a measure of fresh camera updates.
The 16.67 ms slice is cooperative, and measured tile maxima exceed it. Smaller
`view tile` settings are available; OS scheduling and GC can also extend tile
wall time. No tighter deadline is claimed.

Raw summaries are in [input-results.txt](input-results.txt).

```powershell
& "$env:USERPROFILE\.jdks\openjdk-23.0.1\bin\java.exe" --enable-preview '-Djava.awt.headless=false' -cp out/cli ResponsiveViewportBenchmark legacy-lock events=100
& "$env:USERPROFILE\.jdks\openjdk-23.0.1\bin\java.exe" --enable-preview '-Djava.awt.headless=false' -cp out/cli ResponsiveViewportBenchmark events=100
```

## Fixed work and ownership cost

The existing `ViewportBenchmark` uses `renderBlocking()` so complete-pass trace
and full-pipeline measurements remain comparable. It must not be mixed with live
asynchronous rendering on the same viewport.

| Glass sensor | Warmup / frames | Trace median / p95 ms | Frame median / p95 ms |
| --- | ---: | ---: | ---: |
| 400×400 | 50 / 100 | 9.85 / 12.65 | 93.81 / 116.28 |
| 1440×900 | 10 / 20 | 63.78 / 68.67 | 81.08 / 99.98 |

P1's longer 400-square repeat was 9.58 ms trace and 94.88 ms frame median;
its shorter native run was 66.57 ms trace and 84.05 ms frame median. These separate
runs show similar throughput rather than a demonstrated P2 trace speedup. As in
P1, display-stage variation makes frame gains unreliable across runs.

Blocking frame allocation remains about 16.24 MB per frame; trace allocation is
about 4.9 KB at 400 square and 6.7 KB native. Those counts exclude asynchronous
publication copies. Live P2 copies completed RGB into immutable sensor storage:
15.552 MB of pixel payload per native publication. It also retains an extra double
mean staging buffer: 31.104 MB native. There is one active job, no queued camera
snapshots, and at most three owned published RGB images held by this implementation
(displaying, latest, and being copied). Superseded buffers become collectible.
Reuse and display conversion caching belong to P3.

Raw frame rows and settings are [glass-400.csv](glass-400.csv),
[glass-400-settings.txt](glass-400-settings.txt), [glass-native.csv](glass-native.csv),
and [glass-native-settings.txt](glass-native-settings.txt).
Stage summaries are in [frame-results.txt](frame-results.txt).

```powershell
& "$env:USERPROFILE\.jdks\openjdk-23.0.1\bin\java.exe" --enable-preview '-Djava.awt.headless=true' -cp out/cli ViewportBenchmark glass size=400 workers=14 tile=32 warmup=50 frames=100 target=8 output=out/cli/p2-glass-400.csv
& "$env:USERPROFILE\.jdks\openjdk-23.0.1\bin\java.exe" --enable-preview '-Djava.awt.headless=true' -cp out/cli ViewportBenchmark glass size=native workers=14 tile=32 warmup=10 frames=20 target=8 output=out/cli/p2-glass-native.csv
```

## Verification and remaining checks

Thirteen relevant suites pass: all transport suites, parallel scheduling,
responsive cancellation/publication, profiling integration, camera/key bindings,
and console interaction. The staged cancellation test preserves a committed mean
and resumes with identical seeded pixels. Rapid camera/resolution/preset edits,
pause/resume/restart/target changes, returning to an earlier camera, immutable old
images, suspension and close are exercised. A live-async viewport test submits
console commands and clears held input through focus loss and scene switching.

An independent temporary copy of the original sequential tracer from `844b65e`
still agrees bit-for-bit at three spp across all eight presets (67×73, seed 37),
including primitive-test counts. The F3 async preview was rendered and inspected
at `out/cli/perf-viewport-async.png`; the diagnostics fit the panel. Automated
verification opens no windows.

Manual GUI interaction and physical input-to-screen latency remain unverified.
For a live check, move rapidly in native glass, edit resolution/preset through the
console, pause/resume, switch scenes, and change focus with movement held. F3/F4
should show completed lagging previews during sustained movement, then the current complete image and
coherent spp. Exposure edits should keep radiance samples. P3 should address the
recurring display work, and P4 can shorten the remaining camera-preview lag.
