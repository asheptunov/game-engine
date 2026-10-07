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
`rendering`, or `java.awt` in engine sources, then compiles both headless consumers against
only those output classes. `engine-check.ps1` also compiles all sources/tests, runs the
focused extraction regressions, inspects harness logs, and runs the consumer.

## Shared input bindings

`engine.input` provides platform-neutral immutable input values and serializable dispatch:

- Register named actions with `ActionRegistry<A>.register(String, A)`.
- Build `KeyBindings` for `Runnable` actions or `MouseBindings` for
  `Consumer<MouseInput>` actions. `handle` returns whether a registered action ran.
- Load UTF-8 properties with `BindingFiles.load(Path, bindings)`, then call
  `validate(owner)` to reject unresolved action ids. `BindingFiles.save` writes a stable,
  sorted representation that round-trips punctuation such as `+` and `=`.
- `KeyInput` uses a physical `KeyCode` plus exact Ctrl/Alt/Shift/Meta state. Letter identity
  does not depend on Caps Lock. `MouseInput` carries the held button for drag events, exact
  modifiers, normalized mode, coordinates, and precise wheel rotation.

AWT conversion lives outside the engine in `platform.awt.input.AwtInputAdapter`. Use
`AwtInputAdapter.key(event)` and
`AwtInputAdapter.mouse(MouseGesture.DRAG, event, "viewport")`; the latter derives the held
button from AWT down masks because drag events report `NOBUTTON`. Multiple held buttons are
rejected as ambiguous. The compatibility classes in `ui` delegate storage, parsing,
validation, and file I/O to this engine API while retaining their old `MouseEvent` actions
and buttonless drag lookup, so existing viewport and texture-editor property maps keep their
meaning.

An editor can register `undo`, `redo`, `orbit`, `pan`, and `zoom`, then load these chords:

```properties
ctrl+z = undo
ctrl+shift+z = redo
right+drag+viewport = orbit
shift+right+drag+viewport = pan
wheel+viewport = zoom
```

Run `./input-check.ps1` for the engine-only boundary, all-source compilation, shared input
tests, AWT normalization tests, and the existing UI/viewport/texture-editor binding suites.

## Updates and lifecycle

`FixedStepLoop` supplies a daemon fixed-step application clock independent of rendering.
An application may publish a newer immutable world/view/settings tuple while a trace is
pending. A transport edit cancels obsolete work between tiles; a camera-only edit may finish
as a coherent preview. Call `suspend` when rendering becomes inactive and `close` to stop the
coordinator. Closing a session is idempotent.

`RenderProgress.activeGeneration` identifies the exact generation captured by the one
in-flight job, or `-1` while idle. It can differ from `requestedGeneration` while a coherent
older-camera preview finishes, allowing presentation adapters to retain matching immutable
scene metadata without guessing from two timing-sensitive status reads.

The playground uses `RenderEngine.openLegacySession(ViewportState, DirectRgbTracer)` so its
existing commands, autofocus policy, profiler hooks, and blocking benchmarks retain their
numeric behavior. This is the only transitional adapter. Remove it, `ViewportState`, and the
playground's direct tracer accessor after a later application migration moves console
mutation to `SceneDocument`; E3–E4 do not claim that legacy mutation has already moved.

## Coordinates and geometry

Coordinates are right-handed: +X is right, +Y is up, and a standard camera looks toward +Z.
The camera plane spans `edge1` horizontally and `edge2` vertically; its forward vector is
`edge1 × edge2`. Units are application-defined but must be consistent for geometry, light
distance, absorption, focus, and camera movement. Triangle winding uses
`(b - a) × (c - a)`. Ray intersections report the ray parameter; engine-generated primary
directions are normalized, while transformed local rays deliberately remain unnormalized so
the same parameter measures world-ray distance. `IndexedMesh` represents a closed,
connected, outward-wound solid. `TriangleMesh.surface` accepts general open/disconnected
indexed triangles; `TriangleMesh.closedSolid` additionally validates a connected,
two-faces-per-edge, oppositely wound, positive-volume boundary. V1 implements position
indices, faceted geometric normals, and one material asset per node. Normals, UVs, and
material slots are outside V1 and are never silently discarded. Analytic `SphereGeometry`,
open `RectGeometry`, and canonical `BoxGeometry.UNIT` retain existing transport paths.

