# Swing paint cost and correlated frame profiling

Measured October 28, 2026, on the NVIDIA GeForce RTX 3080 (12 GB), Windows and Java 23.
This report is for maintainers choosing the next performance change and players evaluating
what the result means. The normal sample game uses Swing presentation.

## Change and decision

Keep the `GamePanel` change that clears only the black letterbox bars around an opaque game
frame. Previously, it cleared the entire panel immediately before covering those pixels with
the frame. Transparent images still receive a full clear; no-frame startup and optional native
presentation preserve their previous background handling. Scaling, overlays, scene rendering,
transport samples and atmosphere settings are unchanged. The benchmark-only property
`-Dgame.fullPaintClear=true` restores full clearing for comparisons.

The adaptive comparisons below show a substantial reduction in paint cost. They also show why
GPU readback is not the first target for this particular workload: median GPU execution is
2.995–3.588 ms and mapped-memory readback is 0.353–0.412 ms, while full-clear paint medians are
8.968–10.954 ms. These ranges come from the six adaptive runs, not an estimate of every scene.
Painting includes image scaling, UI and Swing work; this experiment does not isolate their
individual costs. Fixed 1080p has a different balance, with substantially more GPU work.

## Workload and method

`tools/gpu/PresentationBenchmark.java` uses the real `GamePanel` and renderer with an offscreen
3840×2160 RGB destination. It requests 1920×1080, enables ruins combat at depth 8 and Full
atmosphere, and follows the same ten-second camera route in each run. Adaptive runs select the
120 FPS movement budget; fixed runs disable it. Each run waits at least two seconds and for an
initial image before measuring. The previous GPU owner is retired before starting another run.

Each group has three pairs in full/bar, bar/full, full/bar order. No build, test or other agent
benchmark ran alongside the final measurements. Other desktop activity was not controlled.
Earlier exploratory runs overlapped verification and are excluded from this report. These
numbers should not be compared directly with earlier three-second-route reports.

A fresh paint counts a distinct publication once. The collector measures completion intervals
between fresh paints, not cached redraws. Its repaint queue coalesces notifications. CSV files
contain only fresh paints; the first interval is zero because there is no preceding measured
paint. Exclude that row when computing interval percentiles. Duration-based throughput uses
the full ten-second measurement window. A 95th-percentile (p95) interval means 95% of measured
intervals were at or below that duration, not that the minimum FPS target has been achieved.

## Adaptive 120 FPS results

| Pair | Full / bars fresh paints/s | Full / bars p95 interval, ms | Full / bars median paint, ms | Full / bars mean internal width |
| --- | --- | --- | --- | --- |
| 0 | 85.6 / 133.8 | 16.303 / 12.346 | 10.954 / 6.509 | 912.3 / 985.8 |
| 1 | 104.3 / 144.7 | 14.632 / 11.255 | 9.089 / 6.207 | 938.8 / 959.7 |
| 2 | 108.7 / 153.9 | 11.716 / 10.408 | 8.968 / 5.861 | 952.5 / 951.9 |

Median throughput across runs rises from 104.3 to 144.7 fresh paints/s (about 39%). The adaptive
grid varies with measured cost, so this is not an identical-resolution comparison. The bar-only
runs did not obtain their gains by a lower mean width in the first two pairs; the third pair is
nearly equal. This does not establish a universal detail benefit. After the change, 0.224–0.455%
of intervals still exceed 16.67 ms, and p95 remains above the 8.33 ms budget for 120 FPS.

Raw results: [adaptive summary](adaptive-summary.csv) and the six `round-*-budget-120-*.csv`
files in this directory. `clear-true` is full clearing; `clear-false` is bar-only clearing.

## Fixed 1080p results

| Pair | Full / bars fresh paints/s | Full / bars p95 interval, ms | Full / bars median paint, ms |
| --- | --- | --- | --- |
| 0 | 54.0 / 56.8 | 28.898 / 24.845 | 10.802 / 7.198 |
| 1 | 55.2 / 56.8 | 28.728 / 23.926 | 9.263 / 6.833 |
| 2 | 55.0 / 54.1 | 28.441 / 26.002 | 9.076 / 6.475 |

All runs keep exactly 1920×1080 internally. Paint time and p95 intervals improve in each pair,
but fresh-paint throughput improves only in two pairs. Median GPU execution is 12.66–13.98 ms
and total host trace time is approximately 16–17 ms. This change does not solve fixed-1080p
60 FPS; longer GPU work and remaining scheduling/paint delays still matter. Readback medians
are 1.16–1.63 ms. The slowest fixed-grid intervals include both long GPU work and long Swing
queue/paint delays, rather than one universal source of stalls.

