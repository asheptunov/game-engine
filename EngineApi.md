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
  modifiers, an immutable set of held non-modifier physical keys, normalized mode,
  coordinates, and precise wheel rotation. Mouse chords spell those keys as `key.<token>`;
  for example, `key.space+right+drag+viewport`. The earlier constructor without held keys
  remains available, and bare tokens such as the legacy `space` mode keep their old meaning.

AWT conversion lives outside the engine in `platform.awt.input.AwtInputAdapter`. Use
`AwtInputAdapter.key(event)` and
`AwtInputAdapter.mouse(MouseGesture.DRAG, event, "viewport")`; the latter derives the held
button from AWT down masks because drag events report `NOBUTTON`. Multiple held buttons are
rejected as ambiguous. The compatibility classes in `ui` delegate storage, parsing,
validation, and file I/O to this engine API while retaining their old `MouseEvent` actions
and buttonless drag lookup, so existing viewport and texture-editor property maps keep their
meaning.

The scene editor registers its concrete history, gesture, and view actions and loads these chords:

```properties
ctrl+z = history.undo
ctrl+shift+z = history.redo
escape = gesture.cancel
right+drag+viewport = view.orbit
middle+drag+viewport = view.orbit
key.space+right+drag+viewport = view.pan
key.space+middle+drag+viewport = view.pan
wheel+viewport = view.zoom
```

The editor's **Bindings…** dialog groups the six fixed actions under History and Navigation;
each action owns zero or more equivalent editable alternatives and stays visible when unbound.
It provides immediate validated Apply and atomic Save. A saved profile lives at the ignored path
`config/scene-editor-bindings.properties`; an absent or invalid profile leaves the shipped
defaults active, and an invalid file is preserved for repair.

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
the same parameter measures world-ray distance. `GeometryAsset` holds one of two canonical
values: `AnalyticSphere` or `PolygonMesh`. `SceneInstance` also accepts these descriptions;
geometry no longer exposes or implements a render-primitive `List`. `AnalyticSphere` retains
the exact sphere/nonuniformly-scaled ellipsoid kernel. `PolygonMesh.parallelogram`,
`PolygonMesh.unitBox`, `triangleSurface`, `triangleClosedSolid`, and `approximateSphere`
construct polygon topology directly. The parallelogram factory and v1/v2 plane migration
store the fourth corner reconstructed from the already-rounded adjacent corners. This is the
rectangle kernel's exact float representation, so extreme-coordinate sub-ULP cancellation
cannot remove emitter capability; ordinary legacy coordinates retain their exact values.

`PolygonMesh` stores immutable ordered vertices and polygon faces with stable,
nonnegative asset-local `long` IDs. Its canonical edges expose adjacent face IDs. A face has
3..1024 distinct referenced vertices and must be finite and nondegenerate. Its dominant-axis
projection must have a simple, strictly convex, consistently wound boundary; every triangle
in the stored first-vertex fan must be nondegenerate and consistently oriented. Faces may be
nonplanar. The area-weighted face normal is the normalized sum of the fan triangle area
vectors. Validation uses double, origin-relative differences and `1e-6` relative turn/area
tolerances, while preparation retains the established float render range. Fan triangulation
preserves listed order and maps every derived triangle back to the stable polygon face ID.
Surface meshes may have boundary edges;
closed solids additionally validate two opposite face windings per edge, one connected
component and finite positive signed volume over those same fans. Geometric self-intersection
between separate faces remains unsupported and undetected.

Renderer/query preparation is internal, immutable and lazy. It expands polygons to an
ordered fan with source-face mapping and indexed robust triangle intersections; analytic
spheres, a single validated parallelogram and the canonical local `-1..1` box retain their
established sphere, rectangle and flat-triangle kernels. Preparation happens when a renderer
or query builds its cache, outside document publication and Swing painting. Cheap canonical
capabilities validate materials atomically: any outward closed polygon mesh may be glass; an
emitter must be one open ordered parallelogram; scattering requires an analytic sphere or the
exact outward six-face canonical local box. Node transforms still provide rotated and scaled
boxes. Asset-coordinate box edits that no longer match this exact topology reject volume
materials rather than broadening containment support.

