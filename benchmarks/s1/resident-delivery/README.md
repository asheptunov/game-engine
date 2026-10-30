# Retained GPU images and controlled frame-delivery measurements

The sample game's optional native presenter now retains the renderer's existing GPU image instead of copying it into another buffer. This removes one GPU submission and fence wait for each retained display image. The default Swing presenter is unchanged. The 120 real FPS target at 1920×1080 remains unmet with the Performance mode of AMD FidelityFX Super Resolution (FSR), the optional temporal upscaler.

This investigation also identifies a benchmark scheduling confound behind the large apparent slowdown in the preceding FSR report: Windows can ignore fine timer requests in invisible processes. Controlling that policy recovers much of the earlier throughput without changing the renderer. That recovery is a measurement correction, not a rendering optimization.

## Resolving the historical comparison

An isolated checkout of pre-FSR commit `8307b6c` and the FSR baseline `63353be` ran the same 960×540 enlargement control in an old/new/new/old sequence. Each invocation measured two twelve-second combat routes. The old build also ran slowly: 88.2–93.0 fresh FPS versus 78.6–89.8 for the new build. Both had roughly 13–14 ms p95 delays between GPU publication and game-owner pickup. These results do not support attributing the entire earlier 117–123 to 81–88 FPS drop to FSR. They do not exclude smaller code-dependent differences. [Matched logs, frame rows and settings](matched/) preserve the comparison.

