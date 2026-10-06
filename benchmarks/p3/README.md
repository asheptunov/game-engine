# P3 display buffer measurements

P3 removes recurring sensor publication and display pixel-buffer allocation and
avoids converting the same completed image repeatedly. The strongest measured
complete-frame gain is at quarter resolution. Native glass still spends about
65 ms tracing a fresh pass, so these changes do not deliver 60 fresh native images
per second.

## Complete frame results

Local Windows/JDK 23.0.1, i5-13600KF, 20 logical processors, 14 workers, tile 32;
glass depth 8, seed 1, one spp per batch, display 1440×900. Timings use
`renderBlocking()`, with continuous sampling and UI drawing, excluding AWT
presentation, overlay drawing, scheduler idle and input delivery.

| Sensor | Before median / p95 ms | P3 median / p95 ms | Before / P3 allocation MB per frame |
| --- | --- | --- | --- |
| Quarter 360×225 | 20.66 / 22.89 | 6.81 / 8.29 | 16.24 / 0.637 |
| 400×400 | 52.76 / 57.38 | 28.18 / 29.24 | 16.24 / 0.636 |
| Native 1440×900 | 82.17 / 94.81 | 75.97 / 87.74 | 16.25 / 0.641 |

Quarter/400 use 50 warmup and 100 measured frames; native uses 20/50. Remaining
whole-frame allocations include snapshots, profiling and UI; this is not a claim
that the whole application allocates nothing. Transport output and primitive-test
counts match at the final fixed sample count. Trace medians before/P3 are
4.43/4.63 ms, 8.83/8.05 ms and 65.42/65.08 ms respectively; P3 changes display
work, not the transport algorithm. Runs are sequential, without competing benchmarks.

Baseline classes come from committed P2 revision `30e8a92`. Quarter baseline uses
isolated copies of that revision's `Viewport` and `AsyncViewportTrace` classes
ahead of `out/cli` on the classpath. The benchmark's working-tree source fingerprint
describes files present when a process starts, not the overridden compiled baseline.
CSV, settings sidecars and logs accompany each result. `source-hashes.txt` identifies
the final implementation files; minor ownership validation changes after the first
measurements do not change conversion arithmetic.

## Isolated display results

`DisplayPipelineBenchmark` uses fixed synthetic RGB radiance, exposure 1, 50 warmup
and 100 measured updates per case. The reference is the unchanged `Resampler` plus
the former display mapping loop. Fused includes conversion and cache restoration;
cached includes restoration only. Matching output checksums accompany every row
in [display results](display-results.txt).

| Sensor | Reference median ms | Fused median ms | Cached median ms |
| --- | --- | --- | --- |
| 1440×900 | 16.015 | 11.368 | 0.150 |
| 360×225 | 15.457 | 1.605 | 0.146 |
| 400×400 | 43.775 | 20.224 | 0.148 |
| 1600×1000 | 73.498 | 25.137 | 0.146 |

Reference allocation is 15,606,120 bytes per update; fused and cached allocate zero
bytes after warmup. Float accumulation order is retained, including fractional
overlap products and normalization. Integer enlargement maps one source value then
replicates its encoded bytes. A dedicated equal-grid loop avoids replication overhead.
Opaque AWT channel packing into existing image storage measures 2.867 → 0.507 ms;
partial alpha retains the original double multiplication/truncation behavior.
This packing measurement excludes actual AWT presentation and image drawing.

## Live scheduling and ownership

The publication pool has three slots: latest image, leased display image, and
coordinator destination. A slot can be reused only when it is neither latest nor
leased. The display acquires/releases under the state lock, including release on
exceptions; copying/conversion runs outside it. Startup/dimension changes allocate
pixel storage; steady-state copies reuse it. Native sensor publication payload is
15.552 MB per slot, at most 46.656 MB for three populated native slots, plus array
headers. The encoded display cache holds 3.888 MB, plus cached overlap arrays for
fractional grids. Tracer accumulation/staging storage is unchanged.

The cache key is captured render key, completed spp and exposure. UI and overlays
draw after restoring the clean cached background on every display update. This
prevents ghosted text or editor content on scene switches. F3's RESAMPLE stage now
includes filtering, tone mapping and encoding; PAINT restores cached bytes. Compare
their combined cost against the old separate stages. Cache-hit RESAMPLE time is zero.

Native glass with 100 synthetic AWT mouse events retains progress during motion:
18 new camera images before versus 19 after, with zero cancelled jobs/wasted paths
in both runs. Display median/p95 is 22.407/27.421 → 1.797/9.269 ms; most P3 display
updates restore a cached image, so this is not fresh-image FPS. Input handling p95
is 0.227 → 0.238 ms; dispatch-through-handler p95 is 0.389 → 0.413 ms. Final current
generation first-image latency is 166.52 → 171.26 ms; no latency improvement is
inferred from these single runs. Logs retain max-tile and generation diagnostics.
The final ownership-validation repeat displayed 20 new camera images, with no
cancellations or lease errors; its log is `final-motion-validation.log`.

