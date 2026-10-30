# FSR temporal upscaling for the sample game

The GPU renderer now offers optional AMD FidelityFX Super Resolution (FSR) 3.1.4 temporal upscaling after NVIDIA Real-Time Denoisers (NRD) RELAX. FSR uses lower-resolution images from successive frames to reconstruct a larger output. At 1920×1080 output, Performance traces 960×540 and Quality traces 1280×720. It replaces the separate native-resolution visibility pass when selected. It does not generate intermediate frames.

The implementation is available for evaluation; it is off by default. It improves the measured quality of low-resolution rendering, but the 120 real FPS target remains unmet. Scope glass remains noisy, and temporal reconstruction can soften fine detail. Human acceptance of visible-window motion and image quality is still pending.

## Enable and evaluate

From the repository root on Windows, run `./setup-fsr.ps1` and `./setup-nrd.ps1`, then `./game.ps1 -Backend gpu -Scene world -Width 1920 -Height 1080`. The NRD setup command rebuilds its bridge with the new jitter interface even if NRD was previously installed. Initial setup needs network access, CMake 3.30 or newer, and Visual Studio C++ build tools. Subsequent setup checks can run with `-Offline`.

Open settings → Render → Temporal upscaling (experimental). Select FSR Performance first. The control also enables reconstruction and selects NRD RELAX, a pinhole camera, uniform sampling, Full atmosphere quality, full lighting scale, positive path depth and Fixed resolution. Incompatible edits suspend FSR and report the reason in renderer status. Requested FSR settings remain selected, so restoring compatible settings resumes it. Selecting a reduced Lighting resolution explicitly turns FSR off to select the separate native-visibility method. CPU rendering rejects FSR. Old captures load with FSR off.

Compare Off, Performance and Quality while strafing past tree trunks and foliage, turning quickly, aiming through the scope, firing and revealing surfaces. Stop to assess stationary detail. Change resolution, toggle upscaling rapidly, switch focus away and back, and close during rendering. Test the moving sun and local fog separately from the fixed-light timing route below. FSR does not change the simulation clock or camera pose; only the renderer's subpixel ray samples change.

## Implementation and limits

`src/gpu/GpuFsr.java` owns the upscaler context and textures on the existing GPU owner thread. `assets/gpu/fsr.comp` packs denoised linear HDR color, finite Vulkan depth, normalized screen motion and a reactive mask, then converts FSR's output back to the renderer's buffer format. Camera and object motion exclude jitter. Sky motion includes rotation but excludes translation. Glass and invalid object correspondence receive a conservative reactive mask, which reduces history reuse where the first surface cannot describe refracted motion.

FSR's jitter values describe projected-image displacement; ray samples use the opposite offset. NRD receives the ray-sample offset in top-left pixel coordinates. The two libraries therefore receive opposite signed offsets for the same rendered frame. The tracer's bottom-up rows are converted explicitly. Cuts, stale history, settings changes and resolution changes reset or recreate the relevant histories. Newly revealed surfaces also use FSR's depth rejection. HDR signals use 16-bit floating-point textures; raw progressive means remain separate and retain their existing precision and sample counts.

The native bridge in `tools/fsr/` loads AMD's checksum-pinned, signed Vulkan DLL from FidelityFX SDK v1.1.4, commit `c6efa6bf7f2027b3ec94f28578bb5965eabb9e55`. The provider reports FSR 3.1.4. The installer checks the DLL's SHA-256 and records bridge/runtime hashes under `out/fsr/runtime/Release`; native binaries are not committed. It preserves the upstream license there. The Vulkan device enables supported float16 arithmetic and the promoted KHR allocation entry points needed by this backend. `GpuImage` and `GpuImagePass` share texture/conversion machinery with NRD. FSR commands run inside the existing trace submission and retain the three-slot image lease protocol.

