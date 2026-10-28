# Direct GPU presentation evaluation

The sample game's opt-in Windows Vulkan presenter removes routine scene-image readback,
but the hidden-window benchmark did not demonstrate a speedup. Keep the existing Swing
presenter as the default. The authoritative requirements and human acceptance steps are in
[PerformanceRequirements.md](../../../PerformanceRequirements.md), S1.2 implementation evaluation.

## Try it

From the repository root, use PowerShell:

```powershell
./game.ps1 -Backend gpu -Scene world -Width 1920 -Height 1080 -NativePresentation
```

Omit `-NativePresentation` for the existing GPU-to-Swing path. CPU launch remains
`./game.ps1 -Backend cpu`. Native presentation requires Windows Vulkan surface/swapchain
maintenance support; failures are reported rather than switching rendering backends.
This experiment is not enabled by default and does not establish the 60/120 FPS targets.

## Reproduce verification and measurements

Use Java 23 with preview features and the repository's pinned LWJGL 3.3.6 dependencies.
`setup-gpu.ps1` now also pins the JAWT jar. `gpu-build.ps1` compiles `present.comp`.

```powershell
./gpu-check.ps1
./game-check.ps1
./verify.ps1
$jdk = "$env:USERPROFILE/.jdks/openjdk-23.0.1/bin"
$cp = 'out/gpu/classes;out/game/classes;out/gpu/lib/*'
& "$jdk/javac.exe" --enable-preview --release 23 -cp $cp -d out/gpu/classes tools/gpu/NativePresentationBenchmark.java
& "$jdk/java.exe" --enable-preview '-Djava.awt.headless=false' '-Dsun.java2d.uiScale=1' -cp $cp game.NativePresentationBenchmark
```

Run the benchmark alone, after verification finishes. It never shows its AWT window.
It alternates three runs of each path on the RTX 3080 12GB, at 1920x1080 internal
resolution, a 3840x2160 destination, depth 8, Full atmosphere and combat enabled in the
AdventureWorld ruins. Each renderer warms for two seconds, then follows the same
three-second camera route. Previous GPU owners retire before the next run.

The Swing side paints the real GamePanel to an offscreen image. The native side draws
the same HUD patches and submits through the real native presenter to an invisible
Windows swapchain. It bypasses the panel's visibility guard solely to exercise that hidden
surface. No actual monitor scanout is measured. Windows may schedule hidden surfaces
differently from visible ones, so these endpoints cannot prove a live-window regression or
improvement. They are sufficient reason to withhold default promotion until visible testing
and further profiling. Raw results are in `comparison.csv`; timings are milliseconds.

The comparison found approximately 50–53 fresh native submissions/s versus 57 fresh Swing
paints/s. Native p95 intervals were approximately 23–27 ms. The initial native implementation
repeated encoding for every 4K pixel and reached only about 45/s; integer enlargement now
encodes each source pixel once, and UI patches are copied to device-local storage before
composition. These changes improved that experiment but did not overtake Swing.

## What is verified and what remains

`DirectPresentationCheck` checks actual GPU image ownership across repeated slot reuse,
a stable allocation plateau, zero routine image-download bytes, explicit exports, retained
reads after shutdown, orientation, equal/integer grid encoding, linear filtering, letterboxing,
4K output, alpha composition and hidden native surface resize/retirement. `GameOverlayCheck`
compares patch packing against complete UI images at 100%, 125% and 200% display scale.
`CompactDisplayCheck resident-captures` writes and reloads snapshot/history bundles.
The game and completion gates cover existing CPU/Swing behavior. Gate logs accompany this file.

Human acceptance is still pending: visible settings over the heavyweight Canvas, cursor and
keyboard focus, aiming/firing, Alt-Tab, minimize/restore, high-DPI monitor movement, F8/F9,
resolution/exposure changes and closing during rendering. Snapshot bundles preserve scene
images and game state; they are not full-window screenshots of the HUD or settings.
The default-path next candidate is reusing/updating the GPU top-level acceleration structure
when combat objects move. Native presentation still needs scheduling/cost profiling.

## Synchronization references

The implementation uses one presentation wait semaphore per swapchain image and present
fences for safe retirement. A completed present fence permits resource retirement; it does
not mean the monitor has scanned out the frame. See the official
[Vulkan semaphore reuse guidance](https://docs.vulkan.org/guide/latest/swapchain_semaphore_reuse.html)
and [present fence contract](https://docs.vulkan.org/refpages/latest/refpages/source/VkSwapchainPresentFenceInfoKHR.html).
