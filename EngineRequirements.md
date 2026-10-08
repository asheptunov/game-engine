# Engine and authoring tools specification

Status: E1–E3 complete; E4 engine work complete with editor-consumer proof in E5;
E5–E7 implemented on `codex/scene-editor` with native GUI acceptance pending.
Updated 2026-10-07.

## Intent

This project is a reusable 3D engine. The current viewport playground is one application
of that engine. A consumer must be able to build a different game, modelling application,
or headless renderer without adopting the playground's presets, console, navigation,
screen registry, or UI.

The application decides what exists and how it changes. The engine represents the
world, answers spatial queries, and renders it. Reusable authoring tools build on those
engine APIs. Game rules and modelling workflows belong to their applications.

The first delivery proves this boundary with the existing playground and a separate
headless application. Later deliveries add persistent scenes, scene editing, and mesh
editing. This document defines intended behavior, not features already implemented.

## Existing foundations and coupling

- `src/MainModule.java` wires the playground, texture editor, AWT presentation, and input.
- `src/scenes/Scene.java` describes application-screen lifecycle, not a 3D world.
- `src/scenes/viewport/Viewport.java` combines input, console, defaults, rendering,
  presentation, and lifecycle.
- `ViewportState` combines world contents, camera, rendering settings, focus policy,
  accumulation metadata, and snapshots. `DirectRgbTracer` consumes that type.
- `SceneInstance` already separates geometry, transform, and material, but uses a name
  without an independent persistent object ID.
- `IndexedMesh` is reusable and immutable but requires a connected, outward-facing
  closed solid. General surface meshes need a less restrictive representation.
- Asynchronous tracing already provides coherent snapshots, cancellation, accumulation,
  and leased images. Preserve these guarantees through the extraction.

## Ownership and dependency boundaries

| Area | Responsibilities |
| --- | --- |
| Engine core | Math, scene data, stable IDs, transforms, cameras, geometry/material assets, spatial query contracts |
| Rendering | Tracing, acceleration/preparation, render sessions, accumulation, reconstruction, image conversion and render diagnostics |
| Platform integration | AWT window/input adapters, image presentation, lifecycle and clock integration |
| Shared authoring tools | Scene/mesh operations, selection helpers, transactions, undo/redo, scene persistence |
| Applications | Playground presets and commands, modelling UI and workflows, game state/rules and control mappings |

Applications depend on public engine/tool APIs. Engine code must not depend on an
application, console command, preset, screen registry, or AWT event/window type.
Rendering depends on core; authoring may consume core and public rendering/query APIs.
Platform adapters connect public APIs to their host. Application composition roots
choose concrete services; consumers must not be required to use our DI container.

Begin within this repository. Establish enforceable source-set/compilation boundaries;
separate repositories, a plugin loader, and a new build system are not prerequisites.
Names below describe responsibilities; implementers may choose concrete API names.

## Required contracts

### World, assets, and identity

- A world contains nodes with stable IDs, human-readable labels, local transforms,
  optional parents, and optional geometry/material, light, or camera components.
- Labels are not identity. Renaming does not invalidate selection or references.
- Meshes and materials are shareable assets with stable IDs and explicit revisions.
  Editing a shared asset affects its references; making an instance unique copies the
  asset and changes that reference. These are distinct operations.
- Define world coordinates, units, handedness, winding, camera conventions, and ray
  distance semantics. Preserve current scene appearance through migration adapters.
- Parenting must reject cycles and missing parents. Define deletion and reparenting
  behavior, including whether world or local pose is preserved.
- General parent transforms can produce shear when rotation and nonuniform scale
  combine. Support affine world transforms or explicitly validate/document a narrower
  composition policy. Never silently approximate the result. Define handling of
  singular and reflected transforms, normals, and dielectric boundary orientation.
- Use a small explicit component model initially. A general ECS scheduler is deferred.

### Updates, snapshots, and rendering

- Separate scene contents, view/camera descriptions, render settings, and render-session
  state. Accumulation, temporal history, in-flight jobs, and image leases belong to a session.
