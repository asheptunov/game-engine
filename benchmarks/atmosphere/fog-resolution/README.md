# Lower-resolution primary fog: experiment and visual controls

The sample game's **Fog** settings tab now offers **Full**, **Half**, and **Quarter** under “Fog rendering quality (experimental).” Full remains the default and numerical reference. Half and Quarter reduce only the primary-ray fog grid's width and height; geometry, surface shading, and diffuse continuation rays retain their requested resolution and integration. CPU rendering always retains direct integration.

This is a modest optimization for the captured ruins view, not an order-of-magnitude improvement. At 1600×766 it reduces total headless frame work by approximately 10–13%. At 836×400 the differences are small and sometimes comparable to run-to-run variation. Quarter is not consistently faster than Half: larger cells cause more pixels to fail reconstruction checks and use direct integration.

## Visual testing

1. Run `./game.ps1 -Backend gpu -Scene world` from the repository root to launch the adventure world containing the ruins. Press Esc if the pointer is captured; this exposes the settings panel. Select its Fog tab, click “Ruins mist (07:30)” to enable the test fog, and click “View ruins (moves camera)” to face it. This is a convenient test view, not the exact recorded benchmark camera. To restore that exact camera and scene, launch with `-Capture $benchCapture` instead; the capture path and availability are described below.
2. Compare Full, Half, and Quarter without changing overall render resolution, exposure, or path depth. Examine pillar silhouettes, the narrow sunlight gap, ground shadows, and thin shafts.
3. Walk and turn the camera, close/open the sunlight gap, and enable the day/night cycle. Compare both normal and 30-second days. There is no temporal blending; each pass uses its captured camera, scene, and current sun.
4. Save F8 snapshots or an F9 history when a difference is objectionable. Captures now include the quality setting, and replay restores it. Older captures without that field load as Full.

Half/Quarter currently require a pinhole camera and the same smooth-fog eligibility as the [sunlight cache](../fog-cache/README.md): one or two resolved, feathered, low-frequency regions of limited optical density, with the sun above approximately 2.9 degrees. Lens blur, absent or unsupported fog, and low sunlight use Full automatically while retaining the requested setting. GPU diagnostics distinguish requested/effective divisors, coarse-pass time, and the number of pixels reconstructed in the last sample. That count is not a quality score.

## Measurements

Hardware: NVIDIA GeForce RTX 3080, driver 2585198592, OpenJDK 23.0.1 on Windows. The saved view looks into the ruins at 7 AM with two overlapping mist regions and path depth 2. Each case uses ten warm-up frames and twenty measured one-sample frames, repeated twice with reversed resolution order. Mode sweeps ran Quarter, Half, then Full. Values below are the mean of the two run medians, in milliseconds; CSV files also contain each run's 95th percentile.

Total time covers session update, tracing, all fog passes, readback, publication, and CPU image conversion. It excludes initial device/scene setup and native window presentation, and is not displayed FPS. Each moving-sun update advances a fixed 0.1 seconds of simulated elapsed time. Normal and accelerated days last 600 and 30 seconds respectively. Sampling seed, camera, exposure, path depth, and geometry are held fixed; adaptive/temporal presentation is disabled.

| Resolution | Sun | Full | Half | Quarter |
| --- | --- | ---: | ---: | ---: |
| 836×400 | Stationary | 18.14 | 17.06 | 17.50 |
| 836×400 | 600-second day | 18.71 | 18.29 | 17.93 |
| 836×400 | 30-second day | 17.70 | 16.67 | 17.41 |
| 1600×766 | Stationary | 64.69 | 58.52 | 57.04 |
| 1600×766 | 600-second day | 64.70 | 58.48 | 56.17 |
| 1600×766 | 30-second day | 62.16 | 55.60 | 55.96 |

`before.csv` records the pre-implementation baseline at commit `2c3e026`; its 1600×766 totals were approximately 62–64 ms. `full.csv`, `half.csv`, and `quarter.csv` are matched modes of the final implementation. GPU execution timings include the extra passes. Continued fog integration on bounced rays, conservative edge fallback, and roughly 12–14 ms of large-image CPU conversion limit whole-frame gains. The next substantial optimization should investigate sharing lighting across those continuation rays or reducing image conversion cost.

## Implementation and accuracy

