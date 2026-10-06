# P5.2 Temporal motion budget

This phase adds an optional work policy, not a new temporal estimator. Defaults
retain existing behavior: temporal off, motion budget off, one moving sample cap,
and a 1× grid cap. Enable it through the console:

```text
view samples 4
view temporal on
view temporal budget on
```

`view temporal budget samples 1` selects the maximum fresh samples per moving
batch; `view temporal budget scale 0.5` optionally caps each grid dimension to
half the requested size. Values are 1..8 samples and 0.25..1 scale. Both are
ceilings, not promises of completing that work in one job. Requested batch/grid
settings remain unchanged and resume after 350 ms without camera motion.

With no compatible history, including cuts, scene/grid edits and expiry, the
coordinator uses the requested batch maximum to seed a new image. Existing 50-ms
batch limits and camera-preview rules still apply. Individual correspondence
failures remain fresh raw estimates; they do not cause per-pixel extra samples.
There is no sparse tracing below one spp, cross-grid history or temporal upscaling.
Lower grids explicitly sacrifice detail, including on glass/mirror pixels.

P4 (`view interactive on`) is independent. Its one-spp moving cap takes precedence;
its grid minimum remains authoritative. The temporal scale is an extra ceiling
within those bounds. Grid changes happen between completed jobs and reset history.
When P4 is off, the chosen scale is fixed during motion, avoiding feedback-driven
oscillation. The temporal budget bypasses volume scenes, scenes with no diffuse
surfaces and deterministic depth-zero point-light scenes; disabling temporal also
disables this policy's effect. Pause, targets and leases retain their existing rules.

F3's grid row reports actual/chosen/requested batch counts. The chosen maximum
can rise during history fallback; actual work can be lower due to target, timing
or camera motion. Accumulated raw spp and history blend remain separate. At one
requested spp with scale 1, there is no tracing reduction.

## Fixed-view quality and processing cost

`TemporalBudgetBenchmark` captures 18 camera views, including a reversal and cut,
after 100 warmup views. Sensor/display comparison grid is 160×100; seed1/2/3 runs
each use an independent 128-spp reference (seed1001/1002/1003). Preset depths,
tile32 and up to14 workers are unchanged. Timings include guides/reconstruction;
they exclude publication copies, display conversion, UI and AWT presentation.
Reference box resampling puts reduced grids on the same linear comparison grid.

Means below average the three runs, rather than selecting the fastest seed.

| Scene/mode | Grid | Processing mean ms | Linear MSE | Horizontal-gradient MSE |
| --- | --- | --- | --- | --- |
| bounce raw4 | 160×100 | 4.857 | .23700 | .47515 |
| bounce raw3 | 160×100 | 3.627 | .31516 | .63235 |
| bounce raw1 | 160×100 | 1.239 | .93103 | 1.86471 |
| bounce budget1 | 160×100 | 2.978 | .21911 | .41532 |
| bounce raw-low1 | 128×80 | 1.159 | .52316 | .69630 |
| bounce budget-low1 | 128×80 | 1.825 | .15908 | .19446 |
| glass raw4 | 160×100 | 5.646 | .01642 | .03292 |
| glass raw3 | 160×100 | 4.231 | .02150 | .04308 |
| glass raw1 | 160×100 | 1.471 | .06344 | .12689 |
| glass budget1 | 160×100 | 2.735 | .04375 | .08584 |
| glass raw-low1 | 128×80 | 1.256 | .04368 | .06546 |
| glass budget-low1 | 128×80 | 1.998 | .03332 | .05035 |

The declared quality target is matching raw4 average linear error on the same
captured-view sequence. Bounce budget1 meets it in all three runs: about 8% lower
mean error and 39% less mean processing time. It traces 336,000 primary paths
instead of 1,152,000 (71% fewer), including requested four-spp reseeding at the cut.
For same-grid seed1, the cut MSE is raw4-exact (.219248); reversal MSE is .225358
versus raw4 .250146. Cut reseeding causes budget1 p95 spikes (8.5–9.0 ms in bounce)
despite lower mean cost; this is a visible budget/quality tradeoff, not 60-FPS proof.

