# AGENTS.md

## Build and verification

Java 23 with preview features; no Maven/Gradle. Sources are in `src/`, tests in `tst/`,
and the entry point is `src/Main.java`. Run from the repository root so assets resolve.

In WSL/Bash:

- `./build` compiles sources and tests to `out/cli/`; compiler output is in `out/cli/build.log`.
- `./test <ClassName>` runs a suite, e.g. `./test di.InjectorTest`.
- `./run` launches the app using the compiled output.

The scripts default to `/mnt/c/Users/andri/.jdks/openjdk-23.0.1`. Set `JAVA_HOME` for
another JDK 23 installation. Windows JDKs require WSL's `wslpath`; the scripts are not
PowerShell scripts. IntelliJ output is separate from `out/cli/`.

PowerShell can run compiled classes directly:

```powershell
& "$env:USERPROFILE\.jdks\openjdk-23.0.1\bin\java.exe" --enable-preview -cp out/cli Main
```

Tests use `@harness.Test` and a `main` calling `SuiteRunner.runThis()`. Inspect the test
results: the harness logs failures but does not return a failing process exit code.
`Workbench` contains disabled migration tests.

## Runtime and architecture

`MainModule` owns DI wiring, configuration, and the scene registry. Startup selects
`TextureEditor`; F12 cycles scenes, and `/` opens the active scene's console.
`SceneAwareProxyBuilder` routes rendering and input through a shared
`AtomicReference<Scene>`. `SceneSwitcher` handles F12; `ProfilingInput` handles F3.

The render loop is `PeriodicExecutor` → `ProfiledFrame` → `CompositeRenderer`:

```text
Eraser → Checkerboard → active scene → PerformanceOverlay → AwtViewer
```

- `Raster`/`PixelRaster` hold pixels; `Painter` and `Printer` draw graphics and bitmap text.
  `AwtViewer` presents the raster through a Swing frame and `BufferStrategy`.
- `TextureEditor` handles painting, selection, color picking, undo/redo, and `.tx` file I/O.
  `ChainRasterSerializer` tries ARGB then RGB formats. Shared console code is in `src/ui/console/`.
- `Viewport` uses `DirectRgbTracer` for iterative diffuse/mirror/dielectric RGB paths with progressive
  accumulation, shadow visibility, and inverse-square point lights. Depth 0 preserves the
  deterministic pixel-center direct diagnostic; depth >0 jitters each pixel's primary ray.
  `Resampler` filters averaged linear
  RGB before fixed exposure, Reinhard tone mapping, and sRGB display encoding.
  `BackwardRayTracer` retains scalar reference behavior; the forward `RayTracer` remains
  available but neither is used by the viewport.
- The default viewport preset is `playground`; `/` then `view help` lists live controls.
  `view preset triangle` selects the original geometry with the new lighting model.
  `view resolution 400` explicitly selects a smaller sensor for editing; default is 1600.
  Immutable named `SceneInstance` edits share the state monitor with frame rendering.
  Prepared geometry and per-object bounds are cached until edits.
- `view preset bounce-room` enables the phase 2 mirror/color-bleeding demo at depth 3.
  Select `view resolution 200` explicitly for faster editing. `view depth`, `samples`,
  `seed`, `restart`, `target`, `pause`, and `resume` control convergence; `view type`
  changes a shared material between diffuse and mirror. `view status` shows settings.
  The active `pathDepth` setting is independent of the old forward `maxBounces`.
  Snapshot invalidation detects camera/scene/light/transport changes between batches;
  exposure, overlays, console, and batch-size edits retain samples. RGB means use double
  precision; counts cap at one billion spp. Input runs between full sensor batches,
  with a 50 ms budget checked after each complete sample (one sample can exceed it).
- `view preset glass` shows a clear sphere and absorbing box against colored stripes;
  `glass-inside` starts the camera inside the sphere. Both use depth 8. `view type dielectric`,
  `view ior 1..3`, and `view absorption r g b` (0..100 inverse scene units) edit materials.
  Glass uses exact Fresnel/Snell/TIR and radiance IOR weights; base color does not tint it.
  Media replace one another through disjoint or strictly nested nonintersecting sphere/
  ellipsoid/box boundaries, including inside cameras. Open glass surfaces are rejected.
  Overlapping/touching boundaries and sub-offset gaps are unsupported. Each entry/exit
  consumes a continuation. Glass blocks direct visibility; reliable refractive caustics
  and direct point lighting across its boundaries remain outside this phase.
- Keyboard and mouse bindings live in `assets/bindings/*.properties`; action registration
  stays in the scenes. Binding parsing/validation is in `src/ui/`.
  Viewport WASD moves relative to the view, Space/Ctrl moves vertically, and mouse
  movement looks around without clicking (pitch clamped to ±89°). CameraControls tracks physical held keys,
  independent of OS repeat, and integrates normalized movement at 3 scene units/second
  before tracing. Elapsed steps cap at 250 ms after stalls. Console opening, camera reset,
  scene switching and window focus loss clear transient input. Mouse look preserves the
  sensor's size and pinhole distance; camera changes invalidate progressive samples.
