# P2.1: prompt sample-game image publication

Implemented on October 10, 2026. Completed tracing now signals the sample game's existing owner thread, which promptly copies a leased image and requests subsequent work. The headless route shows fresher images and larger adaptive moving grids. Automated verification passes; native-window acceptance remains pending.

## What changed

`src/engine/RenderSession.java` adds optional `onImageAvailable(Runnable)` notification. `AsyncViewportTrace` calls it outside the state monitor after completing publication and clearing the active job. The callback carries no image or lease. The consumer must only enqueue work, must not throw, and must tolerate an in-flight signal during detach/close. Cancellation produces no publication signal.

`src/game/GameRenderer.java` coalesces signals into at most one pending task on its existing owner executor. That owner retains its fixed-delay 16 ms input/lifecycle tick and immutable copied `BufferedImage` publications. Close detaches the listener before shutting down the owner; late enqueue attempts tolerate shutdown. No tracing or image conversion moves onto Swing's event thread. The separate 16 ms Swing repaint timer remains.

Transport, lighting, requested grids, depth two, seed one and one sample per batch are unchanged. The game retains fourteen workers and now defaults to 16-pixel tiles, following the route evaluation below. The general engine default remains 32-pixel tiles. Adaptive resolution receives earlier presentation feedback through the existing policy; the policy itself is unchanged.

## Route measurements: completion notification with 32-pixel tiles

Host: Intel i5-13600KF, twenty logical processors, OpenJDK 23.0.1+11-39. The scene has 313 blocks. The window reference is 960×600, requested grid 320×200, temporal reconstruction off. Each mode uses one 30-second warmup and three measured 30-second repeats; the first 25 seconds move and the last five hold the final camera. No competing build or benchmark ran during measured route intervals. Java Flight Recorder was off.

Baseline classes were preserved from commit `edecd02c12646955f4bf55ac9f3fd7be4a6aaa04` before editing. Their hashes are in [baseline-classes-sha256.txt](baseline-classes-sha256.txt). Run order was before adaptive, before fixed, after adaptive, after fixed. Runs were sequential, not interleaved; host scheduling and tracing time varied, particularly in the fixed baseline. The entire FPS difference cannot be attributed solely to notification. Baseline metadata did not populate the CPU-name property; the after metadata records this same host. Source manifests accompany after runs and the tile candidates. The final retained source manifest is [final-source-sha256.txt](final-source-sha256.txt).

The frozen [route](../game/camera-route.csv) has SHA-256 `a705beafb16265242207dcbc9ab9d53c5e9955ce90131f1546be36ceb01b5254`.

| Moving metric | Adaptive before | Adaptive after | Fixed before | Fixed after |
| --- | ---: | ---: | ---: | ---: |
| Fresh copied images/second | 32.91 | 85.49 | 16.57 | 41.85 |
| Median pickup delay, ms | 21.99 | 0.073 | 16.22 | 0.249 |
| p95 pickup delay, ms | 27.17 | 12.69 | 29.57 | 12.91 |
| Median snapshot age, ms | 31.22 | 11.30 | 63.03 | 19.33 |
| p95 snapshot age, ms | 36.35 | 21.18 | 124.19 | 50.80 |
| Median trace time, ms | 8.39 | 9.17 | 38.42 | 15.34 |
| p95 trace time, ms | 20.86 | 14.32 | 99.02 | 43.28 |

Pickup runs from engine completion to copy start; snapshot age ends at copy completion. These pooled moving percentiles use nearest rank. Fresh-image rates count every copied publication once across 75 measured moving seconds. These are software publication metrics, excluding physical input, Swing painting and monitor scanout. They are not monitor FPS. Adaptive comparisons use different actual grids; fixed comparisons keep 320×200 throughout.

Before adaptation used 102×64 for all 2,468 moving publications. After adaptation used 160×100 for 3,869, 240×150 for 944, and 320×200 for 1,599 publications. Thus moving grids contain at least 2.45 times as many pixels, with some full-grid images. This reports detail availability, not a perceptual-quality score.

