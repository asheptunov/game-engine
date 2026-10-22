# S1.1: opt-in GPU sample-game renderer

S1.1 adds a Vulkan ray-query backend for the Java sample game. CPU remains the default. The backend renders opaque diffuse polygon instances, canonical-box textures, one directional sun, sky, perspective pinhole cameras, depths zero through two and progressive accumulation. Unsupported features produce an error with a CPU relaunch command. S1.2 direct GPU presentation and general engine parity remain deferred.

## Launch and build

Run from the repository root on Windows x64 with Java 23 and preview features. The GPU must support Vulkan 1.2, ray queries, acceleration structures, buffer device addresses, float64/int64 shaders and 64-bit compute timestamps. Setup downloads checksum-pinned LWJGL 3.3.6 jars/native libraries from Maven Central; later builds verify them offline. The shader compiler runs during GPU builds when the shader source changes. Normal `-NoBuild` launches load the compiled SPIR-V shader and do not invoke the compiler.

```powershell
./setup-gpu.ps1
./gpu-build.ps1
./game.ps1 -NoBuild -Backend gpu -Width 960 -Height 600
./game.ps1 -NoBuild -Backend cpu -Width 960 -Height 600
```

For the original comparison grid use `-Width 320 -Height 200`. Both dimensions must be 64..1600. Without explicit dimensions the existing game view determines the grid. GPU always uses a fixed grid and labels its disabled adaptive control. An explicitly sized CPU launch starts with adaptation off; ordinary CPU launches retain the existing adaptive default. Close and relaunch to change backend. CPU builds and launches do not need the GPU jars or Vulkan initialization.

The original `./game.ps1` command still launches CPU rendering. WASD, Space/Ctrl, mouse capture, Escape, R camera reset, lighting buttons and focus suspension retain their existing behavior. Backend/device, GPU execution, transfer, host cost, allocation peak, shown generation, capture age and samples appear in the game overlay. Primitive traversal counters and GPU-path host CPU/allocation counters are unavailable, not zero measurements. Initialization/device errors stop GPU work and direct the user to `./game.ps1 -Backend cpu`; fallback is never automatic.

## Architecture and supported limits

`RenderEngine.openSession(..., "gpu")` loads the optional `gpu.GpuRenderSession` module. The ordinary overload remains CPU-only. One daemon GPU owner performs initialization, scene upload, dispatch, waiting and readback. Input/display threads only exchange immutable inputs and bounded leases. Three reusable CPU image slots prevent overwriting an acquired image. Camera motion completes a captured-camera preview; scene/restart/grid/suspension changes reject obsolete work. Closing queues native resource retirement on the GPU owner rather than waiting on the input thread.

`GpuScene` shares one bottom-level acceleration structure per geometry and builds an instance hierarchy. Positions, triangle-to-face mappings, linear texture samples and material data reside in device memory. Camera/light changes update only parameters. Material edits replace material/texture buffers while retaining acceleration structures. Geometry or transform edits currently rebuild the scene's acceleration structures as a whole; selective rebuilds are a later optimization. GPU accumulation uses double precision, with the existing billion-sample limit. Readback prefers CPU-cached coherent memory, falling back to another coherent host-visible type when unavailable. The shader enumerates uncommitted triangle candidates to choose deterministic original-instance/triangle ties; it does not assume driver traversal order. This favors predictable identity over closest-hit pruning and may leave performance available for future work.

A completed sample is copied from device output to mapped host staging, then into a leased linear RGB image. The existing game converts that image into its immutable AWT display copy. Therefore S1.1 includes readback and CPU presentation costs. Vulkan hardware traversal does not imply exact CPU intersection arithmetic. Tests use deterministic image comparisons and separate seam/transform cases; they do not establish correctness for every possible polygon scale or all GPUs.

The first target machine is an RTX 3080 with 12,288 MiB VRAM, driver 616.92, Vulkan device API 1.4.351, an i5-13600KF and Java 23.0.1. Other vendors and operating systems are unverified. Mirrors, glass, volumes, emitters, point lights, rough materials, orthographic/aperture cameras, GPU adaptive resolution and temporal reconstruction are rejected. The gallery contains only supported geometry under the game's sun/sky lighting and can also be selected; this does not broaden transport support.

## Verification and measurements

```powershell
./verify.ps1
./game-check.ps1
./gpu-check.ps1
./gpu-benchmark.ps1 -NoBuild
./gpu-quality.ps1 -NoBuild
```

The repository completion gate covers readability and selected CPU engine/scene suites; game checks cover the original game. GPU checks exercise the real device without opening a window. Their script throws on compilation or assertion failures; unavailable hardware is a failure of this explicitly requested device check, never a false pass. The GPU suite passes 29 checks, including a near-surface cutoff case and injected initialization/device failures. It also paints the actual game panel offscreen, exercises lighting and its disabled adaptive control, and simulates focus suspension. This verifies composition and controls without claiming native-window acceptance.

