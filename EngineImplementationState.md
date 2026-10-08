# Engine/editor dispatch state

Scope: E1–E7 implemented; main integration authorized 2026-10-08. Original plan: [EngineImplementationPlan.md](EngineImplementationPlan.md).
E6 authorized 2026-10-07; plan: [MeshEditingPlan.md](MeshEditingPlan.md).

The user approved integration of the complete editor branch into main on 2026-10-08.
Earlier delivery notes below retain their historical QA status; current table statuses
reflect that integration approval.

| id | title | deps | gui-qa | status | lane | commit | notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A | Engine extraction and independent consumer | — | no | merged | `C:/Users/andri/.codex/worktrees/engine-extraction/RayTracingEngine` | f56ca53 | Independent engine-only build, rendered PNG, session suite and 54-suite log audit passed; documented baseline Caps Lock fixture limitation |
| B | Scene graph, persistence, meshes and queries | A | no | merged | `C:/Users/andri/.codex/worktrees/scene-documents/RayTracingEngine` | f4144c8 | Full scene gate passed; independent engine-only compile, document demo and 16 document/persistence/query tests passed |
| C | Playable scene editor | B | yes | merged | `C:/Users/andri/.codex/worktrees/scene-editor/RayTracingEngine` | ba2d58d | PowerShell 5.1 launcher bug fixed; editor/scene gates and failure probes passed on 5.1; independent build passed; native GUI QA pending |

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
| D3 | Editor controls, overlays and usability | D1, D2 | yes | merged | `C:/Users/andri/.codex/worktrees/scene-editor/RayTracingEngine` | 9f8c450 | PowerShell 5.1 editor/input gates passed; scoped source review clear; window-free overlay/inspector previews produced; native GUI QA pending |
| D4 | Binding preferences and coherent overlays | D3 | yes | merged | `C:/Users/andri/.codex/worktrees/scene-editor/RayTracingEngine` | 0fbc561 | PowerShell 5.1 full input/editor gate passed; coherent moving previews and delayed mode/resize regressions green; native GUI QA pending |
| D5 | Action-first binding preferences | D4 | yes | merged | `C:/Users/andri/.codex/worktrees/scene-editor/RayTracingEngine` | 65db8f8 | Static grouped actions and per-action alternatives; PowerShell 5.1 editor gate passed; native GUI QA pending |

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

## E6 mesh editing

| id | title | deps | gui-qa | status | lane | commit | notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| F1 | Editable topology, extrusion and persistence | D5 | no | merged | existing scene-editor checkout | c1dc59a | Committed on editor branch; independent PS5.1 gate and source review passed, including self-touch polygon fix |
| F2 | Face selection and modelling UI | F1 | yes | merged | existing scene-editor checkout | 404090a | Independent full PS5.1 input/editor gate passed; post-extrusion dual-view preview inspected; human GUI acceptance pending |

Admission: ~75 GiB free on C:, above 8 GiB reserve + 6 GiB single-lane budget.

F1/F2 are committed on `codex/scene-editor`; final implementation commit `404090a`.
All 63 final implementer/root logs audited without harness failures. Evidence copied to
`out/milestones/e6`; manual steps: [MeshEditorQA.md](MeshEditorQA.md).

## E7 specification

Recorded 2026-10-07 in [EngineRequirements.md](EngineRequirements.md): analytic versus
polygon asset representations, direct mesh modes/element edits without conversion,
optional analytic approximation, derived renderer geometry, validated material
capabilities and backwards-compatible persistence.

Plan: [GeometryRepresentationPlan.md](GeometryRepresentationPlan.md).

| id | title | deps | gui-qa | status | lane | commit | notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| G1 | Canonical geometry and renderer boundary | F2 | no | merged | existing scene-editor checkout | 238d54c | Pushed editor branch; independent PS5.1 full gate/source review clear; 62 implementer/root logs audited, zero failures |
| G2 | Direct vertex/edge editing | G1 | yes | merged | existing scene-editor checkout | a184574 | Pushed editor branch; independent full PS5.1 gate/source review clear; dual-view elements PNG inspected |
| G3 | Analytic parameters and explicit approximation | G2 | yes | merged | existing scene-editor checkout | 940fe72 | Pushed editor branch; independent PS5.1 full gate/source review clear; analytic/approximation previews inspected |

Admission: 54 GiB free on C:, above 8 GiB reserve + 6 GiB single writing lane.