This integration follows AMD's [temporal upscaling input and jitter conventions](https://gpuopen.com/manuals/fidelityfx_sdk/techniques/super-resolution-temporal/) and uses the [pinned Vulkan API implementation](https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK/tree/v1.1.4/ffx-api). It is a conventional temporal upscaler on the RTX 3080, not a neural renderer. The diffuse-only NRD pass still does not denoise refracted scope paths. FSR uses fresh raw glass samples rather than an average of differently jittered pixels, so the scope's grain remains visible.

## Quality comparison

`FsrQualityBenchmark` compares identical frozen combat states and thin-geometry scenes against native 512×288, 128-sample raw references. All candidate modes use NRD and path roulette. Combat has twelve states, one fresh sample per state and depth 8. The thin-line tests use depth 1, 48 slowly translated views or one stationary view refined for 32 frames. Mean squared error is measured after the same display mapping; lower is better. These are small diagnostic images, not a substitute for judging motion at 1080p.

| Mode | Combat error | Moving thin lines | Stationary thin lines |
|---|---:|---:|---:|
| Half image enlarged | 0.00195649 | 0.00994447 | 0.00991018 |
| Half lighting with native visibility | 0.00190057 | 0.00661456 | 0.00638832 |
| FSR Performance | 0.00146755 | 0.00108185 | 0.00102972 |
| FSR Quality | 0.00124459 | 0.00077678 | 0.00075405 |

Relative to enlarging the half image, combat error falls about 25% with Performance and 36% with Quality. Thin-line error also falls substantially, but the images show reduced contrast and residual softness: error reduction must not be read as perfect detail recovery. The diagnostic caught an initial jitter-sign error, which was corrected before the measurements above. Only final output is included in [quality evidence](quality/); the unchanged half/split combat modes reproduce the pre-integration error values.

## Performance comparison

The device was an RTX 3080 12 GB with driver 616.92 and Java 23. Each mode uses NRD, roulette, depth 8, one fresh sample, pinhole optics, uniform sampling, Full atmosphere, no local fog or bloom, fixed resolution and a frozen 09:00 sun. `LightingRouteBenchmark` runs the same elapsed-time combat route with 1080p offscreen Swing painting. It warms up each mode, measures twelve seconds, and reverses mode order in the middle of three rounds. FPS counts distinct painted images; cached redraws do not count. Monitor scanout and visible-window pacing are excluded.

| Mode | Fresh FPS, rounds 0 / 1 / 2 | p95 interval range | p95 capture-to-paint age |
|---|---:|---:|---:|
| 960×540 enlarged | 80.8 / 88.1 / 85.8 | 21.5–23.6 ms | 23.4–25.6 ms |
| Half lighting, native 1080p visibility | 55.5 / 54.4 / 56.5 | 31.0–31.6 ms | 33.2–34.8 ms |
| FSR Performance → 1080p | 59.9 / 61.2 / 62.3 | 21.3–28.9 ms | 23.4–32.4 ms |
| FSR Quality → 1080p | 42.8 / 46.6 / 47.7 | 32.6–33.3 ms | 34.5–37.1 ms |

Performance beats native visibility with half lighting in every matched round, by 8–12%. It remains slower than enlarging a 540p image. Quality costs more tracing work and is slower than both. P95 means 95% of measured intervals are at or below that duration. The [deadline counts](deadlines.json) include intervals over the 8.33 ms target and maximum intervals; these results do not support a 120 FPS claim. [Raw frames, profile correlations and settings](route/) retain the complete measurements.

All modes in this historical run, including the unchanged half/split controls, paint more slowly than in the earlier [frame-delivery audit](../frame-pipeline/README.md). The subsequent [resident-image and timer-policy investigation](../resident-delivery/README.md) reproduces slow delivery in the pre-FSR build and recovers throughput by making Windows honor the invisible benchmark process's existing timer requests. It supplies new controlled half-enlargement and Performance measurements; the recovery is not an FSR rendering speedup. The original desktop-policy change remains unknown. Other desktop applications remained open; no competing game appeared in the process check. Builds and tests did not overlap timing runs.