Triangle import preserves unique nonnegative source IDs. Repeated IDs reconstruct one face
only when their triangles form a connected, consistently wound, coplanar disk with a single
simple convex boundary and no interior/unused stable vertices. Boundary traversal starts at
the minimum vertex ID. Disconnected, holed, pinched, folded, overlapping, exhausted or
over-budget groups reject explicitly. `PolygonMesh.approximateSphere(sphere, detail)`
requires an explicit detail from 4 through 64; there is no generic conversion or hidden
default tessellation.
`extrude(faceId,distance)` accepts a finite positive distance,
moves a replacement cap along its listed-winding normal, retains the cap face ID, and
allocates cap vertices and side-quad face IDs from persisted monotonic counters. For a
validated solid the normal is outward. Side order is `vi, vj, vj', vi'`; repeat extrusion
remains manifold. Counter exhaustion rejects the edit.

`translateVertex(vertexId, localDelta)`,
`translateEdge(firstVertexId, secondVertexId, localDelta)`, and
`translateFace(faceId, localDelta)` return new, fully validated `PolygonMesh` values. Face
translation moves each distinct boundary vertex equally without splitting the face or changing
any vertex/face ID or allocation counter. Edge IDs are the canonical ascending endpoint pair.
Deltas are in the geometry asset's local coordinate space. The matching `SceneEdit` methods
replace that geometry ID, so every node sharing the asset observes the edit; Make geometry
unique is the explicit way to isolate one node. A zero face delta still requires a valid face
ID and otherwise returns the same mesh.

## Scene documents and transactions

`SceneDocument` is a single-writer atomic publisher. Create and edit it on one application
update thread; render/query workers may concurrently retain its immutable `SceneSnapshot`.
One `transact` callback may create, rename, transform, reparent, assign, duplicate or delete
nodes and create/edit/make-unique shared assets. `setAnalyticSphere`,
`approximateGeometryAsMesh`, and `extrudeFace` replace the same shared geometry ID within
one transaction. Approximation accepts only a current `AnalyticSphere` and requires its
explicit 4..64 detail. It validates every referencing node/material in the complete result
before one publication. Failure changes
neither snapshot nor revision, nested writes are rejected,
duplicate copies a whole subtree with fresh node UUIDs and fresh geometry/material asset
identities for every copied geometry node, delete
removes a subtree, and `reparentKeepingLocal` deliberately retains the local pose. Labels
may repeat and are never keys. Flattened renderer object/material identity uses UUID strings.
Generic consumers can still share assets explicitly by assigning the same asset IDs.

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

`SceneFiles.save/load/loadInto` uses strict UTF-8 `.scene.xml`. It reads versions 1 through 4
and writes version 4. V1 boxes/planes/triangles migrate to canonical polygon topology;
representable repeated triangle face IDs merge by the validated rules above. V2 ordered
polygon IDs, references, boundary intent and counters remain exact. V2 and V3 polygon inputs
retain their original planar-face validation; V4 admits the validated nonplanar fan semantics.
V3 and V4 accept only analytic `sphere` and canonical `polygon-mesh` assets and never persist
derived triangles. Geometry
is embedded and external references remain unsupported. Files preserve
UUIDs, graph order/references, labels, geometry, materials,
point lights and local cameras while excluding runtime revisions, render settings, jobs,
caches, accumulation and undo history. Loading fully validates before `loadInto` publishes.
Saving writes and forces a sibling temporary file, reload-validates it, and requires atomic
replacement; failure preserves the previous target.