## Verification and reproduction

### Fresh image profiling correction

F3 now counts distinct completed publications after `ProfiledFrame.endFrame`,
including AWT submission in the application. It leads with a two-second fresh-image
rate, update interval mean/p95 and current image hold. Snapshot-to-display age is
shown both currently and as p95 at updates. Current hold participates in interval
statistics when it exceeds the last completed interval; long stalls remain visible
even when no new image arrives. Paused/complete status explains intentionally idle
rendering. Resolution/preset changes reset the rate window; repeated presentation
of the old image after a change does not count as a new update. Scene exit clears it.

The live viewport graph shows fresh intervals, the tallest per time column, an
orange ongoing hold and a white 60 FPS reference. Display-loop FPS and per-update
stage means remain secondary ten-second statistics. TRACE is explicitly labeled
background in asynchronous mode; its last job costs remain separate. Editor and
blocking-mode graphs keep their display-thread stage stacks. These measurements
end at software submission; they do not measure monitor scanout or physical input.

`FreshImageBenchmark` runs glass motion with nominal 144 Hz pacing, three seconds
warmup and three measured seconds for each grid, fourteen workers, tile 32, seed 1,
depth 8 and a 1440×900 display. It excludes overlays and AWT submission. The final
two-second fresh rates are 10.5 / 30.0 / 44.0 FPS at native / half / quarter; display
rates are 88.3 / 74.2 / 78.2 FPS. This workload changes the eye each display update;
it is a validation of counting distinct images, not a forecast of user-run FPS.
Results/settings are in `fresh-image-results.txt`. Run it with:

```powershell
& "$env:USERPROFILE\.jdks\openjdk-23.0.1\bin\java.exe" --enable-preview '-Djava.awt.headless=false' -cp out/cli FreshImageBenchmark
```

Clock-driven tests verify 100 display updates/s versus 10 fresh images/s, long
holds and pause, resolution resets without counting old images, scene exit and
age timestamped after presentation. FrameProfilerTest, ProfilingIntegrationTest,
ViewportKeyBindingsTest and ResponsiveTraceTest pass. F3/F4 raster previews were
inspected for layout and clipping; actual GUI feel remains a user check.

Fifteen relevant suites pass: DisplayConverterTest, ResamplerTest, ResponsiveTraceTest,
ParallelTraceTest, ViewportKeyBindingsTest, ProfilingIntegrationTest, FrameProfilerTest,
ConsoleInteractionTest, AwtPrinterTest, MaterialPlaygroundTest, ProgressivePathTest,
DielectricPathTest, RoughLightingTest, MeshAccelerationTest and VolumePathTest.
Display tests compare exact encoded bytes at equal/integer/fractional/mixed ratios,
different exposure and Y orientation; packing tests exhaust all alpha/channel pairs.
Integration tests cover cache invalidation, overlays, scene re-entry, console/input
and coherent sustained-motion previews. A held old image remains stable across
twelve publications using at most three RGB arrays, followed by a resize.

The existing console preview test submitted an empty command, which console history
now intentionally ignores. It now submits `v` so its ANSI output fixture actually
runs. Native text/clipping and ANSI preview assertions pass. Window-free raster
previews of glass with the overlay and console were inspected. Actual GUI behavior
and physical input-to-screen latency remain manual checks.

Build all sources/tests with JDK 23 preview, then run from the repository root:

```powershell
& "$env:USERPROFILE\.jdks\openjdk-23.0.1\bin\java.exe" --enable-preview -cp out/cli DisplayPipelineBenchmark
& "$env:USERPROFILE\.jdks\openjdk-23.0.1\bin\java.exe" --enable-preview '-Djava.awt.headless=false' -cp out/cli ViewportBenchmark glass size=quarter warmup=50 frames=100 output=out/cli/p3-quarter.csv
& "$env:USERPROFILE\.jdks\openjdk-23.0.1\bin\java.exe" --enable-preview '-Djava.awt.headless=false' -cp out/cli ViewportBenchmark glass size=400 warmup=50 frames=100 output=out/cli/p3-400.csv
& "$env:USERPROFILE\.jdks\openjdk-23.0.1\bin\java.exe" --enable-preview '-Djava.awt.headless=false' -cp out/cli ViewportBenchmark glass size=native warmup=20 frames=50 output=out/cli/p3-native.csv
& "$env:USERPROFILE\.jdks\openjdk-23.0.1\bin\java.exe" --enable-preview '-Djava.awt.headless=false' -cp out/cli ResponsiveViewportBenchmark events=100
```

All automated verification opens no windows. Baseline quarter reproduction requires
compiling the two P2 classes into a separate directory and putting that directory
first on the classpath; never mix blocking and async calls on one viewport.
