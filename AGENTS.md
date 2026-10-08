# AGENTS.md

## Human questions and glossary

`FAQ.md` is the human-facing knowledge base. Only humans add terms or questions;
agents may contribute answers. During relevant repository work, check for unanswered
entries and answer those you can ground in the implementation or reliable sources.
Preserve human wording, numbering, ordering, and follow-up threads. Define `TN <term>`
with a separate `TN: <definition>` paragraph immediately below it, and answer
`QN <question>` with `AN: <answer>` immediately below it. Do not invent new questions,
renumber entries, or replace the human introduction. Questions may use `QN:` and
recursive follow-up identifiers such as `Q3.1.2:`; match the answer identifier
(`A3.1.2:`) and preserve the thread's surrounding context.

Keep answers concise, explain unfamiliar terms in plain language, and use a small
example when helpful. Distinguish current implementation from proposed optimizations;
check the code before explaining project behavior. Link relevant code or sources
where useful. Update inaccurate answers when related code changes, preserving the
question and thread. Longer implementation plans belong in requirements documents;
`PerformanceRequirements.md` contains the proposed optimization phases.

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
- Both consoles use `Console.withAwtText` and the reusable `AwtPrinter` (16-point
  native monospaced glyphs, measured cell dimensions, cached masks). `AwtText` shares
  the native font choice with F3/F4. `RasterPrinter`/`FsFontLoader` remain available
  for bitmap text. Run `rendering.AwtPrinterTest` for native rendering equivalence,
  clipping and ANSI colors; preview: `out/cli/console-awt-preview.png`.
- Console Up/Down recalls submitted commands (including failures), restoring the
  unfinished draft after the newest entry. History is bounded and lasts for the
  scene's lifetime. `help` lists root commands; `help view resolution`,
  `view help resolution`, and `view resolution help` show the same focused page.
  Nested groups have their own indexes (`view light help`) and leaf usage pages
  (`view light position help`). Successful view edits print one-line confirmations;
  `view status` explicitly prints full state. Run `ui.console.ConsoleInteractionTest`
  for history/draft, bounded recall, help routing and response checks.
- `TextureEditor` handles painting, selection, color picking, undo/redo, and `.tx` file I/O.
  `ChainRasterSerializer` tries ARGB then RGB formats. Shared console code is in `src/ui/console/`.
- `Viewport` uses `DirectRgbTracer` for iterative diffuse/mirror/dielectric RGB paths with progressive
  accumulation, shadow visibility, and inverse-square point lights. Depth 0 preserves the
  deterministic pixel-center direct diagnostic; depth >0 jitters each pixel's primary ray.
  `DisplayConverter` fuses linear RGB box filtering, fixed exposure, Reinhard tone mapping,
  and sRGB encoding into a reusable byte cache; `Resampler` remains the numeric reference.
  `BackwardRayTracer` retains scalar reference behavior; the forward `RayTracer` remains
  available but neither is used by the viewport.
- Camera state is one immutable `Camera` description, compiled outside the state monitor into
  allocation-free primary/reference ray generation. `view camera projection perspective/orthographic`
  selects projection and retains active aperture. Legacy `mode perspective/orthographic` closes
  the aperture; `mode lens` restores perspective with remembered radius. Perspective remains the exact legacy
  eye/plane path; `cameraSensor()` is a derived compatibility accessor for its remembered plane.
  `view camera fov <1..179 degrees>` changes vertical framing in perspective/lens; `height <units>`
  changes orthographic vertical span. First orthographic entry matches scale at the focus distance;
  subsequent visits restore height. Off-center/skewed legacy planes retain exact rays but reject
  conversion to orthographic/lens. Navigation moves pose while preserving optics in every mode.
  `view camera focus distance <units>` sets positive axial distance (default 5); `focus center`
  queries the first surface, including glass, and rejects stale results. Live queries are asynchronous;
  status reports their outcome, and failures preserve focus policy/pulls.
  `view camera aperture <radius>` applies in either projection, finite/nonnegative, default 0. Zero is the exact reference path;
  positive radius samples area uniformly using an independent deterministic aperture domain even at
  depth 0. Aperture averaging changes blur without physical exposure or focus breathing.
  Orthographic pupils translate with each image point, preserving mean parallel framing and avoiding
  focus breathing; this is an ideal camera model, not a physical lens assembly.
  Varying origins use worker-local per-origin medium membership. `CameraProjection` implements
  orthographic/pinhole temporal projection; finite aperture keeps requested temporal settings but
  displays raw output and disables dependent budgets. A separate camera-history revision prevents
  aperture/mode toggles reviving old history without cancelling coherent camera-only previews.
  `view camera status` reports pose/optics/DOF/fallback; `reset` restores the preset camera (including
  glass-inside), clears input, and retains edited scene/resolution. Identical effective camera edits
  and inactive focus edits retain accumulation. Run `engine.CameraCompatibilityTest`,
  `CameraFollowupCompatibilityTest`, `CameraModelTest`, `CameraOpticsTest`, and `CameraLifecycleTest`; see `benchmarks/camera/README.md` for previews,
  measurements and human test steps.
