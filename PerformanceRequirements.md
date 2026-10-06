# Renderer performance specification

Status: P0 through P5 implemented. Decimal follow-up phases and P6 onward remain proposed. Measurements and commands are in [P0/P1 results](benchmarks/p0-p1/README.md), [P2 results](benchmarks/p2/README.md), [P3 results](benchmarks/p3/README.md), [P4 results](benchmarks/p4/README.md), and [P5 results](benchmarks/p5/README.md).

This specification follows the six implemented transport phases in [RenderingRequirements.md](RenderingRequirements.md). It defines how to improve rendering throughput, camera responsiveness, and image quality per second while preserving the existing renderer as a correctness reference. The aspiration is 60 or more useful updates per second at the window resolution. This is a measurement target, not a promised result for every scene or quality setting.

The recommended first delivery is measurement, tiled multicore tracing, responsive scheduling, and removal of display-buffer churn. Later phases must earn their place through measurements. Java remains the implementation language for the CPU work; native and GPU backends are separate strategic options.

## Goals and terminology

- **Throughput:** how many paths or samples we finish per second.
- **Sample per pixel or spp:** one estimate of incoming light for each pixel. A sample can include several continuation and shadow rays.
- **Frame time:** elapsed time for an update. A 60 FPS budget is about 16.67 ms.
- **Interaction latency:** time from an input event to an image reflecting that input. A fast display loop showing old images does not satisfy this goal.
- **Quality per second:** how quickly an image approaches a reference of adequate convergence. Faster samples can still be worse if they are noisier.
- **BVH:** a hierarchy of bounding boxes that lets a ray skip groups of geometry.
- **SIMD:** executing the same operation on several numbers at once within a CPU core.
- **Estimator:** the sampling procedure used to estimate light. Some phases preserve identical samples; others preserve expected brightness but change noise.

Track these dimensions separately. A phase may improve responsiveness without improving throughput, or reduce work by accepting a visible quality tradeoff. Such changes must be labeled and controllable.

## Current evidence and limits

User observations at approximately 1400 by 900 are about 6 FPS in playground, 1.3 in bounce-room, and 1.7 in glass. These correspond to roughly 10, 46, and 35 times the throughput needed for 60 FPS at the same work per frame. Glass at quarter resolution is reported around 12 FPS. Quarter means one quarter in each dimension, approximately one sixteenth as many sensor pixels.

Window dimensions are not necessarily tracing dimensions. The configured window is 1440 by 900, while the default sensor has a 1600-pixel long edge, producing 1600 by 1000. Record both dimensions before making comparisons.

Baseline code inspection, before P0 through P2, found:

- `DirectRgbTracer.trace()` runs the pixel loops sequentially. Mutable scratch fields and counters prevent safe parallel use as written.
- `Viewport.render()` holds the state monitor during tracing, resampling, and painting; input handlers acquire that monitor too.
- The 50 ms tracing budget is checked after a complete sensor sample, so one expensive sample can exceed it substantially.
- `Resampler` allocates three display-sized float buffers per viewport frame. Their pixel payload alone is 15.552 MB at 1440 by 900, or 933.12 MB per second at 60 FPS.
- Geometry is prepared and cached. Per-object BVHs exist for larger objects, but the object list is scanned linearly. BVHs use median splits and fixed traversal order.
- The path loop has a depth limit and zero-contribution exits, but no Russian roulette.
- The tracer already avoids per-ray allocation and already implements direct-light sampling and MIS. These are existing foundations, not proposed new gains.

An exploratory run of the existing compiled benchmark, with glass at 200 by 200, depth 8, one spp per batch and seed 1, reported 57.26 ms median headless frame time, a final trace time of 17.30 ms, and 1464 trace-allocated bytes. This was not a clean rebuilt baseline, did not include AWT presentation, and compared a median frame measurement with one trace observation. It motivates full-pipeline measurement; it does not establish an exact stage breakdown.

The host reported an Intel i5-13600KF and 20 logical processors. GPU identity and capabilities remain unverified. No numerical parallel speedup is assumed.

## Priority and dependency rules

The CPU phases below are ranked by estimated practical impact on the current application, with confidence and affected workload stated explicitly. This is an engineering estimate, not a measured global ordering: throughput, responsiveness, and quality cannot be reduced to one reliable speedup number. Dependencies may require a small enabling step first. Re-rank remaining work after each measured result; keep phase identifiers stable.

GPU work has the highest potential hardware ceiling but is a separate backend project. It is ranked separately so its potential does not imply that it should precede the much cheaper CPU improvements. Native integration is likewise conditional.

