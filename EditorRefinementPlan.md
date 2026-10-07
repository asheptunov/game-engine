# Editor refinement after first hands-on QA

Base: `codex/scene-editor` at ba2d58d. Preserve root Goals.md edits. Keep the editor
branch unmerged until human GUI QA. User feedback items 1–10 define this delivery.

## Settled behavior

1. Strict file-backed actions: right-button drag orbits, Shift+right-button drag pans,
   wheel zooms, left click selects/operates gizmos. Horizontal orbit reverses the first
   implementation; vertical direction stays unchanged. No implicit middle/left orbit.
   Describe held gestures as “Right-click and drag”, including the modifier for pan.
2. Generalize the existing properties binding mechanism (5a2f54b and adjacent commits)
   into platform-neutral engine input data/dispatch/serialization. Keep AWT adapters
   outside engine. Existing playground/texture-editor bindings retain compatibility.
   Round-trip files and validate invalid chords/unresolved actions. Editor actually uses
   the shared mechanism for navigation and Ctrl+Z/Ctrl+Shift+Z, not a parallel map.
3. Selection overlays use the exact painted camera/snapshot/content rectangle. Wireframe
   of selected geometry (and geometry descendants of a selected group) is toggleable.
   Light/camera markers are visible and selectable, including objects without surfaces.
   These are editor overlays, not transport geometry. Use explicit x-ray overlay styling;
   finite-aperture cameras use their sharp reference projection, as picking does.
4. Translation and rotation handles operate on the selected node, including lights.
   An entire drag is one undo entry; Escape/focus loss cancels; invalid hierarchy/shear
   edits preserve the prior document. Keep numeric transforms too. Axis semantics must
   be labelled, and hit-testing uses displayed handle geometry. No mesh-topology edits.
5. Show light properties only for light components and camera properties only for camera
   components. Hide unsupported sections. Buttons say Apply; creation uses toolbar or
   duplication. Remove component-add affordances from the ordinary inspector.
6. A per-view “Set selected camera from this view” action atomically captures current view
   pose AND optics into an existing selected camera. Preserve its parent/reference and
   convert world to local correctly; reject unsupported compositions clearly. One undo.
7. Commands use a vertical resize divider, labelled Output/Input, copyable readonly output
   without insertion caret, and a separate bottom status strip. Bound log growth.
8. Open/save dialogs default to compatible `.scene.xml` files (directories navigable);
   disable all-files filter. Preserve unsaved-work protections and error diagnostics.

## Lanes

- D1 shared bindings: isolated worktree; owns engine/input, ui compatibility/adapters,
  binding tests/docs. No editor UI edits. Publishes an API sketch before implementation.
- D2 projection/overlay geometry: isolated worktree; owns new reusable projection API and
  editor overlay/gizmo geometry helpers plus their tests. No edits to existing editor UI
  files. Publishes API sketch so D3 can integrate.
- D3 editor integration: existing scene-editor worktree; owns editor UI/controller,
  binding configuration, scripts/docs and integration tests. Integrates D1/D2 commits
  after review; no concurrent writers in one checkout. Root owns pushes and tracking.

All lanes use Java23 preview. Verification must explicitly exercise Windows PowerShell
5.1 build/check scripts, inspect harness logs, and render actual Swing panels headlessly.
No test windows or focus takeover. Review focused changes and test failure/lifecycle paths.
Independent root checks final compilation, targeted tests and overlay/inspector PNGs.
