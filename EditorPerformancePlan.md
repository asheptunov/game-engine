# Scene and mesh editor performance plan

Status: EP01 and EP02 implemented; automated checks and human drag testing passed. EP03–EP10 remain proposed. Baseline: `ae8c129`, investigated October 8, 2026.

The Swing scene editor becomes visibly unresponsive during rapid vertex dragging, even on the eight-vertex Teal box in the starter scene. The first priority is reliable display of completed edits during sustained input. Larger-mesh costs also need attention, but reducing those costs alone will not resolve the reproduced Teal box stall.

This is the implementation tracker for editor responsiveness. [PerformanceRequirements.md](PerformanceRequirements.md) covers the broader renderer roadmap. This plan does not replace its phases or change the editor's geometry validity, picking, or undo requirements.

## Evidence and interpretation

Measurements used Windows on an Intel Core i5-13600KF, OpenJDK 23.0.1, a window-free 1400 by 850 Swing panel, the starter scene, and both render views active. Painting was to a buffered image. These are exploratory single-run measurements, not monitor FPS or a reproduction of physical mouse delivery. Rendering remained asynchronous. Default wireframe was enabled except where stated otherwise. Each view used the editor defaults: depth 3, one sample per pixel per batch, target 8 samples, up to four workers, and its view-title-derived seed. Trace dimensions came from the editor's aspect-dependent sizing policy and were not recorded in the baseline logs; EP01 must record the actual dimensions.

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

Status values are Proposed, In progress, Blocked, Verified, and Deferred. Change a status only with a linked implementation or measurement result. Preserve the IDs as work is split into issues or pull requests. Codex implemented the first phase; remaining items are unassigned.

| ID | Priority | Status | Work item | Completion evidence |
| --- | --- | --- | --- | --- |
| EP01 | First | Verified | Make fresh-frame progress measurable and reproducible | [Maintained benchmark](tst/editor/EditorDragBenchmark.java), [deterministic regression](tst/editor/RenderViewHandoffTest.java), and first-phase results below |
| EP02 | First | Verified | Prevent image/overlay publication starvation | [Handoff implementation](src/editor/RenderViewPanel.java), first-phase results below, and successful human drag test |
| EP03 | Next | Proposed | Avoid rebuilding unrelated Swing controls during edits | Same controls and state, with lower EDT update cost and allocation |
| EP04 | Next | Proposed | Reduce mesh-cue and wireframe painting cost | Lower stationary and dragging paint times with unchanged picking semantics |
| EP05 | Next | Proposed | Update moved mesh vertices incrementally | Lower allocation and edit time; full-validation equivalence tests pass |
| EP06 | Next | Proposed | Reuse scene snapshot construction work | Avoid duplicate scene-wide work without weakening atomic publication |
| EP07 | Next | Proposed | Share and incrementally update picking preparation | Unchanged objects reuse prepared data; both views remain correct |
| EP08 | Conditional | Proposed | Coalesce excess drag updates | Latest pointer position and final release are preserved; measured benefit beyond EP02 |
| EP09 | Conditional | Proposed | Improve render scheduling during geometry edits | Adopt only if cancellation or render cost remains a measured bottleneck |
| EP10 | Conditional | Proposed | Add cheaper visual feedback while dragging | Adopt only if earlier work misses responsiveness targets; explicit quality policy |

EP01 now enables evaluation of every item. With EP02 verified, rank the remaining work by gains in fresh displayed frames. EP03 and EP04 can be assessed independently. Coordinate EP05–EP07 around immutable geometry ownership and cache invalidation. Conditional items are alternatives to evaluate, not commitments to reduce quality or relax correctness.

## First phase completed: EP01 and EP02