| Phase | Optimization | Estimated impact and confidence | Dependencies |
| --- | --- | --- | --- |
| P0 | Establish comparable measurements | Enabler; no direct speedup | None |
| P1 | Tiled multicore tracing | Very high potential trace throughput; high confidence in unused parallelism | P0 |
| P2 | Responsive rendering and cancellation | Very high interaction benefit; high confidence | P1 tile and worker foundation |
| P3 | Reuse and fuse display buffers | High at low sensor resolution; medium at full resolution; high confidence in avoidable work | P0; coordinate with P2 publication |
| P4 | Dynamic resolution while moving | High interaction benefit through explicit quality reduction; high confidence | P2 |
| P5 | Temporal sample reuse | Implemented quality option; lower one-spp noise, substantial overhead and mixed equal-time benefit | P2 and history validation data |
| P5.1 | Reduce temporal validation and memory cost | Medium to high potential recovery of temporal overhead; medium confidence; no direct gain with temporal off | P5 |
| P5.2 | Trade temporal quality for less tracing | Potentially high interaction benefit; medium confidence on diffuse scenes, low on glass; conditional on net savings | P5.1; P4 for reduced grids |
| P6 | Spatial denoising | Potentially high perceived quality at low spp; medium confidence | Auxiliary surface data and raw-image reference |
| P7 | Top-level object BVH | Medium for current rooms, high as object count grows; medium confidence | P0 |
| P8 | Near-first BVH traversal | Medium in intersection-heavy scenes; medium confidence | Existing BVH; integrate P7 if present |
| P9 | Better BVH construction | Medium for larger meshes, low for simple rooms; medium confidence | P0 |
| P10 | Compact geometry and BVH storage | Medium if cache stalls dominate; low confidence until profiled | P0; preferably settle P7 to P9 first |
| P11 | Adaptive sampling | Medium quality per second for stationary views; medium confidence | Per-pixel sample accounting |
| P11.1 | Sparse temporal updates during motion | Potentially high reduction in traced paths on stable diffuse regions; low confidence and substantial correctness work | P5.1/P5.2 evaluation and P11 per-pixel accounting foundation |
| P12 | Improved sample sequences | Medium quality per second; medium confidence | P0 quality benchmark |
| P13 | Russian roulette | Low to medium for current presets, higher for long lossy paths; medium confidence | P0 quality benchmark |
| P14 | Shared mesh acceleration | Low for current simple rooms, high memory/build benefit with many mesh copies | P0 |
| P15 | Analytic solid intersections | Low to medium for scenes with boxes; medium confidence | Preserve boundary and identity rules |
| P16 | Explicit SIMD kernels | Uncertain medium throughput benefit; low confidence before layout work | P10 and kernel profiling |
| S1 | GPU backend | Highest potential throughput ceiling; feasibility and achieved gain unverified | P0, hardware inventory, backend contract |
| S2 | Native CPU backend | Uncertain gain; evaluate only against optimized Java | P0 and representative Java baseline |

P0 through P5 are implemented. Evaluate P5.1 next if improving temporal mode is the priority, then attempt P5.2 where saved tracing work can exceed reconstruction cost. P7 remains the next direct geometry-throughput option, especially as object count grows; it also accelerates guide queries. P6 is a separate quality experiment. P11.1 has a higher potential ceiling than reducing history overhead alone, but follows per-pixel accounting because it changes which pixels receive fresh paths. Decimal identifiers preserve existing phase numbers; this ordering balances impact, confidence and prerequisites rather than implying a guaranteed speedup ranking.

## Shared completion requirements

Each phase includes controls, documentation, relevant tests, and a before/after result in `tracker.md`. P0 and P1 were delivered together at the user's request; P2 followed separately. An experiment that shows no useful improvement may be marked evaluated and deferred, with evidence; do not retain complexity solely because it was planned.

For implementation-preserving changes, compare fixed seeds, camera, geometry, depth, resolution, and sample counts against the reference. Preserve exact images where arithmetic order is unchanged. Otherwise document justified tolerances and test transport invariants. For sampling changes, use multiple seeds and independent references to compare error at equal elapsed time; identical noisy pixels are not the criterion.

Preserve media containment, inside cameras, absorption, Fresnel/IOR weighting, rough materials, area-light MIS, volume visibility, stable primitive identity, and source-face skipping. No optimization silently reduces path depth, changes material behavior, or weakens intersection robustness. Existing transport limitations remain documented.

Keep F3/F4 useful, and distinguish profiling-on overhead from normal operation. Check applicable transport suites and scene/input integration. Inspect harness results because a successful process exit does not prove tests passed. Background verification must not open windows or steal focus. Hands-on GUI checks are separate from headless measurements.

## P0 Comparable performance and quality measurements

Implemented: rectangular/scaled dimensions, configuration and revision/source fingerprint,
stage distributions, aggregate CPU/allocation, GC, per-frame CSV/settings export,
separate target convergence and optional timed quality comparisons, camera-motion
and many-instance workloads. Actual AWT presentation and input delivery require
live measurements; asynchronous generation age/cancellation metrics arrive with P2.

Extend the benchmark to specify rectangular sensor dimensions and record display dimensions separately. Record revision, JDK, CPU, available processors, worker count, preset, camera, geometry detail, depth, seed, requested and actual spp, accumulated samples, acceleration mode, and all quality/reconstruction modes. Warm up before measuring; compare sequential runs under comparable machine load. Do not run competing benchmarks concurrently.