The reader disables DTDs, entities, external DTD/schema access and XInclude. It rejects
unknown elements/attributes/version, non-finite values, duplicate/dangling IDs, cycles and
unsupported component combinations. Limits are 16 MiB, 250,000 XML elements, XML depth 32,
50,000 nodes, 16,000 assets of each kind, 250,000 vertices, 500,000 derived triangles,
1,500,000 face corners, 1024 vertices per polygon, bounded legacy grouping work, and graph depth 4096. Checked totals cover polygon corners and
derived triangles before topology construction. The writer enforces the same reloadable
structural limits.

Material compatibility validates atomically after approximation or polygon editing.
Closed polygon meshes can be dielectric. The canonical local box retains scattering only
while its exact six-face topology remains intact; extrusion then rejects it. Approximating
an analytic scattering sphere rejects the entire transaction because the resulting polygon
sphere is not a supported volume, leaving geometry, materials, history, and selection intact.

`SpatialQuery.prepare(snapshot)` builds immutable query state and never mutates a render
session. `nearest` scale-safely normalizes finite nonzero directions, so distance is in world
units. `RayHit` includes scene/node/geometry/revision, original primitive/source-face
identity, world/local positions, geometric normal, front-face state and triangle
barycentrics. Exact ties use node order then original primitive order even after BVH sorting.
Compare `RayHit.sceneRevision` with the current snapshot before applying a pick.
`pick(camera,u,v)` and `screenRay` use the renderer's exact reference camera ray, with
coordinates in 0..1 and `v=0` at the image bottom.

The scene editor has Object, Vertex, Edge and Face selection modes. A mode switch changes only
EDT-owned selection state: it does not publish a scene revision, dirty the document or create
history. Vertex and edge selection is x-ray screen-space selection over a deterministic bounded
candidate set. Vertices use an 8 logical-pixel radius; edges use a 6 logical-pixel segment
distance. Equal distances break by node UUID and then stable vertex or canonical edge IDs.
The view prepares world candidates with its immutable scene/asset selection token, projects
them asynchronously only when clicked, and accepts the result only if the painted revision,
mode, node and element selection still match. Camera-only motion reuses this camera-independent
token, preserving coherent lagging previews. Selected-element highlights and translate handles
are projected with the exact displayed camera and published with the image as one bundle.
Every active polygon mode also shows bounded x-ray editing cues before an element is selected:
Vertex shows points and supporting boundaries, Edge shows boundaries, and Face shows boundaries
plus one on-surface center per face. The display budget is 10,000 emitted points/segments,
prioritized by the selected node, node UUID, then stable element IDs; it is independent of the
100,000-candidate click budget. Selected highlights and handles are never consumed by the cue
budget. Face clicks remain depth-visible scene queries: the center is a visual cue, not a pick
shortcut. A selected face translates all of its boundary vertices from their asset-local baseline;
its move-handle pivot is the boundary centroid, distinct from the largest-fan-triangle cue center.

The editor gives every geometry-bearing node its own geometry and material asset identities.
Creation and subtree duplication allocate both identities per object. Loading a legacy file
separates aliases deterministically on the file worker before the immutable snapshot reaches
the EDT; the lowest node UUID retains each original ID, unreferenced assets remain, and the
normalized scene becomes the clean baseline. Generic `SceneFiles.load` still preserves
explicit sharing for other consumers. Expansion is rejected before publication when it would
exceed the centralized asset/topology/XML element or exact serialized-byte limits.

The Mesh inspector shows asset-local center/radius controls only for analytic spheres and
polygon element controls only for polygon assets. Applying sphere parameters replaces the
selected object's private asset once and creates at most one undo entry; identical values
publish nothing. **Approximate as mesh** uses the chosen 4..64 detail, explains the loss of
analytic parameters, and preserves the selected object, geometry ID, transform, and material.
Undo restores the exact analytic value. The Material inspector applies properties only to the
selected object; the editor has no asset-linking or Make unique workflow. Text commands use
`sphere set <center-x> <center-y> <center-z> <radius>` and
`mesh approximate <detail>`; the former generic `mesh convert` route is removed.

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
