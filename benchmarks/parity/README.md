# Common game renderer parity (P1)

Updated: 2026-10-25. Scope and milestone status are maintained in
[RendererParityRequirements.md](../../RendererParityRequirements.md). This evidence describes
the common game profile, not complete CPU/GPU feature equivalence. P2 fatal-error reporting
and atmosphere implementation are not included. Native-window human acceptance is still pending.

## Run the completion gate

From the repository root in PowerShell:

```powershell
./parity-check.ps1
```

This runs the existing style/scene/engine gate, the game suites, the same session conformance
suite on CPU and GPU, seven fixed image comparisons, and the existing GPU numeric/lifecycle/UI
checks. CPU build and conformance run with a classpath that contains no native GPU module.
GPU dependencies must have been installed with `./setup-gpu.ps1`; the gate itself does not
download them. Compilation, checksum and device-probe errors fail verification.

Read `out/parity/summary.json` after the command completes. It records the base Git revision,
a fingerprint of renderer/test/shader sources (including uncommitted changes), stage results,
UTC timestamps, a reason for failure/skip, and the evidence directory. The fingerprint covers
`src`, `tst`, `tools/gpu`, `tools/parity` and `assets/gpu`, not every repository file.
`availability.txt` records the device name and raw Vulkan vendor/driver/API version values.
Individual stages also retain their normal logs in `out/scene-check`, `out/game-check` and `out/gpu`.

| Outcome | Exit behavior | Meaning |
| --- | --- | --- |
| PASS | 0 | All selected CPU and GPU checks passed on this machine |
| PARTIAL | 2 | CPU checks passed but device tests were explicitly skipped or dependencies/hardware were unavailable; this is not GPU verification |
| FAIL | Nonzero | A check, build or probe failed; inspect the reason and stage logs |

An intentional CPU-only run must state why:

```powershell
./parity-check.ps1 -SkipGpuReason "No supported GPU on this machine"
```

Missing native modules or required device features can produce SKIP. An unexpected probe error
cannot. A skipped run does not satisfy the device-verification requirement for a rendering change.
Run the complete gate at the same source revision on a supported machine before declaring it
verified. The executable fixture test `./tools/parity/gate-test.ps1` checks pass, explicit skip,
unavailable hardware, probe failure and image-mismatch classification without opening a window
or requiring a GPU. Its stage implementations are mocks; it verifies the gate's classification,
not rendering. `./style-test.ps1` separately verifies the existing readability gate.

## Public support contract

`RenderEngine.capabilities("cpu" or "gpu")` returns software support without touching native
libraries. `support(Feature)` supplies an enabled state and explanatory reason. Depth, image
dimensions and batch limits are available as numeric queries. The game settings panel consumes
these capabilities for supported controls, depth limits and disabled-control tooltips.

`validate(world, view, settings)` checks input combinations; `issues(...)` returns the first
unsupported input as a path/reason pair. `UnsupportedInput` remains an `IllegalArgumentException`
and also exposes that structured issue. Invalid base values still fail in immutable input
constructors. Shared lighting/material restrictions are checked by `WorldSnapshot` construction.
Thus a true feature flag is not permission to combine arbitrary individually supported features.

The GPU session and game settings use the same validation authority. GPU creation validates
supported input before attempting native module loading. The CPU session applies its checks
before mutating live state. A rejected edit preserves the previous valid settings/generation.
Workers/tiles remain CPU scheduling controls, not GPU performance controls.

`RenderCapabilities.COMMON_GAME_PROFILE` names `game-v1`; `commonGameIssues(...)` checks the
common input subset. This profile covers the three game scenes, diffuse polygon geometry and
existing cube textures, a directional light, sky/discs, perspective depth of field, depth 0..2,
fixed grids and normal game lifecycle. CPU-only features remain available outside it. The game's
old block builders initially contain point lights; the profile refers to the directional-light
worlds the game actually submits, not those intermediate builder snapshots.

`RenderEngine.probe(backend)` is an explicit, potentially expensive availability check. CPU
availability requires no native classes. The GPU probe creates and disposes a Vulkan device;
it does not prepare scenes or shaders. AVAILABLE means those device requirements passed at
probe time, not that later resource allocation or rendering cannot fail. UNAVAILABLE and ERROR
are distinct. Neither the probe nor the game automatically switches backends.

## Verification coverage and image tolerances

[BackendConformance](../../tst/game/BackendConformance.java) runs the same operations for both
backends: valid creation, completion notification, batch retention/equivalence, restart,
invalid-input atomicity, held-image stability, released-pixel rejection, pause/suspend/resume,
camera/world edits, active-work close and idempotent close. It tests capability reasons and
settings controls, negative GPU cases, shared incompatible lighting/material combinations,
and validation at the supported camera/grid extremes. Existing specialized suites supplement
this shared contract with sustained motion, thin geometry, failure injection and lens optics.

[ParityImages](../../tst/game/ParityImages.java) renders gallery, landscape and world at depth
0 and 2, plus a night/lens world. Each uses 128x80, eight samples, seed 1 and batches of four.
The CPU writes a raw RGB reference; the GPU checks matching metadata before accepting the comparison.
The output contains settings files, raw references, per-backend PNGs and a CSV of image errors.
Display checks independently check row orientation, channel order and exposure values 0.5, 1 and 2
against encoded raw radiance. Comparison settings disable temporal presentation.

