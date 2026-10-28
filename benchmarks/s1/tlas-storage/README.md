# GPU top-level build storage reuse

S1.4c reduces scene preparation during combat by retaining the top-level acceleration
structure (TLAS), instance-input buffer and build workspace. The TLAS narrows a ray's
search to scene objects. Vulkan still performs a full BUILD after motion, so this change
preserves hierarchy construction rather than refitting old bounds. Object-count changes
allocate replacements; new geometry and material edits retain their existing handling.

The optimization is enabled by default in `GpuScene` for both GPU display paths. Run:

```powershell
./game.ps1 -Backend gpu -Scene world -Width 1920 -Height 1080
```

Use Full atmosphere and repeat the ruins route while turning, aiming and firing. Check
moving enemies, weapon alignment, glass, shadows, settings, focus switching and shutdown.
CPU rendering is unchanged. Visible-window acceptance is pending. The previous opt-in native
presenter remains opt-in; this change does not promote it to the default.

## Reproduction

Run from the repository root with Java 23 and pinned GPU dependencies. Run each benchmark
separately, after other verification has finished; the tools open no windows.

```powershell
./gpu-build.ps1
./gpu-check.ps1 -NoBuild
./verify.ps1
$jdk = "$env:USERPROFILE/.jdks/openjdk-23.0.1/bin"
$cp = 'out/gpu/classes;out/game/classes;out/gpu/lib/*'
& "$jdk/javac.exe" --enable-preview --release 23 -cp $cp -d out/gpu/classes tools/gpu/TlasPreparationBenchmark.java tools/gpu/RuinsAtmosphereBenchmark.java tools/gpu/PresentationBenchmark.java
& "$jdk/java.exe" --enable-preview '-Djava.awt.headless=true' -cp $cp gpu.TlasPreparationBenchmark
& "$jdk/java.exe" --enable-preview '-Djava.awt.headless=true' -cp $cp game.RuinsAtmosphereBenchmark tlas
& "$jdk/java.exe" --enable-preview '-Djava.awt.headless=true' -cp $cp game.PresentationBenchmark tlas
& "$jdk/java.exe" --enable-preview '-Djava.awt.headless=true' -cp $cp game.PresentationBenchmark tlas static
```

Each tool alternates three runs per mode. The control sets `game.rebuildGpuTlas=true` before
creating each renderer to allocate fresh top-level storage on every motion, matching the
previous policy. The optimized mode retains storage. This JVM property is a benchmark
control, not a graphics-quality setting. Both modes use the same current shaders, barriers,
combat scheduling and display conversion.

The preparation-only tool uses the 416-instance AdventureWorld, with one object's transform
changing. It warms 20 updates and measures 100 updates. It excludes tracing and display,
but includes instance-record writes, allocation when applicable, hierarchy construction and
waiting for completion. The route tools use an RTX 3080 12GB, 1920x1080 internal resolution,
depth 8, one sample per batch, Full atmosphere, no local fog and fixed 09:00 lighting. Each
renderer warms for two seconds and follows the same three-second ruins camera path. Combat
is enabled except for the `static` comparison. Previous GPU owners retire between runs.
The presentation tool paints the real GamePanel into a 3840x2160 offscreen image.

## Results

| Measurement | Allocate each update | Reuse storage |
| --- | --- | --- |
| Preparation median, 416 instances | 0.64–0.99 ms | 0.19–0.22 ms |
| Preparation p95 | 0.78–1.25 ms | 0.29–0.38 ms |
| Completed combat images/s | 59.7–63.7 | 64.3–67.3 |
| Fresh combat paints/s | 58.0–58.7 | 59.7–63.0 |
| Combat paint p95 interval | 23.3–24.9 ms | 20.2–23.5 ms |
| Combat paint intervals over 16.67 ms | 54–62% | 40–53% |

Median fresh-paint rate across the three runs improves by about 6%. An earlier independent
paint comparison also improved, from 56.0–59.3 to 61.7–62.3 paints/s. The preparation test
retains 74,240 additional bytes (72.5 KiB); after the initial allocation, its 120 motions reuse
the same structure rather than creating 120 replacements. Memory remains bounded.

The gains do not establish a minimum 60 FPS or 120+ FPS. Many frame intervals still miss
16.67 ms, and these measurements exclude native window submission, monitor scanout and
physical input delivery. Raw timings are in `preparation.csv`, `completed-images.csv`,
`fresh-paints.csv`, `fresh-paints-earlier.csv` and `ordinary-exploration.csv`. Source hashes
and the passing GPU/completion logs accompany them. Timings are milliseconds unless named
otherwise. The discarded preliminary completed-image run overlapped a style check and is
not used as evidence.

The ordinary camera-only route does not rebuild the TLAS after startup. Its repeated
comparison measured 98.0–108.7 paints/s for allocation mode and 108.0–109.3 for reuse,
with no median regression. The first ordinary comparison was noisier: 98.7–108.0 versus
93.3–108.7. Both are retained as `ordinary-exploration.csv` and
`ordinary-exploration-repeat.csv`; they do not establish a camera-only speedup.

## Verification and remaining work

`TlasReuseCheck`, included in `gpu-check.ps1`, compares exact raw RGB arrays for 36 seeded
frames between fresh allocation and reuse. It covers translation, rotation, scale, materials,
instance reordering and removal/addition. It asserts three allocations and 33 same-count
reuses, stable structure identity for same-count motion, and a stable live-memory plateau.
The broader GPU suite checks lighting, glass, images, captures and lifecycle behavior.
`verify.ps1` runs the readability and selected scene/engine checks.

The next larger planned option is a selectable GPU frame-budget mode (S1.3), which can
reduce internal resolution during expensive movement. Further hierarchy refitting and
presentation profiling remain candidates; their benefits must be measured separately.
Requirements and human acceptance remain in [PerformanceRequirements.md](../../../PerformanceRequirements.md).

The implementation follows Vulkan's [acceleration-structure build and synchronization
contract](https://docs.vulkan.org/refpages/latest/refpages/source/vkCmdBuildAccelerationStructuresKHR.html).
Only same-count top-level builds reuse queried storage and scratch sizing. Builds keep the
same type and flags, overwrite only after previous owner submissions finish, and include
explicit build/traversal dependencies. This is BUILD-mode storage reuse, not UPDATE mode.