The implementation in `RenderViewPanel` allows a completed image/overlay pair to advance even when a newer geometry revision is waiting. The pair retains its captured camera, scene, image, overlay and picking token. Reusing an overlay for a different image still requires exact token and camera correspondence. Selection, tool mode and projected viewport dimensions still reject superseded projections. Close prevents new overlay publication. Only the existing bounded candidate, ready pair and painted pair are retained; intermediate input is not queued.

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

Next, reduce remaining control-refresh and dense-overlay costs under EP03/EP04, then remeasure before adopting the geometry/cache changes or conditional quality policies.

## Proposed changes and safeguards

### EP01 and EP02 Implemented publication policy and continuing safeguards

The sustained-input workload now lives in `tst/editor/EditorDragBenchmark.java`, with deterministic handoff tests in `tst/editor/RenderViewHandoffTest.java`. The exploratory isolated edit/paint/JFR harnesses remain in ignored `out/drag-profile/` and are not durable dependencies; promote or replace their workloads when implementing the corresponding cost optimizations. The result logs linked above are retained in the repository.

Use the maintained benchmark for subsequent comparisons, keeping paint-loop FPS distinct from fresh-image FPS and recording both views separately. Its measurements and limitations are described in the completed-phase section above.

EP02 retains the existing bounded completed image/overlay pair and permits painting it while a newer geometry revision is waiting. The deterministic regression and matching benchmarks validated this choice. Preparing the overlay before candidate publication or coalescing incoming candidates were alternatives considered in the original plan; they are not unfinished EP02 tasks. A coherent, slightly older preview must continue to make progress without matching the newest document revision.

Future changes must keep image, camera, scene snapshot, content rectangle, overlay, and picking token together. Never attach a new overlay to an unrelated older image. Preserve stale-pick rejection and the existing immutable-image/lease rules. Bound retained frames and pending work; do not queue every intermediate mouse position. Retain the regression coverage for newer candidates arriving between overlay request, completion, and painting, plus resize, camera changes, selection changes, and close.

### EP03 Avoid unnecessary control reconstruction

In [SceneEditorPanel.java](src/editor/SceneEditorPanel.java), distinguish geometry-coordinate updates from hierarchy, selection, component, and label changes. Update values in place instead of rebuilding the tree model, inspector tabs/components, parent choices, and camera choices for each mouse event. Keep focus, text drafts, expanded tree nodes, and selected tabs intact. Verify that actual structural changes still refresh the controls.

### EP04 Reduce overlay drawing work

In `RenderViewPanel.drawOverlay` and [OverlayGeometry.java](src/editor/overlay/OverlayGeometry.java), evaluate caching rasterized overlays by their exact visual context, batching lines with the same style, reusing strokes/colors, and clipping cues outside the viewport. Cached geometry alone does not eliminate repeated Java2D rasterization. Include projection, scene/selection revisions, viewport size, display scale, and overlay settings in invalidation.

Reduced marker density, simpler antialiasing, or fewer wireframe details during motion are optional quality tradeoffs. Measure them separately and retain clear selected elements and handles. Do not silently alter x-ray selection or hit radii. Compare screenshots and selection tests at multiple window scales.

### EP05 Incremental mesh edits

[PolygonMesh.java](src/engine/PolygonMesh.java) currently recreates every vertex record, validates every face, and rebuilds edges and adjacency for a translated vertex or edge. Separate immutable topology from positions. Reuse stable IDs, edge connectivity, and adjacency when topology is unchanged; recompute affected face geometry and any dependent global properties.

Validate affected polygons and preserve closed-solid volume/orientation checks and material capability checks. A local move can change emitter or volume eligibility. Do not publish invalid intermediate geometry merely to make dragging faster. Compare incremental results with the full constructor for valid and invalid edits, nonplanar faces, closed meshes, and undo/cancel. Topology-changing operations such as extrusion retain full validation until separately optimized.

### EP06 Reuse snapshot work

