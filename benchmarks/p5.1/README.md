# P5.1 Shared temporal guide corners

Baseline: committed P5 `fa46be2`, including camera-dependent grain and continuous
fading. Candidate: two rolling rows of worker-local corner hits. Neither changes
the raw estimator, sample count or transport-ray counters.

Each suitable pixel still checks a center hit, four corner identities/front faces/
normals and its actual sampled primary hit. Adjacent pixels share exact boundary
corner queries within a tile, replacing the old slightly inset corner rays. Corners
are queried lazily, so misses and unsupported center surfaces do not automatically
pay for all four. Tile borders can repeat queries; workers share no mutable scratch.
Two rows suffice because pixels are visited in row order. Scratch is bounded by tile
width, reused and released on temporal-off/volume fallback. At tile32, each worker
needs 66 cached corner entries (references, three floats and two booleans), plus array
headers. Full histories and publication payloads remain unchanged at the documented
72 and 128 bytes/pixel respectively; those figures exclude this small worker scratch.

## Cost measurements

The existing cost benchmark uses one spp, 18 warmup/18 measured moving images,
seed1, tile32, up to14 workers, depth3/8 and quarter/native grids. Below, baseline
is the repeat run after candidate measurement, which had closer raw timings than
the initial baseline. All runs are archived; do not interpret differences as a
precise speedup forecast.

| Scene/grid | P5 temporal mean/p95 ms | P5.1 temporal mean/p95 ms | Raw means P5 / P5.1 ms |
| --- | --- | --- | --- |
| bounce-room 360×225 | 17.309 / 92.652 | 18.112 / 106.592 | 7.051 / 6.742 |
| bounce-room 1440×900 | 159.854 / 167.229 | 140.363 / 147.299 | 80.492 / 83.453 |
| glass 360×225 | 9.744 / 10.898 | 7.264 / 7.752 | 6.319 / 5.168 |
| glass 1440×900 | 161.601 / 170.980 | 122.979 / 134.257 | 72.287 / 69.358 |

Native temporal means improve about 12%/24% for bounce/glass in this comparison;
subtracting the respective raw means reduces estimated temporal overhead from
79.4 to 56.9 ms and 89.3 to 53.6 ms. Raw timing variation and short warmup limit attribution.
Quarter bounce-room shows no reliable benefit and has large outliers. Temporal still
costs more than raw in every case and stays off by default. No AWT presentation,
publication copies, physical input or monitor scanout are included in these timings.

## Quality and work

Motion benchmark: 160×100, seed1, tile32, up to14 workers, preset depths, 12 warmup/
18 measured images, reversal and cut, independent 128-spp reference per captured
camera. P5 and P5.1 use the same sampling stream and fading behavior.

| Scene | Guide rays P5 → P5.1, including warmup | One-spp linear MSE P5 → P5.1 |
| --- | --- | --- |
| diffuse-room | 1,782,410 → 849,624 (−52%) | .0576739 → .0578071 (+0.23%) |
| bounce-room | 2,266,346 → 969,809 (−57%) | .265996 → .266103 (+0.04%) |
| glass | 1,430,946 → 746,676 (−48%) | .0456190 → .0458223 (+0.45%) |
| volume-room | 0 → 0; raw fallback | .0818850 → .0818850 |

Raw one-spp MSE is identical before/after. Rejected pixels and camera-cut images
remain raw-exact. Reuse counts decrease slightly near boundaries; reconstructed
output is intentionally not bit-identical because the corner positions changed.
Finite center/corner/primary checks still cannot prove the absence of every tiny
subpixel mixture. Mirrors/glass remain unsupported for their own history, and
volumes retain whole-frame fallback.

Soft equal-16.67-ms results are in the quality logs. Budget runs can overshoot at
complete-pass boundaries and finish different spp under machine-load variation.
Candidate temporal MSE versus candidate raw is about 2% lower in diffuse-room,
26% lower in bounce-room and 12% lower in glass in this run, not a universal guarantee.
Some integration tests overlapped the beginning of the baseline quality run;
guide counts and fixed-spp errors are deterministic, but timing comparisons from
that run should be treated cautiously. The cost comparisons above ran separately.

## Verification and reproduction

Six suites pass: TemporalReconstructionTest, ResponsiveTraceTest,
InteractiveResolutionTest, CameraSamplingTest, ParallelTraceTest and
ProfilingIntegrationTest. The temporal suite adds exact guide/raw agreement over
tile8/32/256 and workers1/4, a diffuse-plane query bound and thin silhouette/cache
boundary checks. Existing cancellation, leases, cuts, fading, media rejection and
transport counter checks remain. Headless verification opens no windows; human GUI
motion assessment remains separate.

Compile normally, then run with JDK23 preview features from the repository root:

```powershell
& "$env:USERPROFILE\.jdks\openjdk-23.0.1\bin\java.exe" --enable-preview -cp out/cli engine.TemporalReuseBenchmark cost
& "$env:USERPROFILE\.jdks\openjdk-23.0.1\bin\java.exe" --enable-preview -cp out/cli engine.TemporalReuseBenchmark
```

For the P5 reference, compile only `DirectRgbTracer.java` from `fa46be2` to a separate
class directory and put it before `out/cli` on the classpath. Both then use identical
benchmark/reconstruction classes. Do not run competing benchmarks concurrently.
Archived logs include the initial baseline, initial candidate, rolling-row candidate,
repeat baseline, quality comparisons and suite results. Source hashes identify
the final implementation; releasing scratch on fallback was added after timing runs
and does not change enabled surface tracing.

Jittered-primary substitution was not adopted because it would remove independent
center coverage. Publication copies remain to protect leases. Automatic bypass
needs a measured quality threshold and transition policy; it was not introduced.
P5.2 remains the separate phase that reduces fresh tracing work.
