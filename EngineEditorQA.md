# E5 scene editor — hands-on QA

Built and ready: `C:/Users/andri/.codex/worktrees/scene-editor/RayTracingEngine`.
Branch: `codex/scene-editor`, implementation commit `a793699`, launcher fix `ba2d58d`.

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
2. Click objects in both views. Drag to orbit, Shift/right-drag to pan, wheel to zoom.
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

## Don't report these (pre-existing)

No known pre-existing bugs in the new editor. The playground's Caps Lock-on synthetic
keyboard test limitation is separately recorded in `EngineRequirements.md`; it is not
an editor GUI test failure. Report any new editor problem.

E6 mesh topology/face extrusion is intentionally outside this delivery. Per the handoff
dispatch policy, the final GUI milestone stays unmerged until hands-on QA is complete.