E7 is implemented and pushed on `codex/scene-editor` through `940fe72`.
Final implementer/root full Windows PowerShell 5.1 input/editor/transport gates passed;
64 final logs audited with zero harness failures. Independent source review is clear.
Headless elements, analytic sphere and chosen-detail approximation previews inspected.
Root evidence: `out/milestones/e7/e7-root-final` (plus independent G1/G2 gates).
Human QA: [GeometryEditorQA.md](GeometryEditorQA.md); editor checkout remains unmerged
into main for native acceptance. Shared edits, strict planar/convex polygons, x-ray
element picking and canonical-box volume restrictions are documented there.

## E7 native feedback fixes

Plan: [GeometryEditingFeedbackPlan.md](GeometryEditingFeedbackPlan.md). User requests
usable cube deformation, face translation and visible unselected mesh-mode cues.

| id | title | deps | gui-qa | status | lane | commit | notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| H1 | Polygon deformation and face translation | G3 | no | merged | existing scene-editor checkout | ad83244 | Pushed editor branch; root/implementer full PS5.1 gates clear, 64 logs audited; source review clear |
| H2 | Mode cues and face editing UI | H1 | yes | merged | existing scene-editor checkout | bdec663 | Pushed editor branch; independent full gate/review clear; all three mode previews inspected |

H1/H2 feedback fixes are implemented and pushed through `bdec663`. Final implementer
and root full Windows PowerShell 5.1 input/editor/transport gates pass (30 suites/223
tests per final run, 64 combined logs audited with zero failures). Independent source
review is clear. Actual Teal box vertex/edge/face XYZ numeric edits, two-view handles,
stale face picks and coherent motion are tested. Root evidence:
`out/milestones/e7/e7-h1-root-final` and `out/milestones/e7/e7-h2-root-final`.
Human QA remains separate; [GeometryEditorQA.md](GeometryEditorQA.md) now supersedes
the original planar-face restrictions. Editor checkout stays unmerged into main.

## Independent objects follow-up

Plan: [IndependentObjectsPlan.md](IndependentObjectsPlan.md). User confirmed both
geometry and materials should be independent; editor linking workflows are removed.

| id | title | deps | gui-qa | status | lane | commit | notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| I1 | Independent geometry and materials | H2 | yes | merged | existing scene-editor checkout | 22e5a0f | Pushed editor branch; 31 suites/230 tests, scoped style/review clear; inspector preview inspected |

I1 is implemented and pushed through `22e5a0f`. Creating and duplicating objects gives
each its own geometry and material identity; legacy aliases normalize on the I/O worker
before a clean, history-free load. Linking/Make unique controls and commands are removed.
Implementer and root full PS5.1 gates passed 31 suites/230 tests per run. Root full-run
source hashes were stable; final test-only cleanup passed a fresh 12-test controller run.
Scoped pinned Checkstyle/PMD reports zero new/increased findings, without baseline changes.
Full main `verify.ps1` remains unavailable/red during unrelated readability tooling work;
that checkout was preserved. Evidence/limitations: [IndependentObjectsPlan.md](IndependentObjectsPlan.md).
Native acceptance: [GeometryEditorQA.md](GeometryEditorQA.md). Editor remains unmerged.

## Main integration (2026-10-08)

The user authorized merging the complete editor branch through `22e5a0f` into main.
Code merged cleanly; the specification conflict retained current ownership requirements
and prior delivery evidence. Main verification passed:

- `input-check.ps1 -OutputDirectory out/main-editor-final`: 31 suites, 230 tests.
- `scene-check.ps1 -OutputDirectory out/main-scene-merge`: 14 suites, 97 tests and
  both independent headless consumer examples.
- Four additional fresh-scene preview runs passed after fixing a test-only ambiguity
  between a projected candidate and the actual vertex/edge winner after pixel rounding.
  Helpers now resolve the exact click and require its requested node. Cross-view tests
  capture the full accepted selection and verify older completions cannot overwrite it.
  Production input/picking behavior is unchanged.

Independent semantic review is clear. Scoped pinned Checkstyle/PMD comparison of the
test-only integration fix against `22e5a0f` found zero genuine new/increased findings;
the large preview test's NCSS decreased 451→435. Two changed legacy statement anchors
were mapped to their prior equal-count lines without changing a repository baseline.
Final 55-log audit reported zero failures, and Java hashes remained stable through
the final verification/review period.

Unrelated readability work was saved intact before merging in stash
`9be398bbbb77108d7c6952d60f125301547b320c`, named
`readability work preserved before scene-editor merge 2026-10-08`. It includes all
tracked modifications and untracked tooling. A non-mutating apply check confirmed
overlap with replaced engine code, so it remains unapplied. `verify.ps1` belongs to
that saved work; it is not part of this editor integration's tracked main tree.

The editor can now be launched from the main checkout with `./editor.ps1` or `./editor`.
Historical branch-only and pending-integration notes above are superseded by this section.
