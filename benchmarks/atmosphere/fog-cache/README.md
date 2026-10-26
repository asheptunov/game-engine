# Fog sunlight cache: implementation and measurements

The GPU renderer now caches sunlight transmission through smooth local fog. Previously, each view-ray integration step marched another ray through every fog region toward the sun. The cache replaces many of those nested integrations with spatial interpolation. Geometry shadow rays and the 24-step view integration are unchanged.

The measured scene is a player capture looking into the ruins at 7 AM, with two overlapping fog regions and path depth 2. The cache improves this case even when the sun changes on every rendered update. It does not establish a 60 FPS guarantee.

## Results

Measurements were taken on an NVIDIA GeForce RTX 3080, OpenJDK 23.0.1, Windows. Each configuration has ten warm-up frames followed by twenty measured one-sample frames, repeated twice with reversed resolution order. The table reports the mean of the two per-run medians, in milliseconds. CSV files retain each run's median and 95th percentile.

“Total” measures from the session update through publication and CPU image conversion. It includes cache construction and readback, but excludes native window presentation and initial device/scene setup. It is not displayed FPS. Each moving-sun frame advances simulated time by a fixed 0.1 real seconds, independently of benchmark execution speed. Day durations are 600 seconds and 30 seconds; each update therefore requires the exact new sun direction. All cases start at 7 AM, so the accelerated case traverses a larger range of lighting conditions.

| Resolution | Sun | Direct total | Cached total |
| --- | --- | ---: | ---: |
| 418×200 | Stationary | 7.7 | 5.5 |
| 418×200 | 600-second day | 7.4 | 5.4 |
| 418×200 | 30-second day | 7.7 | 5.4 |
| 836×400 | Stationary | 27.0 | 19.1 |
| 836×400 | 600-second day | 27.9 | 17.6 |
| 836×400 | 30-second day | 28.9 | 18.5 |
| 1600×766 | Stationary | 90.0 | 63.7 |
| 1600×766 | 600-second day | 90.3 | 64.3 |
| 1600×766 | 30-second day | 97.9 | 62.0 |

At 1600×766, GPU execution falls from approximately 73 ms to 46 ms with a stationary sun, and from 81 ms to 44 ms with the accelerated day. GPU timestamps include construction; a moving-sun table build measured approximately 0.04 ms. CPU image conversion still costs roughly 12–13 ms and is not optimized here.

The additional horizon sweep starts at 6 AM. Very low sunlight already renders cheaply, so the final implementation keeps direct integration until the normalized solar direction's Y component exceeds 0.05 (about 2.9 degrees above the horizon). The accelerated horizon sweep crosses that threshold and benefits from caching. Normal horizon runs remain roughly 10–13 ms total; differences within that range are not claimed as a speedup.

## Cache behavior and limits

`src/engine/FogSunTable.java` defines the shared layout/eligibility policy and a CPU implementation used as a numerical oracle. `src/gpu/GpuKernel.java` owns a bounded 49³ RGB table in a Vulkan buffer, approximately 1.8 MiB including padding. `assets/gpu/diffuse.comp` builds the table on the GPU and interpolates it during rendering. A GPU memory barrier separates construction from use.

The key includes fog properties, bounds and the exact solar direction. Sun or fog edits rebuild before rendering that captured state; there is no delayed update, angular quantization or temporal blending. Camera motion, resolution changes and geometry edits do not invalidate fog transmission. Geometry shadows are still queried against the current scene. Other directional lights, points outside the table, and points near fog boundaries crossed by grazing sunlight use direct integration.

The optimization is deliberately limited to one or two smooth regions: feathering at least 0.2, pattern frequency at most 2, and height falloff at most 2. For each color channel, density times extinction times the largest half extent must not exceed 1. The grid must provide at least twelve cells per local half extent, accounting for rotation. Sharper, denser, finer-patterned or insufficiently resolved inputs retain the existing direct integrator. All previously supported fog settings remain usable.