## Scene documents and transactions

`SceneDocument` is a single-writer atomic publisher. Create and edit it on one application
update thread; render/query workers may concurrently retain its immutable `SceneSnapshot`.
One `transact` callback may create, rename, transform, reparent, assign, duplicate or delete
nodes and create/edit/make-unique shared assets. It validates the complete result before one
publication. Failure changes neither snapshot nor revision, nested writes are rejected,
duplicate copies a whole subtree with fresh node UUIDs and shared asset references, delete
removes a subtree, and `reparentKeepingLocal` deliberately retains the local pose. Labels
may repeat and are never keys. Flattened renderer object/material identity uses UUID strings.

`UndoHistory` is an optional bounded authoring wrapper; games can call `SceneDocument`
directly without retaining history. Undo/redo republishes with fresh runtime epochs. If code
edits or replaces the document outside a history wrapper, the wrapper detects the revision
and clears stale stacks rather than overwriting unrelated work. Limit zero disables storage.
Group a multi-field UI Apply action into one `edit` callback.

Scene revision tracks every publication for stale picks. A separate transport revision feeds
`WorldSnapshot`, so labels and camera-only edits retain other sessions' accumulation; actual
flattened geometry/material/light/transform edits and their undo/redo get a fresh transport
epoch. Asset revisions are independent runtime-only epochs.

Parent transforms use positive TRS: scale, then X/Y/Z Euler rotation, then translation.
Composition accepts only finite positive-determinant matrices whose basis columns remain
orthogonal within relative `1e-5`. Shear, reflection, singular results, and camera world
scales nonuniform by more than `1e-5` are rejected before publication. Hierarchy depth is
bounded at 4096. Camera components store exact local `Camera` values; pixel dimensions and
`RenderSettings` remain outside the document.

## Scene files and spatial queries

`SceneFiles.save/load/loadInto` uses strict UTF-8 `.scene.xml` format version 1. Geometry is
embedded. External references produce an explicit unsupported-V1 error and are deferred to
a later schema. Files preserve UUIDs, graph order/references, labels, geometry, materials,
point lights and local cameras while excluding runtime revisions, render settings, jobs,
caches, accumulation and undo history. Loading fully validates before `loadInto` publishes.
Saving writes and forces a sibling temporary file, reload-validates it, and requires atomic
replacement; failure preserves the previous target.

The reader disables DTDs, entities, external DTD/schema access and XInclude. It rejects
unknown elements/attributes/version, non-finite values, duplicate/dangling IDs, cycles and
unsupported component combinations. Limits are 16 MiB, 250,000 XML elements, XML depth 32,
50,000 nodes, 16,000 assets of each kind, 250,000 vertices, 500,000 triangles and graph
depth 4096. The writer enforces the same reloadable structural limits.

`SpatialQuery.prepare(snapshot)` builds immutable query state and never mutates a render
session. `nearest` scale-safely normalizes finite nonzero directions, so distance is in world
units. `RayHit` includes scene/node/geometry/revision, original primitive/source-face
identity, world/local positions, geometric normal, front-face state and triangle
barycentrics. Exact ties use node order then original primitive order even after BVH sorting.
Compare `RayHit.sceneRevision` with the current snapshot before applying a pick.
`pick(camera,u,v)` and `screenRay` use the renderer's exact reference camera ray, with
coordinates in 0..1 and `v=0` at the image bottom.

The independent example builds open and closed meshes, saves/reloads, picks a source face,
and renders without application sources:

```powershell
./engine-build.ps1
& "C:/Users/andri/.jdks/openjdk-23.0.1/bin/java.exe" --enable-preview `
  '-Djava.awt.headless=true' -cp out/engine-build/classes `
  examples.headless.SceneDocumentDemo out/engine-build/demo.scene.xml out/engine-build/demo.png
```

Run `./scene-check.ps1` for the boundary build, E3/E4 hazards, fresh-process persistence,
document consumer, and focused mesh/dielectric/volume/parallel transport regression gate.