Measure full-frame median and p95, stage wall times, aggregate worker CPU time, whole-frame and trace allocation, GC activity, and rays and primitive tests by category. Once workers exist, a coordinator thread's CPU and allocation counters are insufficient: aggregate worker counters without per-ray synchronization. Report time to a target spp and image error at a fixed elapsed time. Include input-to-visible-update latency, camera-generation age, and cancellation waste for interactive modes.

Use playground, bounce-room, glass, rough-room, mesh-room, and volume-room, including native/half/quarter sensor scales, plus an explicit small square grid for existing comparisons. Use fixed settings within each comparison; test both stationary convergence and a repeatable camera movement sequence. Add a many-instance mesh case for scaling phases. Actual AWT presentation cost requires a separately identified live measurement.

Acceptance: reproduce the baseline from documented commands, distinguish trace from display cost, and avoid target-spp completion silently turning a tracing benchmark into an idle benchmark. Store results with their settings. No performance threshold is inferred from the exploratory run above.

## P1 Tiled multicore tracing

Implemented: a shared persistent platform-thread pool, dynamically claimed tiles,
worker-owned scratch/counters, fixed pass snapshots and complete-pass publication.
`view workers` and `view tile` tune scheduling without discarding samples. The
default is min(14, max(1, available CPUs - 2)) workers and 32-pixel tiles, selected
from the local scaling measurements. P2 adds responsive input/cancellation below.

Divide the sensor into tiles and dynamically assign them to a persistent, bounded platform-thread pool. Begin by benchmarking 16 by 16 and 32 by 32 tiles and worker counts such as 1, 2, 4, 6, 10, 14, and 20 where available. Tile size and worker count are experiments, not fixed promises. Avoid one task per pixel and per-ray atomics.

Workers share an immutable render snapshot and prepared geometry. Each owns reusable hit records, sampler, material samples, medium stacks, visibility scratch, and counters. A pixel has one writer for a sample pass. Preserve the existing seed/pixel/sample mapping and per-pixel accumulation order. Reduce statistics at a synchronization boundary. Shut down workers cleanly and avoid duplicating pools on scene switches.

Initially publish complete sample passes so existing uniform spp semantics remain valid. Any partial-work discard must leave committed accumulation coherent; use staging or equivalent ownership. P2 adds cancellation. A minimal snapshot is required here even if the coordinator temporarily retains existing input locking.

Acceptance: one-worker output agrees with the reference; several worker counts and tile sizes produce the same fixed-spp output; pause/restart/resolution/scene changes are safe. Show scaling curves, CPU usage, total allocations, and p95 frame time. Select a default from measured throughput and interaction headroom rather than logical processor count alone.

## P2 Responsive rendering and cancellation

Implemented: the live viewport uses a single background coordinator and immutable
state snapshots. Transport generations cancel obsolete tiles; complete
passes commit from staging storage. Presentation reads an owned immutable RGB
copy, and console drawing snapshots text before painting. There is one active job
and no queue of requested generations. Pause/resume and target edits cancel work
without discarding previously committed radiance; exposure retains samples.

Camera-only motion lets the active pass finish and publish a coherent preview
tagged with its captured generation. The batch stops after that pass, and the next
job captures the newest camera. Preview samples count toward live accumulation
only when the exact render key matches. Cancelling every camera edit starves
publication during sustained movement, so camera previews deliberately permit
one active pass of lag. Transport/resolution/restart changes and suspension still
cancel between tiles and reject obsolete publication.

Workers return between tiles when a 16.67 ms slice expires, then resume the same
pass in another bounded wave. The slice is cooperative: an in-flight tile may
overrun it, and a full pass can take many slices. The existing 50 ms limit remains
an inter-pass batch limit. F3/F4 and the viewport footer show requested/shown
generation, completed spp, age since snapshot capture, first-image latency, cancelled jobs, wasted
primary paths and maximum tile wall time. Display FPS and the display-thread
timeline do not measure fresh sample throughput; last-job CPU/ray/JFR data remain
available. First-image latency ends after conversion to the display raster,
excluding AWT presentation and physical input delivery.

`renderBlocking()` retains deterministic full-pipeline benchmark/test behavior;
mixing it with asynchronous rendering on one viewport is rejected. Sensor-sized
publication copies and an extra double mean staging buffer are bounded. P3 adds
leased publication reuse and cached display conversion. The previous
image stays visible while a new generation is pending, with its generation shown.
Headless race/integration tests and synthetic AWT input measurements pass; manual
GUI checks and physical input-to-screen latency remain unverified. See the
[P2 measurements](benchmarks/p2/README.md).

Separate input/state editing, tracing, and presentation. Acquire the state monitor only to apply edits or capture/publish coherent state; perform expensive tracing and image conversion outside it. Maintain an immutable snapshot and monotonically increasing render generation. Camera or transport changes invalidate accumulation for a different render key. Exposure and overlays retain radiance samples.

