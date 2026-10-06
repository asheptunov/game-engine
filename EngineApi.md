# Engine API guide

E1–E2 expose the existing CPU renderer without requiring the playground, AWT, console,
scene registry, input bindings, or dependency-injection container. Public reusable code
lives in `src/engine/`; the playground remains in `src/scenes/viewport/`.

## Inputs and ownership

- `WorldSnapshot` is an immutable copy of scene instances, legacy primitives, and lights.
  Its nonnegative revision distinguishes edit/undo cycles even when content compares equal.
  The new session boundary accepts immutable `PointLight` values only; unsupported custom
  `Light` implementations are rejected before publication. The legacy `Light` interface
  remains for the reference tracer until a later milestone adds more frozen light values.
- `RenderView` contains one immutable `Camera` and a 64..1600 pixel sensor grid.
- `RenderSettings` contains transport and scheduling choices. `restartRevision` explicitly
  restarts raw accumulation; `temporalRevision` explicitly invalidates presentation history.
  Interactive minimum dimensions are either both zero (the aspect-fitted default) or both
  64..1600.
- `RenderSession` owns mutable accumulation, history, in-flight work, cancellation state,
  and its bounded three-slot publication pool. Sessions may share immutable world values,
  but never mutable accumulation or leases.
- `RenderImage` is an explicit read-only, single-owner lease. Its derived `RgbPixels` view
  exposes channel reads and copying into a caller-owned reusable array, never the mutable
  publication arrays. Keep the image and pixel view on one thread; reads or copies and
  `close` must not overlap. Close every image promptly, normally with try-with-resources,
  or the bounded publisher can run out of writable slots.

The playground copies each fresh leased publication into one display-owned reusable array
before conversion; cached redraws do not copy. On the reference machine, the window-free
`DisplayPipelineBenchmark` measured the leased 1600x1000 conversion at 28.311 ms median
versus 25.701 ms from a raw array (2.610 ms copy overhead), with zero steady-state bytes per
frame. At 1440x900 it measured 13.579 vs 11.562 ms (2.017 ms). The run is recorded in
`out/engine-extraction/pixel-copy.log`.

Validate or construct all three immutable inputs before `RenderSession.update`. The update
then publishes scene, view, and settings together under the session monitor. Geometry
preparation, tracing, reconstruction, and image conversion remain outside application edit
locks.

## Minimal consumer

Construct public values directly, open a session, call `request`, and poll an acquired image
until its generation equals `RenderProgress.requestedGeneration`. The complete executable in
`src/examples/headless/HeadlessEngineDemo.java` writes a PNG without creating a window.

```powershell
./engine-build.ps1
& "C:/Users/andri/.jdks/openjdk-23.0.1/bin/java.exe" --enable-preview `
  '-Djava.awt.headless=true' -cp out/engine-build/classes `
  examples.headless.HeadlessEngineDemo out/engine-build/headless.png
```

`engine-build.ps1` compiles the engine and its small infrastructure dependencies first, with
no application sources on the command line. It rejects imports from `scenes`, `ui`, `di`,
`rendering`, or `java.awt` in engine sources, then compiles the headless consumer against
only those output classes. `engine-check.ps1` also compiles all sources/tests, runs the
focused extraction regressions, inspects harness logs, and runs the consumer.

## Updates and lifecycle

`FixedStepLoop` supplies a daemon fixed-step application clock independent of rendering.
An application may publish a newer immutable world/view/settings tuple while a trace is
pending. A transport edit cancels obsolete work between tiles; a camera-only edit may finish
as a coherent preview. Call `suspend` when rendering becomes inactive and `close` to stop the
coordinator. Closing a session is idempotent.

The playground uses `RenderEngine.openLegacySession(ViewportState, DirectRgbTracer)` so its
existing commands, autofocus policy, profiler hooks, and blocking benchmarks retain their
numeric behavior. This is the only transitional adapter. Remove it, `ViewportState`, and the
playground's direct tracer accessor after E3 document transactions replace command mutation
and all playground diagnostics consume public session metadata.

## Coordinates and geometry

Coordinates are right-handed: +X is right, +Y is up, and a standard camera looks toward +Z.
The camera plane spans `edge1` horizontally and `edge2` vertically; its forward vector is
`edge1 × edge2`. Units are application-defined but must be consistent for geometry, light
distance, absorption, focus, and camera movement. Triangle winding uses
`(b - a) × (c - a)`. Ray intersections report the ray parameter; engine-generated primary
directions are normalized, while transformed local rays deliberately remain unnormalized so
the same parameter measures world-ray distance. `IndexedMesh` still represents only a closed,
connected, outward-wound solid. E4 adds the distinct general open-surface mesh contract.

`SceneInstance` labels remain current renderer identity during this transitional milestone.
Stable node and asset IDs, hierarchy, transactions, persistence, and revisioned query
identity arrive in E3–E4.