Adaptive p95 fresh intervals were 36.293 / 35.306 / 37.591 ms before and 20.926 / 21.144 / 19.619 ms after. Fixed intervals were 138.712 / 130.089 / 69.644 ms before and 55.017 / 44.706 / 46.653 ms after. Every replay passed the historical 200 ms regression threshold. After stopping, adaptive mode produced a matching full-grid image in 0.404 / 0.379 / 0.035 seconds and refined to 257 / 261 / 350 samples per pixel. The last replay was already showing full-grid images during motion. Fixed mode also restored matching final-camera images and refined.

Raw metadata, summaries and all publication CSVs, including warmup, are in [before-adaptive](before-adaptive/summary.csv), [after-adaptive](after-adaptive/summary.csv), [before-fixed](before-fixed/summary.csv), and [after-fixed](after-fixed/summary.csv).

## CPU and worker/tile investigation

`game.GameSchedulingBenchmark cpu` measures matched fixed 320×200 rendering, with five seconds warmup, ten seconds moving, five seconds suspended after a 500 ms drain, and five seconds resumed. Process CPU covers all JVM threads; owner CPU covers `sample-game-update`. Machine-normalized CPU divides process CPU seconds by wall seconds and twenty logical processors. These are short single probes, not repeated energy-efficiency estimates. The benchmark driver itself remains awake every two milliseconds in both versions.

Moving machine CPU rose from 23.572% to 44.200%, as more images were rendered. Owner CPU was 0.984 versus 0.953 seconds over ten seconds. Suspended process CPU was exactly 0.125 seconds over approximately five seconds in each probe, or 0.125% of the machine; suspended owner CPU was 0.046875 seconds in both. This probe finds no suspended CPU increase. Active stationary rendering continues progressive refinement and is not an idle state. Windows CPU counters have timer granularity. Raw probes: [before](cpu-before.csv), [after](cpu-after.csv).

The worker/tile sweep uses the same landscape at the fixed spawn camera, at 102×64 and 320×200, depth two and exactly one seeded sample per pass. It tests 1/4/8/14 workers and 8/16/32-pixel tiles. Each configuration warms 20 passes and measures 40; three rounds alternate configuration order. Timing includes tracing, not game copying or publication pickup. Each pass restarts accumulation while reusing prepared geometry. This is a fixed-camera diagnostic, not a route or larger-world benchmark. Raw rows are in [worker-tile.csv](worker-tile.csv).

| Fourteen workers: median of three trace medians, ms | Tile 8 | Tile 16 | Tile 32 |
| --- | ---: | ---: | ---: |
| 102×64 | 1.188 | 1.656 | 3.763 |
| 320×200 | 9.347 | 9.587 | 12.576 |

At 102×64, 32-pixel tiles create only eight tasks, while 16-pixel tiles create 28. At 320×200, tile 16 creates 260 tasks versus 1,000 for tile 8. Tile 16 was selected for a route follow-up because it captures much of the static benefit with fewer tasks than tile 8. Worker count remains fourteen.

The subsequent tile-16 route repeats keep the same fourteen workers, scene, depth, seed, sampling and requested grid. They follow the notification-only route runs above, without interleaving or JFR. Candidate measurements use an explicit tile override; making that validated layout the game default changes no tracing behavior beyond the layout. Archive: [adaptive](tile16-adaptive/summary.csv), [fixed](tile16-fixed/summary.csv).

| Moving metric | Notification + tile 32 | Notification + tile 16 |
| --- | ---: | ---: |
| Adaptive fresh copied images/second | 85.49 | 92.29 |
| Adaptive p95 snapshot age, ms | 21.18 | 19.61 |
| Adaptive p95 pickup delay, ms | 12.69 | 10.42 |
| Fixed 320×200 fresh copied images/second | 41.85 | 65.13 |
| Fixed p95 snapshot age, ms | 50.80 | 32.71 |
| Fixed p95 pickup delay, ms | 12.91 | 6.49 |

