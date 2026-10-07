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

## D4 — Binding preferences and coherent overlays

Base `9f8c450`; supersedes earlier default gesture choices. One writing lane reuses
the scene-editor checkout; root reviews, verifies and pushes. Preserve root Goals.md.

1. Default right-drag and middle-drag orbit; Space+right-drag or Space+middle-drag pans; wheel zooms.
   Left stays reserved for picking/handles. Space is a held physical key, not an OS
   modifier: extend the common engine mouse chord/input mechanism compatibly to
   support held keys, rather than special-casing pan in the editor. Old bindings
   and constructors retain behavior. Capture gesture at press; release/focus loss,
   dialog opening and close clear transient key/button state; typing spaces works.
2. Add a discoverable Bindings button opening a labelled configuration panel/dialog.
   Users can edit shortcuts and mouse chords, add/remove alternatives, restore
   defaults, cancel, and Apply/Save. Show action names and readable syntax guidance.
   Validate duplicate normalized chords, unknown actions, unsupported gestures and
   reserved left selection before mutation. Apply works immediately across views;
   Save persists across restart. Cancel/errors preserve active mappings. Settings
   must not dirty the scene or enter scene undo history. Derive navigation help
   from effective bindings. Isolate dialog typing from application shortcuts.
3. Prefer one atomic user override profile (ignored `config/scene-editor-bindings.properties`)
   with key/mouse prefixes serialized through the common binding tables. No override
   means shipped defaults. Write a temp sibling then replace before swapping live
   mappings; failed validation/write leaves old mappings and file intact. Use injected
   temporary paths in tests, never overwrite the user's real preferences in tests.
4. Fix presentation gaps by publishing a coherent image + projected overlay bundle.
   Preparing mesh edges stays off the EDT. Projection uses exact captured camera,
   scene/selection token, dimensions and mode; keep previous complete pair until new
   pair ready, never mix old projection with a new camera. Stable spp updates reuse
   projection. Selection/mode/resize changes also produce coherent bundles. Picking
   uses the painted bundle. No unbounded queue, full mesh projection on EDT, or changes
   to transport. Preserve x-ray/toggle behavior and finite-aperture reference.
5. Add deterministic publication/interleaving regressions (every painted image has
   matching overlay during sustained orbit/pan/refinement; no stale pick/handle),
   held-key/reset/typing/remap/conflict/save-failure/restart tests and window-free
   preferences PNG. Run explicit Windows PowerShell 5.1 editor/input gates and inspect
   harness logs; root independently verifies. No native test windows. Update docs/QA.

## D5 — Action-first binding preferences

User requests a static list of actions, each owning editable bindings/alternates.
Reorganize only the preferences UI/model adapter; keep runtime dispatch and saved
profile compatible. Show deterministic History (Undo, Redo, Cancel active handle drag)
and Navigation (Orbit, Pan, Zoom) groups, including unbound actions. Actions are labels,
never editable choices. Each action has editable chord fields/list and Add binding /
Remove binding controls scoped to that action. Removing its last binding leaves the
action visible and unbound; do not remove action rows. Alternatives are equivalent,
so don't imply a persisted primary ordering that the profile does not store.

Preserve Apply, Save, Restore defaults, conflict/error behavior and draft cancellation.
No user profile migration or runtime/renderer changes. Keep the UI compact and scrollable;
inspect a window-free PNG showing defaults plus an unbound action and added alternate.
Use existing preferences tests, extending only for fixed action visibility/ownership,
alternative persistence and conflict preservation. Explicit PS5.1 editor gate required;
root independently reviews/tests. Existing scene-editor writing lane owns changes;
root pushes and records verification. No native test windows; preserve root Goals.md.
