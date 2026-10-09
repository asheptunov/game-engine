# Scene and mesh editor performance plan

Status: the first optimization pass is complete. EP01–EP05 and EP07 are implemented and verified automatically. Human drag testing passed for EP01/EP02; later GUI testing is pending. EP06 and EP08–EP10 are deferred using the measurements and criteria below. Baseline: `ae8c129`, investigated October 8, 2026.

The Swing scene editor becomes visibly unresponsive during rapid vertex dragging, even on the eight-vertex Teal box in the starter scene. The first priority is reliable display of completed edits during sustained input. Larger-mesh costs also need attention, but reducing those costs alone will not resolve the reproduced Teal box stall.

This is the implementation tracker for editor responsiveness. [PerformanceRequirements.md](PerformanceRequirements.md) covers the broader renderer roadmap. This plan does not replace its phases or change the editor's geometry validity, picking, or undo requirements.

## Evidence and interpretation

Measurements used Windows on an Intel Core i5-13600KF, OpenJDK 23.0.1, a window-free 1400 by 850 Swing panel, the starter scene, and both render views active. Painting was to a buffered image. These are exploratory single-run measurements, not monitor FPS or a reproduction of physical mouse delivery. Rendering remained asynchronous. Default wireframe was enabled except where stated otherwise. Each view used the editor defaults: depth 3, one sample per pixel per batch, target 8 samples, up to four workers, and its view-title-derived seed. Trace dimensions came from the editor's aspect-dependent sizing policy and were not recorded in the original exploratory logs. The maintained EP01 benchmark now records them; both panes used 360 by 225 grids in the completed-phase comparisons below.

A **fresh displayed frame** here means a painted scene revision different from the previously painted revision in View A. View A is the left render pane titled "View A" in the scene editor; View B is the right pane titled "View B". Repainting the same revision does not count. A **candidate** is a completed rendered image awaiting display with its corresponding editing overlay. The overlay includes selection cues and handles. The **event dispatch thread (EDT)** is Swing's thread for input and painting. A **BVH** is a hierarchy of bounding boxes used to accelerate ray intersection queries.

### Rapid dragging of the Teal box

An eight-second sinusoidal translation of one vertex used independent Swing timers for edits and painting. Painting requested a 16 ms interval. Input intervals below are requested timer intervals; actual delivery was slower. Candidate counts were observed at paint time and may omit intermediate candidates.

| Requested input interval | Accepted edits | Paint calls | Candidate generations observed | Fresh displayed revisions | Fresh frames per second | Longest interval or final hold |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 50 ms | 124 | 274 | 124 | 119 | 14.86 | 158 ms |
| 16 ms | 265 | 265 | 227 | 37 | 4.62 | 636 ms |
| 4 ms | 365 | 272 | 270 | 2 | 0.25 | 3998 ms |

All three runs reported zero cancelled render jobs and zero wasted paths in View A. All caught up to the current document after editing stopped. [Raw results](benchmarks/editor-drag/fresh-drag.log).

The initial leading explanation was starvation in the image/overlay handoff in [RenderViewPanel.java](src/editor/RenderViewPanel.java), particularly `tick`, `projectOverlay`, and `paintComponent`. A newer candidate could supersede the image for which an overlay was being prepared. Publication and painting required a matching context, so the previous painted frame could remain visible despite many completed renders. The evidence distinguished this from cancelled-render starvation. The first-phase regression test below now confirms this ordering and the implemented fix.

### Mesh size and painting costs

The edit benchmark used 60 warm-up updates and 180 measured updates per case. Updates and explicit paints ran sequentially, with a 10 ms sleep between panel iterations. It measured work cost, not fresh-image progress. Means for vertex dragging with the full panel:

| Mesh | EDT edit and UI refresh | Panel painting |
| --- | ---: | ---: |
| Box with 6 faces | 2.0 ms | 6.6 ms |
| Sphere with 960 faces | 2.9 ms | 18.5 ms |
| Sphere with 3968 faces | 7.2 ms | 40.0 ms |

For the largest mesh, the 95th percentiles were 11.9 ms for edits and 59.4 ms for painting. Each update allocated about 9 MiB on the EDT. The 960-face mesh allocated about 2.2 MiB per update. [Timing and allocation results](benchmarks/editor-drag/results.log).

