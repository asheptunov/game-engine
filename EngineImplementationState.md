# Engine/editor dispatch state

Scope: E1–E5. E6 deferred. Plan: [EngineImplementationPlan.md](EngineImplementationPlan.md).

| id | title | deps | gui-qa | status | lane | commit | notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A | Engine extraction and independent consumer | — | no | merged | `C:/Users/andri/.codex/worktrees/engine-extraction/RayTracingEngine` | f56ca53 | Independent engine-only build, rendered PNG, session suite and 54-suite log audit passed; documented baseline Caps Lock fixture limitation |
| B | Scene graph, persistence, meshes and queries | A | no | merged | `C:/Users/andri/.codex/worktrees/scene-documents/RayTracingEngine` | f4144c8 | Full scene gate passed; independent engine-only compile, document demo and 16 document/persistence/query tests passed |
| C | Playable scene editor | B | yes | needs-qa | `C:/Users/andri/.codex/worktrees/scene-editor/RayTracingEngine` | a793699 | Editor and engine gates passed; independent engine-only editor build and real-panel/controller tests passed; native GUI QA pending |

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