The production CPU renderer is unchanged. A rejected CPU-cache prototype took about 500–513 ms per moving-sun frame at 160×77 versus approximately 320–340 ms for direct integration. CPU construction alone cost around 170–260 ms. The retained CPU path measured roughly 310–335 ms in the final check. These prototype and final measurements are saved separately and must not be mistaken for the GPU results.

## Accuracy and verification

`tst/game/FogSunTableTest.java` checks interpolation against 192-step sunlight integration using the existing 0.015 absolute transmission-error limit. It also checks rotated colored media, cache keys, exterior queries and cases that must keep direct integration. `tools/gpu/FogSunCheck.java` compares real-device cache images with direct integration at depths 0 and 2, across multiple sun positions. It enforces the existing per-channel radiance bound of `0.015 + 0.05 × reference` and CPU/GPU root-mean-square error at most 0.004. It checks reuse across resolution changes and invalidation after fog disable/re-enable.

The paired 836×400, 16-sample images in this directory use the original captured view. Their encoded RGB values differ by at most one level on the 0–255 scale; root-mean-square difference is 0.044. This image comparison supplements the linear-light checks; it does not replace them. Native interactive playthrough remains a separate human check.

Run `./parity-check.ps1` for the repository, game and real-device gates. The new numerical tests are included in `game-check.ps1`; the real-device cache checks are included in `gpu-check.ps1`.

## Reproduce

The original diagnostic capture remains in this workspace at `out/game/captures/2026-10-26T14-08-21.947078900Z-e2ee8470-e5a0-4d6e-b5de-09235006b9c0`. It is not checked in or publicly downloadable, so these exact measurements cannot be reproduced from a checkout alone. If that local directory is unavailable, save your own ruins view with F8 and pass its folder to the benchmark; the resulting numbers describe that different capture. The procedural accuracy tests do not require the private capture. The benchmark requires a displayed image with known rendering context and an atmosphere. Capture handling is described in [the capture guide](../../game/CAPTURES.md).

From the repository root, build the GPU module and compile the game tests, then run the benchmark with the capture's folder path:

```powershell
./gpu-build.ps1
./game-check.ps1
$benchClasspath = 'out/game/classes;out/game-check/classes;out/gpu/classes;out/gpu/lib/*'
$benchCapture = 'out/game/captures/2026-10-26T14-08-21.947078900Z-e2ee8470-e5a0-4d6e-b5de-09235006b9c0'
& "$env:USERPROFILE/.jdks/openjdk-23.0.1/bin/java.exe" --enable-preview -Djava.awt.headless=true -Dengine.fogSunReference=true -cp $benchClasspath game.FogCacheBenchmark $benchCapture gpu '418,836,1600' 7 20
& "$env:USERPROFILE/.jdks/openjdk-23.0.1/bin/java.exe" --enable-preview -Djava.awt.headless=true -Dengine.fogSunReference=false -cp $benchClasspath game.FogCacheBenchmark $benchCapture gpu '418,836,1600' 7 20
```

Arguments after the capture are backend, comma-separated widths, starting hour and measured frame count. Use width `836` and hour `6` for the horizon sweep. Use `cpu 160 7 4` for the small CPU check. `engine.fogSunReference=true` is a launch-time diagnostic switch that disables the GPU cache. The benchmark always uses one sample per pixel, disables temporal/adaptive presentation, and preserves the captured camera, exposure and path depth.

`metadata.json` records the capture identity, hardware and implementation hashes. `gpu-reference-true.csv` and `gpu-reference-false.csv` contain the matched direct/cached runs. The `horizon-*` files are the 6 AM sweep. `cpu-reference.csv`, `cpu-rejected-prototype.csv` and `cpu-final.csv` distinguish the CPU experiment from the retained implementation. `reference-true.png` and `reference-false.png` are the paired direct/cached renders.