Check cancellation and time budgets between tiles. Stop scheduling tiles promptly after transport or resolution edits; in-flight tiles may finish but must not publish into a newer generation. Camera-only edits may finish the active pass as an explicitly tagged preview, then capture the latest camera without a backlog. Publish through explicitly owned buffers so presentation never reads storage being modified. Discard incomplete passes initially; showing partial passes later requires valid per-pixel counts and clear stale-pixel handling.

Keep input responsive while a pass is unfinished. A 16.67 ms display budget does not imply a complete fresh sensor sample fits inside it. Report image age and completed work, not just presentation FPS. Bound queued generations and memory; repeated mouse motion must not accumulate a backlog.

Acceptance: rapid camera movement, resolution changes, preset switches, pause/resume, console interaction, and window focus loss cannot mix generations, race buffers, or deadlock. Continuous camera movement must display newly completed previews before input stops; repeated presentation of one old image is not progress. Check previews against their captured camera and stationary convergence against the latest camera. Measure input latency and wasted obsolete work. Aim for p95 input handling within one 60 Hz interval on the baseline host; separately report time to a visible camera update. Long individual tiles must be visible in diagnostics and motivate smaller tiles.

## P3 Reuse and fuse display buffers

Implemented: `DisplayConverter` caches overlap indices/weights and fuses RGB box
filtering with exposure, Reinhard mapping and sRGB encoding into reusable byte
storage. Equal grids bypass filtering; integer enlargement encodes each source
pixel once and replicates encoded rows. Integer reduction and fractional filtering
retain the reference float arithmetic and order. The original `Resampler` remains
the comparison reference.

The encoded cache is keyed by captured render key, completed spp and exposure.
Repeated images restore the cache before drawing the UI/overlay; this also removes
old console text or editor pixels on scene switches. RESAMPLE now includes fused
filtering/mapping; PAINT measures restoration of the encoded cache. Background
clears/checkerboards were already skipped for the opaque viewport. AWT packing
uses an opaque fast path and preserves the editor's partial-alpha behavior.

A bounded three-slot publication pool reuses sensor RGB storage. Display acquires
an image lease under the state monitor and releases it in `finally`; the coordinator
cannot overwrite the latest or leased image. Pixel allocation occurs at startup or
dimension changes, outside the input lock. Images are immutable for their lease,
not forever after release. There is still one coordinator and no generation queue.

Local glass blocking-frame medians: quarter 20.66 → 6.81 ms, 400 square 52.76 →
28.18 ms, native 82.17 → 75.97 ms. Whole-frame allocation falls from about 16.24 MB
to 0.64 MB; isolated steady-state display conversion/restoration allocates zero bytes.
These headless figures exclude AWT presentation. Native tracing remains about 65 ms
per fresh pass. See [P3 measurements and verification](benchmarks/p3/README.md).

F3 leads with fresh-image FPS over two seconds, counting each traced publication
once after the complete display pipeline. Cached redraws do not increase it.
Update interval mean/p95, current hold, and snapshot-to-display age accompany the
rate; the live viewport graph shows fresh-image intervals and a 60 FPS reference.
Display-loop FPS/stage averages remain secondary ten-second measurements, with
background tracing labeled separately. Resolution/preset changes reset the fresh
history; pause/completion status explains idle rendering. Software submission
timing includes AWT work in the live pipeline, but does not measure monitor scanout
or physical input-to-screen latency.

Reuse display storage across unchanged dimensions. Fuse RGB resampling, exposure/tone mapping, encoding, and output where practical. Cache resampling indices and weights when dimensions change. Bypass resampling for equal grids; for integer enlargement, encode one source pixel and replicate the encoded value. For averaging, filter linear radiance before nonlinear display mapping.

Avoid clears and checkerboard draws only where full opaque viewport coverage makes them redundant. Reduce channel packing/copying into the reusable AWT image where compatible with editor and overlay composition. Redisplay cached output when neither radiance nor presentation settings changed. Keep publication ownership consistent with P2.

Acceptance: compare equal-size, integer enlargement, integer reduction, and fractional resampling against reference images, including orientation, exposure, clipping, console and overlays. Show lower whole-frame allocation and display stage cost. No display-sized allocation should recur in steady state; dimension changes may allocate. Do not compromise the texture editor's alpha behavior.

## P4 Dynamic resolution while moving

Implemented: `view interactive on` enables automatic sensor resolution; it is off
by default. `view resolution` sets the maximum and stationary grid. The controller
preserves that grid's aspect and the camera field of view, reduces only spatial
resolution, and caps moving batches at one complete sample. `view interactive
target <ms>` sets a best-effort update budget (default 16.67 ms). `view interactive
min <width> <height>` sets lower bounds fitted to the requested aspect and capped
by its maximum. The default minimum is quarter resolution, with at least 64 pixels
on each axis. Footer and F3 report shown and requested dimensions separately.

`InteractiveResolution` observes actual eye/sensor changes, including held-key
motion. It chooses from discrete scales using smoothed snapshot-to-raster cost per
pixel and reserves 20% for additional display/presentation overhead. Grid changes
wait for the current job to finish; camera input never repeatedly cancels previews.
A 500 ms cooldown, 25% over-budget threshold and three fresh cheap measurements
before enlargement limit oscillation. Repeated cached redraws do not provide new
cost evidence. After 350 ms without camera changes, the next job restores the
requested grid and starts fresh coherent accumulation. Pause holds its grid;
disabling the mode restores fixed resolution at the next job boundary.