The [fixed-state replay](frozen/summary.csv) isolates rendering jobs from simulation and painting. Each variant has 36 warmup jobs and 180 measured jobs, with three alternating rounds. Median host-job times range from 6.31–6.64 ms for half enlargement, 10.92–11.31 ms for native visibility, 8.59–9.65 ms for FSR Performance, and 12.49–13.33 ms for Quality. FSR's conversion, upscaling and output-conversion GPU work costs 0.432–0.434 ms in Performance and 0.549–0.551 ms in Quality. The displaced native visibility pass costs about 2.38–2.40 ms. Other trace, transfer and host costs remain, so that difference is not an end-to-end FPS multiplier.

Peak tracked device allocation in the replay is approximately 256 MiB for half enlargement, 363 MiB for native visibility, 411 MiB for Performance and 590 MiB for Quality. These counts include FSR's reported internal resources and renderer allocations, but exclude driver overhead and caches.

## Verification and reproduction

The [completion-gate log](verify.log), [game-gate log](game-check.log) and [GPU-gate log](gpu-check.log) record the executed checks. The completion gate covers style and selected engine/scene suites, not the entire project. `FsrCheck` covers output dimensions, finite images, stationary zero motion despite jitter, independent raw means, bounded storage, camera cuts, resizing, fallback policy, rapid mode changes, and packed/resident image leases through shutdown. The offscreen game-panel test selects FSR and checks its prerequisites and output size. Capture tests check round trips and older-file defaults; settings-copy checks retain the selected upscaler.

The [old-shader comparison](raw-reference.log) checks exact raw pixels against the pre-integration shader with upscaling off. Existing NRD, reconstruction, traversal, native-presentation, sunlight and scene-submission tests remain in the GPU gate. Automated rendering opens no visible game windows. Vulkan validation-layer coverage and human visible-window acceptance are not claimed.

The benchmark tools compile against the normal GPU build:

```powershell
./gpu-build.ps1
$java = 'C:/Users/andri/.jdks/openjdk-23.0.1/bin/java.exe'
$javac = 'C:/Users/andri/.jdks/openjdk-23.0.1/bin/javac.exe'
$cp = 'out/gpu/classes;out/game/classes;out/gpu/lib/*'
& $javac --enable-preview --release 23 -cp $cp -d out/gpu/classes tools/gpu/LightingQualityScene.java tools/gpu/ReconstructionBenchmark.java tools/gpu/FsrQualityBenchmark.java tools/gpu/FsrBenchmark.java tools/gpu/LightingRouteBenchmark.java
& $java --enable-preview --enable-native-access=ALL-UNNAMED '-Djava.awt.headless=true' -cp $cp gpu.FsrQualityBenchmark out/fsr/quality-final
& $java --enable-preview --enable-native-access=ALL-UNNAMED '-Djava.awt.headless=true' -cp $cp gpu.FsrBenchmark out/fsr/frozen-final
& $java --enable-preview --enable-native-access=ALL-UNNAMED '-Djava.awt.headless=true' -cp $cp game.LightingRouteBenchmark out/fsr/route-final roulette-nrd540,roulette-nrdsplit540,roulette-fsr540,roulette-fsr720 swing-offscreen three-rounds
./verify.ps1
./game-check.ps1
./gpu-check.ps1
```

Automated runs use the [console-free launcher](../frame-pipeline/run.py), with each executable and argument passed separately, for example `python benchmarks/s1/frame-pipeline/run.py out/fsr/verify.log pwsh -NoProfile -File verify.ps1`. Run performance measurements without overlapping builds or tests. The stored metadata identifies source hashes, settings and exclusions. The next optimization should target the measured remaining trace and delivery costs; multiplying independent speedup estimates would not establish the 120 FPS goal.