A separate stationary test isolated selection-mode painting on the 3968-face mesh, after 60 warm-up paints and over 150 measured paints. With wireframe disabled, object mode averaged about 6 ms, vertex mode 41 ms, and edge mode 22 ms. With default wireframe, object mode averaged 16–17 ms, vertex mode 50 ms, and edge mode 28 ms. No mesh edits occurred in this comparison. [Wireframe disabled](benchmarks/editor-drag/paint-no-wireframe.log), [wireframe enabled](benchmarks/editor-drag/paint.log).

Java Flight Recorder execution samples also identified whole-mesh construction on the EDT and repeated picking/BVH preparation on the view threads. Sample counts are inclusive stack observations, not exclusive timing percentages; EDT samples were sparse in several panel runs. [Sample summaries](benchmarks/editor-drag/samples.log).

## Work tracker

Status values are Proposed, In progress, Blocked, Verified, and Deferred. Verified means the linked automated checks and measurements pass; human GUI outcomes are recorded separately. Change a status only with a linked implementation or measurement result. Preserve the IDs as work is split into issues or pull requests. Codex implemented EP01–EP05 and EP07 in five delivery phases: phase 1 covers EP01/EP02, phase 2 covers EP03, phase 3 covers EP04, phase 4 covers EP05, and phase 5 covers EP07. Deferred follow-ups remain unassigned.

| ID | Priority | Status | Work item | Completion evidence |
| --- | --- | --- | --- | --- |
| EP01 | First | Verified | Make fresh-frame progress measurable and reproducible | [Maintained benchmark](tst/editor/EditorDragBenchmark.java), [deterministic regression](tst/editor/RenderViewHandoffTest.java), and first-phase results below |
| EP02 | First | Verified | Prevent image/overlay publication starvation | [Handoff implementation](src/editor/RenderViewPanel.java), first-phase results below, and successful human drag test |
| EP03 | Next | Verified | Avoid rebuilding unrelated Swing controls during edits | [Control-preservation test](tst/editor/EditorControlRefreshTest.java) and second-phase measurements below; human GUI test pending |
| EP04 | Next | Verified | Cache dense mesh-cue and wireframe painting | [Raster tests](tst/editor/OverlayRasterCacheTest.java) and third-phase measurements below; human GUI test pending |
| EP05 | Next | Verified | Update moved mesh vertices incrementally | [Full-validation comparisons](tst/engine/IncrementalMeshTest.java) and fourth-phase measurements below; human GUI test pending |
| EP06 | Later | Deferred | Reuse scene snapshot construction work | Two constructors cost about 0.015 ms warmed up in the fifth-phase benchmark; revisit if scene-wide publication becomes material |
| EP07 | Next | Verified | Share picking preparation and reuse unchanged objects | [Concurrent/reuse/reference tests](tst/engine/SharedSpatialQueryTest.java) and fifth-phase measurements below; human GUI test pending |
| EP08 | Conditional | Deferred | Coalesce excess drag updates | Existing Teal-box progress and settling targets pass without input coalescing; revisit if excess input work dominates |
| EP09 | Conditional | Deferred | Improve render scheduling during geometry edits | Completed previews progress during sustained input; revisit if cancellation causes starvation or tracing dominates |
| EP10 | Conditional | Deferred | Add cheaper visual feedback while dragging | Teal-box targets pass with existing quality; revisit if a defined heavier workload needs a quality policy |

EP01 now enables evaluation of every item. EP01–EP05 and EP07 are verified. Rank future follow-ups by gains in fresh displayed frames while preserving immutable geometry ownership and cache invalidation. EP06 and EP08–EP10 have explicit reopening criteria above. Conditional items are alternatives to evaluate, not commitments to reduce quality or relax correctness.

## First phase completed: EP01 and EP02

Commit `ae728ea` implemented EP01/EP02 and was pushed to `origin/main`. The implementation in `RenderViewPanel` allows a completed image/overlay pair to advance even when a newer geometry revision is waiting. The pair retains its captured camera, scene, image, overlay and picking token. Reusing an overlay for a different image still requires exact token and camera correspondence. Selection, tool mode and projected viewport dimensions still reject superseded projections. Close prevents new overlay publication. Only the existing bounded candidate, ready pair and painted pair are retained; intermediate input is not queued.

