# Ray Tracing Viewport — Build Tracker

Bottom-up, TDD. Each milestone lands with passing tests before the next starts.

The proposed engine/application separation and scene/mesh authoring roadmap is in
[EngineRequirements.md](EngineRequirements.md). E1–E2 are complete: the playground uses
the public render-session boundary, and an independently compiled headless consumer plus
two-session/fixed-update tests prove reuse. See [EngineApi.md](EngineApi.md). E3–E6 add
persistent scenes, general meshes/queries,
a scene editor, and editable mesh topology with face extrusion. E7 adds direct
vertex/edge/face editing, visible mode cues, exact analytic spheres with optional
mesh approximation, and independent geometry/materials per editor object. Integration
of the complete editor branch into main was authorized 2026-10-08.

## E1–E2 — Engine extraction `[done]`

- Immutable `WorldSnapshot`, `RenderView`, and `RenderSettings` inputs.
- Independent `RenderSession` state with bounded explicit image leases.
- Engine-only PowerShell compilation boundary and window-free PNG consumer.
- Two-view, timed-update, atomic validation, seeded-equivalence, cancellation, focus,
  temporal, and full 54-suite regression evidence in `EngineRequirements.md`.

The next renderer requirements and six playable implementation phases are in
[RenderingRequirements.md](RenderingRequirements.md). All six phases are implemented.

The proposed performance roadmap is in [PerformanceRequirements.md](PerformanceRequirements.md).
It ranks CPU optimization phases by estimated practical impact, with measurement and
completion gates, plus separate GPU and native backend evaluations. P0 through P5,
P5.1 and P5.2 are implemented; P6 onward and P11.1 remain proposed.

## M1 — Vec3 ops `[done]` (16/16 tests pass)

Make `math.Vec3` useful for geometry math. Operations: `add`, `sub`, `scale`, `negate`, `dot`, `cross`, `lengthSq`, `length`, `normalized`. Constants: `ZERO`.

Tests (`tst/math/Vec3Test.java`):
- add/sub/scale/negate produce expected components
- dot identities: `v·v == |v|²`, orthogonal vectors → 0
- cross anti-commutativity, basis: `x × y = z`
- length, normalized → unit, normalize zero throws

## M2 — Ray, Plane, Intersection `[done]` (14/14 tests pass)

- `math.Ray(origin, direction)` — `at(t)` returns origin + t·direction.
- `math.Plane(point, normal)` — `intersect(Ray)` returns `Optional<Float>` (t > 0).
- `math.Intersection(point, normal, distance, incoming)` — `reflect()` returns reflected direction.

Tests for each: hits, misses, parallel-ray, behind-ray, reflection identity (normal incidence flips direction; oblique reflects symmetrically).

## M3 — SceneObject: Tri, Rect `[done]` (20/20 tests pass)

Sealed interface `engine.objects.SceneObject` with `Optional<Intersection> intersect(Ray)`.

- `Tri(a, b, c)` — Möller–Trumbore.
- `Rect(origin, edge1, edge2)` — same algorithm, bounds `[0,1] × [0,1]`. `uv(point)` maps a hit point to `(u,v) ∈ [0,1]²`.

Tests: front/back hit, miss outside bounds, parallel ray, t > 0 only, normal direction.

## M4 — Light + PointLight `[done]` (6/6 tests pass)

Interface `engine.lights.Light` with `List<Ray> sample(int n, Random)`.

`PointLight(position)` emits uniformly on the unit sphere.

Tests: sample count == N; origins all at position; directions are unit length; statistical-ish coverage (centroid of many samples ≈ origin).

## M5 — Viewport state `[done]`

`engine.ViewportState` holds: `List<Light>`, `List<SceneObject>`, camera sensor `Rect`, settings (samplesPerLight, maxBounces, pixel accumulator dims).

## M6 — RayTracer `[done]` (7/7 tests pass)

`engine.RayTracer` orchestrates one frame:
1. For each light, cast N samples.
2. For each ray: find nearest non-light intersection. If none → discard. If bounces > B_max → discard. If it hit the camera sensor's sensing side → map to pixel and accumulate. Else spawn reflection ray, recurse.
3. After all rays, return an accumulator buffer.

Tests: single-ray traversal scenarios — direct hit on camera, miss everything, max-bounce drop, back-side camera hit ignored.

## M7 — Wire into Viewport.render() `[done]`

Default scene: sensor 1×1 at z=0 (normal +z), pinhole eye at (0,0,-1), sun at (0,0,5), tri at z=10
spanning (-2,-2)…(2,2). Backward ray tracing produces a complete image per frame, no accumulation needed.

Forward ray tracer + accumulator are still in `RayTracer.java` for later if we need them.

## M7.5 — Switch to backward ray tracing `[done]` (6/6 tests pass)

Forward + mirror-reflection produced uniform noise (TV static) — geometric hit rates too low without
millions of samples. Switched to backward (pinhole) ray tracing with Lambertian direct lighting:
- For each sensor pixel, shoot a ray from eye through pixel into the scene.
- On the first hit, shadow-test each PointLight and add `max(0, n·lightDir)` per unobstructed light.
- Normals are flipped to face the camera (geometric normal direction agnostic).

`BackwardRayTracer.java`. ASCII debug confirms a clean rendered triangle.

Build a default scene (sun between camera and tri). Each frame: clear, ray-trace, paint accumulator onto the display. Render console overlay on top.

## M8 — E2E `[done — triangle visible in viewport scene]`

ASCII preview from `ViewportDebug`:

```
         ++
         @@
        +@@+
        @@@@
       +@@@@+
       %@@@@%
      :++++++:
```

