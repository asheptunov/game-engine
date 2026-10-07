# E6 mesh editor QA

Checkout: `C:/Users/andri/.codex/worktrees/scene-editor/RayTracingEngine`.
Implementation: `404090a` on `codex/scene-editor`; automated checks passed.
Launch there using `./editor` in Git Bash or `./editor.ps1` in PowerShell.
E6 implements one selected face at a time and positive extrusion along its local
winding normal. Face overlays are x-ray boundaries; they do not promise depth occlusion.

1. Create a box and open its Mesh inspector. Convert it to an editable mesh.
   EXPECT: six editable quad faces; conversion is one undoable edit.
2. Switch to Face selection mode and click a visible box face in either view.
   EXPECT: the selected face ID appears in the inspector; its boundary highlights
   in both views, including when general wireframe is disabled.
3. Enter a positive extrusion distance and click Extrude face.
   EXPECT: the cap moves along its normal and new side faces connect it to the base;
   the cap stays selected. Both views show the same shape.
4. Orbit and pan continuously, then select another face and extrude again.
   EXPECT: coherent image/highlight pairs continue updating; object handles do not
   start drags in Face mode. Object mode restores existing object selection/handles.
5. Undo and redo. Try zero or negative extrusion distance.
   EXPECT: each extrusion is one history entry; invalid distance gives a useful
   message and preserves geometry and history.
6. Duplicate the mesh, then extrude its shared geometry. Make one instance's geometry
   unique and extrude it again.
   EXPECT: shared edits affect both references; unique edits affect one instance.
   The inspector states the sharing count.
7. Save, reopen, select a face and extrude it.
   EXPECT: editable topology, face IDs and sharing survive; loaded geometry remains
   editable. Selection itself is application state, not saved scene content.
8. Convert a plane or sphere and try face selection/extrusion. Close the editor.
   EXPECT: plane remains an open surface; sphere conversion is explicitly faceted.
   Normal file/unsaved safeguards and clean close remain operational.

Pre-existing limitations outside E6: no general mesh self-intersection detection,
polygon repair, multi-face extrusion, vertex/edge manipulation, or texture/UV editing.
Scattering and emissive materials retain the renderer's prior analytic-geometry restrictions.