Requested dimensions stay separate from the live sampled grid. Snapshot capture
and render keys use the actual sampled dimensions; no samples cross grid changes.
Automatic transitions do not reset F3's fresh-image window, so it measures the
whole interaction. Explicit requested-resolution and preset changes still reset it.
Pixel allocation for automatic changes happens in the coordinator, outside the
input lock. The existing three publication slots remain bounded; grid changes may
allocate, so this phase does not promise zero allocation during adaptation.

See [P4 results and verification](benchmarks/p4/README.md) for the repeatable glass
camera path, grid stability, update age, and full-quality recovery measurements.

Add an explicitly enabled interactive resolution mode with a target update time, minimum and maximum sensor dimensions, and displayed actual resolution. Reduce sensor work during sustained movement; restore requested quality after movement settles. Use hysteresis so dimensions do not oscillate. Preserve aspect ratio and field of view. Existing fixed-resolution commands remain authoritative when automatic mode is off.

Resolution changes start coherent accumulation; old grids cannot be silently treated as samples of the new grid. Do not simultaneously reduce depth unless a separate explicit quality option is introduced. This phase intentionally trades spatial detail for interaction speed.

Acceptance: a repeatable camera path shows reduced time to visible updates, bounded resolution changes, and stable return to requested resolution. Report actual sampled dimensions alongside FPS. The target is best effort if even the minimum grid exceeds budget.

## P5 Temporal sample reuse

Implemented as opt-in `view temporal on/off`, off by default. This changes presentation quality, not the raw estimator or spp. Center/corner first-hit guides and neighboring surface checks restrict reuse to opaque diffuse interiors. Reprojection validates stable primitive identity, camera-facing normals and world-space depth; cuts, edits, P4 grid changes, old history and unsupported media reject it. Mirrors/glass/emission remain raw; scattering scenes use whole-frame raw fallback. The existing worker pool handles both guides and reconstruction outside the input monitor.

Two histories keep the previous camera separate from current raw refinement. Local radiance clamping, four nominal history samples, exponential fading and a 500 ms history timeout limit trails. Each valid moving blend has at least 20% fresh radiance; the initial eight-transition age reset was removed because it synchronized pixels into a nine-image noise pulse. F3 separately reports reused pixels, mean blend confidence, reconstruction time and history payload. Disabling immediately displays raw data and releases history on the next coordinator job. Numerical/image tests cover disocclusion, thin geometry, cuts, light/transport edits, unsupported surfaces, cancellation, sustained forward/backward motion and gradual fading through the former reset point. Benchmarks measure reference error, reversal/cut error and cost at quarter/native grids. Finite visibility guards do not prove that every microscopic subpixel mixture has been detected. Nearest-pixel reprojection/clamping can blur or bias presentation.

See [P5 measurements](benchmarks/p5/README.md): lower one-spp noise comes with substantial overhead, and equal-time improvement depends on the scene. Keep this feature optional; it is not a general FPS gain.

P5 traces the same fresh paths as raw mode and adds guide queries, reconstruction and buffer traffic. Its gain is cleaner presentation per fresh sample, not fewer paths or extra geometric detail. The archived quality/cost measurements precede the continuous-fading fix; remeasure the current implementation before choosing defaults. The follow-up phases below turn that quality gain into a possible work reduction. They are not part of implemented P5.

Reproject prior image information into the new camera view instead of always discarding all useful history. Add the auxiliary data needed to validate correspondence, such as first-hit depth, normal, and stable surface identity. Reject newly revealed surfaces, mismatched depth/normals/identity, incompatible scene edits, and unsuitable history.

Begin with opaque diffuse surfaces and keep a raw reference mode. Reflected and refracted views are not reliably validated by the first visible surface alone; initially reject or conservatively limit their history. Volumes and mixed subpixel surfaces require similarly explicit fallback behavior. Keep reconstruction history separate from unbiased raw accumulation and report history confidence separately from raw spp.

Acceptance: motion sequences include disocclusion, camera cuts, changing lights/materials, thin geometry, glass, mirrors, and volumes. Measure ghosting/trailing as well as error at equal frame time. History memory is bounded; disabling the feature restores raw output. Expansion beyond diffuse surfaces requires new evidence rather than applying the same rule everywhere.

## P5.1 Reduce temporal validation and memory cost

Profile guide generation, reprojection/validation, clamping, publication copies and display conversion separately on the current implementation. Guide generation currently uses a center ray and up to four corner rays per sensor pixel, in addition to transport paths. First evaluate reusing actual primary-hit information and sharing correspondence data between tracing and reconstruction. A jittered primary hit does not automatically replace a pixel-center guide, and one hit does not establish that a pixel contains only one surface. Retain conservative edge and disocclusion checks; use targeted extra queries where evidence is insufficient rather than removing safeguards globally.