(Triangle's apex at top, wide base at bottom — matches world tri with apex at (0, 2, 10).)

Frame timing: ~10ms per frame at 100×100 sensor resolution. App targets 144Hz (~7ms) so we're at
~100Hz on the viewport scene. Fine for now; texture editor scene unaffected.

Run app, verify viewport shows light hitting the tri reaching the camera. Iterate on tuning (sample count, intensity) for visibility.

## Performance overlay `[done]`

- F3 toggles a shared overlay in either scene, including while the console is open. Hidden at startup.
- Rolling 10-second timeline of exclusive frame stages: background, scene overhead/editor,
  tracing, resampling, painting, overlay, presentation, uninstrumented work, and idle between renders.
- FPS, average/p95 frame interval, target budget, stage averages/percentages, viewport resolution,
  ray throughput, hits, shadows, and lit pixels. Frame intervals include idle; idle also includes
  scheduler work outside the render pipeline. Wall time is measured, not CPU sampling.
- History is bounded to 10 seconds / 4096 frames. Panel refreshes at 4 Hz; collection is per frame
  even while hidden. Slowest frame per timeline column preserves spikes. Stats show completed frames.
- Cross-cutting implementation lives in `src/profiling/`; render/input decorators are composed in
  `MainModule`, with three timing hooks and ray metadata publication in `Viewport`.
- Headless tests cover timing accounting, history limits, percentiles, toggle behavior, rendering,
  and real scene/DI integration.

## Trace drilldown and runtime telemetry `[done]`

- F4 enables/disables a JFR execution-sampling breakdown: ray generation/other trace
  work, nearest-hit search/materialization, lighting, and shadow intersection tests.
  No timing calls are added to individual rays. Recording and samples are bounded;
  the overlay flags low sample counts and unavailable JFR support.
- F3 includes logical CPU count, whole-machine-normalized JVM/system CPU use, heap,
  physical RAM, GC deltas, and per-trace render-thread CPU time/allocated bytes.
  Opaque overlay pixels are copied directly to avoid adding a blending bottleneck.
- Benchmark supports default/close views and sampling on/off. The close view raises
  hits/shadow rays from 169,362 to 2,240,000 per frame at the same 2,560,000 primary rays.
  A local JFR capture found shadow testing the largest sampled hotspot, followed by
  nearest-hit search; temporary Vec3/Ray/Intersection objects dominate allocations
  (about 247 MiB allocated per close-up trace). GC is secondary in that capture.
- Tests cover sample classification and bounds, toggle debouncing, telemetry reset,
  rendering correctness, and overlay previews. JFR streaming was exercised against
  actual default and close-up benchmark workloads. Timing varies with JIT and load.

## Scalar tracing and shadow-query pruning `[done]`

- Prepared triangle/rectangle geometry feeds scalar intersection and lighting paths.
  One invocation-local scratch hit replaces per-pixel Ray/Vec3/Intersection allocation;
  the object-based geometry API remains available to other callers.
- Skip the hit flat primitive during shadow queries, but retain all other primitives
  and the bias/distance bound. Reject zero-contribution lights before querying shadows.
- The overlay reports actual shadow primitive tests separately from shadow-ray queries.
- Independent reference-image regressions cover distant/close cameras, randomized
  multi-surface/multi-light scenes, external blockers, blockers beyond the light,
  and scene mutation. Existing geometry, rendering, and profiling suites pass.
- Local headless medians: default 33.43 -> 32.95 ms; close 81.37 -> 53.70 ms
  (close p95 97.62 -> 57.92 ms). Shadow pruning alone measured 74.26 ms close-up.
  Close-up trace allocation fell from 258,592,368 to 10,272,816 bytes/frame.
  Same 1600x1600 sensor and lighting; measurements exclude presentation and overlay.
  Live JFR confirmed zero shadow traversal samples for the single-primitive scene.

## Phase 1 — Material and object playground `[done]`

- Active `DirectRgbTracer` renders linear RGB with editable named diffuse materials,
  inverse-square colored point lights, fixed exposure, Reinhard tone mapping, and sRGB.
  It retains direct-only transport (depth 0, one deterministic sample/pixel/frame).
- The default playground has a sphere, a closed 12-triangle box, a floor, backdrop,
  and reference sphere. Positive nonuniform scales and Euler rotations work for all
  geometry. Sphere rays from inside return the exit; original geometric normals and
  object/material/primitive identities remain available for the later glass phase.
- `/` → `view help` discovers presets, object/material selection, transforms, color,
  light editing, exposure, camera/scene reset, and explicit sensor resolution.
  `view preset triangle` preserves the old diagnostic geometry with documented new
  brightness conventions. `view resolution 400` is useful for interactive editing;
  default remains 1600. Edits apply coherently between frames.
- Prepared geometry is cached between edits. A conservative object bounds test avoids
  testing all box triangles on misses; uniform spheres have a world-space fast path.
  RGB storage is reused. F3 reports resolution/depth/spp/continuations, and F4 classifies
  the new tracer. Live JFR collected intersection and visibility samples successfully.
- Geometry, lighting/exposure, seeded reference intersections, shadows, commands,
  resizing, image determinism, and existing renderer/DI/profiling/input/editor tests pass.
  Preview: `out/cli/material-playground.png`. Hands-on checklist and conventions live
  in the requirements document.
- Headless playground at 1600²: 211.71 ms median / 222.00 p95, down from 457.04 / 531.32
  before object bounds/sphere fast paths. At explicitly selected 400²: 21.30 / 23.91 ms.
  These are different quality settings; display is 800², warmup 50 / measured 100 frames.
  RGB triangle at 1600²: distant 53.61 / 56.21, close 82.42 / 89.30 ms; RGB lighting/display
  adds work relative to the scalar predecessor. Unchanged trace allocation is ~0.7 KiB;
  resampling allocations are outside that figure. Full-resolution playground tests:
  12,460,864 primary / 7,741,705 shadow primitives, last-frame throughput ~12.9 Mrays/s.

## Phase 2 — Progressive paths and indirect bounces `[done]`

- One iterative continuation per hit; cosine-weighted diffuse scattering and ideal
  mirror reflection. RGB throughput includes the correctly weighted material sample.
  Direct point lighting is evaluated at diffuse vertices, including the final allowed
  vertex. Mirrors receive reflected views, with no diffuse point-light highlight.
- `bounce-room` has red/green walls, a neutral floor and mirror sphere reflecting a
  gold sphere. Depth defaults to 3 for this preset; original presets stay depth 0.
  `view type diffuse/mirror`, depth, samples, seed, restart, target, pause/resume are live.
- Double-precision online RGB means keep storage fixed; long counts stop at one billion
  spp. Camera, geometry, material, light, resolution, seed and depth edits reset sampling.
  Exposure, overlays, console and sample batch changes preserve it. Pixel/sample-local
  seeded streams reproduce the same result regardless of batch boundaries.
- Footer/F3 show spp and convergence status. F4 distinguishes primitive work for
  primary, continuation and visibility rays; CPU sample attribution remains statistical.
  Benchmark supports progressive settings and reports actual batch work.
- Numeric tests cover sampling density, throughput, final-vertex lighting and bounded
  mirror loops. Seeded room images show color bleeding and declining error; 64 spp has
  about 1.3% of the 1-spp squared error relative to a 512-spp reference. Renderer,
  geometry, profiling, DI/input/editor regressions pass. Previews and hands-on controls
  are documented in the requirements.
- Seed 1, one spp/batch, 800² display: room depth 3 at 200² measured 30.07 ms median /
  47.77 p95; at 400² 96.04 / 144.23. Room depth 0 at 400² measured 23.79 / 47.11;
  triangle depth 0 at 1600² 61.65 / 101.17. Settings differ in quality and work.
  Progressive traces reuse storage and allocate about 1 KiB/batch after preparation.

## Phase 3 — Smooth glass and absorption `[done]`

- Smooth dielectric reflection/refraction uses exact Fresnel, Snell and total internal
  reflection, sampled as one continuation with the appropriate radiance IOR weight.
  Entry/exit consume separate depth events. No diffuse point-light highlight on glass.
- Distance-based RGB absorption replaces per-boundary tint; nested media replace the
  outer medium until exiting. Inside cameras initialize their enclosing media once
  per batch. Reflection preserves the stack; transmission crosses the correct side.
  Supported boundaries are spheres/ellipsoids and closed boxes, disjoint or strictly
  nested with nonintersecting boundaries. Open glass is rejected; overlaps/touching
  and features/gaps smaller than the spawn offset remain outside the supported model.
- `glass` and `glass-inside` presets default to depth 8. `view type dielectric`,
  `view ior` and `view absorption` expose editing; status reports parameters. Shared
  edits validate atomically and preserve other material fields. Glass blocks direct
  visibility queries; direct caustics are not guaranteed. Previews/checklist are in
  RenderingRequirements.md. F4 adds reflection/transmission and medium segment counts.
- DielectricPathTest covers optics, boundaries, nested/inside transport, absorption,
  visibility, commands/invalidation, seeded batch equivalence and convergence. At
  64 spp the glass demo has ~1.3% of first-sample error against a 512-spp reference.
  Earlier renderer, geometry, profiling, DI/input and editor regressions pass.
- Seed 1, one spp/batch, 800² display: glass depth 8 at 200² measured 30.36 ms median /
  49.29 p95; at 400² 93.76 / 100.97. Inside-glass at 200² measured 38.69 / 48.88.
  Unchanged glass tracing allocates ~1.3 KiB/batch, with no per-path object chains.
  Matching earlier demos: bounce-room depth 3 at 200² 29.49 / 36.22; triangle depth 0
  at 1600² 63.26 / 87.10. Different quality settings are not equivalent comparisons.

## Phase 4 — Rough surfaces and extended lights `[done]`

- Editable GGX mirror and dielectric roughness, with matched scattering evaluation,
  direction PDF and radiance IOR weights. Zero roughness preserves ideal phase 3
  behavior. Single-scattering Smith masking may lose energy at high roughness;
  surface frost does not add fog. Earlier media/offset rules remain in effect.
- One-sided emissive rectangles are visible geometry and direct-sampling sources.
  Fixed radiance means larger area emits more power and softens shadows. Power-heuristic
  MIS prevents double lighting; camera/delta hits and terminal vertices receive the
  appropriate full contribution. Point lights remain available; rough materials use
  their scattering evaluation for direct lighting rather than diffuse shading.
- `rough-room` pairs polished/rough mirrors and clear/frosted boxes with stripes,
  a floor and editable ceiling emitter. `view roughness`, `emission`, and emitter
  `light position/color/intensity/size` are live; status/help expose their conventions.
  F4 adds exact area sample, emitter hit and rough event counts alongside CPU samples.
- RoughLightingTest verifies independent solid-angle integration/PDF mass, energy,
  smooth/delta behavior, quadrature/MIS brightness, IOR/absorption, shadows, commands,
  invalidation, batch equivalence and convergence. At 64 spp, error is ~1.1% of first
  sample error against a 512-spp reference. Visually checked 240²/128-spp previews and
  hands-on checklist are documented in RenderingRequirements.md. All 25 suites pass.
- Sequential headless runs, seed 1, actual one spp/batch and 800² display: rough-room
  depth 8 at 200² measured 38.69 ms median / 42.39 p95; 400² measured 125.18 / 144.96.
  Unchanged traces allocate 1,440 bytes/batch. Earlier presets: triangle depth 0 at
  1600² 61.60 / 81.72; bounce-room depth 3 at 200² 30.30 / 35.65; glass depth 8 at
  200² 30.19 / 31.69. Settings represent different quality/work; details in requirements.

## Phase 5 — Closed meshes and acceleration `[done]`

- Immutable indexed closed mesh assets with stable triangle identities, shared geometry,
  independent instance transforms and coherent whole-object dielectric media. Procedural
  faceted spheres support detail 4–64. No importer or smooth shading is required.
- Cached per-instance median-split BVHs use conservative world bounds, leaves of four,
  stackless escape traversal, deterministic ties and allocation-free ray queries. Brute
  traversal bypasses bounds for reference comparisons. Camera edits reuse acceleration;
  immutable scene edits rebuild it. The instance list itself remains linear.
- Mesh-specific double edge functions preserve small/high-detail faces and shared seams.
  Other triangles of an object can shadow it; source skipping applies to one flat face.
  Closed meshes support glass entry/exit, inside cameras and nested absorption/IOR.
- `mesh-room` provides diffuse/mirror/clear/frosted instances with stripes and an area
  light. `view mesh detail`, `copy`, `remove` and `acceleration bvh/brute` expose live
  geometry/performance comparisons; status and benchmark show total primitives/mode.
- MeshAccelerationTest checks topology, immutable sharing, transforms/bounds/normals,
  deterministic hit/visibility/image equivalence, tiny faces/seams, media, controls,
  invalidation and caching. Five-spp seeded images agree exactly, with ~131× fewer
  primitive tests in a comparison frame. All 26 suites pass; inspected 200²/64-spp
  preview and hands-on instructions are in RenderingRequirements.md.
- Sequential benchmarks, seed 1/depth 8/one spp and 800² display: at 64²/detail 12,
  BVH measured 27.39 ms median / 56.27 p95 versus brute 495.62 / 603.82. Detail 32
  at 64² measured 30.94 / 59.84; detail 12 at 200² measured 98.60 / 153.86. Ray/event
  counts match in reference comparisons; unchanged traces allocate about 1.4 KiB/batch.

## Phase 6 Homogeneous volumetric translucency `[done]`

- `volume-room` supplies a cloudy sphere and box with independent editable density,
  absorption and HG anisotropy. Free flights and phase continuations use the existing
  iterative depth/throughput loop, with RGB Beer absorption and no per-path allocation.
- Nested medium visibility integrates extinction through index-matched boundaries;
  refractive boundaries remain blocked for straight light connections. Scattering scenes
  use NEE-only non-delta emitter connections without double counting; zero scattering
  preserves the preceding absorption-only transport and shadow diagnostic.
- Live controls, accumulation invalidation, F4 volume counters/JFR categories and
  the headless benchmark are extended. Tests and hands-on details are in
  RenderingRequirements.md; cloudy/clear 200²/128-spp previews are in out/cli/.
- All 27 suites pass, including eight volume checks. A single-scattering integral
  matches within 0.1%; the multiple-scattering unit enclosure averages 0.998569.
  Local volume-room benchmark at 200²/depth 12/one spp: 57.78 ms median / 98.22 p95;
  64² at the same depth: 25.48 / 50.00. Trace allocation is 1,424 bytes/batch.
  Live JFR succeeds with the same ray/event counters.

## Renderer performance P0/P1 `[done]`

- Delivered measurement and tiled CPU tracing together. Benchmark settings include
  rectangular/scaled sensor grids, worker/tile controls, revision/source identity,
  actual spp, per-stage distributions, aggregate CPU/allocation, GC, convergence,
  optional timed image error, repeatable camera motion and many-instance meshes.
- A shared persistent bounded platform-thread pool dynamically claims tiles. Each
  worker owns scratch and counters; prepared geometry and camera/settings are fixed
  for each pass. Every pixel preserves its seed/sample stream and accumulation order.
  Only complete passes publish; failed/interrupted passes invalidate accumulation
  after draining all workers. Input locking/cancellation remain P2 work.
- Defaults: min(14, max(1, available CPUs - 2)) workers, 32-pixel square tiles.
  `view workers` and `view tile` retain samples; F4 shows configuration and aggregate
  CPU/allocation, and JFR classifies worker stacks. Shutdown drains the shared pool.
- On the local 20-logical-CPU i5-13600KF, glass at 400² traces in 8.52 ms median
  with fourteen workers versus 70.22 ms with one (~8.2×). Frame medians are 53.39
  versus 117.30 ms (~2.2×). Native glass: 66.57 ms trace with fourteen versus
  610.12 ms with one. Display costs now dominate smaller tracing grids.
  A longer repeat retained ~7.6× trace improvement, but display-stage variation
  reduced the frame gain to ~1.3×; both results are archived, without a FPS promise.
  See [measurements, raw data and commands](benchmarks/p0-p1/README.md).
- All eight preset images and primitive-test counts agree bit-for-bit with the
  pre-change source at fixed seed/three spp. ParallelTraceTest covers scheduling,
  uneven edges, edits, pause/restart, close and interrupted recovery. Twelve relevant
  transport/profiling/input/console suites pass. AWT presentation and real event
  latency remain live-only measurements; asynchronous generation metrics arrive in P2.

## Renderer performance P2 `[done]`

- Live rendering now captures state briefly, then traces on one background coordinator
  while the display loop paints an immutable completed image. Camera/transport edits
  advance generations; transport edits stop obsolete tile scheduling. Camera motion
  lets the active pass finish as a tagged preview before capturing the latest camera.
  Preview samples do not count toward a different camera; no camera backlog is queued.
  Input, resampling, tone mapping and console painting do not hold the state monitor.
- Tiles stage mean updates and commit only complete passes. Cancellation preserves
  previously committed spp. Cooperative 16.67 ms tile waves resume unfinished work;
  individual long tiles can overrun. Pause/target edits and focus/scene suspension
  invalidate active requests; exposure retains samples. F3/F4/footer show image age,
  requested/shown generations, first-image latency, cancellation waste and max tile.
  Display FPS/timings are separate from completed-job trace CPU/ray/JFR statistics.
- Initial P2 synthetic AWT native-glass input, 100 events, fourteen workers/tile 32: p95 handler
  time 0.212 ms versus 103.825 ms with the emulated P1 full-frame monitor; synthetic
  dispatch-through-handler p95 0.421 ms versus 103.934 ms. The final settled generation
  reached the display raster after 127.22 ms, excluding AWT presentation. 81 jobs
  cancelled, 6,804,480 obsolete primary paths; max tile 29.30 ms. This policy starved
  publication during sustained motion and was corrected below. Fresh full-resolution
  samples remain far below 60 Hz.
- Blocking measurement remains available for fixed-spp comparisons: native glass
  trace median 63.78 ms / frame 81.08 ms, compared with the earlier P1 66.57 / 84.05 ms
  (separate short runs, no claimed throughput improvement). At 400 square the longer
  run is 9.85 ms trace / 93.81 ms frame, similar to P1's 9.58 / 94.88 ms longer repeat.
  Display conversion and recurring buffers remain P3; P2 adds bounded sensor copies
  and a double mean staging buffer. See [raw results and commands](benchmarks/p2/README.md).
- Thirteen relevant suites pass, covering all transport presets, exact worker/tile
  agreement, cancellation recovery, rapid edits, immutable buffers, console commands,
  focus/scene switches and lifecycle. Automated checks open no windows. Manual GUI
  checks and physical input-to-screen latency remain unverified.

- Continuous-motion correction: cancelling every camera edit prevented any pass
  from finishing while display FPS stayed stable. Camera-only edits now finish the
  active pass as a coherent preview, stop the batch, and capture the newest camera
  next. Transport/resolution/restart edits and suspension remain cancellable. Native
  glass with 100 synthetic events displayed 0 new camera images with the old policy
  versus 15 with previews; p95 input handling remained 0.271 ms. Snapshot age includes
  trace time. A new sustained-motion regression checks preview correctness, separate
  latest-camera spp and exact settled convergence. Results are in the P2 archive.

## Renderer performance P3 `[done]`

- Fused RGB box filtering/exposure/tone mapping/sRGB encoding writes reusable byte
  storage. Cached indices/weights rebuild on dimension changes; equal grids bypass
  filtering and integer enlargement encodes each source pixel once. Unchanged key/
  spp/exposure redisplays the encoded cache before UI/overlay drawing, including
  re-entry after the editor. Reference arithmetic and linear-space filtering remain.
- Three leased publication slots bound memory and reuse sensor RGB copies without
  overwriting an active display reader. Allocation/copy/conversion runs outside the
  input lock. AWT opaque packing is faster; partial alpha remains exact for the editor.
- Glass headless complete-frame median: quarter 20.66 → 6.81 ms, 400² 52.76 →
  28.18 ms, native 82.17 → 75.97 ms. Whole-frame allocation drops ~16.24 → 0.64 MB.
  Isolated conversion and cached restoration allocate zero bytes after warmup.
  Native tracing remains ~65 ms; no 60-fresh-native-FPS claim. Synthetic motion still
  displays new previews (18 before / 19 after), with no cancellation waste.
- Fifteen suites pass, including exact display/reference bytes, exhaustive alpha
  packing, leased-buffer stability/reuse, sustained-motion and UI/scene checks. An
  outdated console preview fixture now submits a nonempty command. Window-free
  composed previews were inspected; actual GUI/presentation latency remains manual.
  See [results, ownership details and commands](benchmarks/p3/README.md).

## F3 fresh image measurements `[done]`

- F3 now leads with distinct completed-image FPS over two seconds instead of
  repeated cached display updates. Updates are timestamped after the whole pipeline;
  snapshot age includes conversion and AWT submission in the live app. Update
  interval mean/p95, current image hold and age expose stutter and camera lag.
- The live viewport graph plots fresh-image intervals, the ongoing hold and a
  60 FPS reference. Display-loop FPS/stage means remain secondary, explicitly
  separate from last background trace cost. Dimension/preset changes reset fresh
  history; scene exit clears it, and pause/complete states remain visible.
- Synthetic clock tests distinguish 100 display FPS from 10 fresh-image FPS and
  cover stalls, pause, resolution changes, scene exit and end-of-pipeline age.
  Four profiling/input/async suites pass; F3/F4 previews were inspected. A paced,
  window-free motion check reports fresh rates 10.5 / 30.0 / 44.0 FPS at native /
  half / quarter with distinct display rates. These are synthetic workload results,
  not user-run FPS or physical presentation measurements; see the P3 archive.

## Renderer performance P4 `[done]`

- Opt-in interactive resolution selects a smaller grid during actual camera
  movement using smoothed completed-image cost. Requested resolution remains
  the maximum/stationary quality. Commands expose enable/off, update budget and
  minimum bounds; footer/F3 show shown/requested dimensions. Depth and FOV stay fixed.
- Complete moving jobs publish before automatic grid changes. A cooldown and
  fresh-feedback hysteresis limit oscillation; no repeated camera cancellation.
  After 350 ms quiet, restore requested quality with independent accumulation.
  Snapshot/grid allocation remains outside the input lock; publication slots stay
  bounded. Fixed mode is the default; pause retains its grid.
- Repeatable glass native camera path: fixed 9.5 fresh FPS versus automatic
  112–114 at quarter dimensions. Update p95 119–123 → 11–12 ms; snapshot age p95
  145–152 → 22 ms. Automatic mode made two warmup changes, held quarter during
  measured motion, and displayed a current full-grid image 499–650 ms after stop.
  All runs reached full-grid 8 spp. This is reduced spatial quality during motion,
  measured without window/AWT presentation, not a native tracing throughput gain.
- Nine relevant suites pass, including exact moving/settled reference pixels,
  cancellation/ownership, all-preset worker scheduling, input/console/profiling and
  footer rendering. F3 composed preview inspected; physical GUI latency remains
  manual. See [P4 results and commands](benchmarks/p4/README.md).

## Renderer performance P5 `[done]`

- Optional `view temporal on/off` reprojects validated opaque diffuse history into
  camera previews. Raw estimator keys, seeded samples and spp remain unchanged;
  off immediately restores raw display. F3 reports reused/eligible pixels, mean
  blend confidence, separate reconstruction cost and bounded history payload.
- Center/corner guides, sampled-hit disagreement, neighborhood identity, normal
  and world-depth checks reject disocclusion, seams and mixed surfaces. Cuts,
  edits, grid changes and expired history reset reuse. Mirrors/glass/emission and
  inside-media cameras stay raw; volume scenes use whole-frame raw fallback.
  Local clamping and exponentially fading capped history limit trails without claiming perfect
  correspondence or unbiased reconstructed output.
- Guides use trace tiles; reconstruction uses disjoint rows on the same bounded
  worker pool. Both run outside the input lock. Partial hard cancellation drops
  history, and raw/reconstructed publications obey the existing three-slot leases.
  Two histories cost 72 bytes/pixel; maximum additional payload including guides
  and reconstructed publications is 128 bytes/pixel (158.20 MiB at native).
- In the initial static-grain run at 160×100, one-spp linear error falls about 45% in bounce-room, 14% in glass,
  and 7% in the diffuse area-light room. At a soft equal 16.67 ms processing budget,
  bounce-room error falls about 23%; glass/diffuse worsen about 10%/24%. Rejected
  pixels and camera-cut frames match raw exactly. Native one-spp processing cost
  roughly doubles with history enabled. Keep the feature off by default; this is
  a quality option with substantial cost, not a general FPS improvement.
- Fourteen suites pass, including exact raw streams/counters across transport
  presets, parallel/scalar reconstruction, cancellation, thin geometry, toggles,
  lease immutability, immediate raw raster restoration and readable F3 metadata.
  Composed images were inspected; physical GUI motion/scanout remains manual.
  A P4/disabled-P5 check shows no consistent default-mode slowdown. See
  [P5 results and reproduction commands](benchmarks/p5/README.md).
- Follow-up: camera position/sensor now deterministically scramble the configured
  seed, so sample-zero grain changes during motion instead of sticking to screen
  pixels. Identical captured views reproduce independently of camera visit order,
  worker scheduling and batch sizes. Stationary refinement and the depth-zero
  point-light diagnostic remain intact. Twelve suites were rechecked, including
  the new CameraSamplingTest. The rerun shows one-spp error reductions of 65% in
  bounce-room and 26% in glass; soft equal-budget reductions were 18% and 8%.
  Diffuse-room equal-budget error rose about 4%. Reprojection/clamping remain
  unchanged, so this is a sampling experiment rather than proof of artifact-free
  history. Original measurements remain labeled separately in the P5 archive.
- Follow-up: removed the hard eight-transition history cutoff that synchronized
  wall noise into a nine-image pulse (about 2.5–3 Hz at 23–27 fresh FPS). Capped
  blending now continuously fades old contributions with at least 20% fresh
  radiance. Surface/cut/edit/expiry rejection remains. Regression tests cover
  96 forward/backward updates and 32-step gradual decay; five affected suites pass.
  Dropping the age arrays also reduces history payload by two bytes per pixel.

## Performance P5.1 — cheaper temporal correspondence

- Shared exact pixel-corner visibility across adjacent pixels in each tile. Two rolling
  cache rows per worker replace repeated corner queries; center/sampled-hit checks,
  correspondence rejection, fading and immutable publication ownership remain.
- Guide rays fall 48–57% on surface motion benchmarks. One-spp reconstructed error
  changes by less than 0.5%; raw streams/counts, rejected pixels and cut output remain
  exact. Native processing improves in these runs; quarter bounce-room is noisy.
  Temporal remains off by default and more expensive than raw. Results and limits:
  [P5.1 measurements](benchmarks/p5.1/README.md).
- Six relevant suites pass. New tests cover exact guide agreement across worker/tile
  configurations, query reduction, thin silhouettes and tile/row cache boundaries.
  Cache storage is reused and released on temporal-off/volume fallback. No full-image
  copy removal or automatic quality bypass was adopted without further evidence.

## Performance P5.2 — optional temporal motion tracing budget

- Added `view temporal budget on/off`, `samples <1..8>` and `scale <0.25..1>`.
  The independent mode is off by default and caps moving batches, optionally grids,
  while retaining configured settings for restoration after 350 ms without motion.
  History preflight restores requested batch maxima for cuts/edits/missing/expired
  history. P4 still caps moving batches at one and honors its own minimum bounds.
- Three independent-seed quality/cost comparisons show bounce-room one-spp history
  matching or improving average four-spp raw error at less processing time. Glass
  does not meet the same quality target. Lower grids trade spatial detail for work.
  Fresh-image measurements compare the same elapsed camera path against P4 alone;
  results and limitations are in [P5.2 measurements](benchmarks/p5.2/README.md).
- Eight suites pass, including motion/restoration/reference accumulation,
  commands/help, history preflight, cancellation, leases, input and F3 metadata.
  F3 preview was inspected. Actual/chosen/requested batch counts are separate from
  history blend; raw estimator and depth remain unchanged. Human GUI motion checks
  remain separate from window-free software-submission measurements.

## C1–C3 — Camera models and controls `[implemented; user accepted]`

- One immutable camera description supplies pinhole, orthographic, and thin-lens rays.
  Existing raw float-bit image fixtures and seed streams remain exact; zero aperture
  shares pinhole identity/output. Projection, guides, keys and motion policy use the model.
- Console mode/FOV/height/aperture/focus/status/reset controls, remembered settings,
  first-entry scale matching, and camera-only glass-inside reset are implemented.
  Center focus queries prepared geometry outside the state monitor and rejects stale edits.
- Per-origin reusable medium scratch supports orthographic/lens origins across boundaries.
  Orthographic temporal geometry is implemented; finite aperture falls back to raw output
  with requested settings preserved, budget restoration and stale-history protection.
- Eighteen affected suites pass, including three camera suites. Analytic/statistical checks
  cover focus rays, uniform disk sampling, exposure, depth-zero blur, medium membership,
  exact compatibility, deterministic parallel/cancellation paths, previews and P4 transitions.
  Four generated previews were visually inspected. Matching pinhole benchmark trace median
  was 22.53 ms before and 22.41 ms after; trace allocation remained approximately 1.1 KiB/job.
- The user reported that the viewport looked good. Commands, previews, measurements
  and limitations: [camera verification](benchmarks/camera/README.md). Specification:
  [CameraRequirements.md](CameraRequirements.md).

## C4–C5 — Independent optics and parameterized focus `[implemented; user accepted]`

- Independent authoritative projection and active aperture, preserved mode shortcuts and remembered
  optics; translated orthographic pupils preserve mean framing while producing finite depth of field.
- Separate source/resolver/query/controller boundaries; normalized screen-point selection, manual
  reciprocal-distance pulls, continuous autofocus, smooth/linear curves, duration, subject dwell and
  accepted-depth tolerance. Exact endpoints allow stationary progressive convergence.
- Query-owned prepared geometry/BVH cache; one in-flight query and one completion mailbox, capped
  at 20Hz. Scene/control/source revisions, bounded age and reference reprojection reject stale results.
  Live one-shot focus queries are asynchronous and report eventual success/failure through status.
- Suspension distinguishes scene activation, window focus and console navigation. F3 shows focus
  policy/source/actual/target and shown captured optics. Zero aperture autofocus preserves estimator
  and temporal identity; finite focus updates retain coherent async previews and P4 settling.
- Original pinhole fixtures plus pre-C4 finite-perspective/zero-orthographic raw fixtures remain exact.
  Twenty-three affected suites pass; numeric optics, fake-clock replay, latched query lifecycle and
  live target-complete autofocus checks supplement existing transport/temporal regressions.
- Warm cached query medians: playground 0.0025ms, mesh-room 0.0026ms; 1,000 measured queries each,
  zero additional preparations and zero observations older than 100ms in the cost run. These are
  small window-free query measurements, not GUI latency or tracking-lag promises. Details and
  manual commands: [camera verification](benchmarks/camera/README.md).

## Engine E3–E4 — scene documents, persistence, meshes and queries `[implemented]`

- Stable UUID graph/assets, duplicate-label-safe identity, atomic single-writer transactions,
  restricted validated hierarchy transforms, subtree duplicate/delete, shared/unique assets,
  and optional bounded undo/redo with stale-history invalidation.
- Strict embedded-only `.scene.xml` V1 with hardened JDK parsing, symmetric file/structure
  limits, complete pre-publication validation, reload-validated atomic saves, real point-light
  and camera components, and fresh runtime revisions after load/undo.
- General open `TriangleMesh` plus explicit closed-solid validation. Public session-independent
  queries return node/asset/source-face identity, robust normalized world distance, positions,
  normals and barycentrics with deterministic ties; camera screen rays share renderer math.
- Engine boundary, 47 focused B/regression tests, fresh-JVM seeded roundtrip and independent
  headless document demo pass. Evidence: [scene-document verification](benchmarks/engine/README.md).
  The E5 editor remains the second interactive document consumer and human picking proof.

## Engine E5 — scene authoring application `[implemented; human QA pending]`

- Separate native Swing executable with hierarchy, two asynchronous camera views, exact
  publication-bound picking, numeric transform/material/light/camera inspectors, shared versus
  unique assets, undo/redo, guarded async XML files, and a command panel using the same edits.
- `editor-check.ps1` compiles the boundary and full product, then runs controller/file/history/
  picking checks plus document, persistence, query, and session regressions. It paints the real
  panel and two engine views to `out/editor-check/scene-editor-preview.png` without opening a window.
- Manual launch and EXPECT card: [editor verification](benchmarks/editor/README.md). Native
  interaction remains pending; E6 mesh editing is tracked separately below.

## Engine E6 — editable mesh modelling `[implemented; human GUI QA pending]`

- This engine roadmap milestone is distinct from the legacy **M6 — RayTracer** milestone above.
  Bounded immutable vertex/edge/polygon topology, stable source-face IDs, deterministic fan
  triangulation, primitive conversion, shared or unique asset edits, positive face extrusion,
  V2 editable XML and V1 read compatibility are implemented and specified in
  [EngineRequirements.md](EngineRequirements.md).
- The editor adds Object/Face selection, exact-painted asynchronous face picking, two-view
  selected-face boundaries, explicit conversion, Make geometry unique, one-step extrusion,
  undo/redo reconciliation, command parity and save/reopen behavior. Face mode disables object
  gizmos, and stale view/mode completions cannot replace current selection.
- Windows PowerShell 5.1 `input-check.ps1 -OutputDirectory out/e6-f2-final` passed the complete
  editor/mesh/session gate and shared legacy input regression; its logs were audited for hidden
  harness failures. Window-free evidence is `scene-editor-mesh-extruded-preview.png`. The native
  workflow remains for human GUI acceptance using the [editor QA card](benchmarks/editor/README.md).

## Engine E7 G1 — canonical geometry and renderer preparation `[implemented]`

- `GeometryAsset` and public `SceneInstance` now use only `AnalyticSphere` or immutable
  `PolygonMesh`; boxes, planes and triangle imports are polygon topology. The old public
  list-backed mesh/primitive model is removed. Renderer/query primitives, indexed fans,
  face mapping and acceleration inputs are derived lazily outside document publication.
- Cheap validated capabilities preserve skewed parallelogram emission, exact canonical
  local-box volume scattering, analytic sphere/ellipsoid transport and closed-mesh glass.
  G1 introduced V3 canonical sphere/polygon assets; strict V1/V2 reads migrate representable repeated
  face groups and reject disconnected, holed, folded, pinched or over-budget input.
- Windows PowerShell 5.1 `input-check.ps1 -OutputDirectory out/e7-g1-final` covers the engine
  boundary, editor and input regressions plus affected material/mesh/volume suites. Exact raw
  RGB hashes for analytic sphere, plane, canonical box and mesh detail 4/12/64 match base
  `404090a`. See [EngineRequirements.md](EngineRequirements.md) and [EngineApi.md](EngineApi.md).

## Engine E7 G2 — direct polygon element editing `[implemented; human GUI QA pending]`

- The native scene editor now exposes Object, Vertex, Edge and Face modes directly for every
  polygon asset. Stable vertex/edge/face selections, deterministic bounded x-ray picking,
  two-view highlights, numeric local deltas and grouped live vertex/edge handles share the
  same transaction and undo path. Entering a mesh mode never converts or clones geometry.
- Shared edits retain the geometry ID and affect all references; Make geometry unique remaps
  only the selected node. History, load and asset replacement reconcile stable IDs, while stale
  cross-view completions and picks during active gestures are rejected against the exact painted
  semantic context. Invalid drag candidates preserve the last valid preview and an invalid
  release restores the baseline.
- The PowerShell 5.1 editor/input gate and a window-free two-view journey cover direct box/plane
  editing, shared/unique behavior, undo, invalid edits, stale picks and coherent camera motion.
  Evidence is `scene-editor-elements-preview.png`; native-window interaction remains pending
  using the [editor QA card](benchmarks/editor/README.md). G3 retains analytic parameter editing
  and explicit bounded sphere approximation.

## Engine E7 G3 — analytic parameters and explicit approximation `[implemented; human GUI QA pending]`

- The Mesh inspector exposes shared asset-local center/radius editing for analytic spheres.
  Explicit approximation requires detail 4..64, preserves geometry identity, references,
  transforms and materials, and explains that polygon replacement loses analytic parameters.
  Undo restores the exact sphere; Make unique remains a separate action.
- The generic conversion API, button and command are removed. `sphere set` and
  `mesh approximate <detail>` use the same validated controller transactions as the UI.
  Incompatible scattering rejects without changing geometry, history, selection, or dirty state.
- PowerShell 5.1 editor/input gates cover shared replacement, chosen tessellation, no-op and
  failure history, stale picks, v4 analytic/polygon reopen, two-view rendering, and legacy
  transport/input regression. Window-free evidence is `scene-editor-analytic-preview.png`
  and `scene-editor-approximation-preview.png`; native QA remains pending using the
  [editor QA card](benchmarks/editor/README.md).

## Engine E7 H1 — deformable polygon faces `[implemented]`

- Polygon faces retain stable IDs while allowing validated nonplanar first-vertex fans.
  Double origin-relative validation rejects degenerate, folded, concave and self-crossing
  boundaries; area-weighted normals, source-face mapping and deterministic fan order remain
  coherent for rendering and spatial queries.
- Vertex, canonical-edge and face-boundary translations rebuild the shared asset atomically.
  Closed connectivity, opposite winding and finite positive volume remain mandatory; emitter
  and canonical-box scattering capabilities reject incompatible deformation without changing
  the snapshot or history.
- Scene persistence writes V4 for deformable polygon topology and reads V1 through V4. V1
  grouped triangles and V2/V3 polygon topology retain their earlier planar validation.
  `EditableMeshTest` covers every canonical-box vertex, edge and face moved by 0.1 on every
  axis; `ScenePersistenceTest` covers V4 warped round trips, strict legacy reads and stable IDs.
  Windows PowerShell 5.1 full-gate evidence is recorded in `out/e7-h1-final`.

## Engine E7 H2 — visible element modes and face movement `[implemented; human GUI QA pending]`

- Face selection now supports numeric asset-local translation and live translate handles in both
  views. The gesture captures the stable face and immutable mesh baseline, previews validated
  revisions, commits once, and restores the baseline on Escape or an invalid release.
- Vertex, Edge and Face modes show deterministic x-ray cues before selection, independently of
  Wireframe. Cue projection shares the coherent exact-camera display bundle; face centers remain
  visual labels while face selection stays depth-visible. A separate 10,000 point/segment display
  cap does not reduce the existing 100,000-candidate click budget or selected highlights.
- Windows PowerShell 5.1 `input-check.ps1 -OutputDirectory out/e7-h2-final` covers the controller,
  exact painted-context two-view journey, bounded overlay policy, persistence/transport regressions
  and shared input routing. Window-free evidence is `scene-editor-vertex-mode-preview.png`,
  `scene-editor-edge-mode-preview.png`, and `scene-editor-face-mode-preview.png`; native QA remains
  pending using the [editor QA card](benchmarks/editor/README.md).

## Sample game G1 — colored cube exploration `[implemented; human GUI QA pending]`

- `game.SampleGameMain` opens a separate window with 128 unit cubes and existing point lights.
  `game.ps1` builds and launches it against the independent engine; no engine changes were needed.
  Game-owned block coordinates/types derive shared-geometry engine instances.
- Click captures mouse look; Escape releases it. WASD follows horizontal heading, Space/Ctrl
  moves vertically, and normalized elapsed-time flight runs at three world units per second.
  R resets the camera. Focus loss clears input, releases the pointer and suspends rendering;
  return resumes rendering with the pointer free. Resize retains vertical framing. Close releases
  the render session, update worker and repaint timer.
- `game-check.ps1` passed four headless cases covering navigation, framing, lifecycle, continuous
  movement publications and immutable display copies. `verify.ps1` passed on 2026-10-09, retaining
  its existing Caps-on synthetic-console skip. Native mouse capture and window focus QA remain
  pending using the [game QA guide](benchmarks/game/README.md), which includes test-generated previews.

## Sample game G2 — textured blocks `[implemented; human GUI QA pending]`

- Original grass/dirt, stone and wood PNGs under `assets/game/` replace gallery colors.
  The elevated grass block exposes a dirt underside; a rotated/scaled wood block demonstrates
  object-local texture attachment. The app decodes assets and reports failures by path.
- Immutable `Texture2D` and `CubeTextures` bind separate top/bottom/side images to diffuse
  canonical cubes. The tracer samples nearest texels in local coordinates without per-ray
  allocations and applies linear reflectance to point/area light and diffuse continuation.
  Material copies retain bindings; unsupported geometry/kinds fail. `SceneFiles.save` rejects
  textured documents before replacing their destination.
- `game-check.ps1` passed five application tests and seven texture tests. `verify.ps1`, plus
  progressive-path and rough-lighting regressions, passed on 2026-10-09. The readability baseline
  was pruned without expansion. The [game QA guide](benchmarks/game/README.md) includes generated
  close-up previews and pending native-window/texture inspection steps.

## Notes / Decisions

- Viewport input: simultaneous physical held keys now drive per-frame camera movement,
  replacing OS key-repeat movement. Space/Ctrl replace E/Q; WASD follows camera rotation,
  diagonal speed is normalized, and button-free mouse movement adds clamped look. Console,
  reset, focus loss and both scene-switch paths clear held input. CameraControlsTest and
  expanded ViewportKeyBindingsTest cover timing, releases, modifiers, rotation and cleanup.

- `float` geometry/transport (consistent with Vec3); progressive RGB means use `double`.
- Epsilon for intersection: `1e-4f`. Tunable later.
- Sealed interfaces for `SceneObject` so the compiler enforces exhaustive handling if/when intersection logic needs branching by type.
- Rect's normal direction = `edge1 × edge2` normalized. Construct sensor rects with edges oriented so the normal faces the scene (sensing side).


## Sample game G3 — sunlight and sky `[implemented; human GUI QA pending]`

- Added immutable `engine.lights.DirectionalLight` and `engine.Sky` values. Sun direction points toward the source; strength is perpendicular-surface irradiance, with shadow visibility extending to infinity. Sky radiance is evaluated on misses and weighted by path throughput, including diffuse continuation rays.
- `WorldSnapshot`, render-session state, captured worker snapshots, and transport identity carry the sky and sun. Existing constructors default to black sky. New lighting explicitly rejects mirror, dielectric, and volume instances at the world boundary; established point/area-light transport remains available in worlds without the new features.
- The standalone gallery now uses a fixed noon sun and blue gradient sky, two continuation bounces, and progressive stationary refinement. Escape reveals Morning, Noon, Evening, and Sky on/off buttons. Camera reset preserves lighting. No constant ambient term or time-of-day simulation was added.
- `./game-check.ps1` passes the existing game/texture suites plus seven `engine.SunSkyTest` cases and two `game.GameLightingTest` cases. Coverage includes distant occlusion, distance-independent lighting, texture modulation, sky directions/energy, immutable leases, edit/undo invalidation, worker/batch agreement, released-pointer buttons, and refinement.
- `./verify.ps1`, advanced transport regression suites, and `./style-test.ps1` pass. Headless previews and the pending human sunlight/sky test are in [the game QA guide](benchmarks/game/README.md). G4 subsequently adds the default landscape and headless responsiveness evidence below.

## Sample game G4 — landscape exploration `[implemented; human GUI QA pending]`

- `./game.ps1` launches a 313-block, 12 by 12 landscape with stepped terrain, an opaque tree canopy, stone outcrop, and wood/stone shelter. `./game.ps1 -Scene gallery` retains the earlier visual checks. Game-owned coordinates/types remain authoritative; all blocks use shared cube geometry and materials.
- The application enables the existing P4 adaptive-resolution policy, calls the public session presentation hook for timing feedback, and provides a released-pointer Adaptive on/off button. The overlay distinguishes requested/displayed grids and spp. Stopping restores requested quality and progressive refinement. Reset retains lighting/adaptation; depth stays two. No engine implementation changes or new acceleration were needed.
- `./game-check.ps1` passes 25 headless cases, including four new landscape cases. `./verify.ps1`, `./style-test.ps1`, and additional interactive-resolution, responsive-tracing, and public-session regressions pass. The existing Caps-on synthetic-console skip remains. No readability baseline expansion was introduced.
- `benchmarks/game/camera-route.csv` freezes an elapsed-time 30-second route through spawn, tree, shelter, and open ground, with the final five seconds stationary. `./game-benchmark.ps1` runs one warmup and three sequential measured replays; `-NoAdaptive` enables a fixed-grid comparison. The runner counts every copied-image publication, filters generations predating each restart, includes initial/final movement holds, and checks full-grid stationary refinement separately.
- On 2026-10-09, an i5-13600KF with OpenJDK 23.0.1 and 14 workers passed all three adaptive-on replays: p95 intervals 34.733, 34.258, and 34.334 ms against the 200 ms target. Full-grid images returned 0.431–0.483 seconds after stopping and refined from 1 to 60–64 spp. Depth 2, seed 1, and a 320 by 200 requested grid stayed fixed. Raw metadata and publication CSVs are in `benchmarks/game/g4-adaptive/`; fixed-grid and live-window performance are not claimed. Native-window capture/focus delivery and a human landscape playthrough remain pending. See [the game QA guide](benchmarks/game/README.md) for evidence, replay commands, previews, and user tests. Object-level acceleration remains deferred under P7 because G4's measured target passed using the existing flat object scan.