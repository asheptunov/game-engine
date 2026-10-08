# E7 native editor QA

Implementation checkout: `C:/Users/andri/.codex/worktrees/scene-editor/RayTracingEngine`.
Launch `./editor` in Git Bash or `./editor.ps1` in PowerShell. This card records human
acceptance separately from automated headless checks. E7 is implemented through
`940fe72`; independent full engine/editor/transport/input gates and source review passed.
Native interaction acceptance remains pending.

- Create a box and a plane. Switch Object/Vertex/Edge/Face modes immediately; no
  conversion is required and mode changes do not mark the scene dirty or add undo.
- Select a vertex or edge in either view. Check its highlight in both views, translate
  with local delta controls and drag handles, undo/redo, then cancel a drag with Escape.
  Attempt a nonplanar polygon edit: geometry/history should remain at the last valid state.
- Select a face and extrude with positive distance; shared copies should update together.
  Make one copy's geometry unique and repeat; only that copy should change.
- Create an analytic sphere. Change center/radius and node scale; its curvature remains
  exact and mesh element controls explain their unavailability. Approximate as mesh at
  two chosen detail values. Undo restores sphere parameters and the exact representation.
- Orbit/pan continuously with an element selected. Highlights stay attached to the
  displayed geometry and previews continue updating. Switch modes while picks are pending.
- Save/reopen a new scene and open old v1/v2 fixtures. Confirm sharing, transforms,
  materials, face IDs and editable polygon topology remain coherent.
- Confirm emitting parallelogram planes and supported volume boxes retain their lighting;
  incompatible geometry edits report a failure without changing the assigned material.

Vertex/edge picking is x-ray: hidden elements are selectable within the documented screen
tolerances (8 logical pixels for vertices, 6 for edges). Deterministic ties use distance
then node and stable element identity. Candidate budgets are independent from wireframe
budgets and retain the first 100,000 stable candidates.

E7 keeps convex planar faces. Moving one corner of a quad box can make its faces
nonplanar and is rejected; use a plane or triangular mesh for valid element translations.
Volume support retains the canonical local ±1 box, with node transforms providing its
position, rotation and dimensions. Asset-coordinate edits that break this capability
are rejected while a scattering material is assigned.