Tile 16 adaptive p95 fresh intervals were 20.692 / 17.523 / 16.139 ms; fixed intervals were 33.106 / 31.697 / 30.645 ms. All repeats passed restoration and refinement. Adaptive matching full-grid recovery was 0.032 / 0.376 / 0.390 seconds, ending at 342 / 234 / 283 samples per pixel. Fixed matching final-camera images arrived in 0.017 / 0.018 / 0.032 seconds and refined to 341 / 348 / 342 samples per pixel.

Adaptive moving publications used 160×100 for 1,921 images, 240×150 for 2,835, and 320×200 for 2,166. Both freshness and moving detail improve over the tile-32 notification run, although host variation remains uncontrolled. Retain game tile 16; keep the fourteen-worker count. Tile 8 was not route-evaluated and is not claimed optimal.

The final retained tile-16 configuration was probed again after the verification gate. Moving machine CPU was 57.728%, with 0.6875 owner CPU seconds over ten seconds. Suspended process CPU was 0.046875 seconds over five seconds, or 0.047% of the machine; owner CPU registered zero at counter granularity. This separate short probe confirms no measured suspended CPU growth, while active rendering uses more machine capacity. It does not establish an energy-per-frame improvement. Raw [final probe](cpu-final.csv).

## Verification and native acceptance

`./game-check.ps1` and `./verify.ps1` passed; logs are [game-check.log](game-check.log) and [verify.log](verify.log). `EngineSessionTest` adds notification-outside-monitor, listener-detachment and stable-lease checks. Existing suites verify sustained movement, suspension/resume, close, captured-camera previews, cancellation, temporal reconstruction, advanced transport and reference images. No style baseline was expanded.

One pre-existing sun/sky worker/batch test assumed an eight-sample batch completed in one call. On a busy host the cooperative batch time limit can stop it early. The test now waits for eight accumulated samples before comparing images. Rendering behavior is unchanged. The existing Caps-on synthetic-console skip remains.

Native acceptance is pending. Run `./game.ps1`, capture the pointer and move/look continuously, then release keys and stop. Confirm responsive motion, full-grid recovery and refinement. Lose/regain window focus, release/recapture the pointer, toggle adaptation and lighting, resize, and close while tracing. Check for stuck movement, stale images, errors or leftover rendering threads. The available automated checks do not establish native pointer delivery, Swing painting latency or monitor smoothness.

## Reproduction and next priority

From the repository root in PowerShell:

```powershell
./game-benchmark.ps1 -OutputDirectory out/p21-adaptive
./game-benchmark.ps1 -NoBuild -NoAdaptive -OutputDirectory out/p21-fixed
. ./tools/style/common.ps1
Invoke-StyleJava @('--enable-preview', '-Djava.awt.headless=true', '-cp', 'out/game-check/classes', 'game.GameSchedulingBenchmark', 'cpu') 'out/p21-cpu.csv'
Invoke-StyleJava @('--enable-preview', '-Djava.awt.headless=true', '-cp', 'out/game-check/classes', 'game.GameSchedulingBenchmark') 'out/p21-worker-tile.csv'
./verify.ps1
```

`GameRouteBenchmark` accepts an optional third argument for tile size, following output directory and adaptive true/false; omitted uses the game default. This permits repeated tile-32 versus tile-16 route checks.

`Invoke-StyleJava` returns the process exit code; require zero. It launches without child console windows. `tools/game/summarize_profile.py` pools measured moving publication metrics when passed a route output directory. The baseline route uses the same `GameRouteBenchmark` from commit `edecd02`. For a matched baseline CPU probe, preserve that commit's compiled classes and put them first on the classpath, followed by current classes supplying the new `GameSchedulingBenchmark`; only its `cpu` mode works with the old API.

Retain P2.1. Next evaluate P8/P9 traversal order and construction, using the P7 query profile and new game publication baseline. Storage changes and analytic boxes remain conditional on further measurements. A hard smooth-gameplay target and minimum acceptable moving detail still require human evaluation.