Reduce redundant full-image copies and memory passes where ownership allows it. Keep bounded histories, immutable leased publications, worker-local scratch and cancellation safety. Evaluate bypassing reconstruction where it has no useful benefit, such as the deterministic depth-zero point-light diagnostic, unsupported scenes, or sufficiently refined stationary images. Switching policies must avoid visible popping or synchronized history resets, and remain explicitly controllable. Automatic bypass needs documented benefit/cost criteria; it must not change transport or raw sample counts.

Acceptance: compare temporal off, current P5 and the optimized version at quarter/half/native grids, with sustained motion, reversals, cuts, thin geometry and mixed diffuse/glass scenes. Report guide rays/tests and wall time, reconstruction/copy time, CPU, allocation, retained memory, fresh-image median/p95 and image age. Preserve raw output and transport counters; compare reconstructed error and trails against independent references. Adopt only changes that reduce total overhead without an unacceptable correspondence regression. No benefit is assumed for temporal-off throughput.

## P5.2 Trade temporal quality for less tracing

Add an opt-in interaction policy that spends validated history's noise reduction on a smaller fresh tracing budget. Compare fewer fresh spp at the same sensor grid when the baseline uses more than one spp, then evaluate lower motion grids with P4. At one fresh spp per pixel, uniform sampling cannot go lower without P11.1 or another explicitly designed sparse scheme. Preserve depth, materials and lighting. Account for unsupported pixels and scenes: glass/mirrors still need fresh raw estimates, and volumes currently bypass temporal reuse entirely.

Select settings using total cost: reduced tracing plus guides, reconstruction, copying and presentation must be cheaper than the raw baseline at acceptable comparable quality. Current temporal reuse operates at the sensor grid; it does not recover missing high-resolution detail. P4 grid changes discard history, so measure warmup and transition costs and avoid frequent grid oscillation. Temporal upscaling or cross-grid history transfer would require a separate specification and validation, not an assumption in this phase.

Expose the policy and its quality/performance tradeoff separately from the existing temporal toggle. Report requested/actual fresh spp, sensor dimensions and history blend independently. Restore the requested stationary quality after motion stops; keep uniform raw mode available. Retain a setting only where measured noise reduction permits enough tracing savings to offset reconstruction cost.

Acceptance: compare against raw rendering and P4 alone on identical repeatable camera paths, using both equal total elapsed budgets and a declared quality target. Include startup, sustained motion, disocclusion, cuts, grid restoration and stationary refinement. Measure fresh-image FPS/p95 and age alongside reference error, edge detail and trailing; cached redraws are not fresh updates. Establish scene-specific useful settings or document that none pay off. Do not make this a default based only on lower one-spp error.

## P6 Spatial denoising

Add an optional spatial filter for noisy radiance, guided where useful by depth, normal, and albedo. Start with a bounded-cost edge-aware filter and benchmark its own cost before adopting a heavier implementation. Operate on a separate presentation result, retaining raw samples and a raw-image toggle. This phase can work without temporal reuse; compare it independently with P5.1/P5.2 rather than assuming their benefits combine.

Acceptance: compare low-spp output with converged references for diffuse, rough, glass, mesh, and volume scenes. Inspect edges, small emitters, highlights, and thin geometry for lost detail or invented smoothness. Report filtering time, memory, and quality at equal total frame budget. A filter that consumes more time than it saves in sampling is not a successful default.

## P7 Top-level object BVH

Build a hierarchy over prepared object bounds to avoid scanning every object for each nearest-hit or visibility query. Retain a small-scene linear path and brute-force reference. Camera changes reuse geometry. Rebuild or refit on relevant instance changes, selecting the simpler correct strategy first.

Acceptance: accelerated and brute queries agree on nearest hits, bounded occlusion, inside/nested media, and volume connections. Preserve stable ties and face identity independently of traversal order. Benchmark both current rooms and increasing instance counts; choose the activation threshold from measurements. Include build cost, not just traversal.

## P8 Near-first BVH traversal

Visit the nearer child first so a close hit can prune more distant bounds. Compare a small reusable traversal stack with the current stackless escape traversal; avoid per-ray allocation. Keep early-exit any-hit queries for opaque visibility and preserve ordered boundary traversal where media require it.

Acceptance: tie cases, rays starting inside bounds, parallel rays, and tiny/shared mesh edges agree with the reference. Measure node visits, primitive tests, stack storage, and elapsed time. Fewer tests alone do not establish a speedup if traversal bookkeeping costs more.

## P9 Better BVH construction

Implement a binned surface-area heuristic, which chooses partitions using an estimated ray traversal cost, as an alternative to median splitting. Tune leaf size only through representative measurements. Retain a robust fallback for degenerate centroid distributions.

Acceptance: compare build time, retained memory, node/primitive work, and repeated tracing time against median splitting at several mesh details. Preserve geometry identities and seeded transport behavior. Keep median construction if the expected scene lifetime is too short to recover the extra build cost.

## P10 Compact geometry and BVH storage