- `FocusController` separates realized camera focus, immutable `FocusTargetSource` selection and
  transition/acquisition policy. `view camera focus mode auto/manual` acquires or freezes;
  `source screen <u> <v>` selects normalized 0..1 coordinates (v=0 bottom), default center.
  `focus center` and `focus pull center` always use (0.5,0.5); `pull source` uses the selected point once.
  `pull distance <units>` takes manual control and interpolates reciprocal distance. `transition
  duration <0..10000 ms>` defaults 300; `curve linear/smooth` defaults smooth. `auto delay
  <0..2000 ms>` defaults 150 for a new subject; `auto tolerance <0..25 percent>` defaults 1 and
  compares reciprocal depth against the accepted target. Segments reach exact endpoints and stop writes.
  One daemon query thread owns a cached immutable geometry/BVH layer and separate scratch/counters;
  at most one query and one completion mailbox, maximum 20Hz. Accept auto results only with current
  scene/control/source revisions, <=100ms capture age and <=2% reprojection displacement; recompute
  axial distance from the world hit. Geometry edit-and-undo still invalidates old results. Focus/aperture
  writes do not self-invalidate reference queries; zero aperture focus changes retain raw/history/P4 keys.
  Live render ticks advance focus before snapshots. Blocking tracing does not advance autofocus.
  Render pause, scene hiding, window focus loss and close suspend/cancel query work; active time excludes
  suspension. Console opening clears navigation without suspending focus. Reset restores defaults.
  F3 separates actual/target focus from shown captured optics. Run `FocusControllerTest`,
  `FocusLifecycleTest`, `ViewportKeyBindingsTest` and `ProfilingIntegrationTest`; window-free
  `engine.FocusQueryBenchmark` measures query cost/preparation separately from transport.
- The default viewport preset is `playground`; `/` then `view help` lists live controls.
  `view preset triangle` selects the original geometry with the new lighting model.
  Default tracing resolution follows the window aspect with a 1600-pixel long edge
  (1600×1000 for the 1440×900 window). `view resolution 800 500` sets width and height;
  each must be 64..1600. `view resolution native`, `half`, `quarter`, or `0.5x` uses
  the window dimensions as a fixed reference, rounding to nearest pixels. Out-of-range
  scales are rejected. The legacy `view resolution 400` still selects a square grid.
  Resolution changes leave the camera field of view unchanged; presets retain the grid.
  Immutable named `SceneInstance` edits share the state monitor with brief snapshot/publication work.
  Prepared geometry and per-object bounds are cached until edits.
- P4 interactive resolution is opt-in: `view interactive on/off`, `view interactive
  target <1..1000 ms>` (default 16.67), `view interactive min <width> <height>`.
  `view resolution` remains the maximum/stationary grid; default minimum is quarter
  with at least 64 pixels per axis. Fit minimum bounds to the requested grid aspect,
  capped by its maximum. Eye/sensor changes trigger motion; after 350 ms quiet,
  restore requested quality at the next completed-job boundary. Pause holds the grid.
  `InteractiveResolution` uses smoothed snapshot-to-raster cost, discrete scales,
  a 500 ms cooldown and fresh-measurement hysteresis. Camera motion never cancels
  every pass. Moving batches cap at one spp; path depth and field of view stay fixed.
  Live `sensorPixelsW/H` are requested dimensions; `sampledWidth/Height`, render keys
  and owned snapshots use the actual grid. Do not mix samples between grids.
  Automatic grid choices allocate no pixels under the state lock; tracer/publication
  buffers resize outside it. F3/footer distinguish shown/requested grids. Keep the
  fresh-image context keyed to requested dimensions so adaptation does not erase
  its window. Run `engine.InteractiveResolutionTest` for policy, commands,
  sustained previews, exact captured-camera pixels, settled convergence and edits.
  `DynamicResolutionBenchmark` compares the same elapsed-time camera path with
  automatic mode off/on; window-free results and commands: `benchmarks/p4/README.md`.