- `view preset rough-room` demonstrates GGX mirror/glass pairs and an emissive ceiling
  rectangle at depth 8. `view roughness 0..1` uses the ideal path at zero; positive values
  use single-scattering GGX/Smith, with lost masking energy retained as loss. Glass keeps
  the earlier medium model. `view emission r g b` sets linear radiance on a single rect
  (0..10000); it emits on edge1 × edge2's side and is opaque on the back. Area lights use
  fixed radiance: larger area softens shadows and emits more power. In rough-room,
  `view light position/color/intensity/size` edits the area emitter; earlier presets keep
  point-light controls. MIS combines one area sample per emitter with BSDF continuation,
  including full emission after delta events and full light estimates at terminal vertices.
  Depth 0 keeps primary pixel centers but area-light sampling is stochastic. F4 counts
  area samples, emitter hits and successful rough events; no per-path scratch allocation.
- `src/di/` provides constructor injection (`@Inject` or a no-arg constructor), `@Provides`,
  qualifiers, singleton/prototype scopes, and list/map bindings. Use `GenericType<T>` for
  parameterized keys. Scenes and the display raster are shared singletons.
- Logging uses `LogManager.instance().getThis()`; levels are configured in `src/logging.txt`.

## Profiling

Keep profiling horizontal: shared components in `src/profiling/`, pipeline/input
decorators composed in `MainModule`, and small timing/metadata hooks in scene code.

F3 toggles the overlay in either scene; hidden at startup. Collection continues while
hidden. Nested stage timings are exclusive wall time, not sampled CPU time. Frame
intervals include idle/scheduler time between renders. History is capped at 10 seconds
and 4096 frames; the panel refreshes at 4 Hz. Timeline columns show the slowest frame,
with a white target-budget line.

F4 toggles a tracing drilldown (and shows the overlay). It uses opt-in JFR execution
sampling at 10 ms, with batched delivery and a bounded 10-second sample history.
Shares describe sampled execution stacks, not exclusive wall-time durations; wait
for sufficient samples after changing the camera. F4 off closes the recording.
F3 only hides the panel; active collection continues. Hardware/JVM counters refresh
at 4 Hz: machine-normalized CPU load, logical CPUs, heap/RAM, and GC deltas. Trace
CPU time and allocated bytes are read once around each trace; CPU time has OS timer
granularity. Unsupported counters display n/a. The tracer does not use the GPU.
The trace detail shows primary/continuation/shadow primitive-test counts, accumulated
spp and sampling status. Primary throughput is labeled separately from all-ray throughput
in the benchmark. Shadow-ray counts are
visibility queries for contributing lights; a query may test zero primitives when
the scene contains only the hit surface. Back-facing lights issue no shadow query.
F4 also shows exact glass reflection/transmission and interior segment counts, even
before sufficient CPU samples arrive. Interior absorption also applies to visible
point-light segments within the same medium.

`./test ViewportBenchmark` measures the triangle diagnostic headlessly; pass `close` for a
near-full-screen triangle and `details` to enable JFR sampling. It warms up 50 frames
and measures 100; it excludes overlay drawing, AWT presentation, and scheduler idle.
Pass `playground` for the material demo and `size=400` for an explicit sensor size.
Pass `bounce-room depth=3 size=200 samples=1 seed=1` for progressive paths. Benchmark
metadata includes requested/actual batch spp, total accumulated spp and sampling seed.
It records dimensions, depth, samples per frame, deterministic sampling, throughput,
trace allocations, and actual primitive tests. Compare matching settings.
Run `scenes.viewport.MaterialPlaygroundTest` for phase 1 numeric/image/control checks;
it writes `out/cli/material-playground.png`. Texture editor key tests need a non-headless
AWT toolkit (they query keyboard lock state), but do not open a window.
Run `scenes.viewport.ProgressivePathTest` for sampling probability/weight, depth,
reflection, seeded convergence, batch equivalence and invalidation checks. It writes
`out/cli/bounce-room-preview-1.png` and `bounce-room-preview-128.png` (320 square).
`ViewportKeyBindingsTest` also requires `-Djava.awt.headless=false`, without opening
a window; it exercises console opening/closing and camera invalidation.
Run `scenes.viewport.DielectricPathTest` for phase 3 optics, nested/inside media,
absorption, validation, invalidation, convergence and image checks. It writes 240²
`out/cli/glass-preview-64.png`, `glass-ior-1-preview.png`, and `glass-inside-preview-64.png`.
Benchmark glass with `glass depth=8 size=200 samples=1 seed=1`, or `glass-inside`.
Run `scenes.viewport.RoughLightingTest` for phase 4 GGX/PDF/energy, area/MIS brightness,
delta events, absorption, shadows, controls and convergence. It writes 240²
`out/cli/rough-room-preview-128.png` and `rough-room-smooth-glass-128.png`.
Benchmark `rough-room depth=8 size=200 samples=1 seed=1`, or explicit `size=400`.

Run `./test profiling.FrameProfilerTest` and `./test ProfilingIntegrationTest` for
headless timing, toggle, rendering, and scene/DI checks. The first writes synthetic
previews to `out/cli/perf-viewport.png` and `out/cli/perf-editor.png`.

`Goals.md` holds the broader roadmap; `tracker.md` records implementation milestones.