Evaluate flat primitive arrays for node bounds, child/range indices, and frequently accessed geometry/material fields. Aim to reduce pointer chasing and improve cache locality. Consider flat accumulation/display arrays where they simplify tiling and eliminate repeated intermediate traffic. Keep prepared data immutable and shared, and scratch data worker-local.

Do not turn all scene/editor objects into low-level storage or introduce general object pools. Preserve readable authoring objects and compile only the hot representation. Retain double-precision means unless a separately measured accuracy case justifies changing them. Avoid worker counters sharing frequently written cache lines.

Acceptance: measure retained bytes, allocation, throughput, and available cache/memory indicators across simple and mesh scenes. Compare numerical behavior and preserve invalidation/cache reuse. Keep only layout changes with evidence of useful benefit or necessary backend interoperability.

## P11 Adaptive sampling

Maintain sample counts and uncertainty estimates per pixel or tile, spending additional work on unconverged regions. Continue periodic exploration to avoid permanently missing rare bright events. Use independent validation or a documented stopping policy to address bias from data-dependent stopping. Uneven sampling requires correct normalization and new min/mean/max spp reporting; the existing global sample counter is insufficient.

Acceptance: compare independent seeds at equal elapsed time, including small emitters, glass, and high-variance paths. Confirm bright rare events are not frozen out and there is no systematic brightness drift. Expose uniform sampling as a reference. Account for the cost and memory of variance tracking, and reset counts coherently on scene changes.

## P11.1 Sparse temporal updates during motion

Reduce work below one fresh path per pixel per update by tracing only a scheduled subset of validated diffuse regions. Prioritize newly exposed, unsupported, stale or uncertain pixels; refresh every reused region on a bounded schedule, with periodic exploration so rare bright events cannot be frozen out. A rotating or interleaved pattern is an initial candidate, not a guaranteed speedup. Correspondence checks still require visibility information: include their cost and do not assume that skipping a transport path also eliminates all guide queries.

Use P11's per-pixel count/normalization foundation; its full variance-based allocation policy is not required. Keep actual fresh samples separate from reprojected presentation history. History weight must not masquerade as raw spp, and old-camera radiance must not enter current-camera raw accumulation. Define age and fading for pixels receiving no fresh sample: P5's current at-least-20%-fresh blend assumes a fresh estimate at every pixel and cannot be carried over unchanged. Bound staleness without synchronized whole-image resets. Specify selection probabilities and validate any data-dependent sampling/stopping bias.

Retain full fresh tracing for rejected or unsupported pixels and a complete raw fallback for cuts, incompatible edits, missing history and volume scenes. Resolve partial-pass ownership, cancellation, target completion and stationary convergence explicitly; expose min/mean/max fresh counts, refreshed fraction, history age and fallback fraction. Memory and queued work remain bounded, and continuous motion must keep publishing coherent previews.

Acceptance: compare with uniform one-spp raw, P5 and P5.2 at equal total cost and comparable quality. Include long motion/reversals, newly revealed surfaces, thin moving silhouettes, small emitters, rare bright paths, lighting edits, glass/mirrors and volumes. Measure saved primary/continuation/shadow work, guide overhead, fresh-image latency, trailing, brightness drift and time to stationary convergence across independent seeds. Enable only where saved transport work exceeds scheduling/validation/reconstruction cost without unacceptable stale detail. This is a larger sampling/publication change than P5.1 and remains conditional on its measured payoff.

## P12 Improved sample sequences

Evaluate stratified or scrambled low-discrepancy sampling against the existing independent pseudorandom stream. Assign stable sample dimensions for camera, material, light, and volume choices so variable path lengths and scheduling do not create unintended correlations. Preserve reproducibility for a seed and sample index.

Acceptance: use several seeds, presets, and spp levels to compare quality at equal time. Check marginal sampling distributions, persistent patterns, and convergence. Keep the current sampler selectable for regression diagnosis. Sampling changes need statistical agreement, not identical pixels.

## P13 Russian roulette

After a configurable minimum continuation depth, probabilistically stop low-contribution paths and divide surviving throughput by the survival probability. Account for transmission IOR scaling so temporary radiance weights inside glass do not cause inappropriate termination. Apply consistent reasoning to volume events and retain the maximum-depth control.

Acceptance: ensemble brightness agrees with roulette disabled at the same maximum depth; compare error per second as well as average path length. Exercise clear/absorbing/inside/nested glass, diffuse rooms, rough surfaces, and volumes. No benefit is expected at depth zero, and short or lossless paths may not benefit. Enable by default only where measured efficiency supports it.

## P14 Shared mesh acceleration

Build one object-space mesh BVH per immutable mesh asset and transform rays into each instance's local coordinates. Keep per-instance bounds/materials separate. Reuse the shared structure across copies and transform edits; geometry edits create or rebuild the relevant asset cache.

Acceptance: compare translated, rotated, nonuniformly scaled, nested, and inside-camera instances with the current world-space reference. Preserve comparable world hit distances, correct normals, stable face IDs, and whole-instance medium identity. Measure memory and build time versus instance count, plus per-ray transform overhead. Sharing is not automatically a traversal speedup.

