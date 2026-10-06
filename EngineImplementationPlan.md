# Engine/editor implementation handoff

Repository: `C:/Users/andri/Documents/RayTracingEngine`, GitHub `asheptunov/game-engine`.
Read `AGENTS.md`, `EngineRequirements.md`, and this document before implementation.
User request: implement through the first playable authoring milestone, E5, committing
and pushing completed intermediate milestones. E6 is outside this delivery.

## Existing machinery and gaps

`src/MainModule.java:53` wires application screens; `src/scenes/viewport/Viewport.java:43`
combines input/UI/rendering; `ViewportState.java:11` combines scene and render state;
`DirectRgbTracer.java:11` consumes that state. Preserve the asynchronous lease/snapshot
and transport machinery, including camera/focus/temporal behavior. `SceneInstance.java:8`
already separates geometry/transform/material; `IndexedMesh.java:11` enforces closed
solids and must not remain the sole surface-mesh API. None of stable scene graph identity,
scene files, general authoring transactions, or a scene editor exists yet.

## Settled design decisions

1. E1/E2 extract the current implementation, retaining numerical behavior. No transport
   redesign or unrelated optimization. Keep engine-facing code independent of AWT,
   application screens, presets, console, DI wiring, and input events. Public core values
   and rendering services live under `engine`; internal renderer/session implementation
   may initially share packages to preserve existing package-private collaboration.
   Document that layout honestly; compilation boundaries must prove application independence.
2. New world snapshots and render settings are public immutable inputs; mutable legacy
   state can survive as an internal adapter. Applications do not need to construct the
   playground to render. Existing playground commands remain supported.
3. E3 world model uses stable UUID node/asset IDs and immutable revisions. Labels can be
   duplicated and are never reference keys. Revisions increase on edits AND undo/redo.
   A document owns edits, published snapshots and bounded undo/redo. Failed transactions
   change neither world nor history. Selection is UI state keyed by ID.
4. Geometry and materials are independent shared assets. Duplicate preserves asset
   references and gives a fresh node ID; make-unique gives fresh asset IDs. Shared edits
   update all references atomically. Delete removes a subtree; reparent preserves local
   pose by default, explicitly labelled. Reject cycles and dangling references.
5. Parent transforms use a documented restricted composition policy if general affine
   support would change the tracer. Reject unsupported shear/reflection/singular cases,
   including invalid descendants, atomically instead of silently approximating them.
6. Use a versioned explicit XML scene schema (`.scene.xml`, JDK parser with DTD/entities
   disabled and bounded input) with embedded geometry (no external assets in
   v1). Preserve IDs, shared references, graph, materials, lights and cameras. Fully
   validate before replacing the active document; use temporary sibling + atomic replace
   for saving or fail safely if atomic replacement is unavailable. Never Java deserialize
   arbitrary objects. Bound file/element sizes. Unknown versions are errors. Duplicate
   operations copy subtrees with fresh node IDs and shared assets. Runtime revisions are
   not persisted; fresh publication epochs prevent old query/history revival. Document
   a relative 1e-5 tolerance for orthogonality/uniform-scale checks in hierarchy composition.
7. General surface meshes and closed boundaries have distinct validation contracts.
   Retain existing glass/volume restrictions. Spatial query results carry revision,
   node/primitive identity, world hit position/normal and distance with normalized rays.
   Queries cannot mutate render sessions. Deterministic tie break: scene node order,
   then original primitive order. Returned revisions permit callers to reject stale picks.
8. E5 is a separate Swing scene-authoring executable, keeping the current playground
   runnable. Native widgets are suitable for hierarchy, numeric inspectors and commands;
   show two independent asynchronously rendered camera views. No extra GUI framework.
   Default to a small attractive scene and modest trace grids for responsive interaction.
9. Initial controls: primitive creation (box/sphere/open plane), hierarchy and click
   selection, numeric position/rotation/scale, duplication/deletion/reparenting, material
   assignment and shared/unique editing, undo/redo, file save/load. A text command panel
   calls the same edit operations. Group multi-field edits in one Apply transaction;
   no drag manipulator is required in this first editor. Show selection feedback and
   validation errors without losing previous content. Provide view orbit/pan/zoom or
   equivalent practical navigation, reset and camera controls without hover-look.
10. Editor state edits live on the UI thread; tracing is asynchronous from snapshots.
    Geometry preparation and file IO must not hold rendering state locks. Rendering and
    image presentation cannot gate scene updates. Closing stops sessions and timers.
11. Keep existing Java 23/preview conventions. Provide a PowerShell build/check/launch
    path for this host if needed. Background verification uses console-free child launch
    with no visible windows. User-requested interactive editor launch may be visible.

## Delivery nodes (serial dependencies)

- A, E1–E2, `refactor(engine): expose independent scene rendering sessions`.
  Extract APIs and migrate playground. Independent consumer, two-session and timed-update
  proofs, engine-only compilation check. Preserve focused existing regressions and record
  representative baseline fingerprints before extraction. GUI QA: no (behavior-preserving).
- B, E3–E4, `feat(engine): add scene documents mesh assets and spatial queries`.
  World graph/assets, transaction semantics, persistence and general meshes/picking.
  Test invalid edits/loads, hierarchy composition, sharing, round trips and seeded images,
  picking transforms/ties/identity, open surfaces and rejected invalid solids. GUI QA: no.
- C, E5, `feat(editor): add interactive scene authoring application`.
  Build the playable editor using A/B public APIs. Automated model/controller checks,
  actual compiled/rendered preview verification, documented manual QA card. GUI QA: yes.

Acceptance checklists and complete scope are in `EngineRequirements.md`. Do not mark a
phase done based on a subset. Update evidence and outstanding limitations for each phase.
If a required criterion cannot be implemented safely, report the precise blocker.

## Gates and repository protocol

Each node gets an isolated managed worktree/branch rooted at current integrated HEAD;
never write another lane's checkout. Root checkout has user edits to `Goals.md`: preserve
them and never stage them. Documentation from the preceding planning turn is in scope.
No hosted CI configuration was found. Compile all src/tst and run affected suites plus
the established regression gate; inspect harness output (exit 0 is not a pass).
Use `C:/Users/andri/.jdks/openjdk-23.0.1/bin/{javac,java}.exe` with preview/release 23.
Log checks under the lane's `out/` directory. Do not open test windows or steal focus.

Before committing, review/simplify changes and fix findings. Independent orchestrator
verification is required before integration. User authorization allows intermediate
commit/push: A and B may fast-forward main after passing gates, then push normally.
The final UI node remains on a reviewable committed branch until human GUI QA. Do not
force-push main. The orchestrator owns integration and pushes; implementers commit only.
PRs are optional for these user-authorized local milestone commits, not a gate.

Existing in-scope bugs must be documented separately rather than silently changing
baseline behavior. Refresh stale review hashes after rebases. Inspect captured logs.
No logs in `/tmp`; lane `out/` is the designated scratch location for this arc. After
two focused failed attempts at a blocker, report evidence for an orchestrator decision.

End reports with DONE A/B/C (commit, gate results, clean lane), BLOCKED A/B/C (Question,
Options, recommendation, state), or FAILED A/B/C (evidence). Keep reports under 300 words.

## Verification and handover

Document actual exact commands as scripts become available; do not invent a passing run.
Final QA exercises creating/transforming objects, selecting in each view, sharing vs unique,
undo/redo, saving/loading, camera navigation and clean close. Record EXPECT for each action.
Keep final user handover concise and identify intermediate commits pushed and final branch.
