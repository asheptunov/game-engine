# Scene editor verification and manual QA

Automated checks are deliberately window-free. They build the real Swing panel, render two
independent engine views, exercise picks and controller operations, and paint the complete
layout to `out/editor-check/scene-editor-preview.png` with `java.awt.headless=true`.

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
5. Hold the right button and drag to orbit; hold Shift before right-dragging to pan; use the
   wheel to zoom. Left-drag a Move and Rotate axis, then press Escape during another drag.
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
11. Minimize or move focus away, then return and close. **EXPECT:** rendering suspends while inactive,
    resumes when active, and close exits cleanly without a terminal window or popup error.

Binding files are `assets/bindings/scene-editor.properties` and
`assets/bindings/scene-editor-mouse.properties`; restart the editor after changing them.
