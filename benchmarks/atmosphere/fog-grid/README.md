# Shared 3D fog lighting comparison

The sample game's Fog tab adds **Shared 3D fog lighting (experimental)**. The checkbox defaults off and works independently of Full/Half/Quarter. It shares local atmospheric and fog lighting across primary and bounced rays. Full/Half/Quarter select the primary fog integration grid at full, half, or quarter width and height (divisors 1, 2, or 4); geometry resolution and bounced-ray integration stay unchanged by that selector. CPU rendering retains direct integration. This experiment follows the [primary fog resolution comparison](../fog-resolution/README.md).

## Try it

Run `./game.ps1 -Backend gpu -Scene world`. Press Esc to expose settings, select Fog, then use **Ruins mist (07:30)** and **View ruins (moves camera)**. Compare the new checkbox off/on at Full first, then combine it with Half or Quarter. Watch pillar edges, thin shafts, the sunlight gap, and ground haze while walking and while the day/night cycle runs. These buttons provide a convenient scene, not the exact benchmark camera below.

F8 captures and F9 histories save the checkbox; replay restores it. Older captures default it off. GPU status reports requested/effective grid state and construction time. Unsupported fog or atmosphere keeps direct integration while preserving the requested checkbox. An unavailable required GPU texture format fails explicitly through the existing fatal-report path.

## What is shared

`assets/gpu/diffuse.comp` builds six lighting coefficient fields on a 49×49×49 world-space grid covering the fog bounds. These contain extinction, molecular/aerosol sunlight, approximate multiple scattering, and each fog region's incident scattering. Direction-dependent scattering is evaluated for each ray. `src/gpu/FogLightingTexture.java` packs the fields into a hardware-filtered 3D texture atlas, with about 10.8 MiB of texel payload while active. Vulkan allocation may include padding. Turning the checkbox off releases the active atlas and retains a tiny descriptor placeholder.

The grid rebuilds every sample from current captured lighting and air/fog properties. It contains no temporal history. Geometry shadow queries and the integration steps along each ray remain intact; hard shadow visibility is not interpolated. Solar-disc primary rays stay direct. The first prototype used manual buffer interpolation and was slower; hardware 3D filtering made the approach useful.

Eligibility combines `FogSunTable.layout`'s existing smooth-fog limits with `src/engine/FogLightingGrid.java`'s local-atmosphere limits: one or two feathered, low-frequency, resolved regions of limited optical density, sunlight above approximately 2.9 degrees, volume diagonal at most the smaller of 1 km or 0.0002 planet radii, and vertical cells no larger than one twelfth of the smaller atmospheric scale height. A resolved region has at least twelve grid cells per local half extent in each axis, so tiny or widely separated fog regions use direct integration. A scale height is the altitude over which density falls by a factor of e. These bounds limit interpolation error and curvature approximation; they are not restrictions on scenes the direct renderer supports. Lens blur can use this world-space grid, although the separate Half/Quarter optimization still requires a pinhole camera.

`RenderSettings.fogLightingGrid` carries the request through settings and captures. `GpuRenderSession` invalidates accumulation when it changes. `GpuKernel` owns dispatch, synchronization, effective eligibility, and timing. Grid-off retains the reference specialized pipeline.

## Matched measurements

NVIDIA GeForce RTX 3080, driver 2585198592, Windows, OpenJDK 23.0.1. The saved ruins view uses two overlapping fog regions, path depth 2, and a 7 AM starting clock. Each case warms up ten frames, then measures twenty one-sample frames twice, reversing resolution order in round two. The sweep runs grid off then on, each with divisors 1, 2, 4. Values are the mean of two run medians in milliseconds. Raw medians and 95th percentiles are in `timings.csv`.

Total time includes update, all GPU passes, readback, publication, and CPU image conversion. It excludes initial device/scene creation and native window presentation; these are not displayed FPS measurements. Moving-sun cases advance a fixed 0.1 seconds of simulation per update. Camera, geometry, seed, exposure, and depth stay fixed, with adaptive/temporal presentation disabled.

