# S1.1e — Owned GPU display arrays

October 28, 2026. For players testing the sample game and maintainers reproducing performance.
Implemented and automatically verified; hands-on acceptance of this increment is pending.

## Result and player check

Normal GPU launch now avoids copying a complete packed readback image into another pixel
array before Swing can paint it. `GameRenderer` wraps the transferred array as a BufferedImage.
Resolution, image bytes, exposure and path sampling are unchanged. GPU readback and Swing
painting still happen. One owned pixel array per fresh image is still allocated; this is not
an allocation-free display path.

Relaunch normally with `./game.ps1 -Backend gpu -Scene world -Width 1920 -Height 1080`.
Keep the 120 FPS movement budget or compare Fixed at 1080p, with Full atmosphere in the ruins.
Check movement, colors, exposure edits and resolution recovery. Save F8/F9 captures and verify
that old images remain intact while newer frames render. No new setting is required.

The player has already reported substantially smoother movement with S1.3a's 120 FPS budget
and substantially slower native presentation. Those observations do not constitute acceptance
of this new ownership change. Swing remains the default; native presentation remains opt-in.

## Matched measurements

RTX 3080 12GB, Windows, Java 23. Real ruins combat, Full atmosphere, no local fog, fixed 09:00,
depth 8, one sample per combat frame, requested 1920x1080. Each run warms two seconds and
measures the same three-second elapsed-time camera route. Real GamePanel painting targets a
3840x2160 offscreen image. Three rounds alternate the order of the old copy and new ownership
paths. Each GPU owner retires before the next starts. Measurements ran separately from gates.

| Fixed 1080p | Round 1 | Round 2 | Round 3 |
| --- | ---: | ---: | ---: |
| Copy: fresh paints/s | 54.3 | 61.0 | 61.0 |
| Transfer: fresh paints/s | 67.7 | 66.0 | 66.0 |
| Copy: median handoff ms | 2.362 | 2.157 | 2.079 |
| Transfer: median handoff ms | 0.025 | 0.019 | 0.010 |
| Copy: p95 fresh interval ms | 24.4 | 26.4 | 24.9 |
| Transfer: p95 fresh interval ms | 20.2 | 25.4 | 22.9 |

The median rate across runs rises about 8% (61 to 66). The first copy run was an outlier;
do not advertise its 25% paired gain as typical. An earlier independent repeat also improved
all three fixed-grid pairs but had lower absolute rates. Host variation prevents comparing
absolute rates across sessions. A minimum 60 FPS remains unproven; p95 intervals exceed
16.67 ms, and these runs exclude physical input, native window submission and monitor scanout.

With the 120 FPS budget, copy/transfer rates were 87.0/113.0, 115.0/120.7 and 107.3/101.0.
Corresponding frame-weighted mean internal widths were 933/1156, 1018/1096 and 997/928.
Two pairs gained throughput and detail, one lost both; there is no universal adaptive gain.
The controller can spend reduced handoff cost on a larger grid rather than a higher rate.
Mean width is weighted by fresh paintings, not wall time. It is not a quality score.

See `fixed.csv`, `adaptive.csv` and the `earlier-*.csv` files for all percentiles, maximum
intervals, grid ranges, tracing costs and conversion/handoff costs. `medianCopyMs` measures
GameRenderer's image handoff, including array allocation/copy or wrapping. It excludes GPU
readback, which remains inside tracing. Current source hashes accompany the final runs.

## Ownership and checks

`RenderImage.takeDisplayPixels(exposure)` optionally transfers an independent top-down
0x00RRGGBB array. `GpuRenderSession` grants this only with a matching cached exposure and
one lease. The session lock protects only the reference transfer. Multiple leases fall back
to copying, so a simultaneous reader cannot be accessing an array as ownership changes.
The publication slot loses its reference and allocates another array when it is reused.
Later leases retain independent raw snapshots. The transferred array stays valid after
resizing, restarting, closing the lease and closing the session.

CPU rendering, exposure mismatch, multiple leases and device-resident presentation use the
existing fallback. Ordinary snapshots and recording continue to own their displayed images.
The change neither lends mutable publication storage nor removes raw image access.

`./gpu-build.ps1`, `./gpu-check.ps1 -NoBuild`, `./verify.ps1`, and `./game-check.ps1` pass.
The new `gpu.DisplayOwnershipCheck` verifies exact bytes, single-transfer behavior, refusal
with multiple leases, caller mutation isolation, 20 resizes, exposure mismatch, restart and
retained images after close. Existing GPU checks exercise display conversion, packed and
resident captures, adaptive grids, fog, atmosphere and hidden native windows. Gates and logs
are included here. No visible game window or physical input was used for this work.

To reproduce from the repository root after building, in PowerShell:

```powershell
$jdk = 'C:/Users/andri/.jdks/openjdk-23.0.1/bin'
$cp = 'out/gpu/classes;out/game/classes;out/gpu/lib/*'
& "$jdk/javac.exe" --enable-preview --release 23 -cp $cp -d out/gpu/classes tools/gpu/PresentationBenchmark.java
& "$jdk/java.exe" --enable-preview '-Djava.awt.headless=true' -cp $cp game.PresentationBenchmark ownership fixed
& "$jdk/java.exe" --enable-preview '-Djava.awt.headless=true' -cp $cp game.PresentationBenchmark ownership
```

The benchmark sets `game.copyDisplayPixels=true` for the old path and false for the new one.
Normal launches use ownership transfer. Recorded processes used a console-free launcher.

## Rejected spatial reconstruction experiment

A current-frame GPU bicubic prototype enlarged smaller images to the requested 1080p grid,
clamping interpolation overshoot before tone mapping. Full-size output increased conversion,
readback and presentation costs. Its first three 120-budget runs fell to 52.7–77.7 paints/s,
versus 115.7–121.7 without it. A cheaper conversion variant reached 81 versus 116 in one pair.
Adding the ownership change reached 93.7 versus 118.7 in another pair, with mean internal
widths 896 versus 919. These are exploratory observations, not statistically established
quality comparisons. No image-quality benefit was validated, and no minimum FPS was met.

The experiment was removed from production code. The `rejected-spatial-*.csv` files record
the decision evidence; the reconstruction prototype is not a supported reproducible mode
of the final build. No spatial-upscaling control or shader change is shipped here. Temporal
reconstruction also remains unimplemented. Future reconstruction work must measure the whole
output path and prove a useful detail/smoothness tradeoff before it becomes a player feature.