RMSE is root-mean-square error across raw linear RGB channel values. Existing GPU regression
limits are retained: 0.002 for deterministic depth-zero fixtures and 0.04 for stochastic depth-two
fixtures. A new maximum-channel-error guard of 0.02 and 0.25 respectively prevents small omitted
features from hiding behind the average. These are conservative fixture regression limits, not
universal perceptual or statistical error bounds. Initial measurements are far below the RMSE
limits; the largest observed individual error is approximately 0.0213 in the depth-two landscape.
The maximum guards preserve headroom for occasional divergent stochastic paths while rejecting
an omitted unit-radiance pixel. New feature fixtures need their own justified limits.

Every run deliberately corrupts a single value in an isolated synthetic fixture and verifies
that the maximum-error guard rejects it even when average error passes. No production shader is
modified. Separate existing tests check physical invariants and visible disc pixels/occlusion,
so agreement between the two implementations alone is not treated as correctness.

The retained `verification/` evidence is a snapshot of the successful P1 run; fresh runs write
to `out/parity`. Images and settings there cover this machine and these cases, not all possible
scenes or other GPU vendors. Full project coverage is not claimed.

## Before/after performance experiment

[ParityBenchmark](../../tools/parity/ParityBenchmark.java) measures the real game owner and
image-copy path without an AWT window. It uses the adventure world at 480x300, depth 2, seed 1,
fixed resolution, one sample per batch, unlimited refinement and default static noon lighting.
Each run moves the camera along the formula in the benchmark for six seconds, then holds its
pose for two seconds. Run 0 warms up; runs 1..3 are the measured repetitions. CPU and GPU run
sequentially, never simultaneously. The before binaries are from `72d481f`; after binaries
include P1. No timing target or cross-backend speed equivalence is asserted.

Reproduce after building with `./gpu-build.ps1`:

```powershell
$jdk = 'C:/Users/andri/.jdks/openjdk-23.0.1/bin'
& "$jdk/javac.exe" --enable-preview --release 23 -cp out/game/classes -d out/game/classes tools/parity/ParityBenchmark.java
& "$jdk/java.exe" --enable-preview '-Djava.awt.headless=true' -cp 'out/game/classes;out/gpu/classes;out/gpu/lib/*' game.ParityBenchmark cpu out/parity-route
& "$jdk/java.exe" --enable-preview '-Djava.awt.headless=true' -cp 'out/game/classes;out/gpu/classes;out/gpu/lib/*' game.ParityBenchmark gpu out/parity-route
```

See `before/` and `after/` for all four runs, raw publication rows, settings and runtime details.
Initialization is elapsed time to the first copied image; report cold run 0 separately from
later JVM-warm runs. Fresh-image intervals and snapshot age describe copied publications, not
monitor scanout, real input latency or Swing painting. Heap values are sampled live heap usage,
not a high-water mark of all committed memory. Trace allocations cover completed-job counters,
not all UI/driver allocations; GPU's zero counter means unsupported/unreported, not zero allocation.
GPU device-memory peak is recorded in its runtime status. These limits matter when comparing runs.

The machine was Windows 11, JDK 23.0.1, Intel Family 6 Model 183 CPU and an RTX 3080.
The table takes the median of the three measured per-run statistics; it is not a pooled percentile.

| Backend | Median interval before → after (ms) | p95 interval before → after (ms) | p95 snapshot age before → after (ms) |
| --- | --- | --- | --- |
| CPU | 43.414 → 43.873 | 58.669 → 58.490 | 63.768 → 61.680 |
| GPU | 1.322 → 1.301 | 2.258 → 2.055 | 4.467 → 4.059 |

CPU median intervals changed by about 1%; p95 ranges overlap. GPU steady-state intervals also
remain close. These short runs do not establish statistical equivalence or an improvement claim.
Cold time to first image was 455.7 → 627.6 ms on CPU and 855.0 → 902.7 ms on GPU. Warm CPU starts
ranged 61.7–103.8 ms before and 66.8–80.4 ms after; warm GPU starts ranged 172.4–204.1 ms before
and 179.0–402.2 ms after. The single 402.2 ms warm GPU start was not repeated in its following two
runs (179.0 and 198.5 ms); initialization remains variable and is not hidden in the steady-state table.

Reported CPU trace allocation totals stayed near 22 MB per measured route. Sampled heap usage
varied with garbage collection: CPU maxima across measured runs were 245.4 → 280.1 MB and GPU
329.6 → 336.9 MB. GPU status reported the same 10.5 MiB device-memory peak before/after.
These are measurements of this workload, not bounds on all scenes or total native/driver memory.
No frame-path file I/O or new frame-history buffer was introduced by P1.

## Human check still required

Launch `./game.ps1 -Scene world -Backend cpu -Width 480 -Height 300`, then repeat with `-Backend gpu`.
Use the same paused clock hour, seed, exposure, depth and optics. Explore, refine while stationary,
change focus and lighting, resize, lose/regain window focus and close. Compare the visible scene,
supported control behavior and responsiveness. Inspect disabled GPU controls for specific reasons.
The automated offscreen panel checks do not establish this human playthrough. Record acceptance
separately in the requirements milestone table after the user performs it.