- P5 adds optional diffuse temporal presentation: `view temporal on/off` (default off).
  P5.2 adds a separate off-by-default `view temporal budget on/off`, with `samples
  <1..8>` (default 1) and `scale <0.25..1>` (default 1) subcommands. It caps moving
  batches and optionally grids only with temporal enabled and a stochastic diffuse
  scene without scattering. Requested settings remain unchanged; restore them after
  350 ms without motion. Missing/cut/edited/expired history uses the requested batch
  maximum to seed history; P4's independent one-spp moving cap still takes precedence.
  Guides still reject unsupported pixels; lowering the grid also lowers their detail.
  F3 reports actual/chosen/requested batch maxima separately from history blend.
  Grid transitions reset history; there is no cross-grid reuse or temporal upscaling.
  Run `engine.TemporalBudgetTest` for controls/preflight/bounds/lifecycle;
  cost/quality and window-free fresh-image results are in `benchmarks/p5.2/README.md`.
  Raw estimator keys, RGB means, seed streams and spp remain independent. Off immediately
  restores raw conversion, including while a job is pending. A separate mode revision
  prevents rapid off/on edits from reviving an old completed publication. The coordinator captures
  center/corner first-hit guides in existing trace tiles, then reprojects and validates
  history. P5.1 shares exact pixel-corner queries within each tile using two rolling
  rows of worker-local hit metadata; center and sampled-primary agreement still guard
  each pixel. Cache rows reset at tile boundaries and release on temporal-off/volume
  fallback. Never share mutable corner scratch across workers. Boundary rays replaced
  inset corners, so reconstructed pixels need not match original P5 exactly; raw paths
  and counts still must. See `benchmarks/p5.1/README.md` for cost/quality comparisons.
  Reconstruction uses the same process-wide pool. No tracing/reconstruction is under the input
  monitor. Guides use stable prepared primitive identity and camera-facing normals;
  misses, emitters, mirrors/glass, mixed pixels and inside-media cameras reject reuse.
  Scattering anywhere in the scene disables history for the whole image. Reject surface
  seams, depth/normal mismatches, scene/grid/seed/depth/restart edits, cuts and stale history.
  P4 grid changes discard history. Pause/target/suspend/enable changes clear it conservatively.
  `TemporalReconstruction` owns two bounded histories; repeated refinement uses a frozen
  prior camera, so current spp never count twice. History is clamped to current local
  radiance, capped at four nominal samples and 500 ms between images. Valid history
  fades exponentially: each moving estimate includes at least 20% fresh radiance.
  Do not restore the hard eight-transition age reset; it synchronized wall pixels
  into a visible nine-image pulse. Sustained forward/backward and decay tests cover it.
  Raw and reconstructed publications share the three-slot lease protocol; do not retain
  either array after releasing its image. F3 shows reused/eligible pixels, mean history
  blend (not a correctness probability or raw spp), reconstruction wall time and history
  payload. Trace CPU/allocations include guides but exclude reconstruction; guide rays/tests
  have separate tracer counters and do not inflate transport-ray counts.
  Run `engine.TemporalReconstructionTest` for correspondence, thin geometry,
  fallback, raw equivalence, leases/toggles, cancellation and scalar/parallel agreement;
  `ProfilingIntegrationTest` checks immediate raw display restoration and F3 composition.
  `engine.TemporalReuseBenchmark` measures motion quality against 128 spp and a
  soft equal-time budget; `cost` measures quarter/native overhead. See `benchmarks/p5/README.md`.
  Camera sampling now scrambles the user seed with a deterministic hash of the captured
  eye and sensor float components, calculated once per tracing call. Movement changes
  sample-zero grain; identical views reproduce it independently of job/visit order.
  Workers still key paths by pixel/sample. Stationary refinement, batch equivalence
  and the depth-zero point-light center diagnostic remain intact. Explicit ray-level
  numeric adapters retain the old camera-independent seed. Run
  `engine.CameraSamplingTest` for changed streams and exact reproduction across
  visits/workers/batches; earlier P5 benchmark numbers used the original static grain.