`RenderViewHandoffTest` manually advances the private handoff with the view workers stopped. It forces twenty repetitions of a newer geometry candidate arriving before an older projection completes, checks coherent progress and final catch-up, and covers superseded selection mode, tool, size and close contexts. The progress assertion [fails on the original code](benchmarks/editor-drag/phase1-regression-before.log) and [passes with the fix](benchmarks/editor-drag/phase1-regression-after.log). Existing editor preview tests additionally cover camera motion, both views, vertex/edge/face gestures, stale picks, invalid edits, release, undo, redo, cancellation and resizing.

The maintained benchmark measures both views, actual edit delivery, edit/paint cost, EDT edit allocations, observed candidate generations and overlay serials, fresh painted revisions, edit-to-paint age, longest holds including the final hold, cancellation and final-revision latency. Candidate/overlay counts are observations at paint time, not exhaustive worker completion counts. The benchmark uses one vertex of the Teal box, with a baseline-relative sinusoidal X translation of amplitude 0.15 scene units. Each eight-second case has 100 warm-up paints at requested 30 ms intervals. Input and paint use independent Swing timers. Trace grids were 360 by 225 in both views; other settings match the evidence section above.

Three matched repetitions ran on the baseline machine, first with the original `ae8c129` view class and then with the updated class. The benchmark and other compiled classes were identical:

| Requested input interval | View A median fresh FPS before / after | View B median fresh FPS before / after |
| --- | ---: | ---: |
| 16 ms | 2.75 / 28.97 | 2.75 / 28.97 |
| 4 ms | 0.62 / 32.84 | 0.62 / 32.84 |

Across the updated cases, the longest interval including startup and final holds was 139 ms; the worst per-run 95th percentile was 64 ms. Final-revision latency was at most 56 ms. Actual input delivery ranged from 32 to 55 edits per second. All cases met the proposed fresh-frame and settling targets. The slowest original case held one revision throughout its eight-second drag. Small numbers of render cancellations occurred in some repetitions, while other stalled baseline cases had zero; cancellation remains a separate conditional concern. [Matching baseline logs](benchmarks/editor-drag/phase1-before.log), [updated logs with timing and allocation statistics](benchmarks/editor-drag/phase1-after.log).

`./verify.ps1` passed, followed by `editor.RenderViewHandoffTest`, `editor.EditorControllerTest`, `editor.GizmoDragTest`, `editor.OverlayGeometryTest` and `editor.SceneEditorPreviewTest`. Two resolved style-baseline allowances were removed; none were added. The user tested dragging in the application on October 8 and reported a very noticeable improvement. This confirms the observed interaction improvement; broader GUI testing on dense meshes and different display scales remains useful for later phases.

To repeat the current measurements, run `./verify.ps1` to compile sources and tests, then from the repository root:

```powershell
& "$env:USERPROFILE/.jdks/openjdk-23.0.1/bin/java.exe" --enable-preview '-Djava.awt.headless=true' -cp out/scene-check/base/classes editor.EditorDragBenchmark 3
```

The first argument is the number of repetitions. An optional second argument selects `vertex`, `edge` or `face`; the recorded three-run comparison used `vertex`. For a historical comparison, compile the original `RenderViewPanel.java` from `ae8c129` into a separate directory and place that directory first on the same classpath. Window-free results measure software painting, not monitor scanout or physical mouse latency.

The second phase below reduces control-refresh costs. Dense-overlay caching under EP04 is complete below. Incremental mesh editing under EP05 is complete below; remeasure before adopting further cache changes or conditional quality policies.

## Second phase completed: EP03

Commit `aa21dd3` implemented EP03 and was pushed to `origin/main`.

`SceneEditorPanel` rebuilds its hierarchy only when displayed node IDs, labels, parents, order or icons change. Selection changes update the existing tree selection. Inspector tabs and parent choices remain in place when their content is unchanged. Inspector fields refresh when their underlying label, transform, geometry, material, light or camera content changes, preserving drafts in unrelated fields. `RenderViewPanel` retains camera choices when their IDs and labels are unchanged. Rendering still receives every accepted scene publication.