Glass budget1 fails this quality target: error is about 2.7× raw4. It remains a
manual experimental setting, not a recommended equivalent-quality speedup for
glass. Both scenes remain slower than raw1 at the same grid. Lower-grid filtering
can lower noise/global error while losing sharpness; neither global nor gradient
MSE proves detail retention. Previews were inspected: glass boundaries/unsupported
reflections remain noisier, and reduced grids soften detail. Do not infer that all
presets benefit from bounce-room's result.

Previews: `out/cli/p52-<preset>-seed<seed>.png`, four columns (raw3, budget1,
budget-low1, reference), two rows (before cut, cut). PNGs are local generated
artifacts, not tracked binaries. Each log includes p95, exact primary/guide counts,
reversal/cut errors and a horizontal-gradient diagnostic.

## Live software fresh-image measurements

`TemporalBudgetLiveBenchmark` uses the actual asynchronous viewport and display
conversion, an identical elapsed-time camera path, 800×500 requested grid,
bounce-room depth3, seed1, requested batch3, target8, nominal144-Hz pacing,
2 seconds warmup plus 3 seconds measured motion. It then stops and checks full-grid
convergence. Forward/reverse ordering reduces simple warmup/order bias. F3 metrics
count fresh publications through software submission, not cached redraws.

| Mode | Fresh FPS, repeated runs | Fresh p95 ms | Snapshot age p95 ms | Moving grid |
| --- | --- | --- | --- | --- |
| raw3 | 18.5 / 19.5 | 66.88 / 62.00 | 96.97 / 92.06 | 800×500 |
| budget1, scale 1 | 20.5 / 20.5 | 57.51 / 58.87 | 87.66 / 85.89 | 800×500 |
| P4 alone | 44.5 / 44.5 | 23.97 / 23.44 | 47.75 / 46.31 | 200×125 |
| budget + P4 | 45.0 / 45.0 | 23.97 / 23.70 | 47.36 / 46.35 | 200×125 |
| budget, scale 0.5 | 40.5, one run | 26.55 | 52.56 | 400×250 |

The full-grid gain is modest (about 5–11%), much smaller than the fixed-work
comparison: sustained input already ends raw batches after their active pass.
The combination with P4 shows no meaningful FPS improvement over P4 alone.
The fixed half-grid option was faster than full-grid, at explicitly lower detail;
it used one motion grid transition, while P4 changed twice. Full-grid restoration
after stopping was about 51–88 ms for budget scale 1, 454 ms for scale 0.5 and437–467 ms
for P4 modes, including the 350-ms settling interval where a grid changes. All runs
ended at the requested 800×500 grid and 8 raw spp.

These runs exclude AWT presentation/overlay drawing, physical input latency and
monitor scanout. Three-second windows and local machine load limit precision.
The live sequence is not the same as the fixed-view quality sequence, so the two
tables are separate evidence, not a matched live-image-quality guarantee. Hands-on
motion/trail/sharpness checks remain necessary before choosing a preferred mode.

## Verification and reproduction

Eight suites pass: TemporalBudgetTest, InteractiveResolutionTest,
TemporalReconstructionTest, ResponsiveTraceTest, ProfilingIntegrationTest,
ViewportKeyBindingsTest, ConsoleInteractionTest and CameraSamplingTest. Tests cover
controls/help, caps and 64-pixel bounds, P4 precedence, missing/edit/cut/expired
history, motion previews, exact stationary raw convergence, restoration, toggles,
cancellation, leases, input and F3 batch metadata. The composed F3 preview was
inspected and remains readable. Verification opens no windows.

After compiling with JDK23 preview features:

```powershell
& "$env:USERPROFILE\.jdks\openjdk-23.0.1\bin\java.exe" --enable-preview -cp out/cli engine.TemporalBudgetBenchmark seed=1
# Repeat sequentially with seed=2 and seed=3.
& "$env:USERPROFILE\.jdks\openjdk-23.0.1\bin\java.exe" --enable-preview '-Djava.awt.headless=false' -cp out/cli TemporalBudgetLiveBenchmark
```

Run benchmarks sequentially without competing verification. Archived final logs
and source hashes identify this implementation. Sampling/history settings stay
explicit; no scene preset enables this mode automatically. Sparse updates and
per-pixel sample accounting remain P11.1.