- `view preset bounce-room` enables the phase 2 mirror/color-bleeding demo at depth 3.
  Select `view resolution 200` explicitly for faster editing. `view depth`, `samples`,
  `seed`, `restart`, `target`, `pause`, and `resume` control convergence; `view type`
  changes a shared material between diffuse and mirror. `view status` shows settings.
  The active `pathDepth` setting is independent of the old forward `maxBounces`.
  Snapshot invalidation detects camera/scene/light/transport changes between batches;
  exposure, overlays, console, and batch-size edits retain samples. RGB means use double
  precision; counts cap at one billion spp. Live tracing uses `AsyncViewportTrace`:
  one background coordinator, no queued camera generations, immutable state snapshots
  and leased completed RGB buffers. Input and image conversion run outside the state
  monitor. Tiles write a staged double mean; only complete passes swap into committed
  accumulation. Cancellation discards partial work without changing committed spp.
  Camera-only edits let the active pass finish as a complete preview of its captured
  camera, then stop that batch. The next job captures the latest camera; no camera
  backlog is queued. Preview samples count toward live accumulation only when its
  exact render key matches. Transport/resolution/restart edits, pause/target changes,
  focus/scene suspension and close still cancel between tiles. Do not cancel every
  camera edit: that starves publication during continuous movement.
  Workers check cancellation and a cooperative 16.67 ms slice between tiles; an
  unfinished pass continues in another wave. A long tile can exceed the budget.
  The 50 ms inter-pass batch limit remains. Old images remain visible until a new
  generation completes; coherent lagging camera previews update during motion.
  Footer/F3/F4 show requested/shown generation, age since snapshot capture,
  first-image latency, cancelled jobs, wasted paths and max tile time. First-image
  latency ends at raster conversion and excludes physical input/AWT presentation.
  Display FPS/timeline describe the display thread; background trace CPU/ray/JFR
  diagnostics describe the last completed job. P3 uses a bounded three-slot publication
  pool: acquire/retain/release under the state monitor; never read RGB after releasing
  its lease. The coordinator cannot overwrite the latest or leased image. Display cache
  invalidation uses captured key, spp and exposure; restore its clean opaque background
  every frame before UI/overlays, even on cache hits or re-entry from the editor.
  RESAMPLE includes fused filtering/mapping; PAINT restores cached bytes. Equal grids
  bypass filtering, integer enlargement encodes once per source pixel, and fractional
  overlap weights are cached per dimension change. `RgbPacking` preserves partial alpha
  and accelerates opaque AWT packing. `Viewport.close()` shuts down its coordinator.
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
  stays in the applications. Platform-neutral values, parsing, validation, dispatch and
  deterministic load/save live in `src/engine/input/`. `src/platform/awt/input/` converts
  AWT events without Caps Lock state and resolves held drag buttons. The `src/ui/`
  compatibility layer preserves existing viewport/texture-editor maps and buttonless drag.
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

- `view preset mesh-room` demonstrates shared indexed closed meshes as diffuse/mirror/
  clear/frosted instances at depth 8. `view mesh detail 4..64` regenerates a spherical
  asset for all instances sharing the selected mesh; `view copy <name>` duplicates it
  three units along +Z and selects the copy. `view remove <name>` removes an instance.
  Copy material/geometry are shared; transforms are independent. Total instances cap
  at 128. `view acceleration bvh/brute` restarts samples for deterministic comparison.
  BVHs are cached per instance (16+ primitives), use median splits/leaves of four and
  stackless escape traversal; the instance list remains linear with object bounds.
  Brute mode bypasses all bounds. Camera edits reuse cached geometry. Mesh faces use
  double ray-aligned edge functions to retain tiny faces/shared seams. Original triangle
  IDs survive traversal sorting; glass uses whole-instance media and per-face source
  skipping. Indexed boundaries must be closed/outward/connected and non-self-intersecting;
  self intersections are not detected. Nested/nonintersecting and offset limits remain.