- Scene operations are atomic transactions. A render/query snapshot sees either side
  of a transaction, never a partially applied multi-object edit.
- Specify mutation ownership and thread access. Background workers consume immutable
  snapshots; expensive geometry preparation/tracing stays outside the scene edit lock.
- Track geometry, transform, material, camera, and presentation changes sufficiently
  to invalidate the right caches. Undo restores content without reviving stale queries
  or invalid temporal history. Do not use labels as cache identity.
- Independent render sessions can view one scene through different cameras. They must
  not share mutable accumulation/history. Share immutable prepared geometry where safe;
  cross-session cache optimization is not a prerequisite for the first extraction.
- Preserve coherent camera-motion previews, transport-edit cancellation, deterministic
  seeded sampling, bounded publication storage, and explicit image lease lifetime.
- Application updates advance independently of render completion. A runtime host must
  expose update timing independently of presentation; rendering may consume the latest
  snapshot. Do not make game movement depend on samples completed.
- Lifecycle explicitly closes sessions/workers and handles pause, visibility, focus loss,
  and outstanding image leases. Controls/policies such as WASD or autofocus are optional
  services selected by the application, not mandatory behavior of scene data.

### Geometry and queries

- Render meshes contain indexed triangles with a documented attribute model for normals,
  UVs, and material slots. Attribute storage does not imply texture/shading support;
  document implemented channels and diagnose unsupported use rather than ignoring it.
- General surface meshes may be open. Solid dielectric/medium use requires additional
  boundary validation and remains subject to existing transport limitations.
- Editable meshes retain polygon topology, adjacency, and stable element IDs. A half-edge
  representation is a candidate, not a mandatory implementation. Conversion produces an
  immutable triangulated render asset and a mapping back to editable faces for picking.
- Ray queries return scene revision, node ID, primitive/face identity, distance, position,
  and relevant hit coordinates/normals. Define filtering and stale-result handling.
  Picking is a spatial query; collision response and physics are separate future services.

## Milestones and completion gates

Track each milestone here. Mark complete only with implementation, reproducible checks,
and recorded limitations. E identifiers are separate from rendering/performance phases.

| ID | Deliverable | Depends on | Status |
| --- | --- | --- | --- |
| E1 | Public engine scene/render boundary and migrated playground | Existing renderer | Complete |
| E2 | Independent headless consumer and multiple-view proof | E1 | Complete |
| E3 | Stable scene graph/assets and versioned persistence | E2 | Complete |
| E4 | General mesh assets and reusable scene queries | E3 | Engine complete; editor consumer proof in E5 |
| E5 | Initial scene-authoring application | E4 | Implemented on editor branch; GUI acceptance pending |
| E6 | Editable topology and first mesh-modelling operation | E5 | Implemented on editor branch; GUI acceptance pending |
| E7 | Geometry representation simplification and direct mesh editing | E6 | Implemented; native GUI QA pending |

### E1 — Extract the engine boundary

- [x] Define public world snapshot, camera/view, render settings, session, and image APIs.
- [x] Move reusable scene/geometry/render functionality out of application ownership;
      retain transitional adapters as needed, with removal criteria recorded.
- [x] Adapt the playground and its commands to the APIs. Keep defaults and input behavior.
- [x] Keep AWT, console, presets, and application-screen lifecycle outside engine core.
- [x] Add an executable dependency/compilation check preventing application imports from
      engine code. Merely moving files without removing coupling does not complete E1.
- [x] Verify representative seeded renders and camera/transport invalidation, asynchronous
      cancellation, lease stability, and close behavior against the pre-extraction baseline.

Gate: the existing playground operates through the public boundary without renderer-owned
knowledge of playground screens or presets. No rendering algorithm change is required.

### E2 — Prove a second consumer

- [x] Add a separate executable that constructs geometry, material, camera, and light via
      public APIs and writes a rendered image without creating a window or console.
- [x] Build/run that consumer against the engine without application implementation sources
      on its classpath. Document commands using the current Java 23 build conventions.
- [x] Exercise two cameras/sessions over the same world; edits reach both while session
      accumulation, resolution, history, and image leases remain independent.