`EditorControlRefreshTest` checks twenty geometry updates for unchanged tree identity, inspector tab identity, field drafts, caret position and absence of tab/dropdown reconstruction events. It then verifies that rename, transform, material and selection changes refresh their controls. The test [fails with the phase-one UI](benchmarks/editor-drag/phase2-regression-before.log) and [passes with EP03](benchmarks/editor-drag/phase2-regression-after.log). `./verify.ps1` and all six relevant editor suites passed: the new control test, handoff test, editor preview test, controller test, gizmo test and overlay geometry test. Seven resolved style-baseline allowances were removed; none were added.

Three matching benchmark repetitions compared the `ae728ea` UI classes with EP03 on the same machine and with the first-phase settings. Medians below are medians of the three per-run means, not pooled event percentiles:

| Requested input interval | EDT edit mean before / after | EDT allocation per edit before / after | View A median fresh FPS before / after |
| --- | ---: | ---: | ---: |
| 16 ms | 1.084 / 0.379 ms | 106.1 / 69.7 KiB | 28.73 / 29.96 |
| 4 ms | 1.123 / 0.377 ms | 104.9 / 68.7 KiB | 32.73 / 32.84 |

EDT edit work decreased about 65–66%, with about 34% less allocation. Both views retained the EP02 responsiveness targets: all updated cases exceeded 27 fresh frames per second, the longest hold was 135 ms, and final-revision latency was at most 53 ms. View B median fresh FPS changed from 28.73 to 29.96 at 16 ms and from 32.60 to 32.72 at 4 ms. Painting remains the larger cost for this small scene. [Before logs](benchmarks/editor-drag/phase2-before.log) and [after logs including paint cost and percentiles](benchmarks/editor-drag/phase2-after.log).

Human GUI test pending: type an unfinished transform or material value, switch inspector tabs, and drag a vertex or edge repeatedly. Confirm the unrelated draft and selected tab stay in place. Rename/reparent objects, select another object or clear selection, and add/remove a scene camera to confirm structural controls still refresh. The user authorized committing, pushing and continuing without waiting for a GUI response.

## Third phase completed: EP04

Commit `959ea94` implemented EP04 and was pushed to `origin/main`.

Dense overlays now use one reusable transparent raster per render view in [OverlayRasterCache.java](src/editor/OverlayRasterCache.java). `RenderViewPanel` uses the cache for at least 256 cues, including wireframe when enabled; smaller overlays retain direct drawing. A new projected overlay rasterizes once. Repeated paints of that exact frame reuse its pixels. Picking still uses the original projected geometry.

The cache checks frame identity, content rectangle, wireframe setting, raster dimensions, font, rendering hints, display scale and fractional pane origin. It retains at most 16 MiB of pixel payload per view and releases that buffer on close or return to a small overlay. Oversize rasters, rotation/shear and nonstandard alpha composition use direct drawing. `OverlayRasterCacheTest` compares translucent overlapping cues and text against direct drawing at 100%, 125%, 150% and 200% scale, including fractional pane origins. Differences are bounded to three channel levels from premultiplied alpha rounding. The suite also checks cache invalidation, reuse and fallback. `./verify.ps1`, this suite, the handoff/control suites, `SceneEditorPreviewTest` and `OverlayGeometryTest` passed. No style allowances were added.

[EditorOverlayBenchmark.java](tst/editor/EditorOverlayBenchmark.java) measures stationary full-panel painting with a 3968-face sphere at detail 32, both views active, 20 warm-up paints and 60 measured paints. Three repetitions compared the `aa21dd3` view class with EP04 using identical compiled dependencies. Medians of per-run paint means with default wireframe were:

| Selection mode | Before | After |
| --- | ---: | ---: |
| Object | 19.916 ms | 6.074 ms |
| Vertex | 61.081 ms | 5.505 ms |
| Edge | 38.895 ms | 5.392 ms |

Vertex and edge painting improved about 91% and 86%. With wireframe disabled, the vertex median changed from 43.209 to 5.013 ms and edge from 23.873 to 4.302 ms. Object mode without wireframe uses direct drawing, so variation in that case is not attributed to caching. [Stationary before](benchmarks/editor-drag/phase3-stationary-before.log), [stationary after](benchmarks/editor-drag/phase3-stationary-after.log). Repeat with `editor.EditorOverlayBenchmark 3` on the compiled classpath above.