## Profiling

Keep profiling horizontal: shared components in `src/profiling/`, pipeline/input
decorators composed in `MainModule`, and small timing/metadata hooks in scene code.

F3 toggles the overlay in either scene; hidden at startup. Collection continues while
hidden. Nested stage timings are exclusive wall time, not sampled CPU time. Frame
intervals include idle/scheduler time between renders. History is capped at 10 seconds
and 4096 frames; the panel refreshes at 4 Hz. Live viewport F3 leads with fresh-image
FPS over two seconds: count each captured publication once at `endFrame`, after
the complete pipeline (including AWT submission). Cached redraws never count.
Update mean/p95 include an ongoing hold when it exceeds the last interval; current
hold and snapshot-to-display age expose stalls/lag. Paused/complete status remains
visible. Resolution/preset changes reset fresh history; editor entry clears it.
The viewport timeline shows fresh-image intervals, tallest per column, with an
orange current-hold bar and a white 60 FPS line. Display-loop FPS and exclusive
stage averages remain secondary ten-second metrics; asynchronous TRACE is labeled
background rather than displayed as zero-cost work. In the editor/blocking mode,
the graph retains display-stage stacks and the configured target-budget line.
These counters measure software submission, not monitor scanout or physical input
latency. `FreshImageBenchmark` checks paced live motion at native/half/quarter without
a window; settings/results are in `benchmarks/p3/README.md`.

F4 toggles a tracing drilldown (and shows the overlay). It uses opt-in JFR execution
sampling at 10 ms, with batched delivery and a bounded 10-second sample history.
Shares describe sampled execution stacks, not exclusive wall-time durations; wait
for sufficient samples after changing the camera. F4 off closes the recording.
F3 only hides the panel; active collection continues. Hardware/JVM counters refresh
at 4 Hz: machine-normalized CPU load, logical CPUs, heap/RAM, and GC deltas. Trace
CPU time and allocated bytes aggregate coordinator and active worker costs; CPU time has OS timer
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
accepts `size=1440x900`, `native`, `half`, `quarter`, `workers=14`, `tile=32`,
`warmup=50`, `frames=100`, `target=8`, `qualityMillis=1000`, `reference=128`,
`motion`, `instances=32` (mesh-room), and `output=out/cli/benchmark.csv`.
CSV rows and a settings sidecar are saved when output is supplied. Timing runs are
always continuous; target convergence is measured separately. Headless runs exclude
AWT presentation and actual input delivery. See `benchmarks/p0-p1/README.md` for
measurements and reproducible commands. Run `engine.ParallelTraceTest` for
seeded agreement across workers/tiles, lifecycle, commands and interrupted joins.
`view workers <count>` and `view tile <pixels>` retain accumulation. Defaults are
min(14, max(1, available CPUs - 2)) workers and 32-pixel square tiles. Workers share
one lazy process-wide daemon pool, drained by a JVM shutdown hook; scene switches
do not create pools. `ViewportBenchmark` calls `renderBlocking()` for comparable
complete-pass timings; never mix blocking and asynchronous rendering on one viewport.
Run `engine.ResponsiveTraceTest` for staged cancellation recovery, immutable
publication, rapid generation edits, pause/restart/target and suspension/close checks.
It also requires completed previews during sustained camera edits, exact preview
pixels for their captured cameras, and correct stationary convergence afterward.
`ViewportKeyBindingsTest` also covers asynchronous console edits and focus/scene switches.
`ResponsiveViewportBenchmark` posts synthetic AWT mouse events during concurrent
native glass tracing/conversion without opening a window; `legacy-lock` emulates
the former full-frame monitor. Use `-Djava.awt.headless=false`. Results and limitations
are in `benchmarks/p2/README.md` and `benchmarks/p3/README.md`.
Run `engine.DisplayConverterTest` for exact reference display bytes across grids/
exposure/orientation, clean cache restoration, and exhaustive alpha/channel packing.
`ResponsiveTraceTest` also checks leased-image stability and bounded buffer reuse.
`DisplayPipelineBenchmark` compares reference, fused and cached display work without
tracing or a window. P3 commands/results are in `benchmarks/p3/README.md`.
Benchmark metadata includes requested/actual batch spp, total accumulated spp and sampling seed.
It records dimensions, depth, samples per frame, deterministic sampling, throughput,
trace allocations, and actual primitive tests. Compare matching settings.
Run `engine.MaterialPlaygroundTest` for phase 1 numeric/image/control checks;
it writes `out/cli/material-playground.png`. Texture editor key tests need a non-headless
AWT toolkit (they query keyboard lock state), but do not open a window.
Run `engine.ProgressivePathTest` for sampling probability/weight, depth,
reflection, seeded convergence, batch equivalence and invalidation checks. It writes
`out/cli/bounce-room-preview-1.png` and `bounce-room-preview-128.png` (320 square).
`ViewportKeyBindingsTest` also requires `-Djava.awt.headless=false`, without opening
a window; it exercises console opening/closing and camera invalidation.
Run `engine.DielectricPathTest` for phase 3 optics, nested/inside media,
absorption, validation, invalidation, convergence and image checks. It writes 240²
`out/cli/glass-preview-64.png`, `glass-ior-1-preview.png`, and `glass-inside-preview-64.png`.
Benchmark glass with `glass depth=8 size=200 samples=1 seed=1`, or `glass-inside`.
Run `engine.RoughLightingTest` for phase 4 GGX/PDF/energy, area/MIS brightness,
delta events, absorption, shadows, controls and convergence. It writes 240²
`out/cli/rough-room-preview-128.png` and `rough-room-smooth-glass-128.png`.
Benchmark `rough-room depth=8 size=200 samples=1 seed=1`, or explicit `size=400`.
Run `engine.MeshAccelerationTest` for mesh/topology/BVH/reference, same-object
visibility, tiny faces/seams, transforms, nested/inside media, commands, cache reuse and
seeded image agreement. It writes `out/cli/mesh-room-preview-64.png` (200 square).
Benchmark `mesh-room depth=8 size=64 detail=12 samples=1 seed=1`, adding `brute` for
reference traversal; use explicit `size=200` or `detail=32` for more work. Mode and total
primitives are reported. Keep matching seed/spp/camera/geometry for comparisons.

