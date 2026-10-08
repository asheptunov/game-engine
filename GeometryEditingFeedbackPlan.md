# E7 editing feedback implementation

Status: H1 `ad83244` and H2 `bdec663` verified and pushed. Native interaction QA is
pending; automated gates and independent review are clear. One writing lane admitted with ~52 GiB free on C:,
above the 8 GiB reserve plus 6 GiB lane budget.

User feedback: face translation is missing; cube vertex/edge moves are blocked by
planarity; active modes do not show the elements that can be selected. Fix all four.
Implementation checkout: `C:/Users/andri/.codex/worktrees/scene-editor/RayTracingEngine`,
branch `codex/scene-editor`, base `940fe72`. Root docs remain in
`C:/Users/andri/Documents/RayTracingEngine`. Read checkout AGENTS.md/FAQ.md and preserve
unrelated main/readability work. Handoff dispatch continues: one product writer, readonly
reviewer, root independent verification/push. No native test windows.

## Settled behavior

1. Keep stable polygon faces and vertex IDs when deforming a mesh. A face may become
   nonplanar; deterministic derived fan triangles define its rendered/query surface.
   Do not silently split authored faces, allocate IDs, or require user conversion.
   Preserve supported convex/simple projected boundary restrictions and reject collapsed,
   folded/inconsistently wound fan triangles. Compute a double-precision Newell area vector,
   using the equivalent sum of unchanged fan crosses in v0-relative double coordinates
   (cast before subtraction), validate the dominant-axis projected boundary, and normalize
   it as the face normal
   for extrusion/tool purposes; individual render normals remain triangle normals.
   Existing planar geometry must keep primitive order and seeded rendering unchanged.
2. Retain manifold connectivity, consistent shared-edge winding, positive outward volume,
   finite values and capability/material validation. Ordinary small box vertex/edge/face
   moves must succeed along X/Y/Z. Degenerate, self-crossing, collapsed and incompatible
   emitter/volume edits still fail atomically. General global self-intersection repair or
   concave polygon triangulation is outside this fix.
3. Add face translation of all boundary vertices by one asset-local delta. Numeric
   controls, `face move <dx> <dy> <dz>`, and translation handles share one engine edit.
   Face pivot is its vertex centroid. Face mode uses Move handles; rotate remains object
   mode. Preserve shared geometry semantics, captured baseline, undo/cancel/invalid-final
   release rules, semantic stale checks and camera-independent context reuse.
4. Mode cues must be visible before an element is selected and regardless of Wireframe:
   Vertex shows clearly sized unselected points plus supporting boundaries; Edge shows
   selectable boundary edges; Face shows polygon boundaries and face center cues, with
   a distinct selected treatment. Keep analytic objects free of fake mesh elements.
   Face picks use the actual triangulated surface; vertex/edge picks remain x-ray.
   Place face markers on a derived triangle (largest-area fan triangle centroid, stable
   fan-index tie), not the potentially off-surface centroid of a warped boundary. Face
   picking remains depth-visible; label its distinction from x-ray boundary overlays.
   Ensure ordinary sample meshes are readily selectable in both views with all modes.
5. Cue projection must remain bounded/coherent/off EDT with the displayed image camera.
   Reuse prepared immutable context and retain continuous-motion display progress.
   Prioritize selected mesh cues when applying a documented finite display budget, then
   deterministic node/element order. Do not project unbounded scene candidates every tick;
   asynchronous click-time picking keeps its independent bounded candidate budget.
   Expose truncation honestly and never let cue cache/mode completions restore stale cues.
6. Specify scene evolution before saving warped faces: new writes use v4 polygon topology
   semantics; read v1/v2/v3 unchanged, enforcing legacy planar rules for v2/v3 topology.
   v4 retains stable IDs/counters/sharing and all XML bounds/atomic save/load guarantees.
   Roundtrip warped box edits and keep exact analytic spheres analytic. Document the new
   polygon surface semantics and supersede E7's earlier strict-planarity limitation.

## Deliveries

| ID | Commit | Dependencies | Native QA |
| --- | --- | --- | --- |
| H1 | feat(engine): support polygon deformation and face translation | E7 | no |
| H2 | fix(editor): expose selectable elements and face movement | H1 | yes |

H1: engine validation/preparation/normal/face-translation, format v4, old/new fixtures,
engine/controller API prerequisites. Prove each box vertex, edge and face translated
0.1 local units along each axis retains IDs, valid queries and closed boundaries; zero
delta is a no-op. Test warped triangulation/normals, malformed/folded polygons, invalid
materials, undo/cancel, warped v4 roundtrip, strict legacy rejection and exact old seeds.

H2: controller/numeric/command/drag face integration and independent mode cues. Test
unselected sample cube points/edges/face cues with Wireframe off; actual both-view picks
and X/Y/Z numeric/drag moves for vertex/edge/face, shared/unique and undo/cancel. Test
bounded large cue sets, selected priority, stale completions and sustained camera-motion
progress. Generate Vertex/Edge/Face previews for root visual review. Update spec/API/QA.

Full explicit Windows PowerShell 5.1 `input-check.ps1 -OutputDirectory out/e7-h1-final`
and `out/e7-h2-final` from the checkout are the gates (including transport suites).
Audit logs for harness failures. Use descriptive names/readable methods and document
coordinate spaces/locking/numerical assumptions; no compressed new one-line algorithms.
Review/reuse pass before commit. Report genuine blockers after two focused attempts.
Only implement the assigned delivery, commit after gate, let root review/verify/push.
End DONE Hx with commit/gate/clean status, BLOCKED Hx Question/Options/Recommendation/
State, or FAILED Hx; keep under 300 words.
