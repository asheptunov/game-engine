# Scene editor verification and manual QA

Automated checks are deliberately window-free. They build the real Swing panel, render two
independent engine views, exercise picks and controller operations, and paint the complete
layout to `out/editor-check/scene-editor-preview.png` with `java.awt.headless=true`.
The mesh journey also writes `out/editor-check/scene-editor-mesh-extruded-preview.png`, with
the selected stable face highlighted in both exact rendered views, and
`scene-editor-elements-preview.png`, with a selected stable edge and translate handles in both
views. `scene-editor-vertex-mode-preview.png`, `scene-editor-edge-mode-preview.png`, and
`scene-editor-face-mode-preview.png` show each active mode's wireframe-independent cues and
selected-element feedback under both exact displayed cameras.

```powershell
./editor-check.ps1
```

Human native-window QA remains required before merging the editor branch. From PowerShell:

```powershell
./editor.ps1
```

From Git Bash:

```bash
./editor
```

The launcher builds when needed, starts `javaw.exe` without a terminal window, and records
startup failures in `out/editor/editor-error.log`.

## Manual QA card

1. Launch the editor. **EXPECT:** an attractive floor/pedestal/sphere/box scene appears in two
   independently rendered views; the hierarchy, inspector, command panel, and saved-state marker
   remain responsive while samples refine.
2. Select `Composition`, create Box, Sphere, Plane, and Group. **EXPECT:** each appears beneath
   the selected parent with a fresh UUID and becomes selected. Select the `Scene` root to create
   at root.
3. Enter position, rotation, and scale, then press **Apply transform (one edit)**. **EXPECT:** both
   views update atomically. Invalid, singular, cycle, shear, or out-of-range values show an error
   and leave the prior scene visible.
4. Click visible objects and the light/camera markers in each view. Toggle **Wireframe** on a
   selected object and on `Composition`. **EXPECT:** hierarchy and inspector select the object shown,
   groups show descendant outlines, and every visible light/camera marker is selectable
   under that exact captured camera. Clicking empty space clears selection; a stale image reports
   `View changed; click again` rather than selecting through a newer camera/scene.
5. Hold the right or middle button and drag to orbit; hold Space before right- or middle-dragging
   to pan; use the wheel to zoom. Left-drag a Move and Rotate axis, then press Escape during another drag.
   **EXPECT:** left click never navigates, each completed handle drag is one undo step, Escape
   restores the prior transform, and navigation is independent per view and never dirties the scene.
6. Duplicate a geometry node. Edit its shared material. **EXPECT:** all users change. Press
   **Make unique**, edit again, and **EXPECT:** only that node changes. Material usage count explains
   sharing.
7. Reparent with **keep local pose**, delete a subtree, then Undo/Redo. **EXPECT:** local numeric pose
   stays unchanged, subtree behavior is atomic, redo clears after a new edit, and both views follow.
8. Select/add a point light and camera. **EXPECT:** the Components tab appears only for a node that
   has a supported component. Point-light color/intensity and node transform, plus camera
   projection/framing/focus/aperture and node transform, are editable and validated. Choose a
   scene camera, navigate while retaining its optics, then press **Capture view**;
   **EXPECT:** the selected camera adopts the displayed pose and full optics in one undo step.
9. Run equivalent `create`, `transform`, `material edit`, `light set`, `camera set`, `undo`, and
   `redo` commands. **EXPECT:** they produce the same hierarchy/inspector/view results as buttons.
10. Save as `.scene.xml`, make another edit, and reopen. **EXPECT:** unsaved New/Open/Close prompts
    Save/Discard/Cancel; saving during a newer edit leaves the marker unsaved; reopen preserves IDs,
    hierarchy, components, and shared assets. A failed load leaves the current world intact.
11. Open **Bindings…**. **EXPECT:** fixed History and Navigation sections show Undo, Redo,
    Cancel active handle drag, Orbit, Pan, and Zoom even when an action is unbound. Each action
    owns its keyboard or mouse alternatives; there is no editable action selector. Add or remove an alternative,
    Apply it, and verify the tooltip/navigation changes immediately. Invalid duplicates, left-drag,
    unknown actions, or non-viewport modes remain unapplied with a visible message. Restore defaults,
    Save, relaunch, and **EXPECT:** the saved profile is active without making the scene dirty.
