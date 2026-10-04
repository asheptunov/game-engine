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
- `Viewport` uses `BackwardRayTracer` for pinhole rays and Lambertian direct lighting with
  shadows, then `Resampler` scales the sensor buffer to the display. The forward `RayTracer`
  remains available but is not used by the viewport.
- Keyboard and mouse bindings live in `assets/bindings/*.properties`; action registration
  stays in the scenes. Binding parsing/validation is in `src/ui/`.
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

`./test ViewportBenchmark` measures the default view headlessly; pass `close` for a
near-full-screen triangle and `details` to enable JFR sampling. It warms up 50 frames
and measures 100; it excludes overlay drawing, AWT presentation, and scheduler idle.

Run `./test profiling.FrameProfilerTest` and `./test ProfilingIntegrationTest` for
headless timing, toggle, rendering, and scene/DI checks. The first writes synthetic
previews to `out/cli/perf-viewport.png` and `out/cli/perf-editor.png`.

`Goals.md` holds the broader roadmap; `tracker.md` records implementation milestones.