Raw results: [fixed summary](fixed-summary.csv) and the six `round-*-budget-0-*.csv` files.

## Reading the correlated timings

`GpuRenderSession.FrameTiming` retains at most 4096 scalar records when
`-Dgame.profileFrames=true` was set before session construction. It retains no pixel arrays,
images or leases. Collection is off in normal gameplay. Each record is matched to a painted
publication by capture timestamp and accumulated sample count; the benchmark fails if a match
is absent or the host timings are inconsistent. The measured runs use one sample per batch.
For other workloads, the GPU fields describe the last sample, not the sum of a larger batch.

The detailed CSV columns use milliseconds except dimensions and `batchSamples`:

- `elapsedMs`: fresh paint completion relative to measurement start.
- `intervalMs`: elapsed time since the preceding measured fresh paint.
- `queueMs`: time from this coalesced repaint being queued until its Swing event-dispatch-thread
  callback starts. It can include the previous paint and is not specific to one publication.
- `paintMs`: wall time for the panel paint and temporary graphics setup/disposal.
- `pickupMs`: completed GPU publication to the renderer starting image handoff.
- `copyMs`: renderer image handoff/wrapping time, despite the historical column name.
- `copyToPaintMs`: handoff completion to fresh paint completion.
- `captureToPaintMs`: captured render request to fresh paint completion.
- `hostMs`: host duration of the last GPU trace call, including preparation and waiting.
- `prepareMs`: the trace call's resource/scene/image preparation time.
- `gpuExecuteMs`: GPU timestamp duration covering build/compute work through display encoding.
- `gpuTransferMs`: GPU timestamp duration for snapshot/display transfers.
- `submitWaitMs`: host command submission and fence wait, including time while the GPU executes.
- `readbackMs`: host copying of mapped staging memory into Java display/output arrays.

GPU execute/transfer times overlap host submit-and-wait time. Preparation and readback are
parts of host time. Queue, paint and capture-to-paint measurements also overlap. Do not sum
these columns or unrelated percentiles into an alleged frame budget. Published frames may be
skipped by painting; the CSV describes painted frames, not all GPU work or scheduling gaps.

## Reproduce and verify

From the repository root in PowerShell, build with `./gpu-build.ps1`, then compile:

```powershell
$jdk = 'C:/Users/andri/.jdks/openjdk-23.0.1/bin'
$cp = 'out/gpu/classes;out/game/classes;out/gpu/lib/*'
& "$jdk/javac.exe" --enable-preview --release 23 -cp $cp -d out/gpu/classes tools/gpu/PresentationBenchmark.java
& "$jdk/java.exe" --enable-preview '-Djava.awt.headless=true' '-Dgame.benchmarkSeconds=10' '-Dgame.benchmarkBudget=120' '-Dgame.profileFrames=true' '-Dgame.profileDirectory=out/performance/paint-120' -cp $cp game.PresentationBenchmark paint
```

Repeat with budget `0` and a different output directory for fixed 1080p. `paint` runs the three
alternating full-clear/bar-only pairs. `profile` instead compares fixed and adaptive modes at
the current clearing setting. Do not run builds, checks or another benchmark concurrently.
Use a console-free subprocess launcher for automated/background runs on Windows.

`verify.ps1`, `gpu-check.ps1` and `game-check.ps1` passed; logs are stored here. The GPU gate
now includes `GamePaintCheck`, which compares exact pixels against full clearing across opaque
and alpha images, odd and tiny dimensions, letterboxes, clipped repaint regions, and
100/125/200% scaling. Existing gates cover image ownership, captures, adaptive restoration and
native fallback. `source-hashes.json` identifies measured sources relative to base commit
`e57f6be`; unrelated fullscreen launcher edits were already present in the workspace.

## Limits and next check

This excludes native window submission, desktop composition, monitor scanout and real input
latency. It cannot validate a minimum on-screen FPS or the separate native presentation mode.
Some slow intervals remain even when the painted frame's GPU work is fast; neither a fence
rewrite nor asynchronous readback is justified solely by that observation. Next, investigate
remaining paint/queue tails with a real-window trace, and profile fixed-grid GPU cost before
choosing another transport optimization.

Player check: relaunch the normal GPU game, select 1080p and the 120 FPS budget, use Full
atmosphere, and move through the ruins. Stop to verify full-resolution recovery. Open and close
settings, and resize a windowed game through wide and tall aspect ratios. Black bars must stay
clean without stale scene or HUD pixels. Human testing remains pending.