- [x] Demonstrate timed application updates while a render is pending, independent of
      completed samples; verify final scene state and eventual matching publication.

Gate: a new application can build and render its own world without importing playground
code. This completes the first delivery; do not postpone it until an editor exists.

### E3 — Scene graph, asset identity, and persistence

- [x] Introduce stable node/asset IDs and hierarchy with explicit transform policy.
- [x] Implement transactional create, transform, rename, reparent, assign, duplicate,
      make-unique, and delete operations with reference/deletion rules.
- [x] Define a versioned scene file schema for nodes, asset references/data, materials,
      lights, and cameras. Exclude renderer caches, jobs, and accumulation.
- [x] V1 embeds assets and explicitly diagnoses external references as deferred; unknown
      versions fail. Validate fully before replacing the active scene.
- [x] Save without corrupting the previous valid file on failure. Define schema evolution
      policy; no migration from nonexistent historical file formats is required.
- [x] Verify round trips preserve identity, hierarchy, shared assets, and rendered results;
      cover invalid references, hierarchy cycles, failed loads, and shared/unique edits.

Gate: save a constructed scene, load it in a fresh process, and obtain equivalent world
content and a matching seeded render. Rendering/display preferences are separate data.

### E4 — General meshes and spatial queries

- [x] Separate general triangle-mesh validity from solid-boundary/material compatibility.
- [x] Add programmatic mesh construction with clear validation errors and documented
      attribute support; retain procedural primitives as reusable constructors.
- [x] Expose nearest-hit/picking queries independent of viewport and console state.
- [x] Preserve source face IDs through triangulation/preparation and acceleration ordering.
- [x] Verify open diffuse surfaces, transformed instances, hit identity/distance, shared
      assets, and rejected invalid solid/medium assignments against brute-force references.

Gate: both consumers can construct and render an open surface and a closed solid, and
identify selected nodes/faces through the same public query API. Existing glass and volume
restrictions remain explicit; general meshes do not automatically become valid media.

E3/E4 engine evidence and exact commands are in [the scene-document verification record](benchmarks/engine/README.md).
The independent headless document consumer and engine query tests satisfy B's API gate. The
second interactive document consumer and end-to-end selection workflow are intentionally
verified with the E5 editor rather than attributed to the still-legacy playground.

### E5 — Scene-authoring application

- [ ] Provide a hierarchy view, primitive creation, object selection by hierarchy and
      picking, translation/rotation/scale controls, duplication, and deletion.
- [ ] Provide material assignment/editing and explicit shared-asset versus unique edits.
- [ ] Route UI and console edits through the same transactions; implement undo/redo in
      the authoring layer, without requiring games to maintain edit history.
- [ ] Group a drag or multi-object operation into one undoable edit; clear redo on a new
      branch of edits. Failed operations leave content and history unchanged.
- [ ] Expose save/load from E3 and demonstrate two views over the same scene.
- [ ] Record manual UI checks plus automated transaction/history/picking checks.

Gate: a user builds a multi-part scene, edits it from either view, undoes/redoes changes,
saves, and reopens it without writing Java. Existing playground remains a separate consumer.

### E6 — Mesh editing

- [ ] Implement editable vertex/edge/face topology and stable selection identities.
- [ ] Define supported polygon inputs and deterministic triangulation, including explicit
      rejection of unsupported polygon shapes. Preserve render-to-editable face mapping.
- [ ] Implement face selection and extrusion as the first complete modelling operation.
- [ ] Publish a new immutable mesh revision atomically; integrate undo/redo, asset sharing,
      scene queries, validation diagnostics, and persistence of editable topology.
- [ ] Verify topology and winding, picking after edits, undo/redo, save/load, and behavior
      when an edit makes a mesh unsuitable for its assigned solid/medium material.

Gate: create a primitive, select and extrude a face, inspect the result in both views,
undo/redo it, and save/reopen with editable topology and selection mapping intact.

### E7 — Geometry representation and direct editing