Active dragging needs a fresh raster for each projected edit. The same three-repeat drag workload at sphere detail 32 uses amplitude 0.0001 scene units to avoid invalid large moves. Median full-panel paint means changed from 51.090 to 46.525 ms at requested 16 ms input and from 48.844 to 46.067 ms at 4 ms. View A median fresh FPS changed from 14.36 to 15.43 and from 15.09 to 16.13 respectively; View B changed from 14.11 to 15.19 and from 14.96 to 15.33. Some dense-mesh holds still exceeded 200 ms. This is a modest active-drag gain, and dense geometry construction and fresh cue drawing remain bottlenecks. [Dense before](benchmarks/editor-drag/phase3-dense-before.log), [dense after](benchmarks/editor-drag/phase3-dense-after.log). Run the drag benchmark with `3 vertex 32` to select this workload; the third argument is sphere approximation detail, with zero retaining the default Teal box.

The small Teal box retained direct drawing. Its three-repeat check met the responsiveness targets in both views: 30–33 fresh FPS, longest hold 147 ms and final-revision latency at most 51 ms. [Teal box check](benchmarks/editor-drag/phase3-box-after.log). Human GUI test pending: select vertices and edges on a dense sphere, toggle wireframe, resize the window and move it between display scales. Check that cues remain aligned, picking selects the shown elements, and close/reopen works. GUI response does not block the next phase.

## Fourth phase completed: EP05

Commit `203b966` implemented EP05 and was pushed to `origin/main`.

Position-only translations in `PolygonMesh` retain validated immutable topology. A vertex-to-face incidence table is built with the topology and identifies faces needing geometric validation after vertex, edge or face moves. Unchanged vertex records and face normals are retained. Lookup maps and the vertex list are copied; this remains proportional to mesh size, but no longer reconstructs every edge and adjacency set. Closed meshes still undergo the complete signed-volume calculation in the original face/fan order. Material capabilities are recomputed, and new positions get an independent prepared-geometry cache. Extrusion and mesh creation retain full validation.

[IncrementalMeshTest](tst/engine/IncrementalMeshTest.java) compares 900 deterministic random vertex/edge/face translations with independently reconstructed meshes. It checks acceptance/rejection, stable content, capabilities, normals, adjacency and prepared primitives. Additional cases cover chained moves, old prepared-data stability, nonplanar faces, volume inversion and extrusion after deformation. `./verify.ps1`, this test, `EditableMeshTest`, `SceneEditorPreviewTest`, `EditorControllerTest`, `GizmoDragTest` and `RenderViewHandoffTest` passed. The style baseline was tightened without adding allowances.

Three matching dense-sphere drag repetitions compared the phase-three mesh class with EP05, using the preceding detail-32 workload. Medians of per-run means and fresh FPS were:

| Requested input interval | EDT edit before / after | EDT allocation before / after | View A fresh FPS before / after | View B fresh FPS before / after |
| --- | ---: | ---: | ---: | ---: |
| 16 ms | 5.550 / 0.529 ms | 9417.9 / 636.6 KiB | 17.38 / 21.82 | 14.99 / 20.30 |
| 4 ms | 4.731 / 0.470 ms | 9417.2 / 635.7 KiB | 17.68 / 21.97 | 15.93 / 20.22 |

Edit cost fell about 90% and allocation about 93%. Fresh-frame progress improved in both panes, while fresh overlay drawing remains a substantial dense-mesh cost. [Before](benchmarks/editor-drag/phase4-dense-before.log), [after](benchmarks/editor-drag/phase4-dense-after.log). The Teal box check retained the targets: 37–44 fresh FPS across both panes, longest hold 105 ms and final revision within 50 ms. This check has no matching phase-three Teal-box baseline, so its FPS difference is not attributed solely to EP05. [Teal box results](benchmarks/editor-drag/phase4-box-after.log).

Human GUI test pending: deform a dense sphere and the Teal box using vertices, edges and faces; verify final release, undo/redo and Escape cancellation. Try an invalid move and confirm it leaves the last valid geometry intact. Continuing implementation is authorized without waiting for that response.

## Fifth phase completed: EP07; remaining alternatives deferred

Commit `4cc029e` implements EP07 and the preview-test precondition correction described below.

`SpatialQuery.prepare` now shares an immutable query between views while callers retain it. New snapshots can reuse unchanged per-object preparations from the previous query, checking geometry identity, transform, material and stable node identity on the query worker. New entries supply the current scene and geometry-asset revisions to hits. Changed objects rebuild their bounds and BVH. This changes picking preparation; render sessions continue owning their tracing state independently.

