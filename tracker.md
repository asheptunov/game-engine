# Ray Tracing Viewport — Build Tracker

Bottom-up, TDD. Each milestone lands with passing tests before the next starts.

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

## Notes / Decisions

- `float` throughout (consistent with existing Vec3, Camera).
- Epsilon for intersection: `1e-4f`. Tunable later.
- Sealed interfaces for `SceneObject` so the compiler enforces exhaustive handling if/when intersection logic needs branching by type.
- Rect's normal direction = `edge1 × edge2` normalized. Construct sensor rects with edges oriented so the normal faces the scene (sensing side).