The route benchmark uses `benchmarks/game/camera-route.csv`: 25 seconds moving, five seconds stationary. Each backend/resolution gets a thirty-second warmup and three measured replays; order alternates and the previous renderer drains before switching. Depth is two, seed one, one sample per batch, adaptation and temporal reconstruction off. CPU uses fourteen workers and sixteen-pixel tiles. Fixed grids are 320x200 and 960x600 for a 960x600 reference window. The process opens no native window. Fresh-image counts, intervals and age end at the actual game's AWT-image copy completion, excluding Swing painting, real input delivery and monitor scanout. Report those limits when comparing these results to physical FPS or input latency.

The benchmark records the actual backend, settings, source hashes, JDK, driver, device memory and configured GPU power limit. No power policy is changed or clock lock applied. Normal host activity, installed driver layers, thermal state and scheduling can affect results; the measurements do not isolate those causes. The Vulkan allocation peak counts host and device allocations owned by this backend, not total driver memory. The sum of JVM heap-pool peaks is an upper bound assembled from separate pool maxima, not a simultaneous heap measurement. Active-thread allocated-byte deltas include game image copies and benchmark bookkeeping; terminated threads may be absent, so these are not exact process-wide lifetime totals. Cold initialization/build is separate from warmed route timing.

### Results on the target machine, 2026-10-11

Rates below average the three measured moving intervals. The p95 columns give the range across those runs. These are fresh images copied by the game per second, not physical display FPS.

| Grid | Backend | Copied images/s | p95 interval (ms) | p95 capture-to-copy age (ms) |
| --- | --- | ---: | ---: | ---: |
| 320x200 | CPU | 122.81 | 19.14–20.07 | 20.19–21.20 |
| 320x200 | GPU | 1213.97 | 1.01–1.03 | 2.00–2.02 |
| 960x600 | CPU | 13.72 | 171.20–172.81 | 182.08–182.50 |
| 960x600 | GPU | 134.99 | 8.70–8.76 | 17.34–17.41 |

The native reference grid improves throughput by 9.84 times. Every replay continued publishing during movement and accumulated stationary samples afterward. GPU stationary recovery took 17–19 ms at 960x600, versus 105–120 ms for CPU. The GPU's median host tracing cost was 3.29–3.52 ms and the game's image-copy cost was 7.21–7.25 ms; those stages overlap and must not be added as sequential frame time. This suggests presentation deserves investigation in S1.2, but does not authorize or establish the benefit of a presentation rewrite. The native-grid backend allocation peak was 35.3 MiB, including host staging. Initialization and maximum scene preparation are recorded separately in each run's metadata; they are excluded from warmed route rates.

The stationary quality comparison gives each backend 500 ms after preparation and restart. It compares the last complete copied linear RGB image against an independent seed-73 CPU reference at 2048 samples per pixel. Root mean square error (RMSE) is the square root of the mean squared channel difference; lower is better. Three runs use seeds 1, 2 and 3, alternating backend order. The reference's change from 1024 to 2048 samples was 0.000817 at 320x200 and 0.000590 at 960x600, so the reference itself still contains noise.

| Grid | Backend | Samples completed within budget | Mean linear RGB RMSE |
| --- | --- | --- | ---: |
| 320x200 | CPU | 70–77 | 0.004468 |
| 320x200 | GPU | 217–227 | 0.002658 |
| 960x600 | CPU | 10–11 | 0.008185 |
| 960x600 | GPU | 67–133 | 0.002700 |

The quality harness polls every millisecond and copies raw radiance; it does not use the route benchmark's continuous game callback. Its polling, scheduling and copy costs constrain throughput. The large native GPU sample-count spread is retained in the results rather than filtered out. All three GPU runs had lower error than all three CPU runs on this workload; this is not a general guarantee for other scenes or hardware.

Evidence: [route summary](summary.csv), [quality samples](quality.csv), [raw route events and per-run metadata](route-evidence.zip), [render source hashes](source-sha256.txt), [shader/library/harness hashes](artifact-sha256.txt), [device](device.txt), [29 GPU checks](check.log), [offscreen panel check](ui-check.log), and [CPU/game/tooling verification logs](verification-logs.zip). `./verify.ps1`, `./game-check.ps1`, `./gpu-check.ps1` and all 21 `./style-test.ps1` command cases passed. Image examples are the [offscreen game panel](game-panel.png), [500 ms CPU](quality-cpu-960.png), [500 ms GPU](quality-gpu-960.png), and [2048-sample reference](reference-960.png). The quality examples use seed 1.

## Human acceptance still required

Automated checks and window-free images do not establish native-window smoothness. Launch each backend at the same fixed grid, capture the mouse and follow the scene, release with Escape, change sun/sky, reset the camera, move focus away/back and close. Confirm readable diagnostics, coherent camera previews, correct texture orientation, no seams or flashing, responsive input and no lingering GPU activity after close. Try CPU recovery after a reported GPU error. Human acceptance remains pending until these checks are actually performed.