Snapshot caches use weak references, so retaining a snapshot in undo history does not keep an unused query or inherited preparation alive. If the garbage collector reclaims the cache, the next query request safely rebuilds it. There is no global cache or chain of previous snapshots. Preparation is serialized only for one snapshot, outside the editor input monitor. Query intersection scratch remains local to each call; the independent brute-force reference does not reuse accelerated object preparations.

[SharedSpatialQueryTest](tst/engine/SharedSpatialQueryTest.java) checks eight concurrent callers sharing a query, brute-force hit equivalence, object reuse, geometry/transform/material changes, rename, restored revisions, removal and deterministic cache reclamation. The existing preview suite exercises both views, stale picks and element gestures.

`EditorPreparationBenchmark` isolates the two views' preparation calls and two scene-snapshot constructions in the starter scene with a detail-32 sphere. It uses 20 warm-up and 60 measured iterations per case, with three repetitions. Geometry edits occur before timing query preparation. The box-edit case leaves the dense sphere unchanged, while the sphere-edit case rebuilds its geometry. Snapshot construction is measured separately and excludes content/transport comparisons and publication metadata work. Run `editor.EditorPreparationBenchmark 3` on the compiled classpath above.

Three matched repetitions compared the `203b966` query/snapshot/document classes with EP07. Medians of per-run means were:

| Edited object, with a detail-32 sphere present | Two-query preparation before / after | Preparation allocation before / after |
| --- | ---: | ---: |
| Teal box; dense sphere unchanged | 7.423 / 0.010 ms | 3647.5 / 7.7 KiB |
| Dense sphere | 7.308 / 4.031 ms | 4488.8 / 2661.8 KiB |

The dense-sphere case saves about 45% of preparation time and 41% of preparation allocation. Unchanged-object reuse removes nearly all repeated dense preparation in the box-edit case. These figures measure preparation on one calling thread, not parallel wall-clock improvement or monitor FPS. [Before preparation](benchmarks/editor-drag/phase5-preparation-before.log), [after preparation](benchmarks/editor-drag/phase5-preparation-after.log).

The three-repeat active drag comparison uses the preceding settings: both panes, 360 by 225 tracing grids, full-panel painting and detail-32 vertex edits of amplitude 0.0001. Median fresh FPS changes are small, consistent with fresh cue painting remaining the larger cost:

| Mesh and requested input interval | View A before / after | View B before / after |
| --- | ---: | ---: |
| Dense sphere, 16 ms | 21.43 / 22.55 | 20.60 / 21.31 |
| Dense sphere, 4 ms | 21.86 / 23.22 | 21.14 / 21.72 |
| Teal box, 16 ms | 38.37 / 39.10 | 38.33 / 38.85 |
| Teal box, 4 ms | 42.48 / 43.37 | 42.48 / 43.37 |

The Teal box retains amplitude 0.15 and the unmodified starter scene. Both views meet the targets: all updated cases exceed 36 fresh FPS, longest hold is 117 ms and final-revision latency is at most 70 ms. The dense sphere still has one first-run interval as long as 366 ms; its median progress and settling improve modestly. EP07 principally reduces preparation CPU work and allocations; these results do not establish a large visible drag improvement by themselves. [Dense before](benchmarks/editor-drag/phase5-dense-before.log), [dense after](benchmarks/editor-drag/phase5-dense-after.log), [Teal box before](benchmarks/editor-drag/phase5-box-before.log), [Teal box after](benchmarks/editor-drag/phase5-box-after.log).

During verification, the asynchronous cross-view picking scenario timed out with both the new implementation and [the unchanged baseline](benchmarks/editor-drag/phase5-preview-before-3.log). Its readiness/blocking loops paint both panes and can replace a previously checked context. The test now waits for a current context before each simulated click. Ordering and stale-pick assertions remain intact; three corrected runs passed for each implementation. [Baseline example](benchmarks/editor-drag/phase5-preview-fixed-before-3.log), [updated example](benchmarks/editor-drag/phase5-preview-fixed-after-3.log). `./verify.ps1`, `SharedSpatialQueryTest`, `SpatialQueryTest`, `SceneDocumentTest`, `RenderViewHandoffTest`, `EditorControllerTest` and `GizmoDragTest` passed. No style allowances were added.

