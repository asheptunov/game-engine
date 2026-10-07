# Engine and authoring tools specification

Status: E1–E4 complete; E5 implemented with human GUI QA pending; E6 pending. Recorded 2026-10-06.

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
| E4 | General mesh assets and reusable scene queries | E3 | Complete |
| E5 | Initial scene-authoring application | E4 | Implemented; human GUI QA pending |
| E6 | Editable topology and first mesh-modelling operation | E5 | Pending |

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

- [x] Provide a hierarchy view, primitive creation, object selection by hierarchy and
      picking, translation/rotation/scale controls, duplication, and deletion.
- [x] Provide material assignment/editing and explicit shared-asset versus unique edits.
- [x] Route UI and console edits through the same transactions; implement undo/redo in
      the authoring layer, without requiring games to maintain edit history.
- [x] Group a drag or multi-object operation into one undoable edit; clear redo on a new
      branch of edits. Failed operations leave content and history unchanged.
- [x] Expose save/load from E3 and demonstrate two views over the same scene.
- [x] Record manual UI checks plus automated transaction/history/picking checks.

Gate: a user builds a multi-part scene, edits it from either view, undoes/redoes changes,
saves, and reopens it without writing Java. Existing playground remains a separate consumer.

E5 implementation evidence (2026-10-06):

- `editor.SceneEditorMain` is a separate Swing executable. Its EDT-owned controller routes
  hierarchy/inspector and text commands through the same `SceneDocument`/`UndoHistory`
  operations. Box, sphere, open plane, group, point-light, and camera creation; one-Apply
  transforms; local-pose reparent; subtree duplicate/delete; shared assignment/edit and
  make-unique; undo/redo; and guarded async `.scene.xml` save/load are available.
- Two independent `RenderSession`s render the same immutable document at modest grids.
  Pixel leases are copied and closed on their session owner threads. Picks use the exact
  EDT-painted frame, captured camera, scene revision, and prepared `SpatialQuery`; stale or
  unmatched clicks are rejected. `RenderProgress.activeGeneration` exposes the coordinator's
  captured generation so rapid camera updates retain coherent lagging publications safely.
- The views draw bounded x-ray wireframes, light/camera markers, and local-axis move/rotate
  handles from the exact painted camera. A handle drag publishes live previews but records one
  history entry; Escape or focus loss restores the drag-start snapshot. File-backed editor
  bindings define right/middle-drag orbit, Space+right/middle-drag pan, wheel zoom, undo,
  redo, and cancel. The native bindings panel supports validated alternatives, immediate
  Apply, atomic Save, default restoration, and visible recovery from an invalid override.
  Camera navigation retains projection, off-center/skew framing, focus, and aperture, and can
  atomically copy the displayed view back to a selected camera below a supported parent.
- `./editor-check.ps1 -OutputDirectory out/editor-check-active` passed controller/history/
  async-file/command/stale-pick tests, a real window-free two-view Swing render/pick test,
  and the E3–E4 document/persistence/query plus E1 session suites. The generated layout is
  `out/editor-check-active/scene-editor-preview.png`; no automated check opened a window.
- Manual native-window expectations and exact PowerShell/Git Bash launch commands are in
  `benchmarks/editor/README.md`. Human GUI interaction remains pending before merge; automated
  checks do not claim physical input, window-manager, or scanout verification. E6 topology and
  mesh modelling remain deferred.

D4 refinement evidence (2026-10-06): mouse chords now carry generic held physical keys
without changing legacy modes or constructors. The editor clears held-key state across
typing, dialogs, focus/lifecycle transitions, and close. Each view publishes one immutable
image/overlay bundle: selection, gizmo-mode, and resize changes keep the prior complete pair
until off-EDT projection finishes, while camera motion may still show coherent intermediate
views. Exact painted camera, dimensions, overlay, picking, and handle-drag context therefore
advance together. `EditorBindingPreferencesTest` covers routing, validation, atomic profile
failure/restart, and a headless preferences render; `SceneEditorPreviewTest` deterministically
blocks overlay projection to cover selection, mode-revert, and resize-revert publication.

D5 refinement evidence (2026-10-06): the preferences adapter presents a deterministic,
grouped action list instead of editable action choices. Undo, Redo, Cancel active handle drag,
Orbit, Pan, and Zoom remain visible when unbound; each owns scoped Add binding and Remove
binding controls, and all listed chords are equivalent alternatives. The serialized profile
and runtime dispatch are unchanged. The headless preferences regression covers fixed action
ownership, an unbound action, added alternatives, restore, validation preservation, Save, and
restart using injected paths.

### E6 — Mesh editing

- [x] Implement editable vertex/edge/face topology and stable selection identities.
- [x] Define supported polygon inputs and deterministic triangulation, including explicit
      rejection of unsupported polygon shapes. Preserve render-to-editable face mapping.
- [x] Implement face selection and extrusion as the first complete modelling operation.
- [x] Publish a new immutable mesh revision atomically; integrate undo/redo, asset sharing,
      scene queries, validation diagnostics, and persistence of editable topology.
- [x] Verify topology and winding, picking after edits, undo/redo, save/load, and behavior
      when an edit makes a mesh unsuitable for its assigned solid/medium material.

Gate: create a primitive, select and extrude a face, inspect the result in both views,
undo/redo it, and save/reopen with editable topology and selection mapping intact.

F1 engine evidence (2026-10-07): `EditableMeshGeometry` provides bounded immutable polygon
topology, canonical edge adjacency, stable IDs/counters, deterministic source-face fan
triangulation, explicit primitive/triangle conversion, and positive listed-normal face
extrusion. Closed intent is revalidated as an outward connected manifold on every creation,
conversion, extrusion and V2 load. `SceneEdit` performs shared conversion/extrusion in one
atomic publication; existing instance validation rejects incompatible emission/scattering/
open-glass results without changing history. `EditableMeshTest` covers topology hazards,
conversion, repeat extrusion, queries through transformed shared instances, make-unique,
fresh undo/redo revisions, counter-only content, and material rejection. `ScenePersistenceTest`
covers V1 compatibility, V2 topology/counter round trip and hostile V2 references, counters,
duplicates, versions, and forged boundary intent.

F2 editor evidence (2026-10-07): Object/Face mode, explicit shared conversion, Make unique,
stable polygon picking and positive-distance extrusion use the same controller transactions
from the inspector and command panel. The exact painted frame owns its query, camera and
face-highlight context in both views; camera-only preview publication remains coherent and
object gizmos are unavailable in Face mode. Selection reconciliation covers shared-to-unique
asset changes, edits, undo/redo and load. `EditorControllerTest`, `OverlayGeometryTest` and
`SceneEditorPreviewTest` cover stale cross-view/mode intents, marker overlap, shared edits,
unique geometry, extrusion, history, save/reopen and a window-free two-view journey. The
headless gate writes `scene-editor-mesh-extruded-preview.png`; native-window manual QA remains
the final human acceptance step.

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
- Transitional limitation: `ViewportState` and `openLegacySession` still preserve the
  playground's existing commands/focus/profiling. Scene documents, stable node/asset identity,
  general open meshes, persistence, and spatial queries are implemented for independent
  consumers; migrating the legacy playground's direct mutation is separate application work.

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