Intent: geometry is editable according to its representation. Entering mesh selection
mode must not require converting polygon geometry, cloning assets, or changing scene
content. Analytic geometry supports a different set of operations; approximation is an
explicit, optional representation change. These requirements supersede E6's explicit
conversion prerequisite for boxes, planes and already-polygonal geometry.

#### Asset model and renderer boundary

- [x] Retain `GeometryAsset` identity, label, sharing and revision independently of its
      geometry contents. Use two primary asset representations: analytic geometry and
      polygon meshes. Concrete names may differ; responsibilities must remain explicit.
- [x] Analytic geometry stores mathematical parameters, initially sphere center/radius.
      It supports node translation/rotation/scale and applicable parameter edits without
      tessellation. Nonuniform node scaling retains the existing exact ellipsoid behavior.
      It has no invented editable vertices, edges or polygon faces.
- [x] Polygon geometry always stores immutable topology: stable vertex IDs/positions,
      stable face IDs/ordered vertex references, allocation counters and boundary intent.
      Derive canonical edges and adjacency from face boundaries. Preserve E6 validation,
      supported polygon restrictions, winding and deterministic triangulation contracts.
- [x] Box and plane constructors produce polygon meshes directly (six quads and one quad).
      Imported triangle geometry is polygon topology with three vertices per face;
      triangle source-face grouping must be preserved or explicitly migrated, never
      silently duplicated, discarded or guessed. Curved-looking polygon meshes remain
      polygon meshes; operation availability follows representation, not appearance.
- [x] Unify `EditableMeshGeometry` and asset-level `TriangleMesh` around polygon geometry.
      Remove public `MeshGeometry extends List<SceneObject>` coupling. Geometry descriptions
      must not masquerade as primitive collections. Render triangulation is a derived
      representation, not a second independently editable or persisted source of truth.
- [x] Introduce an explicit geometry preparation boundary producing immutable render/query
      geometry: analytic intersection shapes or indexed triangles with source-element
      mapping, validated capabilities, bounds and acceleration data as applicable.
      Preparation/expensive caching stays outside scene mutation locks and UI painting.
      Rename or replace `SceneObject` with a clearly scoped internal intersection/render
      primitive; actual scene identity remains with nodes/instances and assets.
- [x] Rendering, picking and overlays consume consistent prepared geometry from the same
      immutable asset revision. Preserve public query face identity, stale-result checks,
      transport invalidation, independent sessions, leases and coherent motion previews.
      Compilation gates continue to exclude application, AWT and editor dependencies.

#### Editing behavior

- [x] Every geometry node supports Object mode and node transforms. Polygon geometry
      additionally supports Vertex, Edge and Face selection modes directly. Switching mode
      changes only application selection/tool state: no geometry conversion, new asset ID,
      dirty flag, render-content revision or undo entry. Invalidate pending selection
      intents on actual mode transitions; reselecting the active mode is idempotent.
- [x] Analytic objects expose parameter controls and object transforms. Mesh-only controls
      are unavailable with a clear explanation; analytic objects cannot yield fake mesh
      element selections. Keep selecting objects practical while a mesh mode is active.
- [x] Provide stable vertex/edge/face selection and bounded element editing: at minimum
      vertex translation, edge translation of its endpoint vertices, and the existing
      positive face extrusion. Each operation publishes one validated immutable revision
      and one undo entry; a drag previews live and commits once or cancels fully.
      Failed edits preserve geometry/history, including invalid polygons and incompatible
      solid/media assignments. Topology repair, subdivision, bevels and multi-element
      workflows are not prerequisites for E7.
- [x] Element highlighting and picking use the exact displayed camera, scene/asset revision
      and prepared selection context. Define deterministic screen-pick tolerances and
      tie breaks for vertices/edges; document x-ray versus depth-visible behavior. Both
      views reflect shared edits and reconcile stable selections across undo/redo/load.
- [x] Keep geometry sharing independent of editing mode. Shared edits affect all references;
      Make geometry unique explicitly creates a new asset identity for one node. Show
      reference count and operation scope. Neither entering mesh mode nor approximation
      implicitly makes an asset unique.
