# E5 scene editor — hands-on QA

Built and ready: `C:/Users/andri/.codex/worktrees/scene-editor/RayTracingEngine`.
Branch: `codex/scene-editor`, current implementation `0fbc561` (includes launcher fix `ba2d58d`).

Launch from Git Bash:

```bash
cd /c/Users/andri/.codex/worktrees/scene-editor/RayTracingEngine
./editor
```

The launcher compiles the editor and opens it with Java 23 `javaw`; startup errors go
to `out/editor/editor-error.log`. The original playground remains independently runnable.

1. Select **Composition**, create a **Box**, and change its position/rotation/scale using
   **Apply transform (one edit)**.
   **EXPECT:** the hierarchy and both rendered views update; Undo/Redo reverses the whole edit.
2. Left-click objects in both views. Right-click and drag to orbit,
   Shift+right-click and drag to pan, and use the wheel to zoom.
   **EXPECT:** selection matches the visible object; the two cameras move independently.
3. Duplicate an object and edit its shared material. Then **Make unique** and edit again.
   **EXPECT:** the first material edit changes both instances; the second changes only the unique one.
4. Reparent an object, delete it, undo, and try an equivalent text command (`help` lists commands).
   **EXPECT:** reparent preserves local pose, delete removes the subtree, and commands share undo history.
5. Save a `.scene.xml`, edit it, and reopen; try Cancel at the unsaved prompt.
   **EXPECT:** saved identities, hierarchy and materials return; Cancel keeps the current work.
6. Try the camera/light inspectors; minimize, return, and close.
   **EXPECT:** component changes appear in both views, rendering resumes, and close exits cleanly.

## Automated evidence

Passed `editor-check.ps1`, `engine-check.ps1`, `engine.ParallelTraceTest`, and
`editor-build.ps1`. Independent verification compiled the editor using only the
engine classpath, then passed `editor.EditorControllerTest` and
`editor.SceneEditorPreviewTest`. Logs were inspected for harness failures.
Actual Swing panels with rendered views were painted to PNG without opening windows;
native dialogs, mouse feel, and desktop lifecycle remain human QA.

Follow-up: reproduced the javac preview-note failure on Windows PowerShell 5.1 and fixed
native output handling. Editor build, editor checks, and scene checks pass on 5.1;
compiler errors, missing executables, and log-file failures still propagate. The fix
is present in the built worktree and pushed editor branch.

More detailed checks: `benchmarks/editor/README.md` in the editor worktree.

## First feedback refinements

The editor now uses shared engine key/mouse bindings, loaded from
`assets/bindings/scene-editor.properties` and `scene-editor-mouse.properties`.
Restart after editing these files. Left click selects objects and operates handles;
the default middle button has no navigation action.

1. Right-drag left/right, then up/down; hold Shift before right-dragging.
   EXPECT: horizontal orbit follows the corrected direction, vertical behavior is
   unchanged, and only the Shift gesture pans. Left-drag does not orbit.
2. Select a mesh or group; toggle Wireframe and switch Move/Rotate.
   EXPECT: selected geometry or group descendants have dashed wireframes; local
   X/Y/Z handles translate/rotate. Light and camera anchors are visible/selectable.
   Overlays are intentionally visible through geometry (x-ray), using the displayed
   camera's sharp reference projection even with depth of field.
3. Drag a handle, release, then Ctrl+Z and Ctrl+Shift+Z; start another drag and press Escape.
   EXPECT: a whole drag is one undo entry; Escape restores the starting transform.
4. Select a mesh, then a light, then a camera.
   EXPECT: camera/light properties appear only on objects supporting them, with Apply
   buttons. Navigate a view and capture it into the selected camera; undo restores it.
5. Resize the divider above Commands, select/copy output, and type a command in Input.
   EXPECT: Output remains readonly with no insertion caret; recent action is in the
   separate bottom strip. Undo shortcuts work while a property/input field has focus.
6. Open a scene file.
   EXPECT: directories and compatible `.scene.xml` files are shown; unrelated files
   are filtered out. Existing unsaved-work confirmation still applies.

Native dialog behavior, mouse feel, and desktop focus remain hands-on checks.

Refinement verification: Windows PowerShell 5.1 editor and input gates passed,
including legacy texture-editor/viewport bindings. Independent root editor build and
the complete editor gate passed again on the final source; harness logs contain no
failures. Move/rotate, light, camera, and component-absence PNGs were inspected.
Logs and previews are preserved under `out/milestones/editor-refinements/d3`.

## Don't report these (pre-existing)

No known pre-existing bugs in the new editor. The playground's Caps Lock-on synthetic
keyboard test limitation is separately recorded in `EngineRequirements.md`; it is not
an editor GUI test failure. Report any new editor problem.

E6 mesh topology/face extrusion is intentionally outside this delivery. Per the handoff
dispatch policy, the final GUI milestone stays unmerged until hands-on QA is complete.

## D4 follow-up QA — bindings and overlay continuity

These defaults supersede the Shift-pan instructions above: right/middle drag orbit;
Space+right/middle drag pan; wheel zoom. Left remains selection/handles.
Focus the viewport before holding Space. Hold it before pressing the mouse button;
the gesture keeps the key state captured at its start.

Bindings opens from the toolbar. Apply changes the current session; Save also writes
`config/scene-editor-bindings.properties` for the next launch. Close discards unapplied
draft edits (previously applied changes remain). The override file is ignored by Git;
the shipped `assets/bindings/scene-editor*.properties` remain defaults. Mouse chords
use `key.space` for the held key; the older bare `space` token remains a mode name.

1. Open Bindings from the toolbar. Add an alternate shortcut, Apply, and use it.
   EXPECT: both views use the new mappings immediately; scene undo/dirty state is unchanged.
2. Enter a duplicate/conflicting chord or try binding left-drag navigation.
   EXPECT: a clear validation error; previous active bindings still work.
3. Save a valid configuration, close/relaunch the editor, then reopen Bindings.
   EXPECT: mappings persist. Restore defaults changes the draft until applied/saved.
4. Hold Space while right- or middle-dragging; release it, then start another drag.
   EXPECT: first gesture pans; the next orbits. Typing spaces in Input still works;
   changing focus or opening the bindings dialog clears held navigation keys.
5. Select geometry and continuously orbit/pan in both views, then resize and switch Move/Rotate.
   EXPECT: visible rendered images retain matching wireframes/handles without blank-overlay
   frames. A previous complete image may remain briefly while the next pair is prepared.
   Selection and handles continue to target the displayed image.

D4 verification: full Windows PowerShell 5.1 input/editor gate passed, followed by an
independent root editor gate. All 36 logs were audited with zero harness failures.
Tests cover delayed projection, mode/resize reversal, and advancing coherent displayed
pairs during sustained camera motion; preferences and scene previews were inspected.
Evidence: `out/milestones/editor-refinements/d4`. Native input/dialog QA remains pending.
