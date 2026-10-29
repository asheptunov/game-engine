# Live center detail and high-resolution rendering

This experimental GPU feature lets the player trade peripheral detail for a sharper center while rendering at 1440p or 4K. It extends the [smooth reconstruction prototype](../smooth/README.md). Human acceptance of the stronger falloff is pending.

## Try it

Relaunch the game, open Escape → Render, and select **4K strong falloff**. This selects 3840×2160, enables both center-detail options, sets **Sharp center (%)** to 25 and **Edge spacing (pixels)** to 12, and disables the automatic FPS resolution budget. It leaves native presentation unchanged. The game still scales its output to the window; this does not change the monitor mode.

Adjust the sliders live. Sharp center accepts 10–75 percent of each image axis; edge spacing accepts 2–16 pixels. Lower center percentages and larger edge spacing concentrate work more strongly near the aim point. Changes apply when the slider is released or stepped with the keyboard. The defaults remain 50 percent and 6 pixels, reproducing the previous smooth mode. These controls affect smooth center detail only; both center-detail switches must be enabled. At 4K, the shortcut preserves roughly 960×540 pixels in the full-density central rectangle.

The resolution selector also includes **1440p QHD** (2560×1440) and **4K UHD** (3840×2160). Ordinary resolution selections retain quality and FPS-budget settings. Supported dimensions are now 64–3840 on each axis, including launcher arguments. CPU views accept the larger bounds, but their high-resolution performance has not been established.

Compare thin silhouettes, dark surfaces, and peripheral movement at the default and strong settings. The stretched grid has continuous spacing rather than resolution bands, but aggressive reconstruction can still soften or distort peripheral edges. The quality sliders are manual. Automatic peripheral-quality adaptation is deferred; the existing FPS budget continues to control the whole rendering grid.

## Measurements

Measured on the RTX 3080 12 GB using the displayed camera and world from capture `2026-10-28T23-02-50.137238100Z-cda17c85-0df9-4815-8468-de81c266aba8`: depth 8, Full atmosphere, exposure 1, one sample per call, packed output readback. Each mode had 15 warmup calls and 60 measured calls in each of three rounds, reversing mode order in the middle round. No builds or tests ran concurrently; other desktop activity was uncontrolled. These are provisional headless timings, excluding Swing presentation, gameplay and monitor scanout.

The table reports the median of the three per-round median times. Host time includes the call's CPU work and GPU waiting. The [raw log](high-res-benchmark.log) includes per-round p95 GPU times.

| Resolution | Mode | Primary rays | GPU ms | Host ms |
|---|---|---:|---:|---:|
| 2560×1440 | Uniform | 3,686,400 | 26.73 | 31.24 |
| 2560×1440 | Default smooth | 1,819,576 | 21.04 | 25.12 |
| 2560×1440 | Strong smooth | 792,248 | 12.60 | 16.52 |
| 3840×2160 | Uniform | 8,294,400 | 56.59 | 65.94 |
| 3840×2160 | Default smooth | 4,095,564 | 38.29 | 46.64 |
| 3840×2160 | Strong smooth | 1,778,000 | 22.01 | 30.27 |

1440p varied substantially: uniform round medians ranged 17.11–27.72 ms, default smooth 16.65–24.30 ms, and strong smooth 9.21–13.81 ms. Treat those comparisons as directional. Strong 4K used 21.44 percent of uniform primary rays and reduced median GPU time by about 61 percent. Its 22 ms GPU time still exceeds the 16.67 ms budget for 60 FPS, before presentation. It does not establish a 60 or 120 FPS result.

Peak tracked device allocation during the benchmark was 764.19 MiB, cumulative across modes and resizes. The correctness check, which also retains raw/reference data, peaked at 1,022,961,776 bytes. Neither figure bounds total live-game VRAM use. Device storage-buffer limits were checked directly; see [device limits](high-res-limits.log).

One-sample strong-mode previews: [1440p](strong-falloff-2560x1440.png), [4K](strong-falloff-3840x2160.png). These show the same captured scene, including visible noise and peripheral softness; they are not converged reference images.

## Verification and reproduction

`tools/gpu/HighResolutionCheck.java` verifies slider release behavior, the 4K shortcut, quality ranges, complete finite output and packed/raw agreement at 1440p and 4K, protected center equivalence, compact coordinate encoding, multi-row dispatch coverage, live generation resets and retained-image leases. The [device check log](high-res-check.log) records its successful run. Capture tests verify new settings round trips and default values for older captures.

The implementation uses 12-bit compact coordinates and two-dimensional workgroup dispatches for large linear workloads. Changing active smooth quality rebuilds the sampling grid and resets accumulation. Inactive quality preferences do not discard samples. GPU resources remain owned by the render coordinator.

From PowerShell at the repository root, run `./gpu-build.ps1`, then:

```powershell
& "$env:USERPROFILE/.jdks/openjdk-23.0.1/bin/javac.exe" --enable-preview --release 23 -cp 'out/gpu/classes;out/game/classes;out/gpu/lib/*' -d out/gpu/classes tools/gpu/HighResolutionBenchmark.java
& "$env:USERPROFILE/.jdks/openjdk-23.0.1/bin/java.exe" --enable-preview -cp 'out/gpu/classes;out/game/classes;out/gpu/lib/*' gpu.HighResolutionBenchmark 'out/game/captures/2026-10-28T23-02-50.137238100Z-cda17c85-0df9-4815-8468-de81c266aba8'
```

Completion gates are `./verify.ps1`, `./game-check.ps1`, and `./gpu-check.ps1`; the GPU gate includes `HighResolutionCheck`. Saved gate logs accompany this report. Source hashes identify the implementation used for these checks. User testing of live movement and peripheral quality is the remaining acceptance step.
