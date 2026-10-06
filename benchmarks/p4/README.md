# Interactive resolution measurements

P4 makes camera movement faster by tracing fewer spatial samples. It is opt-in
and restores the requested grid when movement stops. On this host, the repeatable
native glass camera path produced 9.5 fresh images/s in fixed mode and 112–114
with automatic resolution at 360×225. These are different spatial quality levels,
not a gain in native-resolution tracing throughput.

## Controls and behavior

Enable with `view interactive on`; disable with `view interactive off`.
`view resolution` sets the maximum and stationary grid. `view interactive target
16.67` requests a best-effort update budget in milliseconds; the accepted range
is 1..1000. `view interactive min 360 225` sets minimum bounds. Defaults are a
16.67 ms budget and quarter dimensions, with at least 64 pixels on both axes.
Bounds fit the requested aspect and are capped by the requested maximum. For
example, minimum 300×200 with requested 800×500 becomes 320×200.

The controller observes eye/sensor changes, including integrated held-key motion.
Before it has moving-image timings it starts at half resolution. It estimates
cost per pixel from first snapshot-to-raster presentation, smoothing each new
measurement with 25% weight. It chooses discrete scales (1, .75, .5, .375, .25,
.1875, .125, .09375, .0625), plus the fitted minimum, and reserves 20% of the
budget for remaining display/presentation work. Cached redisplays do not count
as new measurements. A 500 ms cooldown, 25% over-budget deadband, and three fresh
measurements below 65% of budget before enlargement limit grid oscillation.

Automatic grid changes happen between completed jobs. Continuous mouse input
finishes coherent previews rather than cancelling every pass. Moving jobs cap at
one spp; depth, transport and field of view remain unchanged. After 350 ms without
camera changes, the next job restores the requested grid and begins new raw
accumulation. Pause holds the current grid. Explicit resolution/transport edits
retain P2 cancellation behavior; disabling automatic mode restores fixed quality
at the next job boundary.

Live requested dimensions remain separate from actual sampled dimensions.
Snapshots and render keys contain the actual grid, and no sample history crosses
grid changes. Tracer/publication pixel allocation occurs in the coordinator,
outside the input lock. Three publication slots still bound storage; adaptation
may allocate on grid transitions. Only a timestamp is retained for feedback.

The footer and F3 show shown/requested grids. Internal adaptation keeps the
fresh-image FPS window intact; explicit requested resolution and preset changes
still reset it. The requested budget is best effort, especially if the minimum
grid or display/presentation overhead exceeds it.

## Repeatable camera path

Baseline code is commit `3945680` plus P4 with automatic mode disabled. Both modes
run in the same compiled program; the fixed branch retains existing tracing
settings and P2 camera-preview behavior. The footer fix is present in both.

Settings: glass, display/requested 1440×900, depth 8, seed 1, 14 workers, tile 32,
batch maximum 8, stationary target 8 spp. Moving automatic batches are capped at
one sample. Camera X follows `sin(elapsedSeconds * 1.5) * .1`; camera direction
is unchanged. Each case has three seconds of warmup motion, four seconds of
measured motion, then stationary recovery. Display pacing targets 144 Hz.
Cases run fixed/auto/auto/fixed to expose order effects. FPS and interval/age
statistics use the last two seconds of movement, matching F3.

| Mode | Fresh FPS | Update mean / p95 | Snapshot age p95 | Moving grid | Grid changes during all 7 s of motion | First current full-grid image after stop |
| --- | ---: | ---: | ---: | --- | ---: | ---: |
| Fixed 1 | 9.5 | 105.1 / 119.2 ms | 144.5 ms | 1440×900 | 0 | 131.6 ms |
| Automatic 1 | 114.0 | 8.8 / 11.4 ms | 21.8 ms | 360×225 | 2 | 650.1 ms |
| Automatic 2 | 112.0 | 8.9 / 11.8 ms | 21.7 ms | 360×225 | 2 | 498.8 ms |
| Fixed 2 | 9.5 | 108.6 / 122.6 ms | 152.1 ms | 1440×900 | 0 | 238.1 ms |

Automatic mode changed native → half → quarter during warmup and held quarter
through the measured motion interval. All four cases restored or retained
1440×900 and reached 8 stationary spp. Full-quality recovery includes the 350 ms
quiet period, remaining active work and the full-grid trace. Reduced-grid previews
remain visible during recovery; the recovery time is not a frozen display gap.

This benchmark has no window, actual AWT input delivery, overlay cost during
timing, AWT submission, or monitor scanout. The reported age ends after raster
conversion. A composed F3 preview is generated after motion and inspected for
readability. Live F3 timestamps updates after the full pipeline including AWT;
these results do not predict exact user-run FPS or physical input-to-screen age.
Raw output: [motion results](motion-results.log). Source hashes/settings:
[source metadata](source-hashes.txt).

## Verification

`InteractiveResolutionTest` checks opt-in behavior, aspect/minimum bounds, cooldown,
fresh-feedback requirements, pause/off, commands, complete previews during motion,
exact pixels for a retained captured camera, and exact settled two-spp full-grid
output. Its reference uses two single passes so the 50 ms batch limit cannot
truncate the expected sample count under load. Explicit resolution/depth/pause
edits and resumption are covered with automatic mode enabled.

Nine relevant suites passed: InteractiveResolutionTest, ResponsiveTraceTest,
ParallelTraceTest, ProgressivePathTest, ViewportKeyBindingsTest,
ProfilingIntegrationTest, FrameProfilerTest, ConsoleInteractionTest and
ViewportFooterTest. Parallel scheduling checks cover all transport presets.
Tests and benchmarks open no windows. Actual GUI input/presentation remains a
manual check. Test logs are archived alongside the motion result.

## Reproduction

From the repository root, compile sources/tests with Java 23 preview (see
`AGENTS.md`), then in PowerShell:

```powershell
& "$env:USERPROFILE\.jdks\openjdk-23.0.1\bin\java.exe" --enable-preview -cp out/cli scenes.viewport.InteractiveResolutionTest
& "$env:USERPROFILE\.jdks\openjdk-23.0.1\bin\java.exe" --enable-preview '-Djava.awt.headless=false' -cp out/cli DynamicResolutionBenchmark preview
```

The optional `preview` argument writes `out/cli/p4-live-preview.png` after timed
motion. Omit it to avoid that untimed raster export.