A separate Java Flight Recorder diagnostic found short timed parks extending to approximately 15 ms; [selected game-owner events](park-diagnostic.json) retain requested and actual wait durations. Microsoft documents that [Windows 11 may ignore timer-resolution requests for invisible or occluded processes](https://learn.microsoft.com/en-us/windows/win32/api/timeapi/nf-timeapi-timebeginperiod). [OpenJDK 23 already requests fine timers around short waits](https://github.com/openjdk/jdk/blob/jdk-23%2B37/src/hotspot/os/windows/os_windows.cpp). The new `tools/gpu/DeliveryRouteBenchmark.java` wrapper uses [SetProcessInformation](https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-setprocessinformation) to honor existing requests in its own process and restores the original policy afterward. It makes no additional timer-resolution request and changes no game setting.

An extra `timeBeginPeriod(1)` request alone left half enlargement near 90 FPS. Honoring the process's requests raised it to 114–116 FPS. A subsequent paired experiment with that policy found no consistent benefit from an additional application request: half enlargement ran 114–122 FPS without it and 116–124 with it; FSR ran 75–84 and 79–83 respectively. The supplemental application timer code was discarded. The experiment's `coarse` directory means **extra request off**, not proof that the JVM used a coarse timer; `precise` means **extra request on**. See [timer-on](timer-on.log), [honored-policy](timer-honored.log) and [paired experiment](paired-timers.log).

The evidence explains why unqualified cross-run FPS comparisons were unreliable. It does not establish which desktop or power-policy change affected the original historical runs. Production simulation timing, sunlight timing and scheduling remain unchanged.

## GPU image ownership

`GpuDevicePixels` shares an immutable `GpuKernel.Snapshot` with its publication. On the GPU owner thread, `GpuRenderSession` tracks retained display readers separately from the three publication-slot leases. Before writing into a reused slot, it replaces any buffer still held by a display or capture. The previous image stays valid until its last reader releases it. Up to three detached unused buffers are recycled; additional retained images require storage for their lifetimes. Shutdown materializes retained images before destroying the device.

This avoids both the display copy and pinning all three publication slots while captures retain images. Raw accumulation, NRD denoising, FSR reconstruction and pixel values are unchanged. `-Dgame.copyDeviceFrames=true` restores the old GPU-copy path for comparisons; it is a diagnostic JVM option, not a saved quality setting. Shared storage is the default whenever GPU-resident display images are requested. The native presenter itself remains opt-in.

## Final gameplay measurements

Final runs used an RTX 3080 12 GB, driver 616.92, Windows 11 and Java 23.0.1. Each route has two seconds of warmup and twelve measured seconds of elapsed-time movement, turning, firing and aiming through the scope. Settings are depth 8, one fresh sample, path roulette, the RELAX filter from NVIDIA Real-Time Denoisers (NRD), pinhole optics, uniform sampling, Full atmosphere, no local fog or bloom, fixed resolution and a frozen 09:00 sun. Both modes output 1920×1080: half enlargement renders a 960×540 image, while FSR Performance reconstructs 1080p from 960×540 input. There is no frame generation.

All final runs explicitly honor the JVM's timer requests. The native comparison alternates copied/shared storage in three pairs and reverses the order in the middle round. The Swing measurements are a separate run, not alternating endpoint pairs. No builds, tests or profiler ran during these final measurements. Other desktop applications remained open and GPU clocks were not locked.

| Hidden native mode | Copied storage, FPS in rounds 0 / 1 / 2 | Shared storage, FPS in rounds 0 / 1 / 2 |
|---|---:|---:|
| Half enlargement | 78.7 / 87.8 / 89.6 | 86.6 / 89.9 / 89.6 |
| FSR Performance | 74.5 / 75.3 / 76.6 | 73.1 / 74.7 / 80.8 |

Half enlargement improves in two pairs and is unchanged in the third. FSR regresses in two pairs and improves in the third, so there is no consistent FSR throughput gain. Median retained-frame handoff time falls in all six pairs: by 0.10–0.37 ms at half resolution and 0.04–0.22 ms with FSR. That stage still takes 2.1–2.4 ms with sharing because obtaining the retained view waits for the same GPU owner that runs tracing and presentation. Removing the copy submission does not remove that scheduling delay.

| Final endpoint and mode | Fresh FPS, rounds 0 / 1 / 2 | P95 image interval across rounds | P95 capture-to-display age |
|---|---:|---:|---:|
| Swing, half enlargement | 102.3 / 118.6 / 126.1 | 10.0–13.9 ms | 12.2–17.7 ms |
| Swing, FSR Performance | 76.6 / 73.7 / 79.0 | 17.1–20.3 ms | 20.4–25.0 ms |
| Native shared, half enlargement | 86.6 / 89.9 / 89.6 | 19.5–22.6 ms | 37.7–39.5 ms |
| Native shared, FSR Performance | 73.1 / 74.7 / 80.8 | 21.0–22.3 ms | 37.5–41.2 ms |

The large half-enlargement spread is retained rather than selecting the fastest round. In Swing, 27–66% of half-resolution image intervals and more than 99% of FSR intervals exceed 8.33 ms. FSR's median render-job host time is 9.8–10.2 ms, while its GPU timer reports 5.5–5.8 ms. Native shared FSR lowers host-job time to 7.1–7.5 ms and avoids normal image readback, but its median time from completed handoff to presentation is still 13.6–15.1 ms. Native scheduling therefore remains a major unresolved cost. Swing stays the default; these results do not justify a default switch or a 120 FPS claim. The next delivery investigation should remove waits for image retention and presentation on the tracing owner, then remeasure end-to-end latency alongside remaining rendering costs.

Fresh FPS counts distinct images after offscreen Swing painting or successful hidden native presentation submission. Neither endpoint measures monitor scanout, visible-window pacing or physical input latency. P95 is the duration that 95% of measured intervals do not exceed; meeting 120 FPS requires intervals near 8.33 ms, not just a favorable average. Timing stages overlap and must not be summed as independent costs. [Summary rows](summary.csv), [native pairs](native-final.log), [Swing rounds](swing-final.log) and [metadata](metadata.json) retain the evidence and exclusions. Table percentiles use recorded fresh-image rows; console interval summaries additionally include a final hold and can differ slightly.

## Verification and human acceptance

`ResidentStorageCheck` compares both storage paths across 24 moving, alternating-size publications. Two retained views add two GPU submissions with copying and zero with sharing. It checks sibling views, five retained images, an independently held raw publication and images surviving shutdown. It invalidates CPU caches before rereading retained images from the GPU so a cached array cannot hide an overwrite. Existing native checks cover no normal-display readback and an allocation plateau; NRD/FSR checks cover reconstruction toggles, resizing and retained-image retirement. The [GPU gate](gpu-check.log), [game gate](game-check.log), [completion gate](verify.log) and [hidden native check](native-check.log) passed. The completion gate covers selected suites, not the entire project.

For a visible-window check, launch `./game.ps1 -Backend gpu -Scene world -Width 1920 -Height 1080 -NativePresentation`. Repeat movement, aiming, firing, resolution changes, rapid reconstruction/upscaler changes, capture/export, focus loss and closing during rendering. Retained captures must remain unchanged while play continues. Compare motion and frame pacing with the default launch without `-NativePresentation`. Native presentation remains opt-in until this human check establishes acceptable behavior.

## Reproduction

Build and compile once, then run timings without overlapping compilation or verification:

```powershell
./gpu-build.ps1
$java = 'C:/Users/andri/.jdks/openjdk-23.0.1/bin/java.exe'
$javac = 'C:/Users/andri/.jdks/openjdk-23.0.1/bin/javac.exe'
$cp = 'out/gpu/classes;out/game/classes;out/gpu/lib/*'
& $javac --enable-preview --release 23 -cp $cp -d out/gpu/classes tools/gpu/LightingRouteBenchmark.java tools/gpu/DeliveryRouteBenchmark.java
python benchmarks/s1/frame-pipeline/run.py out/delivery/native-final.log $java --enable-preview --enable-native-access=ALL-UNNAMED '-Djava.awt.headless=false' -cp $cp game.DeliveryRouteBenchmark honor out/delivery/native-final roulette-nrd540,roulette-fsr540 native-hidden compare-storage
python benchmarks/s1/frame-pipeline/run.py out/delivery/swing-final.log $java --enable-preview --enable-native-access=ALL-UNNAMED '-Djava.awt.headless=true' -cp $cp game.DeliveryRouteBenchmark honor out/delivery/swing-final roulette-nrd540,roulette-fsr540 swing-offscreen three-rounds
./verify.ps1
./game-check.ps1
./gpu-check.ps1
```

The console-free launcher keeps automated runs from opening consoles or taking focus. Use `system` instead of `honor` to leave timer policy unchanged. The wrapper is Windows-specific. Historical timing experiments used the earlier copied-storage implementation; only `native-final` and `swing-final` describe the final code. One exploratory native run that included a diagnostic attach attempt is excluded from the final figures.