Duplicate snapshot construction costs about 0.015 ms after warm-up in the baseline, so EP06 remains deferred for this workload. EP08–EP10 are also deferred: the Teal-box progress and settling targets already pass with existing input delivery and rendering quality. Reopen them if their tracker conditions occur. Fresh dense-cue rasterization remains a measured limitation; the current plan does not promise 60 FPS on the dense sphere.

Human GUI test pending: switch between both views while picking and dragging vertices/edges/faces, change a camera or object transform, and exercise undo/redo and object removal. Confirm selection corresponds to the shown image and stale clicks are rejected. These checks remain useful even when the automated pass is complete.

## Proposed changes and safeguards

### EP01 and EP02 Implemented publication policy and continuing safeguards

The sustained-input workload now lives in `tst/editor/EditorDragBenchmark.java`, with deterministic handoff tests in `tst/editor/RenderViewHandoffTest.java`. The exploratory isolated edit/paint/JFR harnesses remain in ignored `out/drag-profile/` and are not durable dependencies; promote or replace their workloads when implementing the corresponding cost optimizations. The result logs linked above are retained in the repository.

Use the maintained benchmark for subsequent comparisons, keeping paint-loop FPS distinct from fresh-image FPS and recording both views separately. Its measurements and limitations are described in the completed-phase section above.

EP02 retains the existing bounded completed image/overlay pair and permits painting it while a newer geometry revision is waiting. The deterministic regression and matching benchmarks validated this choice. Preparing the overlay before candidate publication or coalescing incoming candidates were alternatives considered in the original plan; they are not unfinished EP02 tasks. A coherent, slightly older preview must continue to make progress without matching the newest document revision.

Future changes must keep image, camera, scene snapshot, content rectangle, overlay, and picking token together. Never attach a new overlay to an unrelated older image. Preserve stale-pick rejection and the existing immutable-image/lease rules. Bound retained frames and pending work; do not queue every intermediate mouse position. Retain the regression coverage for newer candidates arriving between overlay request, completion, and painting, plus resize, camera changes, selection changes, and close.

### EP03 Implemented control refresh policy

[SceneEditorPanel.java](src/editor/SceneEditorPanel.java) now distinguishes geometry-coordinate updates from hierarchy, selection, component, and label changes. It updates changed values while retaining controls whose source content is unchanged. Continue preserving focus, text drafts, expanded tree nodes and selected tabs in future changes; actual structural edits must still refresh their controls. The second-phase tests and measurements above verify this policy.

### EP04 Implemented dense overlay cache and continuing safeguards

The third phase caches dense rasterized overlays by their exact visual context, with invalidation and resource limits described above. Retain those safeguards in future changes to `RenderViewPanel.drawOverlay` or [OverlayGeometry.java](src/editor/overlay/OverlayGeometry.java). Batching lines, reusing strokes/colors and clipping cues are possible follow-ups if fresh cue drawing remains a measured bottleneck; they are not unfinished EP04 commitments.

Reduced marker density, simpler antialiasing, or fewer wireframe details during motion are optional quality tradeoffs. Measure them separately and retain clear selected elements and handles. Do not silently alter x-ray selection or hit radii. Compare screenshots and selection tests at multiple window scales.

### EP05 Implemented incremental mesh edits and continuing safeguards

[PolygonMesh.java](src/engine/PolygonMesh.java) now shares immutable faces, edges, stable IDs, face adjacency and vertex-to-face incidence during vertex, edge and face translations. It retains unchanged vertex records, copies position/normal lookup maps and validates affected faces. Closed-solid volume and material capabilities are still recomputed. Prepared geometry is specific to the new positions; previously prepared meshes remain immutable.

Validate affected polygons and preserve closed-solid volume/orientation checks and material capability checks. A local move can change emitter or volume eligibility. Do not publish invalid intermediate geometry merely to make dragging faster. Compare incremental results with the full constructor for valid and invalid edits, nonplanar faces, closed meshes, and undo/cancel. Topology-changing operations such as extrusion retain full validation until separately optimized.

### EP06 Deferred snapshot-construction reuse