Run `./test profiling.FrameProfilerTest` and `./test ProfilingIntegrationTest` for
headless timing, toggle, rendering, and scene/DI checks. The first writes synthetic
previews to `out/cli/perf-viewport.png` and `out/cli/perf-editor.png`.

`Goals.md` holds the broader roadmap; `tracker.md` records implementation milestones.

## Volumetric transport

`view preset volume-room` loads the phase 6 cloudy sphere/box demo at depth 12,
preserving explicit resolution. `view scattering` edits scalar density (0..100 per
world unit); `view anisotropy` edits HG g (-0.95..0.95). RGB absorption remains
distance-based. Scattering requires dielectric sphere/box interiors; meshes are
surface/glass only. Scaling changes optical thickness. Nested nonintersecting media
use innermost coefficients; arbitrary overlaps/touching remain unsupported.

Free-flight probabilities account for scattering extinction in the sampled path;
do not multiply it again into throughput. Only absorption is a deterministic RGB
flight weight. Light visibility integrates full absorption+scattering extinction
through equal-IOR boundaries. Refractive boundaries block straight connections.
Scattering scenes use NEE-only non-delta emitter connections without counting their
continuation emitter hits twice; surface-only scenes retain MIS and the previous
opaque glass visibility diagnostic. Zero density restores previous transport.

Run `engine.VolumePathTest` for free-flight/HG statistics, independent
single-scattering integration, multiple-scattering energy, nested/inside visibility,
Beer absorption, surface lighting, control invalidation and seeded convergence.
Previews: `out/cli/volume-room-preview-128.png` and `volume-room-clear-128.png`
(200² at 128 spp). Benchmark `volume-room size=200 depth=12 samples=1 seed=1`;
`details` enables JFR. F4 adds volume flights/events/visibility segments. Containment
tests are included in visibility primitive counts; volume shadow traversal is
classified before nearest-hit frames in sampled CPU attribution.