`RenderSettings.fogDownsample` accepts 1, 2, or 4. `GameSettingsPanel` edits it, `GpuRenderSession` treats edits as accumulation-invalidating changes, and `CaptureCodec` supplies the backward-compatible capture default. Full is still the CPU implementation's behavior even if a smaller grid is requested.

`GpuKernel` uses specialized pipelines from `assets/gpu/diffuse.comp`. The coarse pass stores integrated light, transmission, and surface guides. Full-resolution path shading records primary-hit guides and computes the remaining path lighting. A separate pass reconstructs and composites primary fog before progressive accumulation. Keeping reconstruction out of the path shader removed a regression in the first prototype.

Reconstruction rejects mismatched objects, surface normals/planes, depth jumps, incomplete neighboring coverage, and large differences in neighboring fog radiance. Rejected pixels use direct integration. Solar-disc rays stay direct. The grid is rebuilt for every sample, so camera motion, sunlight changes, and geometry edits cannot reuse stale fog. These conservative checks reduce artifacts but do not prove an error bound for every unseen scene. Thin unresolved lighting features and small spatial changes remain reasons for visual testing.

The extra active buffers at 1600×766 occupy about 51 MiB in Half and 41 MiB in Quarter, including full-resolution guides; returning to Full releases them. Existing Vulkan ownership, barriers, and image leases remain in effect.

`tools/gpu/FogResolutionCheck.java` compares Full against both modes at depths 0 and 2, multiple sun positions, camera movement, odd grid dimensions, and open/closed sunlight gaps. For every procedural comparison in that test, both requested divisors must satisfy whole-image linear RGB root-mean-square error at most 0.004, and every pixel channel must satisfy `abs(actual - reference) <= 0.015 + 0.05 × abs(reference)`. The reference is Full GPU integration with identical inputs and sample index. These cases use 197×131 pixels and one sample; the optional capture comparison uses 836×400 and 16 samples. The same bounds apply to each captured-image comparison. They retain the project’s existing regression tolerances and are acceptance checks for these fixtures, not a guarantee for arbitrary scenes. It also checks exact Full restoration, direct fallbacks, resource release, and leased images across quality changes. The game tests cover capture round trips and old-file defaults; GPU UI checks exercise the dropdown. Run `./parity-check.ps1` for the complete repository/game/CPU/GPU gate.

The included 836×400 images use 16 samples and exposure 1. Half versus Full has linear RGB RMS error 0.000230; Quarter has 0.000297. Both meet the per-channel bound. Encoded RGB differences on the 0–255 scale have RMS 0.223/0.254, maxima 4/5, and 99th percentile 1 for Half/Quarter respectively. These static comparisons do not replace a native motion playthrough.

## Reproduce

The exact private capture remains at `out/game/captures/2026-10-26T14-08-21.947078900Z-e2ee8470-e5a0-4d6e-b5de-09235006b9c0` in the original workspace; it is not included in Git. A checkout alone cannot reproduce that exact view. If it is missing, save your own F8 capture and substitute its directory; the results describe that different view. Procedural verification requires no capture.

From the repository root in PowerShell:

```powershell
./gpu-build.ps1
./game-check.ps1
$benchCapture = 'out/game/captures/2026-10-26T14-08-21.947078900Z-e2ee8470-e5a0-4d6e-b5de-09235006b9c0'
$benchClasspath = 'out/game/classes;out/game-check/classes;out/gpu/classes;out/gpu/lib/*'
foreach ($divisor in @(1, 2, 4)) {
    & "$env:USERPROFILE/.jdks/openjdk-23.0.1/bin/java.exe" --enable-preview -Djava.awt.headless=true -cp $benchClasspath game.FogCacheBenchmark $benchCapture gpu '836,1600' 7 20 $divisor
}
```

`game.FogCacheBenchmark` is the existing saved-view benchmark, extended for this experiment; it is the intended entry point for the table above. Its arguments are capture directory, backend, comma-separated widths, starting hour, measured frame count, and optional fog divisor (default 1). It loads the displayed camera and geometry from that capture, overrides the starting clock hour, and runs the stationary/600-second/30-second cases twice. The example tests the same configurations as the table; the recorded sweep used divisor order 4, 2, 1 rather than the example’s 1, 2, 4, so timings will not be identical. `metadata.json` identifies the implementation hashes and image metrics. `fog-1.png`, `fog-2.png`, and `fog-4.png` are Full/Half/Quarter renders; `capture-quality.txt` records their linear-light comparison. The controls and benchmark are experimental; Full remains the reference when evaluating new captures.