- [x] Replace the generic Convert to editable mesh prerequisite with an optional
      Approximate as mesh operation on analytic geometry. Offer explicit bounded
      tessellation settings and explain the loss of the analytic representation.
      Publish the approximation as one atomic, undoable shared-asset replacement;
      preserve asset identity/references and let users make unique separately. Undo restores
      the analytic geometry and parameters. Object transforms/materials remain unchanged
      unless existing compatibility validation rejects the operation atomically.

#### Compatibility and completion gates

- [x] Replace class/list-equality special cases for rectangular emission and box scattering
      with explicit geometry-derived, validated rendering capabilities. Ordinary polygon
      boxes/rectangles must retain existing transport support; edited shapes retain it only
      when they satisfy the documented capability criteria. Never silently disable a
      material, assert capabilities without validation, or broaden physical support as
      part of this refactor. Preserve closed-mesh dielectric and current volume limits.
- [x] Specify scene-format evolution before writing new files. Read existing v1 analytic
      primitives/triangle assets and v2 editable topology; preserve scene/asset/element IDs,
      sharing, transforms, boundary validity and rendered meaning. Legacy box/plane assets
      migrate deterministically to polygon topology; spheres remain analytic. Diagnose
      unrepresentable source-face groupings explicitly. Persist only canonical geometry;
      retain strict bounded validation, atomic save and failure-safe load guarantees.
- [x] Demonstrate engine-only consumers constructing analytic and polygon geometry through
      the simplified public API. Verify exact analytic sphere intersections, polygon
      construction/triangulation/query mapping, supported material capabilities and seeded
      render compatibility. Document intentional migration differences and adapter removal.
- [x] Add automated controller and two-view checks for mode switches without mutations,
      direct element picking/editing, shared versus unique operations, approximation with
      chosen detail and undo, failed edits, save/reopen and stale asynchronous selections.
      Run the engine boundary, scene/editor and shared-input regression gates; record
      actual commands/results and native human QA separately.

Gate: create a box or plane and immediately select/edit its mesh elements without a
conversion step. Create an analytic sphere, edit its parameters/transform while retaining
exact curvature, then explicitly approximate it at a chosen detail and edit that mesh.
Undo/redo, both views, asset sharing and old/new scene-file round trips remain coherent.

G2 editor evidence (2026-10-07): Object, Vertex, Edge and Face modes operate directly on
polygon geometry without changing scene content. X-ray vertex and edge picking is bounded to
100,000 stable-ID candidates, uses 8 px and 6 px tolerances respectively, and resolves ties by
distance, node UUID, then stable element IDs. Candidate preparation is camera-independent;
projection occurs asynchronously only for an explicit click against its exact painted camera.
Selected elements and translation handles are published as one coherent two-view overlay.
Numeric and live vertex/edge moves replace the shared asset in asset-local coordinates, validate
the complete scene, and create one undo entry; an invalid final drag restores its baseline.
Mode, history, load, Make unique and stale asynchronous completion tests reconcile or clear
stable selections without dirtying the document on mode switches.

G3 editor evidence (2026-10-07): the Mesh inspector exposes asset-local center/radius Apply
controls only for analytic spheres and polygon controls only for polygon assets. Approximate
as mesh requires an explicit 4..64 detail and explains shared same-ID replacement and the loss
of analytic parameters. Controller/engine tests cover chosen detail, exact analytic undo,
identical-value no-op history, shared references, stale pre-approximation picks, scattering
failure with unchanged snapshot/history/selection, and v3 analytic/polygon save/reopen.
`scene-editor-analytic-preview.png` and `scene-editor-approximation-preview.png` are
window-free two-view evidence. Windows PowerShell 5.1
`editor-check.ps1 -OutputDirectory out/e7-g3-final` and
`input-check.ps1 -OutputDirectory out/e7-g3-final` pass with audited logs. Native-window
interaction remains pending separately using the editor QA card.

Deferred: a general capability/plugin framework, new analytic shape families, generalized
polygon repair/self-intersection detection, transport algorithms and unrelated optimization.
Concrete representation types and validated operations are sufficient for this phase.

