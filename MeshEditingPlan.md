# E6 — editable topology and face extrusion

User request (2026-10-07): implement the next engine/editor milestone, referred to as M6.
The engine specification calls this E6. Repository: `asheptunov/game-engine`.
Implementation checkout: `C:/Users/andri/.codex/worktrees/scene-editor/RayTracingEngine`,
branch `codex/scene-editor`, starting at `65db8f8`. Preserve root `Goals.md` edits.
Read `AGENTS.md`, `EngineRequirements.md`, and this plan. Continue the existing editor
branch; no merge to main is required for this GUI milestone.

## Existing machinery and gaps

`src/engine/TriangleMesh.java:17` provides immutable surface/solid triangle assets and
source-face mapping. `MeshGeometry.java:8` exposes that mapping; `SpatialQuery.java:52`
already returns it in `RayHit`. `SceneEdit.java:39` supports shared geometry replacement
and make-unique. `SceneFiles.java:56` accepts bounded, validated v1 XML and saves atomically.
`EditorController.java:74` rejects stale asynchronous picks. `RenderViewPanel.java:24`
owns coherent captured image/overlay bundles. `SceneEditorPanel.java:253` owns inspectors.
Reuse these contracts and UndoHistory; do not rebuild rendering or scene history.

Gaps: polygon topology/adjacency, topology persistence, conversion of primitives,
face selection/highlighting, extrusion UI and commands, and end-to-end modelling checks.
FAQ currently has no unanswered human entries.

## Design decisions (settled)

1. Add a public immutable engine editable mesh geometry with stable nonnegative long
   vertex and face IDs scoped to the geometry asset. Edges have canonical endpoint-ID
   pairs; expose adjacency using those identities. Lists preserve deterministic order.
   Reject duplicate IDs, repeated face vertices, missing vertices, unused vertices,
   nonmanifold edges or inconsistent shared-edge winding. Open surfaces are supported.
2. Supported polygons are finite, nondegenerate, planar, strictly convex simple polygons
   with at least three vertices. Reject concave, nonplanar, self-crossing and collinear
   polygons with useful errors. Use scale-aware tolerances documented in the API.
   Triangulate as a fan from the first listed vertex, preserving polygon winding and
   mapping every generated triangle to its stable editable face ID. Unsupported
   attributes retain the existing explicit rejection policy.
3. Render geometry derives from topology and remains immutable. Preserve MeshGeometry
   identity/mapping through primitives and queries; no application dependencies in engine.
   Surface-vs-closed-solid validation stays explicit. General geometric self-intersection
   detection and topology repair remain deferred, as with existing solid meshes.
4. Provide conversion of box (six quads), plane (one quad), sphere (existing reusable
   tessellation or a bounded documented constructor), and triangle meshes (one face per
   source triangle). Preserve supplied source IDs where unambiguous. If multiple source
   triangles share a face ID, reject conversion explicitly rather than inventing polygon
   reconstruction; already-editable geometry returns itself. Conversion is an undoable
   explicit action, never an accidental consequence of clicking.
5. Extrude one polygon along its local winding normal by finite positive distance
   (outward for a validated closed solid).
   Replace its cap with translated fresh vertices while retaining the selected face ID;
   append one side quad per original edge in boundary order. Preserve every other face
   and vertex ID; retain base vertices used by side quads. For boundary edge vi→vj,
   side winding is [vi,vj,vj′,vi′]. Only prune genuinely unreferenced vertices.
   Allocate IDs from persisted monotonic next-ID counters, checked for overflow. Undo
   restores prior topology; revisions distinguish abandoned redo branches/stale queries.
   Closed-solid construction, conversion, load and extrusion must all pass existing
   connected/outward boundary checks; never trust a caller-supplied closed flag.
   Scene validation checks
   every shared instance/material before atomic publication; failure changes no history.
6. Geometry editing is shared by default, visibly labelled with reference count. Offer
   Make geometry unique before edits. Conversion and extrusion each publish one geometry
   revision/one undo entry, and both views consume the same new snapshot.
7. Add explicit Object/Face selection mode, shared across views. Face mode uses the exact
   painted query/camera/revision and stable sourceFaceId; face selection includes node,
   geometry and face IDs. Switching mode or hierarchy selection invalidates pending picks.
   Do not start object gizmos in face mode. A miss clears face selection; invalid/stale
   picks leave content unchanged. Reconcile surviving selection on edit/undo/redo; clear
   invalid IDs and file replacement selection. Selection remains application state.
