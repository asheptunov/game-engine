# Engine/editor dispatch state

Scope: E1–E5. E6 deferred. Plan: [EngineImplementationPlan.md](EngineImplementationPlan.md).

| id | title | deps | gui-qa | status | lane | commit | notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A | Engine extraction and independent consumer | — | no | merged | `C:/Users/andri/.codex/worktrees/engine-extraction/RayTracingEngine` | f56ca53 | Independent engine-only build, rendered PNG, session suite and 54-suite log audit passed; documented baseline Caps Lock fixture limitation |
| B | Scene graph, persistence, meshes and queries | A | no | merged | `C:/Users/andri/.codex/worktrees/scene-documents/RayTracingEngine` | f4144c8 | Full scene gate passed; independent engine-only compile, document demo and 16 document/persistence/query tests passed |
| C | Playable scene editor | B | yes | needs-qa | `C:/Users/andri/.codex/worktrees/scene-editor/RayTracingEngine` | ba2d58d | PowerShell 5.1 launcher bug fixed; editor/scene gates and failure probes passed on 5.1; independent build passed; native GUI QA pending |

Admission: C: has ~83 GiB free; reserve 8 GiB and budget 6 GiB per active lane.
Use one writing lane at a time because nodes depend on preceding APIs. Independent
verification happens before integration. Preserve existing uncommitted `Goals.md` edits.

E1/E2 verification logs and preview artifacts preserved in `out/milestones/e1-e2`;
its completed managed worktree is archived and recoverable. Source is on main/origin.

E3/E4 gate logs and independent verification artifacts are preserved in
`out/milestones/e3-e4`; its completed managed worktree is archived and recoverable.

Final editor branch: `codex/scene-editor`. Keep this worktree available for hands-on QA.
Launch from that worktree with `./editor` in Git Bash or `./editor.ps1` in PowerShell.
QA card: `benchmarks/editor/README.md` in the editor worktree. Independent final preview:
`out/orchestrator-verify/editor-preview.png`. No native test windows were opened.

## First GUI feedback refinements

Plan: [EditorRefinementPlan.md](EditorRefinementPlan.md). Base ba2d58d.

| id | title | deps | gui-qa | status | lane | commit | notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| D1 | Shared serializable engine bindings | C | no | merged | `C:/Users/andri/.codex/worktrees/engine-bindings/RayTracingEngine` | 97e3bd4 | Integrated into editor branch; independent core+AWT tests and legacy gate passed |
| D2 | Projection, wireframe and gizmo helpers | C | no | merged | `C:/Users/andri/.codex/worktrees/editor-overlays/RayTracingEngine` | 0d6439b | Integrated into editor branch; independent 12 projection/overlay/gizmo tests passed |
| D3 | Editor controls, overlays and usability | D1, D2 | yes | needs-qa | `C:/Users/andri/.codex/worktrees/scene-editor/RayTracingEngine` | 9f8c450 | PowerShell 5.1 editor/input gates passed; scoped source review clear; window-free overlay/inspector previews produced; native GUI QA pending |
| D4 | Binding preferences and coherent overlays | D3 | yes | needs-qa | `C:/Users/andri/.codex/worktrees/scene-editor/RayTracingEngine` | 0fbc561 | PowerShell 5.1 full input/editor gate passed; coherent moving previews and delayed mode/resize regressions green; native GUI QA pending |
| D5 | Action-first binding preferences | D4 | yes | needs-qa | `C:/Users/andri/.codex/worktrees/scene-editor/RayTracingEngine` | 65db8f8 | Static grouped actions and per-action alternatives; PowerShell 5.1 editor gate passed; native GUI QA pending |

Admission: ~79 GiB free; 8 GiB reserve + 6 GiB per lane fits three isolated writing lanes.

D1/D2 helper worktrees are archived after integration. Final editor commit `9f8c450`
includes both helpers; the editor worktree remains available. Independent final
Windows PowerShell 5.1 engine-only/editor build and complete editor gate passed;
all 34 implementer gate logs were audited without harness failures. Overlay and
conditional inspector previews were visually checked. Evidence lives in
`out/milestones/editor-refinements/d1`, `d2`, and `d3`; updated manual steps are in
[EngineEditorQA.md](EngineEditorQA.md). Native GUI QA remains pending; no test windows opened.

D4 committed as `0fbc561` on the editor branch. Full explicit PowerShell 5.1 input/editor
gate plus independent root editor gate passed (36 logs audited, no harness failures).
Continuous-motion tests verify both overlay coherence and displayed progress; delayed
mode/resize completions cannot restore obsolete context. Camera-only requests share the
immutable scene/selection token so complete intermediate previews remain paintable.
Preferences screenshot inspected; evidence saved under `out/milestones/editor-refinements/d4`.

D5 `65db8f8` provides fixed action groups with per-action binding fields and alternatives.
Implementer and independent root Windows PowerShell 5.1 editor gates passed; 24 logs
audited without harness failures. Root inspected the action-first preview; evidence
saved in `out/milestones/editor-refinements/d5`. Profile/runtime format is unchanged.
