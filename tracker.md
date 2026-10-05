# Ray Tracing Viewport — Build Tracker

Bottom-up, TDD. Each milestone lands with passing tests before the next starts.

The next renderer requirements and six playable implementation phases are in
[RenderingRequirements.md](RenderingRequirements.md). All six phases are implemented.

The proposed performance roadmap is in [PerformanceRequirements.md](PerformanceRequirements.md).
It ranks CPU optimization phases by estimated practical impact, with measurement and
completion gates, plus separate GPU and native backend evaluations. All performance
phases are pending; begin with P0 through P3.

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

Sealed interface `scenes.viewport.objects.SceneObject` with `Optional<Intersection> intersect(Ray)`.

- `Tri(a, b, c)` — Möller–Trumbore.
- `Rect(origin, edge1, edge2)` — same algorithm, bounds `[0,1] × [0,1]`. `uv(point)` maps a hit point to `(u,v) ∈ [0,1]²`.

Tests: front/back hit, miss outside bounds, parallel ray, t > 0 only, normal direction.

## M4 — Light + PointLight `[done]` (6/6 tests pass)

Interface `scenes.viewport.lights.Light` with `List<Ray> sample(int n, Random)`.

`PointLight(position)` emits uniformly on the unit sphere.

Tests: sample count == N; origins all at position; directions are unit length; statistical-ish coverage (centroid of many samples ≈ origin).

## M5 — Viewport state `[done]`

`scenes.viewport.ViewportState` holds: `List<Light>`, `List<SceneObject>`, camera sensor `Rect`, settings (samplesPerLight, maxBounces, pixel accumulator dims).

## M6 — RayTracer `[done]` (7/7 tests pass)

`scenes.viewport.RayTracer` orchestrates one frame:
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