8. Highlight the selected polygon boundary distinctly in both views using the existing
   captured overlay layer, with selection identity included in camera-independent context.
   Retain coherent camera-motion previews and D4 token reuse; never compare current camera
   when accepting a coherent intermediate bundle. Face highlighting remains visible when
   general wireframe is disabled. Explicitly label x-ray rendering; no depth occlusion claim.
9. Mesh inspector provides Convert to editable mesh, shared reference count, Make geometry
   unique, face ID, distance, and Extrude face. Controls enabled only when applicable.
   Include corresponding discoverable text commands through the same controller operations.
   Existing bindings/preferences and right/middle orbit, Space pan remain operational.
10. Introduce scene XML v2 for editable topology, IDs, counters and boundary intent. Read
    v1 unchanged; write v2 when needed (or consistently v2 with documented policy).
    Reject editable topology under v1, unknown versions/attributes, excess topology,
    duplicate/dangling IDs and invalid counters. Account for topology in existing file,
    asset and geometry limits before allocation using checked long totals for corners
    and derived triangles. Bound polygon corners before quadratic validation; bound
    reference token lists before splitting. Counters must exceed every existing ID.
    Never persist derived triangles twice.

## Delivery

- F1: `feat(engine): add editable polygon meshes and face extrusion`. Topology, conversion,
  triangulation, extrusion, geometry equality, queries, shared transaction validation,
  versioned persistence. GUI QA: no. Commit only after full editor gate with new engine
  suites; remain on editor branch to preserve the unmerged dependencies.
- F2: `feat(editor): add face selection and mesh extrusion`. Controller/UI/commands and
  coherent face overlay integration. Depends on F1. GUI QA: yes. Commit after full gate,
  headless rendered preview and regression review; root independently verifies and pushes.

## Acceptance and verification

- [x] Engine consumer constructs editable open/closed meshes without editor imports.
- [x] Topology tests cover identity, adjacency, winding, deterministic triangulation,
      invalid polygons, IDs/counter overflow, conversion and repeat extrusion.
- [x] Queries return cap and side face IDs before/after transformed/shared edits.
- [x] Scene tests cover shared and unique edits, monotonic revisions, undo/redo and atomic
      rejection of invalid geometry/material combinations, including open glass.
- [x] Persistence tests cover v1 compatibility, v2 topology/counters round trip, invalid
      schema and existing save/load failure guarantees.
- [x] Editor tests cover explicit modes, stale pick/mode changes, selection reconciliation,
      controller-command parity, two-view rendering/highlights, undo/redo and save/load.
- [ ] Human can create box, convert, select face, extrude, orbit in either view, undo/redo,
      save/reopen and select/extrude again without Java.

Use Windows PowerShell 5.1:
`C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./editor-check.ps1 -OutputDirectory out/e6-check`.
Extend that gate with new suites. Audit harness logs for errors, since zero process exit
alone is not success. Run engine-only compilation and input regression gate where affected.
Render Swing previews headlessly; never open automated windows or steal focus.
Keep evidence under checkout `out/`; update EngineRequirements, EngineApi, tracker and
benchmarks/editor docs, and provide native manual steps. No transport algorithm changes.

## Implementer protocol

Implement F1 then F2 in this existing checkout, with one writer. Check current source
against the pointers; retain unrelated changes. Decisions above are settled; report a
load-bearing contradiction rather than silently diverging. Test boundary/collision cases,
review reuse/simplicity before each commit, and inspect full local gate logs. No hosted CI
is configured; local checks are the gate. Commit only your scoped files; root owns pushes
and independent review. Document pre-existing unrelated bugs separately. After about two
focused attempts at an unresolved blocker, report evidence and a concrete decision needed.
End with DONE F1/F2 (commits, gate, clean checkout), BLOCKED (question/options/recommendation/
state) or FAILED (evidence). Human GUI verification remains outstanding until user feedback.

## Delivery evidence — 2026-10-07

F1 `c1dc59a` is pushed on `codex/scene-editor`. Independent PS5.1 editor gate passed
at `out/e6-f1-root-final`; review caught and fixed nonadjacent polygon contact validation.
F2 `404090a` implements the complete editable face workflow. Implementer `out/e6-f2-final` and
independent `out/e6-root-final` full input/editor gates passed; all 63 final implementation
and root logs were audited without harness errors. Root visually inspected the post-extrusion
two-view PNG, including full mesh/face labels and highlights with wireframe disabled.
Evidence is copied under root `out/milestones/e6`. No native test windows were opened.
Human steps: [MeshEditorQA.md](MeshEditorQA.md). GUI acceptance remains pending.
