# S1.3a — GPU movement frame budgets

Implemented October 28, 2026. Audience: players trying the sample game and maintainers
reproducing its performance. Automated checks passed; native-window human acceptance
and temporal reconstruction remain pending.

## What to try

Launch `./game.ps1 -Backend gpu -Scene world -Width 1920 -Height 1080`.
In Escape → Render, set **Movement frame budget** to **60 FPS** or **120 FPS**.
Move and aim through the ruins with Full atmosphere enabled, then stop. The renderer
lowers internal detail during camera movement and restores 1920x1080 after 350 ms quiet
and the next completed job. Compare against **Fixed resolution**, the unchanged GPU default.
These budgets are targets, not FPS caps or guarantees. Advanced exposes custom target
milliseconds and minimum dimensions; **Custom budget** reflects those values.

The overlay distinguishes requested and displayed internal dimensions. Presentation scales
the current frame to the window. Camera framing, optics, path depth and atmosphere quality
settings remain unchanged. Moving batches are capped at one sample. CPU retains its existing
adaptive policy; the new selector also provides shortcuts to that policy.

Check weapon silhouettes, small enemies, textures and fog while moving, especially during
resolution changes. Detail loss is expected; trails or stale-camera images are not. Check
Fixed during movement, pause/resume, requested resolution changes, and stationary refinement.
No visible window or physical input was exercised by this performance experiment.

## Measured result

RTX 3080 12GB, Windows, Java 23. Three alternating rounds of Fixed/60/120 modes; odd rounds
reverse the order. Every run uses the same elapsed-time ruins camera route, combat enabled,
Full atmosphere, no local fog, fixed 09:00 lighting, depth 8 and one sample per combat frame.
Requested grid is 1920x1080; Swing GamePanel paints into a 3840x2160 offscreen image.
Each run warms for two seconds, then measures three seconds of motion including the initial
full-resolution frame and adaptation. The previous GPU owner retires before the next starts.
Verification ran separately from measurements.

| Mode | Fresh paints/s, three runs | p95 interval (ms), three runs | Frame-weighted mean internal width |
| --- | --- | --- | --- |
| Fixed | 58.0 / 60.3 / 62.3 | 23.2 / 24.2 / 20.0 | 1920 / 1920 / 1920 |
| 60 FPS budget | 79.0 / 91.0 / 85.3 | 21.2 / 17.7 / 20.2 | 1657 / 1479 / 1581 |
| 120 FPS budget | 124.7 / 125.7 / 112.0 | 11.0 / 10.4 / 12.2 | 1016 / 1055 / 1031 |

See [fresh-paints.csv](fresh-paints.csv) for every interval percentile, maximum and actual-grid
range. The 120 mode reached widths as low as 720 (405 high); its maximum includes 1920
before reduction. Default bounds permit 480x270 for this requested grid. Width is averaged
per fresh paint, not over wall time. The 60 mode sometimes renders faster than its target
because the controller chooses discrete grids and reserves budget for presentation.

At the 120 target, p95 intervals are below the 16.67 ms threshold for 60 FPS, but still above
8.33 ms for 120. Maximum intervals of 21.6–33.8 ms remain. These results do not establish a
minimum 60 or 120 FPS, nor native-window smoothness. Fewer pixels account for the gain;
image detail is reduced. Actual input, native submission and monitor scanout are excluded.

## Behavior and ownership

`src/gpu/GpuResolutionPolicy.java` selects grids from recent capture-to-consumer-copy cost.
It preserves requested aspect ratio with pixel rounding, reserves 20% for external presentation,
and uses a 500 ms cooldown plus three fresh inexpensive images before increasing detail.
Custom minima are honored at the next complete-frame boundary and capped by requested size.
Only camera changes trigger movement; independent animation while the camera is still does not.

`GpuRenderSession` captures the actual grid in its accumulation key and publication. It does
not mix samples across grids or change the requested view. GPU work and resizing stay on the
single owner thread. The bounded three-slot lease protocol still protects retained images.
Pause stops submissions; suspension, adaptive toggles and requested-grid edits reset policy.
Game settings expose both budget presets and the existing numeric controls.

This delivery uses existing spatial image scaling. It adds no temporal history, denoising or
learned upscaling. Temporal reconstruction remains a separate S1.3 increment; this mode works
with default Swing presentation and the existing opt-in resident GPU presentation.

## Verification and reproduction

`./gpu-build.ps1`, `./gpu-check.ps1 -NoBuild`, `./verify.ps1`, and `./game-check.ps1` pass.
`GpuAdaptiveCheck` checks synthetic timing/hysteresis and minimum bounds, real movement
publication, stationary recovery even with a one-sample target, pause, disable, resize/resume,
exact seeded RGB for the captured camera/grid, and retained leases across resize/close.
It exercises packed and device-resident publication. GPU UI checks select 60/120/Fixed and
verify that refresh does not mutate settings. Backend conformance accepts GPU adaptation
while continuing to reject unsupported temporal modes. Full GPU checks cover fog, atmosphere,
optics, capture, compositor and hidden-window presentation regressions.

From the repository root in PowerShell, after building:

```powershell
$jdk = 'C:/Users/andri/.jdks/openjdk-23.0.1/bin'
$cp = 'out/gpu/classes;out/game/classes;out/gpu/lib/*'
& "$jdk/javac.exe" --enable-preview --release 23 -cp $cp -d out/gpu/classes tools/gpu/PresentationBenchmark.java
& "$jdk/java.exe" --enable-preview '-Djava.awt.headless=true' -cp $cp game.PresentationBenchmark budget
```

Repository scripts use hidden child processes; the recorded run also used a console-free
Python launcher. Raw check logs and source SHA-256 hashes accompany the CSV. No timing run
was combined with profiling or verification. Human quality acceptance is the next checkpoint;
120 FPS mode is available to try without enabling experimental native presentation.