## P15 Analytic solid intersections

Evaluate direct box boundary intersection instead of testing its constituent faces. Preserve face identity for normals/material behavior and source skipping, and entry/exit behavior for dielectric and scattering interiors. Keep mesh/faceted geometry behavior unchanged. Extend to other analytic shapes only when a measured workload warrants it.

Acceptance: compare faces, edges, corners, parallel rays, transformed boxes, inside origins, absorption, and volume visibility to the reference. Report ray cost and total scene benefit. Robustness at shared edges and tiny gaps must not be weakened to improve timing.

## P16 Explicit SIMD kernels

Prototype vectorized bounds or primitive tests after profiling identifies a suitable hot kernel and P10 supplies suitable storage. Test processing several primitives for one ray as well as coherent ray batches where appropriate. Irregular secondary rays may waste vector lanes; do not assume camera-ray results generalize.

Use a scalar fallback and isolate any incubating API/build flags. Acceptance requires end-to-end improvement, matching numerical tolerances, portable fallback behavior, and no loss of small-face/seam robustness. A fast isolated kernel that increases batching or data conversion cost overall is insufficient.

## Strategic backend phases

### S1 GPU rendering backend

Inventory the GPU and supported compute/ray-tracing APIs, then choose one backend for a feasibility prototype. The highest potential throughput gain in this roadmap is hardware parallelism on a suitable GPU, but its actual value is unknown until tested. This is not a prerequisite for the CPU phases and is not a promise of 60 FPS.

Define a backend contract around immutable scene snapshots, generations, radiance output, and diagnostics. Keep geometry and accumulation resident on the GPU where possible; avoid per-frame scene uploads and round-trip image transfers when presentation can consume GPU output directly. Measure upload, execution, synchronization, and presentation together. Choose kernel organization based on measured divergence and supported hardware rather than assuming a CPU loop translates efficiently.

Acceptance starts with a clearly labeled subset and CPU comparisons, then covers all transport features before calling it a replacement. Benchmark quality and input latency at native resolution. Retain the Java CPU reference. Document unsupported features instead of silently rendering them differently.

### S2 Native CPU rendering backend

Evaluate a representative native intersection or render batch, potentially using Embree, only when it answers a measured bottleneck or supplies a needed capability. Compare against the improved Java implementation on identical scenes and quality settings, including native-call/data conversion overhead. Avoid crossing the language boundary for individual rays when batching is possible.

Acceptance: quantify end-to-end benefit, packaging burden, memory ownership, diagnostics, and feature parity. Keep the editor and orchestration in Java unless there is independent evidence for moving them. An FPS number alone is not a language-migration criterion. A scalar rewrite with unchanged algorithms is not the intended deliverable.

## How we choose the next phase

After each phase, record the achieved effect and remaining bottleneck. Use the following decisions rather than treating every phase as mandatory:

- If tracing dominates and workers scale well, finish P1/P2 and then target measured intersection or sampling costs.
- If display work dominates at low resolution, prioritize P3 before additional transport work.
- If raw throughput is acceptable but motion is poor, investigate P2/P4 and then reconstruction.
- If temporal mode lowers FPS, evaluate P5.1 overhead first and P5.2's tracing reduction second. Cleaner one-spp output alone does not justify a performance claim; total cost must decrease at acceptable quality.
- If motion already uses one fresh spp per pixel, further path-count reduction requires P11.1's sparse updates and per-pixel accounting; lowering depth or treating history weight as raw samples is not a substitute.
- If scene complexity raises primitive work sharply, prioritize P7 through P10.
- If samples are fast but convergence is slow, prioritize P11 through P13 or explicitly enabled reconstruction.
- If the required native-resolution quality still exceeds the measured CPU budget, evaluate S1. Evaluate S2 only when native kernels offer a demonstrated advantage or useful integration.

No gains should be multiplied together as a forecast: removing one bottleneck changes the value of the next optimization.

## References for implementation

- Local implementation: `src/scenes/viewport/DirectRgbTracer.java`, `Viewport.java`, `Resampler.java`, `PreparedObject.java`, `PrimitiveBvh.java`, `DisplayMapping.java`, and `src/rendering/AwtViewer.java`.
- Local verification: `tst/ViewportBenchmark.java`, transport suites listed in `AGENTS.md`, and [RenderingRequirements.md](RenderingRequirements.md).
- [PBRT on BVH construction](https://pbr-book.org/4ed/Primitives_and_Intersection_Acceleration/Bounding_Volume_Hierarchies).
- [PBRT on improved path tracing and roulette](https://pbr-book.org/4ed/Light_Transport_I_Surface_Reflection/A_Better_Path_Tracer).
- [PBRT on GPU execution](https://pbr-book.org/4ed/Wavefront_Rendering_on_GPUs/Mapping_Path_Tracing_to_the_GPU).
- [OpenJDK discussion of Java vector computation](https://inside.java/2024/10/23/java-and-ai/).
- [Embree](https://www.embree.org/) and [Java foreign function integration](https://inside.java/2024/03/21/sip095/).