## Verification and implementation tracking

Before E1, record representative baseline commands/settings and results. Reuse existing
transport, camera, focus, temporal, responsive tracing, and display tests as applicable;
add tests for new contracts rather than duplicating implementation details. Inspect harness
results because the current test harness does not fail the process on test failures.

For each delivery, record in this document or a linked results file: completed checklist
items, implementation references, commands/results, manual checks, deviations, and remaining
limitations. Update the index in `tracker.md`. Review unanswered `FAQ.md` entries during
related implementation; do not invent questions. Preserve existing documentation history.

Compare matching scenes/seeds/resolutions/spp before and after extraction. Record any
preparation/allocation or interaction regression; optimizations must not mask changed
transport or broken identity. No new FPS promise is made by this specification.

### E1–E2 evidence (2026-10-06)

- API and ownership guide: [EngineApi.md](EngineApi.md). Reusable sources are under
  `src/engine`; `Viewport`, presets, commands, controls, and display conversion remain
  application-owned. The playground opens its coordinator through `RenderSession`.
- Executable boundary: `./engine-build.ps1 -OutputDirectory out/engine-extraction/boundary`.
  This compiled engine/math/logging/profiling support without application sources, then
  compiled the independent `HeadlessEngineDemo` against only those classes.
- Independent result: `headless.png`, 160×100 at 4 spp, SHA-256
  `7D55D9DDE7EAB440FE23F89C9D83DCF5A678095F2E156A0AFFA9BD4F3474BA9C`.
- Contract proof: `engine.EngineSessionTest` covers immutable copies and atomic rejected
  inputs; temporal/minimum reset semantics; two sessions with different cameras, grids,
  sample counts, leases, and independent temporal histories; semantic same-value world
  republication; and five fixed updates during a pending trace followed by an exact seeded
  match to a fresh final-state session. `WorldSnapshot` rejects unsupported custom/mutable
  light implementations; `RgbPixels` prevents mutation of pooled publication arrays and
  checks lease lifetime.
- The display adapter copies a fresh lease into reusable storage and performs no cached-redraw
  copy. `DisplayPipelineBenchmark` measured 1600x1000 leased/raw-array conversion medians of
  28.311/25.701 ms (2.610 ms overhead) and zero steady-state bytes per frame; log:
  `out/engine-extraction/pixel-copy.log`.
- Baseline/post-extraction playground preview SHA-256 remained
  `37874155A3700CE7ACAA6C7280D609D403D9DE96CD0DE96A2C809B2B3C533ACE`.
  An initial post-extraction sweep passed all 54 harness suites under
  `out/engine-extraction/full-regression`. The final product sweep passed 53/54 under
  `out/engine-extraction/final-full-regression`; `ViewportKeyBindingsTest` reproduced the
  same Caps-on synthetic-lowercase failure three times against untouched baseline classes
  (`out/engine-extraction/baseline/ViewportKeyBindingsTest-caps-*.log`). Its test-only
  Caps-on branch prints an explicit skip for that keyboard path, exercises the same
  `ViewportCommand` updates directly without changing production input or operator state,
  and passed three focused reruns under `out/engine-extraction/final`.
- Transitional limitation: `ViewportState` and `openLegacySession` preserve playground
  commands/focus/profiling until E3 transactions replace direct state mutation. Stable
  node/asset identity and general open meshes remain E3–E4 work.

## Deferred scope and coordination

Physics/gravity, collision response, networking, scripting/plugin loading, skeletal
animation, full ECS scheduling, external mesh-format import/export, advanced modelling
operations, GPU rendering, and a replacement renderer are outside E1–E6. APIs should leave
room for them without implementing speculative subsystems now.

This work complements [RenderingRequirements.md](RenderingRequirements.md),
[PerformanceRequirements.md](PerformanceRequirements.md), and
[CameraRequirements.md](CameraRequirements.md). Existing transport/camera behavior remains
the reference. Coordinate package/API moves with those efforts; do not bundle unrelated
algorithm changes into extraction. Larger-scene acceleration work can follow the performance
roadmap once measurements justify it.
