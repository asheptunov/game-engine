# E7 native editor QA

Implementation checkout: `C:/Users/andri/.codex/worktrees/scene-editor/RayTracingEngine`.
Launch `./editor` in Git Bash or `./editor.ps1` in PowerShell. This card records human
acceptance separately from automated headless checks. E7 is implemented through
`22e5a0f`; independent full engine/editor/transport/input gates and source review passed.
Native interaction acceptance remains pending.

Native feedback follow-up: [GeometryEditingFeedbackPlan.md](GeometryEditingFeedbackPlan.md)
adds face movement, warped-face deformation and visible mode cues.
Ownership follow-up: [IndependentObjectsPlan.md](IndependentObjectsPlan.md) gives each
object independent geometry and materials and removes the linking workflow.

- Create a box and a plane. Switch Object/Vertex/Edge/Face modes immediately; no
  conversion is required and mode changes do not mark the scene dirty or add undo.
- With Wireframe off, switch Vertex/Edge/Face modes before selecting an element. Check
  visible points, edges, and face boundaries/markers in both views.
- Select a vertex, edge, or face of the Teal box in either view. Check its highlight in both views, translate
  with local delta controls and drag handles, undo/redo, then cancel a drag with Escape.
  Move along X/Y/Z: ordinary warped-face edits should succeed. Attempt a collapsed or
  folded edit: geometry/history should remain at the last valid state.
- Duplicate the Teal box. Select a face and extrude with positive distance; only the
  selected object should change. Change its material color; the original stays unchanged.
  Undo/redo both edits and the duplication. Repeat a geometry edit on the Pedestal and
  confirm the Teal box is unaffected.
- Create an analytic sphere. Change center/radius and node scale; its curvature remains
  exact and mesh element controls explain their unavailability. Approximate as mesh at
  two chosen detail values. Undo restores sphere parameters and the exact representation.
- Orbit/pan continuously with an element selected. Highlights stay attached to the
  displayed geometry and previews continue updating. Switch modes while picks are pending.
- Save/reopen a new warped scene and open old v1/v2/v3 fixtures. Confirm transforms,
  materials, face IDs and editable polygon topology remain coherent. Older shared files
  should open clean with independent object geometry/materials; editing one object must
  leave the others unchanged. Inspectors should show no Make unique or sharing controls.
- Confirm emitting parallelogram planes and supported volume boxes retain their lighting;
  incompatible geometry edits report a failure without changing the assigned material.

Vertex/edge picking is x-ray: hidden elements are selectable within the documented screen
tolerances (8 logical pixels for vertices, 6 for edges). Deterministic ties use distance
then node and stable element identity. Candidate budgets are independent from wireframe
budgets and retain the first 100,000 stable candidates. Visible cues have a separate
10,000-point/segment budget, prioritizing the selected node. Face boundary/center cues
are x-ray overlays, but face clicks use depth-visible surface queries.

Polygon faces may warp while keeping their IDs. A deterministic first-vertex triangle
fan defines the rendered surface; collapsed/folded fans and nonconvex/self-crossing
projected boundaries remain invalid. New saves use v4; old topology files retain their
original planar validation.
Volume support retains the canonical local ±1 box, with node transforms providing its
position, rotation and dimensions. Asset-coordinate edits that break this capability
are rejected while a scattering material is assigned.