[SceneDocument.java](src/engine/SceneDocument.java) constructs a candidate and then a published [SceneSnapshot](src/engine/SceneSnapshot.java). The isolated benchmark below finds duplicate construction inexpensive for the starter scene, so this work is deferred. If larger scenes make publication a measured bottleneck, evaluate reusing validated candidate content with new publication revisions and sharing unchanged transform/render-entry data. Preserve the single-writer model, atomic snapshots, asset revisions, and edit/undo invalidation. Do not mutate a snapshot already used by rendering or picking.

### EP07 Implemented shared picking preparation and continuing safeguards

Both view threads now share one lazy immutable [SpatialQuery](src/engine/SpatialQuery.java) per snapshot. A new publication inherits prepared objects only when node identity, geometry reference, world transform and material match. Changed objects rebuild their bounds and hierarchy. The new query uses new snapshot entries, so hit revision and asset metadata remain current. Independent brute-force reference queries still prepare separately. Retain these invalidation rules; mutable query scratch must remain private to its worker.

BVH refitting, which updates bounding boxes while retaining tree structure, remains a possible measured follow-up rather than an unfinished EP07 commitment. Compare its traversal quality with rebuilding before adoption, and rebuild when quality deteriorates. Verify any future changes against brute-force hits, including shared assets and rapid edits.

### EP08 Deferred input coalescing

Keep the newest pending drag position and process it at a bounded cadence if input events outrun useful publication. Compute each position from the gesture baseline; do not lose accumulated movement. Apply the final mouse-release position synchronously or through a completion barrier before committing. Keep one undo entry per gesture and preserve Escape/focus-loss cancellation and invalid-edit behavior. Measure actual delivered input and latency; a nominal timer interval is not an achieved event rate.

### EP09 Deferred render-scheduling alternatives

The Teal box reproduction had no cancelled jobs, so changing tracing cancellation is not the first fix. If heavier scenes later show cancellation starvation, evaluate allowing one immutable captured geometry pass to complete before switching to the newest pending edit. Never merge its samples into a different scene revision. Also evaluate prioritizing the actively edited view over the secondary view, without allowing the secondary view to stall indefinitely. Preserve pause, close, and resource bounds.

### EP10 Deferred temporary interaction quality

If needed, evaluate reduced tracing resolution during a gesture, delayed refinement until input settles, or a separate lightweight editing preview. Define what appears during dragging, what remains selectable, and when full quality returns. These options must not conceal or substitute for EP02. Do not mix samples across grids or revisions; camera framing and final geometry remain unchanged. Any approximate preview needs explicit rules for correspondence with picking.

## Acceptance and tracking procedure

The following are proposed engineering targets for the starter-scene reproduction on the baseline machine, not guarantees for arbitrary hardware or mesh sizes:

- During eight-second Teal box drags at requested 16 ms and 4 ms input intervals, each view displays at least 15 distinct edited revisions per second, with no display hold over 200 ms after initial warm-up.
- After release, the final edited revision appears within 200 ms. Settled rendering continues refining normally. Record the actual input rate and final-revision latency directly.
- Deterministic tests force the image/overlay race and prove bounded progress without relying on elapsed-time thresholds. Use timing targets in benchmarks, not as fragile universal unit-test assertions.
- Repeat performance runs at least three times with fixed scene, seed, render settings, panel size, and hardware recorded. Report medians, 95th percentiles, maximum holds, and allocations. For EP03–EP07, compare the relevant isolated costs and end-to-end fresh-frame progress; do not close an optimization solely because a microbenchmark improved.
- Exercise vertex, edge, and face dragging; rapid repeated gestures; both views; wireframe on/off; dense meshes; invalid moves; release, undo, redo, cancel, resize, camera/selection changes, and close. Confirm no stale selection, lost final edit, unbounded queue, or mutation of leased images.
- Run `./verify.ps1` and relevant editor, mesh, spatial-query, and rendering tests for implementation changes. Follow with human GUI QA on the Teal box at normal and fast dragging speeds; offscreen rendering cannot certify physical input-to-screen responsiveness.

For each completed item, add its commit or PR, workload/settings, before/after measurements, correctness checks, and human-QA outcome beside its status or in a linked result record. EP01–EP05 and EP07 are complete. Retain the GUI checklist and use new measurements to reopen deferred items or propose further dense-cue work. A deferred item is not an implemented optimization.
