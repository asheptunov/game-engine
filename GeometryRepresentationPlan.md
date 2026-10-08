# E7 implementation handoff

Repository: `C:/Users/andri/Documents/RayTracingEngine` (orchestrator documents).
Implementation: `C:/Users/andri/.codex/worktrees/scene-editor/RayTracingEngine`,
branch `codex/scene-editor`, base `404090a`. Preserve existing E7 documentation edits
and unrelated root `Goals.md` changes. Read AGENTS.md and FAQ.md. Authoritative scope:
EngineRequirements.md, section E7. No hosted CI/PR gate is configured; deliver commits
on the existing editor branch, keeping native GUI work unmerged into main.

## Existing machinery and gaps

GeometryAsset already owns identity/revision/sharing. EditableMeshGeometry already
validates immutable convex planar polygon topology, stable IDs, canonical edges,
positive extrusion and closed boundaries. SceneEdit, UndoHistory and EditorController
already provide atomic validated publication and grouped gestures. SceneFiles reads
v1/v2 strictly and saves atomically. RenderViewPanel has coherent captured image/overlay
pairs and stale selection checks; do not rebuild these mechanisms.

Current GeometryData.java:7 exposes primitive lists. MeshGeometry.java:8 extends List;
SceneInstance.java:9 infers material support by concrete primitives/list equality.
SceneFiles.java:22 owns canonical XML. These are the model-boundary gaps. Editor only
has Object/Face modes and conversion prerequisite; vertex/edge edits and explicit
analytic approximation remain missing.

## Design decisions (settled)

1. GeometryAsset holds analytic sphere or immutable PolygonMesh. Constructors for box
   and plane yield polygon topology; triangle imports become triangular polygon faces.
   Remove asset EditableMeshGeometry/TriangleMesh duplication and public List coupling.
   Renderer triangles are derived and never persisted independently.
2. Use an explicit immutable prepared geometry value (primitives/indexed triangles,
   face mapping, validated capabilities). Rename SceneObject to a scoped render primitive.
   Legacy application adapters may be migrated, but no competing editable model remains.
3. Preserve exact analytic sphere/ellipsoid intersections. Rectangle emission requires
   validated single four-corner parallelogram polygon geometry, including skewed legacy
   RectGeometry (do not narrow existing support to perpendicular edges). Box scattering requires validated
   closed rectangular box geometry; retain existing transform/transport restrictions.
   Record precise capability validation before implementing it; never silently drop materials.
   Parallelogram constructors/migration canonicalize the fourth corner from the stored
   origin and adjacent corners using the renderer's float construction; document any
   extreme-offset float cancellation difference. Arbitrary skew quads are not emitters.
4. New saves use version 3 with canonical sphere/polygon assets. Read v1/v2 and migrate
   boxes/planes deterministically using E6 IDs/winding; retain v2 IDs/counters exactly.
   Unique triangle source IDs survive import. Repeated IDs reconstruct only a validated
   connected coplanar triangulated disk with one simple convex boundary and consistent
   winding; deterministic boundary start uses minimum vertex ID. Reject disconnected,
   overlapping, holed or otherwise unrepresentable groups explicitly, never discard IDs.
   Retain all resource bounds and bound migration work.
5. Selection modes Object/Vertex/Edge/Face change app state only. Same-mode selection
   is idempotent. Vertex identity is its stable ID; edge identity canonical endpoint IDs.
   For screen picking use 8 logical pixels vertex radius and 6 pixels edge distance,
   x-ray selection consistent with existing overlays. Tie-break by distance, then node
   ID, then stable vertex/edge IDs. Document x-ray behavior. Face picks retain ray query.
6. Vertex/edge translation operates in asset local coordinates (edge moves both ends).
   Numeric XYZ delta controls and drag handles share validated edits. Drags commit one
   history entry, cancel fully, and never accumulate deltas against the wrong baseline.
   Invalid nonplanar/concave edits fail atomically; do not invent polygon repair.
7. Analytic sphere inspector exposes center/radius. Approximate as mesh exposes integer
   detail 4..64, explains shared replacement/loss of analytic parameters, preserves asset
   identity and references. Make unique remains independent. Undo restores exact sphere.
8. Both views use coherent captured geometry/revisions for highlights/picks. Camera-only
   tokens remain reusable; accepting lagging coherent frames must not require latest
   camera equality (that starves continuous motion). No prep under paint/state lock.
9. Cheap immutable capability validation belongs with canonical geometry so incompatible
   material edits fail atomically. Expensive triangulation/BVH preparation stays outside
   mutation publication. Preserve legacy primitive/generator order for seeded rendering;
   retain exact paths where feasible and measure/document any intentional numerical
   difference from robust polygon intersection rather than claim bit equivalence.

## Deliveries and acceptance

| ID | Conventional commit | Depends | Native QA |
| --- | --- | --- | --- |
| G1 | refactor(engine): separate canonical geometry from rendering | E6 | no |
| G2 | feat(editor): add direct vertex and edge editing | G1 | yes |
| G3 | feat(editor): expose analytic parameters and mesh approximation | G2 | yes |

G1 includes model/preparation/capabilities, all consumers migrated, v3 persistence,
legacy fixtures, engine-only consumer examples, API docs. Tests: exact sphere rays,
quad topology/fan mapping, IDs and invalid imports, positive extrusion, rectangle/box
capability preservation and rejection, seeded transport compatibility, bounded hostile
files and failure-safe saves/loads. Full editor gate before commit.

G2 includes controller modes/selections/reconciliation, direct polygon creation/mesh
editing, deterministic screen picking and selected-element overlays, numeric translation
and grouped drag UX. Tests: no mutation on mode change, same-mode/pending stale picks,
tie breaks, invalid edits/history, unique/shared references, both views and motion progress.

G3 includes analytic inspector, explicit detail approximation, removing generic Convert
UI/commands, help/docs/migration notes, save/reopen and approximation undo. Tests: chosen
detail, analytic restoration, material validation preserving state/history, two-view
integration. Full shared input/editor regression gate and headless PNG previews.

Completion requires every E7 checklist item, not merely happy-path UI. Native QA card
must show create box/plane -> direct element edit; exact sphere parameter edit -> explicit
approximation -> mesh edit; sharing/unique, undo/redo, both views and old/new files.

## Verification and protocol

Run Windows PowerShell 5.1 explicitly from the implementation worktree:

`C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./editor-check.ps1 -OutputDirectory out/e7-g1`

Final gate substitutes `input-check.ps1` and `out/e7-final`. Audit harness logs for
`[ERROR]`/`failed with exception`/`AssertionError`, regardless of process exit code.
Use console-free verification; no native windows/Robot/focus stealing. Generate and
inspect headless Swing previews. Worktree writes/.git operations require narrow sandbox
escalation. Do not create new checkouts or overwrite docs/unrelated edits.

You are implementing this plan. Recheck existing machinery, test boundary/collision
hazards, run focused review/reuse pass and full gate before committing. Report genuine
design blockers after two focused failed attempts; do not silently diverge. Orchestrator
owns independent review/verification and push. End with DONE Gx (commit, gate, clean
lane), BLOCKED Gx (Question/Options/Recommendation/State), or FAILED Gx, under 300 words.