12. Orbit continuously while toggling Move/Rotate, selecting different nodes, and resizing the
    window. **EXPECT:** the rendered image, wireframe, markers, and handles change as one complete
    frame without blinking off or briefly showing the wrong mode/selection. Picking and dragging
    always target the overlay actually visible.
13. Minimize or move focus away, then return and close. **EXPECT:** rendering suspends while inactive,
    resumes when active, and close exits cleanly without a terminal window or popup error.
14. Select `Teal box` or `Floor` and switch among **Vertex**, **Edge**, and **Face**. **EXPECT:**
    polygon elements are immediately selectable without conversion, a new asset, a dirty mark, or
    an undo entry. Vertex/edge selection is x-ray; close candidates resolve consistently. The
    selected element is highlighted in both views, and Vertex/Edge/Face show Move handles for the
    selected element. Before selection, Vertex points/supporting boundaries, Edge boundaries, and
    Face boundaries/on-surface centers remain visible even with Wireframe off. An analytic sphere
    can still be selected as an object in these modes and reports
    that it has no mesh elements rather than inventing them.
15. With a vertex selected, enter a small **Local delta** such as `0.1 0 0`, apply it, and Undo.
    Repeat on an edge, then drag an axis and press Escape during a second drag. **EXPECT:** numeric
    edits and completed drags are each one undo step, the edge moves both stable endpoints, Escape
    restores the drag-start mesh, and invalid quad/solid/material edits show validation text while
    preserving the prior world. Duplicate a polygon node and confirm a shared edit reaches both;
    use **Make geometry unique** and confirm the next edit affects only one. Orbit continuously
    during these checks; highlights remain paired with their displayed image and keep advancing.
16. In Face mode click a polygon in either view, turn Wireframe off, and **EXPECT:** the selected
    boundary and translate handles remain visible in both views. Enter a small **Local delta** and
    Apply, then drag a handle in the other view and cancel another drag with Escape. **EXPECT:**
    the whole face boundary moves, each completed move is one undo entry, and cancellation restores
    its drag-start topology. Then enter a positive local-unit distance and press
    **Extrude face**. **EXPECT:** one atomic edit moves the stable cap and adds sides. Undo/Redo,
    Save, and reopen; **EXPECT:** topology, stable element identity and rendering survive while
    selection is safely reconciled or cleared if an asset/element disappears. Repeat with
    `mode vertex`, `vertex select <id>`, `vertex move <dx> <dy> <dz>`, `mode edge`,
    `edge select <a> <b>`, `edge move <dx> <dy> <dz>`, `mode face`,
    `face move <dx> <dy> <dz>`, and `face extrude <distance>`.
17. Select `Terracotta sphere` and open **Mesh**. **EXPECT:** asset-local Center X/Y/Z and
    Radius fields are visible; polygon element controls are absent. Apply a changed positive
    radius and center, then Undo/Redo. **EXPECT:** both shared instances update together as an
    exact analytic sphere, identical Apply creates no extra undo step, and Undo restores the
    exact previous center/radius. Repeat with
    `sphere set <center-x> <center-y> <center-z> <radius>`.
18. Set **Detail** to 12 and click **Approximate as mesh**. **EXPECT:** the explanation states
    that the shared asset is replaced and analytic parameters are lost; the same nodes,
    transforms, materials, and geometry identity remain. Polygon controls become available in
    both views. Undo restores the exact analytic sphere. A scattering sphere rejects
    approximation without changing the scene. Save/reopen both states and repeat with
    `mesh approximate 12`; **EXPECT:** canonical v4 sphere/polygon data and sharing persist.

Shipped defaults are `assets/bindings/scene-editor.properties` and
`assets/bindings/scene-editor-mouse.properties`. **Bindings…** saves the single ignored user profile
`config/scene-editor-bindings.properties` atomically. An invalid profile is preserved, reported in
the status strip, and ignored in favor of the shipped defaults.