| Resolution | Sun | Off Full | Off Half | Off Quarter | On Full | On Half | On Quarter |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 836×400 | Stationary | 21.03 | 20.89 | 21.18 | 14.12 | 14.75 | 14.72 |
| 836×400 | 600-second day | 20.77 | 20.42 | 19.37 | 15.53 | 14.03 | 14.57 |
| 836×400 | 30-second day | 19.27 | 18.26 | 18.52 | 15.07 | 13.38 | 13.40 |
| 1600×766 | Stationary | 70.62 | 65.36 | 65.21 | 52.36 | 48.41 | 47.46 |
| 1600×766 | 600-second day | 68.91 | 64.58 | 65.14 | 50.45 | 48.10 | 46.04 |
| 1600×766 | 30-second day | 67.38 | 62.84 | 61.66 | 51.31 | 45.73 | 45.27 |

At 1600×766, the grid reduces Full total time by about 24–27%; combining it with Quarter reduces time by about 33% relative to Full/grid-off. Full GPU execution falls from approximately 45–46 ms to 28 ms, including grid construction (approximately 0.02 ms per sample). CPU conversion alone still takes about 11 ms at this resolution. This is a substantial gain, but not an order-of-magnitude improvement. Half/Quarter rankings vary at lower resolution; compare them visually rather than assuming Quarter is always best. Absolute timings differ from the earlier report, so use this sweep's matched off/on values rather than comparing across runs.

## Accuracy and verification

`tools/gpu/FogGridCheck.java` compares against Full/grid-off GPU rendering at matching inputs and sample indices. Procedural cases cover depths 0/2, morning/noon/evening and moving sunlight, camera motion, a closed sunlight gap, lens blur, hazy air, rotated colored fog, an additional directional light, odd dimensions, exact unsupported-input fallback, released resources, accumulation resets, and stable leased images. The complete gate is `./parity-check.ps1`; `verification.json` records its outcome and source fingerprint.

Every tested image must have linear RGB root-mean-square error at most 0.004, and every channel must satisfy `abs(actual-reference) <= 0.015 + 0.05*abs(reference)`. These fixture bounds are not a guarantee for arbitrary scenes. `procedural-quality.txt` and `capture-quality.txt` contain results. Capture/UI tests also cover round trips, old-file defaults, and the checkbox.

The saved 836×400 view was rendered at 16 samples with exposure 1. Grid-on Full/Half/Quarter linear RGB RMS errors were 0.0000816/0.000245/0.000308; all channels met the bound. Encoded RGB RMS differences on the 0–255 scale were 0.158/0.260/0.286, maxima 1/4/5, and 99th percentile 1 for all three. Visual inspection retained the pillar silhouettes, sun, and shafts.

The included `grid-0.png` is Full/grid-off; `grid-1.png`, `grid-2.png`, and `grid-4.png` are grid-on at Full/Half/Quarter. `metadata.json` contains implementation hashes and encoded image metrics. Static image checks do not replace a native motion playthrough.

## Reproduce

The exact private capture is `out/game/captures/2026-10-26T14-08-21.947078900Z-e2ee8470-e5a0-4d6e-b5de-09235006b9c0` in the original workspace and is not in Git. A checkout alone cannot reproduce that exact view. Substitute your own F8 capture if absent; procedural verification needs no capture.

From the repository root in PowerShell:

```powershell
./gpu-build.ps1
./game-check.ps1
$benchCapture = 'out/game/captures/2026-10-26T14-08-21.947078900Z-e2ee8470-e5a0-4d6e-b5de-09235006b9c0'
$benchClasspath = 'out/game/classes;out/game-check/classes;out/gpu/classes;out/gpu/lib/*'
foreach ($grid in @('false', 'true')) {
    foreach ($divisor in @(1, 2, 4)) {
        & "$env:USERPROFILE/.jdks/openjdk-23.0.1/bin/java.exe" --enable-preview -Djava.awt.headless=true -cp $benchClasspath game.FogCacheBenchmark $benchCapture gpu '836,1600' 7 20 $divisor $grid
    }
}
```

`game.FogCacheBenchmark` arguments are capture directory, backend, comma-separated widths, starting hour, measured frame count, optional primary fog divisor (default 1), and optional lighting-grid flag (default false). It uses the captured camera and scene, overrides clock time, and runs stationary/600-second/30-second day cases twice. To render the saved-view quality images, first run `./gpu-check.ps1`, then invoke `gpu.FogGridCheck $benchCapture` with the same Java options and classpath above.