[SceneDocument.java](src/engine/SceneDocument.java) constructs a candidate and then a published [SceneSnapshot](src/engine/SceneSnapshot.java). Evaluate reusing validated candidate content with new publication revisions, plus sharing unchanged transform and render-entry data. Preserve the single-writer model, atomic snapshots, asset revisions, and edit/undo invalidation. Do not mutate a snapshot already used by rendering or picking.

### EP07 Reuse picking and acceleration data

Both view threads call [SpatialQuery.prepare](src/engine/SpatialQuery.java) for new revisions. Evaluate sharing immutable world-query preparation between views and rebuilding only changed objects. Camera-independent prepared geometry can be shared; mutable query scratch must remain private to its worker.

For position-only mesh changes, consider BVH refitting, which updates bounding boxes while retaining tree structure. Compare its traversal quality with rebuilding, and rebuild when quality deteriorates. Invalidate on geometry, transforms, relevant materials, topology changes, and undo as required by each cached representation. Verify hits against the brute-force reference, including shared assets and rapid edits.

### EP08 Coalesce excess input work

Keep the newest pending drag position and process it at a bounded cadence if input events outrun useful publication. Compute each position from the gesture baseline; do not lose accumulated movement. Apply the final mouse-release position synchronously or through a completion barrier before committing. Keep one undo entry per gesture and preserve Escape/focus-loss cancellation and invalid-edit behavior. Measure actual delivered input and latency; a nominal timer interval is not an achieved event rate.

### EP09 Render scheduling

The Teal box reproduction had no cancelled jobs, so changing tracing cancellation is not the first fix. If heavier scenes later show cancellation starvation, evaluate allowing one immutable captured geometry pass to complete before switching to the newest pending edit. Never merge its samples into a different scene revision. Also evaluate prioritizing the actively edited view over the secondary view, without allowing the secondary view to stall indefinitely. Preserve pause, close, and resource bounds.

### EP10 Temporary interaction quality

If needed, evaluate reduced tracing resolution during a gesture, delayed refinement until input settles, or a separate lightweight editing preview. Define what appears during dragging, what remains selectable, and when full quality returns. These options must not conceal or substitute for EP02. Do not mix samples across grids or revisions; camera framing and final geometry remain unchanged. Any approximate preview needs explicit rules for correspondence with picking.

## Acceptance and tracking procedure

The following are proposed engineering targets for the starter-scene reproduction on the baseline machine, not guarantees for arbitrary hardware or mesh sizes:

- During eight-second Teal box drags at requested 16 ms and 4 ms input intervals, each view displays at least 15 distinct edited revisions per second, with no display hold over 200 ms after initial warm-up.
- After release, the final edited revision appears within 200 ms. Settled rendering continues refining normally. Record the actual input rate and final-revision latency directly.
- Deterministic tests force the image/overlay race and prove bounded progress without relying on elapsed-time thresholds. Use timing targets in benchmarks, not as fragile universal unit-test assertions.
- Repeat performance runs at least three times with fixed scene, seed, render settings, panel size, and hardware recorded. Report medians, 95th percentiles, maximum holds, and allocations. For EP03–EP07, compare the relevant isolated costs and end-to-end fresh-frame progress; do not close an optimization solely because a microbenchmark improved.
- Exercise vertex, edge, and face dragging; rapid repeated gestures; both views; wireframe on/off; dense meshes; invalid moves; release, undo, redo, cancel, resize, camera/selection changes, and close. Confirm no stale selection, lost final edit, unbounded queue, or mutation of leased images.
- Run `./verify.ps1` and relevant editor, mesh, spatial-query, and rendering tests for implementation changes. Follow with human GUI QA on the Teal box at normal and fast dragging speeds; offscreen rendering cannot certify physical input-to-screen responsiveness.

For each completed item, add its commit or PR, workload/settings, before/after measurements, correctness checks, and human-QA outcome beside its status or in a linked result record. EP01 and EP02 are complete. Continue measuring and ranking EP03–EP10 using the remaining bottlenecks.
