# GPU frame delivery audit

The sample game's renderer now combines ordinary moving-scene acceleration builds and tracing in one GPU submission. This removes a CPU fence wait without changing rendering quality. The measured gameplay improvement is small: half-resolution throughput is effectively unchanged, while native 1080p visibility improves by about 1–3% in three paired runs. The target of 120 real rendered FPS at 1080p remains unmet.

Swing remains the default presentation path. A matched hidden-window comparison found the existing native GPU presenter slower, despite avoiding image readback. This audit does not establish visible-window smoothness or monitor scanout performance.

## What changed

`src/gpu/GpuScene.java` retains the existing top-level acceleration structure (TLAS), which identifies potentially intersected scene objects. When moving objects keep the same count, it records a complete hierarchy rebuild into the command buffer that `GpuKernel` uses for tracing. Explicit acceleration-structure barriers protect the retained storage and make the completed build visible to compute-shader ray queries. The synchronization follows the [Khronos ray-tracing synchronization guidance](https://docs.vulkan.org/guide/latest/extensions/ray_tracing.html).

This remains a full BUILD, rather than a refit of old bounds. Initial geometry builds, new geometry and object-count changes retain their synchronous allocation/build path. Material uploads retain their existing handling. GPU resource ownership, the three publication slots, resolution, samples, denoisers and path depth are unchanged. The GPU trace timer starts after the retained scene build; total host job time includes that build in both variants. Preparation time alone is therefore not a measure of saved work.

`-Dgame.separateGpuSceneSubmission=true` restores the separate build submission for diagnosis. It is a Java virtual machine (JVM) comparison switch, not a saved quality option. The default uses batching. `VulkanDevice` also counts completed submissions and their total submit-and-wait duration for the frozen replay benchmark.

The native audit exposed a startup race: Java AWT Native Interface (JAWT) could initialize LWJGL's native-memory stack before `VulkanDevice` configured its size. `GpuGameSurface` now configures the same 512 KiB stack before initializing JAWT. A hidden-window regression initializes the surface before the first Vulkan device, without a stack-size JVM override.

## Gameplay measurements

Measurements ran on October 30, 2026, on an RTX 3080 12 GB, driver 616.92, Java 23 and Windows. Apex was closed. The final paired runs did not overlap builds or test suites. GPU clocks were not locked.

`LightingRouteBenchmark` follows a twelve-second elapsed-time AdventureWorld combat route after two seconds of warmup. It includes movement, turns, firing and a scoped segment at seconds 8–10. Each mode has three separate/batched pairs; the order reverses in the middle pair. Both variants use completion-driven rendering and painting. Fresh images count after `GamePanel` paints to an offscreen 1920×1080 image, including the interface. There is no frame generation. Physical input, native window presentation and monitor scanout are excluded.

Settings use the RELAX filter from NVIDIA Real-Time Denoisers (NRD), path roulette enabled, depth 8, one fresh sample, pinhole optics, uniform sampling, Full atmosphere quality, bloom off, local fog off, fixed resolution and a frozen 09:00 sun. The unchanged sunlight simulation can still be enabled in the game; these comparisons hold lighting fixed.

| Rendering configuration | Separate submissions, FPS in rounds 0 / 1 / 2 | Batched submission, FPS in rounds 0 / 1 / 2 |
|---|---:|---:|
| 960×540 image enlarged to 1080p | 114.3 / 125.5 / 122.3 | 117.1 / 122.5 / 122.6 |
| 960×540 lighting with native 1920×1080 visibility | 70.6 / 69.2 / 71.2 | 71.3 / 71.1 / 72.3 |

The first mode has no consistent throughput gain: one pair improves, one regresses and one barely changes. Native visibility improves in all three pairs, by 0.9%, 2.8% and 1.5%. Its pooled median render-job host time falls from 11.294 ms to 10.954 ms, while GPU trace time stays near 7.4 ms. These short runs support a modest improvement, not a large FPS multiplier.

With batching, p95 fresh-image intervals remain 10.0–10.9 ms at half resolution and 20.1–21.0 ms with native visibility. P95 is the 95th percentile: 95% of measured intervals are at or below that duration. About 35–48% and essentially 100% of intervals, respectively, exceed the 8.33 ms target. P95 capture-to-paint age is 12.1–13.2 ms and 22.1–23.4 ms. Roughly 120 average FPS at half resolution does not mean consistently meeting the target or preserving native visibility detail.

[Round summaries](paired-route.log), [separate-submission frames and settings](paired-route/separate/) and [batched frames and settings](paired-route/batched/) retain per-frame evidence. Each `-profile.csv` correlates the painted publication with its GPU job. Timings overlap and must not be added together as independent costs.

## Isolated replay and presentation findings

`FramePipelineBenchmark` replays twelve frozen combat states, with 36 warmup frames and 180 measured frames per variant. It runs three alternating pairs for each combination of half/native visibility and CPU-packed/GPU-resident delivery. Ordinary moving frames drop from two submissions to one. Preparation medians fall by roughly 0.25–0.4 ms in most variants, but the hierarchy build now runs in the remaining submission. The median of the three half-resolution packed-job medians falls only from 6.297 ms to 6.215 ms. Native-visibility packed-job medians are effectively unchanged, 10.847 ms versus 10.870 ms. [All replay rows](frozen/) retain the variability and both resident variants. This replay excludes simulation, image pickup, painting and native composition.

The matched native experiment uses the same twelve-second gameplay route and rendering settings, two rounds with reversed mode order, and a hidden 1920×1080 swapchain. Both endpoints use completion callbacks; unlike the older `NativePresentationBenchmark`, Swing is not restricted to timer-only painting. Native FPS counts successful software presentation submissions, which are not physical scanout events.

| Batched configuration | Swing offscreen, three final rounds | Native hidden, two rounds |
|---|---:|---:|
| 960×540 image | 117.1–122.6 FPS | 83.3–91.4 FPS |
| Native 1080p visibility, half lighting | 71.1–72.3 FPS | 65.3–65.8 FPS |

These endpoint runs are separate comparisons, not alternating endpoint pairs. The native route saves the normal CPU image download, but `GpuDevicePixels` makes an extra immutable GPU image copy with its own submission/wait. `GpuGameSurface` then schedules composition and presentation on the same GPU owner used by tracing. The native run's median image-copy stage is approximately 2.4–2.7 ms, including waiting for that owner. These costs explain why removing readback alone is insufficient; their individual contribution to the FPS difference was not isolated. [Native summaries](native-hidden.log) and [correlated native timings](native-hidden/) retain the evidence.

The native timing run used `-Dorg.lwjgl.system.stackSize=512` to bypass the startup race discovered during this audit. The startup fix is verified separately without that override. No visible windows were opened by these automated runs. Visible-window presentation, focus, resizing and monitor pacing still require human evaluation before changing the default endpoint.

Image pickup is already prompt in the final Swing runs, with pooled medians around 0.3 ms. There is no evidence here for restoring the older diagnosis of a 14 ms pickup delay. Native visibility and scope rendering still consume substantial GPU time. Temporal upscaling is the next proposed major increment: reconstruct output detail over time from lower-resolution samples, with motion/depth validation, rather than merely enlarging the current low-resolution image. It needs its own quality and timing comparison. A native ownership/presentation redesign remains separate work.

## Verification and reproduction

`SceneSubmissionCheck` compares 36 frames with separate and batched submissions, using both built-in reconstruction and NRD with split visibility. Raw RGB and packed output must match exactly through motion, material edits, reordered objects, removal and addition. It verifies one fewer submission for each retained build and a stable memory plateau. `TlasReuseCheck` separately compares first-trace raw output against fresh hierarchy allocation. The GPU gate also covers cancellation, leases, resizing, shutdown, native composition and the new surface-first startup order. See [GPU gate](gpu-check.log), [game gate](game-check.log) and [completion gate](verify.log).

From the repository root, build and compile the benchmark tools:

```powershell
./gpu-build.ps1
$java = 'C:/Users/andri/.jdks/openjdk-23.0.1/bin/java.exe'
$javac = 'C:/Users/andri/.jdks/openjdk-23.0.1/bin/javac.exe'
$cp = 'out/gpu/classes;out/game/classes;out/gpu/lib/*'
& $javac --enable-preview --release 23 -cp $cp -d out/gpu/classes tools/gpu/LightingQualityScene.java tools/gpu/FramePipelineBenchmark.java tools/gpu/LightingRouteBenchmark.java
& $java --enable-preview --enable-native-access=ALL-UNNAMED '-Djava.awt.headless=true' -cp $cp game.LightingRouteBenchmark out/pipeline/paired-route roulette-nrd540,roulette-nrdsplit540 swing-offscreen compare-submissions
& $java --enable-preview --enable-native-access=ALL-UNNAMED '-Djava.awt.headless=true' -cp $cp gpu.FramePipelineBenchmark out/pipeline/frozen
& $java --enable-preview --enable-native-access=ALL-UNNAMED '-Djava.awt.headless=false' -cp $cp game.LightingRouteBenchmark out/pipeline/native-hidden roulette-nrd540,roulette-nrdsplit540 native-hidden
./verify.ps1
./game-check.ps1
./gpu-check.ps1
```

For automated execution, launch these commands through a console-free process with console-free children, as required by `AGENTS.md`. The saved [Windows launcher](run.py) accepts a repository-relative log path followed by the executable and its arguments, for example `python benchmarks/s1/frame-pipeline/run.py out/pipeline/verify.log pwsh -NoProfile -File verify.ps1`. Run benchmarks separately from builds and tests. [Metadata](metadata.json) records source hashes, commands and exclusions.

For a human check, launch `./game.ps1 -Backend gpu -Scene world -Width 1920 -Height 1080`. No new switch is required for batching. Repeat movement, aiming, firing and enemy removal; check weapon alignment, shadows, scope glass, stationary refinement, resolution changes and closing during rendering. The modest throughput gain does not replace these visual checks.
