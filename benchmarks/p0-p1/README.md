# P0/P1 measurements

Measured October 4–5, 2026 on the local i5-13600KF, 20 available logical processors,
Windows, OpenJDK 23.0.1. All runs were sequential. These are headless pipeline
measurements with a 1440×900 display raster: they exclude AWT presentation and
scheduler idle. Fixed settings use seed 1, one requested spp, preset depth,
BVH traversal, unchanged camera and raw accumulation unless the run says otherwise.

[summary.csv](summary.csv) contains stage distributions and aggregate costs;
[frames.csv](frames.csv) retains individual timing, allocation, spp and ray/test
counts; [runs.txt](runs.txt) records commands/settings, revision, convergence,
quality and JFR output. Later runs include a source fingerprint because the
working tree was changing during implementation. Earlier runs identify the base
revision and command, and predate the final bounded queue and metadata additions.
Their transport kernel and scheduling policy are the same. Source hashes describe
the files at invocation; always rebuild before benchmarking.

The original tracer was rebuilt before its baseline: glass at 400×400 measured
114.65 ms median / 120.33 ms p95 over 100 frames after 50 warmup frames. Its last
trace was 67.67 ms; that individual trace is not a median.

## Scaling

Glass, 400×400 sensor, 32-pixel tiles, 20 warmup / 30 measured frames:

| Workers | Trace median ms | Frame median ms | Frame p95 ms |
| ---: | ---: | ---: | ---: |
| 1 | 70.22 | 117.30 | 129.59 |
| 2 | 34.69 | 80.25 | 86.32 |
| 4 | 17.68 | 63.03 | 70.65 |
| 6 | 12.18 | 57.01 | 63.47 |
| 10 | 10.06 | 55.10 | 61.12 |
| 14 | 8.52 | 53.39 | 56.66 |
| 20 | 7.56 | 52.35 | 62.65 |

Fourteen workers deliver about 8.2× trace throughput and 2.2× frame throughput
in this comparison. The full frame still allocates about 16.24 MB; tracing
allocates about 5.2 KB. Resampling takes about 34 ms and painting about 10 ms.
The 400×400-to-1440×900 fractional resampling case costs more than the integer
scale cases below. This is evidence for P3.

The final 50-warmup/100-frame repeat measured 73.17 ms trace / 122.93 ms frame
with one worker and 9.58 ms trace / 94.88 ms frame with fourteen. The trace gain
remained about 7.6×, but fourteen-worker resampling rose to 64.35 ms (versus
34.40 ms in the one-worker run). The cause of that display-stage variation is
unresolved; do not present the earlier 2.2× frame gain as a stable guarantee.
Both longer runs are retained as `p1-final-w1` and `p1-final-w14`.

Glass at native 1440×900, 10 warmup / 20 measured frames:

| Workers / tile | Trace median ms | Frame median ms | Frame p95 ms |
| --- | ---: | ---: | ---: |
| 1 / 32 | 610.12 | 627.45 | 713.83 |
| 6 / 32 | 104.82 | 122.98 | 133.07 |
| 10 / 32 | 73.73 | 91.64 | 96.19 |
| 14 / 32 | 66.57 | 84.05 | 88.52 |
| 20 / 32 | 54.66 | 71.52 | 74.91 |
| 14 / 16 | 64.43 | 82.00 | 84.74 |

The default is min(14, max(1, available CPUs − 2)) workers and 32-pixel tiles.
On this machine that leaves six logical processors beyond the tracing-worker
count. Twenty workers improve native throughput, while fourteen offers more
headroom. This is a conservative initial policy, not a portable optimum or a
promise of reserved physical cores. `view workers` and `view tile` permit tuning.
The tile difference is small enough to retain 32; repeat longer runs before
claiming 16 is superior. Native glass is still well below 60 updates/second.

## Preset coverage

Fourteen workers / tile 32, 10 warmup / 10 measured frames. Frame medians in ms:

| Preset | Quarter 360×225 | Half 720×450 | Native 1440×900 |
| --- | ---: | ---: | ---: |
| playground | 20.15 | 21.96 | 31.21 |
| bounce-room | 23.90 | 38.39 | 93.01 |
| glass | 21.96 | 32.90 | 89.03 |
| rough-room | 24.09 | 39.35 | 105.81 |
| mesh-room | 31.35 | 60.93 | 209.25 |
| volume-room | 27.00 | 47.34 | 152.75 |

These short coverage runs establish workload coverage, not precise p95 estimates.
Independent native glass runs vary (84–89 ms at fourteen workers). JVM warmup,
hybrid-core placement, machine load and scheduling matter. Do not multiply these
gains into forecasts for later phases.

Quality smoke runs use a 64×64 sensor, an approximately 100 ms headless frame
budget and a separately seeded 32-spp reference. They report actual elapsed time,
completed spp and linear RGB RMSE, including complete-pass overshoot. The reference
is noisy; use longer references and several seeds for estimator decisions. Motion
uses a repeatable sinusoidal camera translation. The 32-instance mesh workload
exercises object-list scaling. These modes are recorded in the archive.

Aggregate CPU time includes worker and coordinator counters; allocations include
worker costs in both trace and whole-frame totals. Windows CPU timers can round
short worker intervals to zero; interpret totals over many frames. GC deltas also
include benchmark bookkeeping outside the timed interval. Timing/allocated-byte
measurements exclude CSV/history-reading bookkeeping.

Actual AWT cost, event-delivery latency and input-to-visible-image latency have
not been measured here. P1 still holds the state monitor throughout rendering;
generation age and cancellation waste become measurable with P2. F3/F4 retain
live presentation timing. JFR started successfully outside the restricted sandbox
and classified worker execution (30 transport, 184 intersection, 39 shadow samples);
its enabled runs are separate from ordinary timings.

## Reproduce

Build from the repository root, then run sequentially. Bash/WSL examples:

```bash
./build
./test ViewportBenchmark glass size=400 workers=1 tile=32 warmup=50 frames=100 target=8 output=out/cli/glass-w1.csv
./test ViewportBenchmark glass size=400 workers=14 tile=32 warmup=50 frames=100 target=8 output=out/cli/glass-w14.csv
./test ViewportBenchmark glass size=native workers=14 tile=16 output=out/cli/glass-native.csv
./test ViewportBenchmark rough-room size=200 workers=14 qualityMillis=1000 reference=512 output=out/cli/rough-quality.csv
./test ViewportBenchmark glass size=quarter workers=14 motion output=out/cli/glass-motion.csv
./test ViewportBenchmark mesh-room size=64 detail=12 instances=32 workers=14 output=out/cli/mesh-instances.csv
```

PowerShell equivalent invocation (replace arguments as needed):

```powershell
& "$env:USERPROFILE\.jdks\openjdk-23.0.1\bin\java.exe" --enable-preview '-Djava.awt.headless=true' -cp out/cli ViewportBenchmark glass size=1440x900 workers=14 tile=32 output=out/cli/glass.csv
```

Correctness: all eight presets agree bit-for-bit with the pre-change tracer at
67×73, seed 37 and three spp, including primitive-test counts. ParallelTraceTest
checks one/four workers, 16/32 tiles, uneven edges, pause/restart, resolution/preset
changes, scheduling controls and interruption recovery. All relevant transport,
profiling, console, camera and viewport-binding suites pass.
