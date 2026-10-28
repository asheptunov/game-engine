# Renderer performance specification

Status: P0 through P5, P5.1, P5.2, P7, P2.1, P8 and P9 implemented. P7, P2.1, P8 and P9 are automatically verified; native-window acceptance remains pending. P6, P10 through P16, P11.1, and S2 remain proposed. S1.1 GPU game rendering is implemented; verification and native-window acceptance status are tracked in its milestone table below. S1.2 direct GPU presentation is authorized; the October 27 GPU performance program below defines its incremental delivery and pending acceptance. The October 10 game profiling review set the delivery order below; the subsequent [P7 results](benchmarks/p7/README.md) record its implementation and measurements. Measurements and commands are in [P0/P1 results](benchmarks/p0-p1/README.md), [P2 results](benchmarks/p2/README.md), [P3 results](benchmarks/p3/README.md), [P4 results](benchmarks/p4/README.md), [P5 results](benchmarks/p5/README.md), and [game profiling](benchmarks/game/PROFILE.md).

This specification follows the six implemented transport phases in [RenderingRequirements.md](RenderingRequirements.md). It defines how to improve rendering throughput, camera responsiveness, and image quality per second while preserving the existing renderer as a correctness reference. The aspiration is 60 or more useful updates per second at the window resolution. This is a measurement target, not a promised result for every scene or quality setting.

The original first deliveries—measurement, tiled multicore tracing, responsive scheduling, and removal of display-buffer churn—are complete. Later phases must earn their place through measurements. Java remains the implementation language for the CPU work; native and GPU backends are separate strategic options.

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

## Historical room baseline and limits

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

The host reported an Intel i5-13600KF and 20 logical processors. GPU identity was unverified at this historical baseline; the October 11 S1 inventory below verifies an RTX 3080 with 12 GB of VRAM and Vulkan ray-tracing support. No numerical parallel speedup is assumed.

## Priority and dependency rules

The table below retains the original phase identifiers and estimates for the room workloads. It is a catalog, not today's execution order. The game-specific order follows it. Throughput, responsiveness, and quality cannot be reduced to one reliable speedup number. Dependencies may require a small enabling step first. Re-rank remaining work after each measured result; keep phase identifiers stable.

GPU work has the highest potential hardware ceiling but is a separate backend project. It is ranked separately so its potential does not imply that it should precede the much cheaper CPU improvements. Native integration is likewise conditional.

| Phase | Optimization | Estimated impact and confidence | Dependencies |
| --- | --- | --- | --- |
| P0 | Establish comparable measurements | Enabler; no direct speedup | None |
| P1 | Tiled multicore tracing | Very high potential trace throughput; high confidence in unused parallelism | P0 |
| P2 | Responsive rendering and cancellation | Very high interaction benefit; high confidence | P1 tile and worker foundation |
| P2.1 | Game completion-to-display scheduling | Implemented completion notification reduces game pickup delay and improves adaptive detail | P2; P7; [measurements](benchmarks/p2.1/README.md) |
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
| S1 | GPU backend | Highest potential throughput ceiling; S1.1 implemented; measured results and acceptance status below | P0, backend contract, measured prototype |
| S2 | Native CPU backend | Uncertain gain; evaluate only against optimized Java | P0 and representative Java baseline |

P0 through P5.2, P7, P8, P9 and the P2.1 follow-up are implemented. P5.1 reduces temporal overhead; P5.2 offers optional tracing reductions where they pay off at acceptable quality. P6 remains a separate quality experiment. P11.1 follows per-pixel accounting because it changes which pixels receive fresh paths. Decimal identifiers preserve existing phase numbers.

### Current priority: the sample game, October 10, 2026

The representative workload is the 313-block landscape reached by `./game.ps1`: diffuse textured blocks, sun and sky, depth two, one sample per batch, and temporal reconstruction off. The requested grid is 320×200 for a 960×600 window. Adaptive resolution can reduce motion images to 102×64. See [the profiling report](benchmarks/game/PROFILE.md) for CPU samples, per-publication timings, raw evidence, commands, and limitations.

The game passed its original 200 ms p95 freshness threshold by reducing resolution substantially. That milestone remains complete, but passing it no longer justifies deferring throughput work. Fixed-grid freshness and image age must accompany adaptive results. Native-window smoothness remains unverified.

| Order | Work and status | Why this comes next | User-visible completion and evidence |
| --- | --- | --- | --- |
| Delivered | P7: object-level acceleration; implemented and automatically verified | The original profile found nearest-hit and shadow queries dominated CPU samples. The tracer now prunes groups of objects for both. | Equal-grid route and scaling results are in [P7](benchmarks/p7/README.md). Adaptive restoration and reference equivalence are checked; native-window acceptance remains pending. |
| Delivered | P2.1: game publication scheduling; implemented and automatically verified | Completion signals wake the existing game owner instead of waiting for its periodic tick. Adaptive feedback now reflects faster delivery. | Fixed/adaptive replays, CPU checks and the worker/tile investigation are in [P2.1](benchmarks/p2.1/README.md). Full-grid recovery remains checked; native-window acceptance remains pending. |
| Delivered | P8: near-first object traversal; implemented and automatically verified | Nearer object bounds are visited first so close hits prune deferred groups. Primitive BVHs retain escape traversal because the mesh experiment did not justify a new default. | [P8 results](benchmarks/p8/README.md) include fixed/adaptive routes, matched traversal work, allocations and reference checks; native-window acceptance remains pending. |
| Delivered | P9: surface-area construction; implemented and automatically verified | Sixteen-bin partitions reduce landscape traversal work; eight-primitive leaves trade a little mesh speed for lower hierarchy memory. | [P9 results](benchmarks/p9/README.md) include pinned construction/trace measurements, alternating fixed-grid routes, memory and reference checks. Native-window acceptance remains pending. |
| 1 | P10: compact prepared storage; conditional experiment | Profile the remaining intersection work after P9 and compare a flat representation with the current objects. | Keep only a measured fixed-grid gain with equivalent transport and bounded memory. A hot Java query alone does not establish cache stalls; retain the current layout if the experiment fails. |
| 2 | P15: analytic box intersections; conditional experiment | Cubes use polygon faces. Face work may become the next bottleneck after object pruning. | Keep only a measured fixed-grid gain with equivalent images, texture orientation, surface identity and shadow behavior. The current profile does not isolate face cost from inlined bounds/loop work. |
| Deferred | P14, chunk/hidden-face world representations, P6/P11/P12/P13, further temporal work, S2 | Shared acceleration and chunks need a larger-world workload; quality changes and new backends do not address the first measured CPU bottleneck as directly. | Reopen using scaling, quality-at-equal-time, or residual CPU-budget evidence. Chunking is an investigation, not an approved new world architecture. |

S1 was reopened for evaluation on October 11. Its initial game-first scope did not authorize S1.2; the October 27 request below now authorizes that work.

P0 measurement work belongs in each delivery: retain the frozen route, source fingerprints, fixed/adaptive comparisons, actual grids, image age, copy/pickup timings, and separate profiled versus unprofiled runs. Add larger block counts before claiming world scalability. Keep the existing 200 ms threshold as a historical regression check, not a smooth-gameplay acceptance target. The original 60-useful-updates-per-second aspiration remains; a new hard gameplay target and minimum acceptable moving resolution require human evaluation and are not settled by this profiling review.

For P7, also measure a short object-bounds/primitive-work diagnostic if needed to choose the hierarchy design. Java Flight Recorder identifies hot query paths, but JIT inlining prevents treating method sample shares as an exact split between object bounds and polygon intersections. Acceleration must preserve touching block faces and deterministic cross-object ties as well as the general engine's glass and volume behavior.

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

## P2.1 Fresher game images after tracing completes

Implemented and automatically verified. `RenderSession.onImageAvailable` signals a completed publication outside engine state locks. `src/game/GameRenderer.java` coalesces signals into at most one pending task on its existing owner executor. The owner retains its 16 ms periodic input/lifecycle tick, image leases and immutable display copies. A signal wakes it promptly to publish an image and request subsequent work; cancellation and shutdown retain their existing rules.

Warmed fixed/adaptive route repeats, pickup delay, snapshot age, fresh intervals, CPU checks, and a fixed-grid worker/tile sweep are recorded in [P2.1 results](benchmarks/p2.1/README.md). Adaptive delivery improves without lowering requested resolution, depth or lighting quality, and it uses larger moving grids than the prior baseline. The fixed-camera sweep and route follow-up justify a 16-pixel game tile default; fourteen workers and the general engine's 32-pixel default remain unchanged. The Swing repaint timer remains separate; copied-image timings exclude Swing delivery and monitor scanout.

Native acceptance remains pending: continuously look and move, release held keys, lose/regain focus, suspend/resume, and close while tracing. Confirm that motion remains responsive and stopping restores the requested grid and refinement. The automated lifecycle and route checks cover these engine/game state transitions without a native window.

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

Implemented: corner visibility is shared between adjacent pixels within each trace tile, using two rolling rows of worker-owned cached hit identities/normals. Rays sample exact pixel boundaries rather than the previous 0.001/0.999 inset positions. Center-hit and sampled-primary checks remain, as do front-face/normal checks, neighboring-surface validation and raw fallback. The small scratch cache is reused across tiles and released when temporal mode is disabled or volumes require fallback. Raw transport streams/counts are unchanged. See [P5.1 measurements](benchmarks/p5.1/README.md).

Measured guide-ray reductions are 48–57% in the surface motion workloads, with under 0.5% change in one-spp reconstructed reference error. Native processing improved in these runs; quarter bounce-room showed no reliable timing improvement. Temporal remains an optional quality mode and still costs more than raw. Primary-hit substitution was not adopted because jittered hits do not supply equivalent center/corner coverage. Full-image copy elimination and automatic bypass remain candidates requiring separate evidence; immutable publication ownership is retained.

Profile guide generation, reprojection/validation, clamping, publication copies and display conversion separately on the current implementation. Guide generation currently uses a center ray and up to four corner rays per sensor pixel, in addition to transport paths. First evaluate reusing actual primary-hit information and sharing correspondence data between tracing and reconstruction. A jittered primary hit does not automatically replace a pixel-center guide, and one hit does not establish that a pixel contains only one surface. Retain conservative edge and disocclusion checks; use targeted extra queries where evidence is insufficient rather than removing safeguards globally.

Reduce redundant full-image copies and memory passes where ownership allows it. Keep bounded histories, immutable leased publications, worker-local scratch and cancellation safety. Evaluate bypassing reconstruction where it has no useful benefit, such as the deterministic depth-zero point-light diagnostic, unsupported scenes, or sufficiently refined stationary images. Switching policies must avoid visible popping or synchronized history resets, and remain explicitly controllable. Automatic bypass needs documented benefit/cost criteria; it must not change transport or raw sample counts.

Acceptance: compare temporal off, current P5 and the optimized version at quarter/half/native grids, with sustained motion, reversals, cuts, thin geometry and mixed diffuse/glass scenes. Report guide rays/tests and wall time, reconstruction/copy time, CPU, allocation, retained memory, fresh-image median/p95 and image age. Preserve raw output and transport counters; compare reconstructed error and trails against independent references. Adopt only changes that reduce total overhead without an unacceptable correspondence regression. No benefit is assumed for temporal-off throughput.

## P5.2 Trade temporal quality for less tracing

Implemented as opt-in `view temporal budget on/off`, separate from `view temporal on/off`. `view temporal budget samples <1..8>` caps the moving batch (default 1); `view temporal budget scale <0.25..1>` optionally caps the moving grid (default 1, retaining full resolution). Requested settings are restored after 350 ms without motion. Missing/incompatible/cut/expired history uses the requested batch maximum to seed a new image, subject to existing pass/budget limits. P4 retains its independent one-spp cap and minimum grid bounds. Volume/no-diffuse scenes and deterministic depth-zero point-light scenes bypass this budget; unsupported pixels still receive fresh raw estimates. No samples are skipped below one per pixel, and there is no temporal upscaling.

Three independent-seed comparisons show a useful bounce-room setting: one fresh spp with seeded history has about 8% lower average error than four raw spp and takes about 39% less headless processing time. Glass does not match three/four-spp raw quality with this setting. Low-grid noise/error improvements also involve loss of spatial detail. Live comparisons and P4 results are documented in [P5.2 measurements](benchmarks/p5.2/README.md); they do not establish a general FPS gain. F3 separates actual/chosen/requested batch counts, sampled dimensions and history blend. The mode remains off by default.

### Outcome and priority after hands-on evaluation

The user reports no significant difference between budget on/off, with both substantially slower than temporal off. This is consistent with the live scheduling limits: continuous camera changes already end a batch after its active sample pass, and P4 explicitly caps moving batches at one spp. P5.2's default sample cap therefore often removes no work, while guide generation, reconstruction and memory traffic remain. The fixed-view four-spp comparison demonstrates a quality/work tradeoff but overstates its relevance to continuous mouse movement; it must not be presented as the expected interactive speedup.

Retain P5/P5.1/P5.2 as off-by-default quality experiments. Defer further temporal performance work for the current workload and use temporal off as the raw throughput baseline. Lowering the P5.2 grid can increase FPS by sacrificing detail, but P4 already offers that work reduction without temporal overhead. P11.1 remains a conditional, larger redesign that could remove work below one fresh spp; do not prioritize it without evidence that correspondence and reconstruction cost can be repaid by skipped transport paths.

Next, profile representative raw motion workloads with temporal off and select an existing tracing phase from the measured bottleneck:

- P7 targets the linear object scan; its value grows with object count and may be small in current rooms.
- P8/P9 target traversal order and BVH quality, primarily in mesh/intersection-heavy scenes.
- P10 targets pointer chasing, memory layout and accumulation traffic; cache/memory limits remain hypotheses until measured.
- P15 targets box face-test cost; P13 targets long low-contribution paths, with energy/noise validation.

These phases cover the intersection and memory directions. The October 10 game review separately adds P2.1 for measured application pickup delay. Reuse P0/F3/F4 measurements to choose or re-rank work; add a focused phase only if profiling identifies work outside existing scope. Faster ray queries would also reduce temporal guide cost, but raw end-to-end throughput is the immediate acceptance target.

Add an opt-in interaction policy that spends validated history's noise reduction on a smaller fresh tracing budget. Compare fewer fresh spp at the same sensor grid when the baseline uses more than one spp, then evaluate lower motion grids with P4. At one fresh spp per pixel, uniform sampling cannot go lower without P11.1 or another explicitly designed sparse scheme. Preserve depth, materials and lighting. Account for unsupported pixels and scenes: glass/mirrors still need fresh raw estimates, and volumes currently bypass temporal reuse entirely.

Select settings using total cost: reduced tracing plus guides, reconstruction, copying and presentation must be cheaper than the raw baseline at acceptable comparable quality. Current temporal reuse operates at the sensor grid; it does not recover missing high-resolution detail. P4 grid changes discard history, so measure warmup and transition costs and avoid frequent grid oscillation. Temporal upscaling or cross-grid history transfer would require a separate specification and validation, not an assumption in this phase.

Expose the policy and its quality/performance tradeoff separately from the existing temporal toggle. Report requested/actual fresh spp, sensor dimensions and history blend independently. Restore the requested stationary quality after motion stops; keep uniform raw mode available. Retain a setting only where measured noise reduction permits enough tracing savings to offset reconstruction cost.

Acceptance: compare against raw rendering and P4 alone on identical repeatable camera paths, using both equal total elapsed budgets and a declared quality target. Include startup, sustained motion, disocclusion, cuts, grid restoration and stationary refinement. Measure fresh-image FPS/p95 and age alongside reference error, edge detail and trailing; cached redraws are not fresh updates. Establish scene-specific useful settings or document that none pay off. Do not make this a default based only on lower one-spp error.

## P6 Spatial denoising

Add an optional spatial filter for noisy radiance, guided where useful by depth, normal, and albedo. Start with a bounded-cost edge-aware filter and benchmark its own cost before adopting a heavier implementation. Operate on a separate presentation result, retaining raw samples and a raw-image toggle. This phase can work without temporal reuse; compare it independently with P5.1/P5.2 rather than assuming their benefits combine.

Acceptance: compare low-spp output with converged references for diffuse, rough, glass, mesh, and volume scenes. Inspect edges, small emitters, highlights, and thin geometry for lost detail or invented smoothness. Report filtering time, memory, and quality at equal total frame budget. A filter that consumes more time than it saves in sampling is not a successful default.

## P7 Top-level object BVH

Implemented in `DirectRgbTracer`: a median-split hierarchy with leaves of up to four objects, enabled at 32 prepared objects. It shares the existing bounds/tree builder, retains original object indices for ties, and uses one reusable traversal cursor per worker. Camera changes reuse the prepared hierarchy; relevant scene edits rebuild it. Brute mode bypasses both hierarchy levels. The 32-object cutoff avoids measured regressions in small optical rooms. See [P7 evidence and reproduction](benchmarks/p7/README.md). Native-window playthrough remains an acceptance step; the requirements below are retained as the delivery contract.

Build a hierarchy over prepared object bounds to avoid scanning every object for each nearest-hit or visibility query. Retain a small-scene linear path and brute-force reference. Camera changes reuse geometry. Rebuild or refit on relevant instance changes, selecting the simpler correct strategy first.

For the game, deliver this through the existing landscape without changing block geometry, textures, sun/sky, depth, sampling or requested resolution. Include both nearest-hit queries and opaque sun visibility; accelerating only camera rays leaves the measured shadow cost. Test touching blocks and cross-object equal-distance ties explicitly. Evaluate near-first traversal as part of the P7 comparison when useful; retain P8 as the broader follow-up rather than requiring an unmeasured traversal rewrite first.

Acceptance: accelerated and brute queries agree on nearest hits, bounded occlusion, inside/nested media, and volume connections. Preserve stable ties and face identity independently of traversal order. Benchmark both current rooms and increasing instance counts; choose the activation threshold from measurements. Include build cost, not just traversal.

## P8 Near-first BVH traversal

Implemented October 10, 2026 for P7's object hierarchy. `DirectRgbTracer` visits the nearer child first and uses fixed worker-local stacks with cached entry distances to prune deferred nodes. Opaque visibility retains early exit; volume/glass visibility retains ordered nearest-boundary queries. Exact surface ties use original object/primitive identities. Camera edits reuse immutable prepared trees.

Primitive hierarchies retain escape traversal: the evaluated near-first mesh variant reduced work but did not establish a reliable timing gain. At the P8 delivery, tree construction and leaf sizes remained median/four; P9 changes construction below. The retained object configuration reduces matched single-worker median time by 3.2% in the landscape and 5.4% in the synthetic 1,024-block view; route timings remain subject to host variation. See [P8 results](benchmarks/p8/README.md) for fixed/adaptive route measurements, node/bounds/primitive counts, stack payload, zero worker tracing allocations and pending native-window acceptance.

Automatic acceptance passes tie cases, rays starting inside bounds, parallel rays, touching block faces, tiny/shared mesh edges, advanced transport, seeded reference images and worker agreement. `verify.ps1` and `game-check.ps1` pass. The two cursors reserve 512 bytes of array payload per worker; the P8 capacity argument relied on median splitting with leaves of four. P9 now caps construction depth at thirty to preserve this capacity. Fewer tests alone do not establish a speedup if traversal bookkeeping costs more.

## P9 Better BVH construction

Implemented: `BvhBuilder` chooses partitions using a sixteen-bin surface-area heuristic (SAH), an estimate of traversal cost based on child box area and item count. Cached object trees use four-item leaves; primitive trees use eight-item leaves. Degenerate or unusable centroid partitions fall back to a stable median split. A depth cap of thirty emits a complete leaf, preserving every item and the fixed worker traversal stacks. Geometry, original surface identities, bounds padding, sampling and transport remain unchanged.

Pinned single-worker measurements at 48×48 show landscape tracing falling from 2.3377 to 1.5542 ms, with hierarchy construction rising from 0.0913 to 0.1884 ms: less than one measured pass repays the extra build cost. Mesh details 4/12/32 trace 6.2–8.1% faster with 35–47% less retained hierarchy memory. The synthetic 1,024-block view has no useful trace gain and adds about 0.256 ms of construction; no scaling gain is claimed there. Prepared trees are reused across camera edits. Median construction remains available for comparisons and workloads where rebuilding cannot repay the extra cost.

Automatic acceptance compares leaf sizes, degenerate and skewed inputs, bounded depth, ray limits, identities, seeded images, advanced transport and worker agreement. Alternating fixed-grid game replays in one JVM deliver 53.5 versus 78.9 copied images/s; separate before/after JVM timings are mixed, so this is measured evidence rather than a guaranteed gameplay gain. See [P9 results](benchmarks/p9/README.md) for commands, memory scope, preparation costs, raw evidence and the residual profile. `verify.ps1`, `game-check.ps1`, `style-test.ps1` and the focused construction suite pass. Native-window input, focus and smoothness acceptance remains pending.

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

Updated October 24, 2026. Audience: the developer/player evaluating the sample game and maintainers implementing the renderer. This section is the authoritative S1 specification. Implementation of S1.1 was requested after scope agreement. The first delivery is an explicitly selected GPU version of the sample game; CPU rendering remains the default and correctness reference. Full engine replacement is outside that delivery.

#### Intent and verified evidence

Allow the player to explore the existing textured landscape at higher useful resolution or quality while retaining responsive camera updates. A faster shader alone does not meet this purpose: compare complete image delivery and quality at equal elapsed time. The original prototype had no mandatory FPS threshold. The October 27 program below now targets a 60 FPS floor and preferably 120+ FPS, with resolution and quality acceptance still to be settled.

Local read-only probes on October 11 reported NVIDIA GeForce RTX 3080, 12,288 MiB VRAM, driver 616.92 and Vulkan device API 1.4.351. `vulkaninfo` reported both extensions and true feature flags for acceleration structures, ray queries and ray-tracing pipelines. Reproduce with `nvidia-smi --query-gpu=name,driver_version,memory.total,compute_cap --format=csv` and `vulkaninfo`. This establishes API availability, not successful renderer execution or speed. Recheck at initialization rather than assuming every machine has these capabilities.

The [P9 residual profile](benchmarks/p9/README.md) identifies nearest-hit and shadow queries as the dominant sampled CPU paths. These are a plausible target for hardware traversal, but their sample shares do not predict GPU speedup. GPU correctness and benchmark evidence is recorded in [S1.1 results](benchmarks/s1/README.md); window-free evidence does not establish native-window acceptance. The existing [RenderEngine](src/engine/RenderEngine.java) and [RenderSession](src/engine/RenderSession.java) accept immutable world/view/settings inputs; [RenderImage](src/engine/RenderImage.java) exposes leased CPU-readable pixels. [GameRenderer](src/game/GameRenderer.java) converts those pixels to AWT images. These are useful integration boundaries, with a CPU-specific image contract that direct GPU presentation must extend.

#### Scope and architecture direction

Agreed behavior: the first delivery covers the landscape game's textured opaque geometry, sun and sky, perspective pinhole camera, depth-two diffuse paths, progressive accumulation and CPU/GPU comparisons. GPU selection is explicit. CPU remains the startup default. Initialization failures and unsupported content produce a visible reason and an explicit way to choose CPU; they never silently change backend or approximate unsupported materials. Existing CPU modes remain available.

Implemented engineering direction: use [LWJGL](https://www.lwjgl.org/) Java bindings to Vulkan and GLSL shaders compiled to SPIR-V, Vulkan's shader intermediate format. Keep the editor, game logic, navigation and session orchestration in Java. Start with compute-shader ray queries: a shader traces one pixel sample, using hardware-supported geometry traversal and shader code for lighting and continuation. [Vulkan's ray-tracing guide](https://docs.vulkan.org/guide/latest/extensions/ray_tracing.html) describes ray queries and full ray-tracing pipelines. NVIDIA-only CUDA/OptiX remains an alternative if measured evidence warrants changing the direction; that platform restriction requires revisiting this specification before adoption.

Pack immutable scene data into device buffers. Build bottom-level acceleration structures for reusable geometry and a top-level structure for transformed instances. These are GPU-managed hierarchies, not uploads of the Java BVH objects. Retain geometry, textures and accumulation on the GPU across images; camera motion updates camera parameters, material edits update material data, and geometry edits rebuild affected acceleration data. Cache validity must use content identity/revisions and handle edit/undo cycles. Preserve original object and face identities separately from GPU traversal ordering.

Submit batches across the Java/native boundary, never individual rays. Start with a simple path loop; separating tracing and shading into queues is a conditional optimization if divergent paths demonstrably waste execution. Neither queue organization nor a new public Java API name is prescribed. Package pinned Java/native dependencies and compiled shaders through the existing script-based build. Document setup; subsequent normal launches must not download dependencies or require a shader compiler. CPU-only launch must work without initializing Vulkan.

Readback to the existing CPU image contract is acceptable in S1.1. Measure GPU-to-host transfer, synchronization, CPU conversion and AWT presentation. S1.2 removes that round trip from normal display, while retaining explicit readback for comparisons and exports. Native-window integration and overlay/input composition belong to that delivery; they cannot be assumed to work by replacing the tracer alone.

#### Shared behavior and correctness constraints

- Preserve immutable captured inputs, generation labels, sample counts and completed-image leases. Bound in-flight GPU jobs and storage; do not queue every mouse event. During sustained camera motion, complete coherent captured-camera previews and then use the latest camera. Never mix their samples into a different render key.
- Submitted GPU work may have to finish after invalidation. For scene/resolution/restart changes or suspension, reject obsolete publications and defer storage reuse until GPU completion and all readers release it. Close must retire resources safely without blocking the input thread on long tracing work. A device failure stops GPU submission, reports a reason, and offers CPU restart with fresh accumulation.
- Preserve scene brightness, texture coordinates/filtering, normal orientation, shadow behavior, depth semantics and sampling probabilities for the supported subset. GPU hardware does not automatically reproduce CPU intersection rounding or equal-distance tie order. Test touching block faces, shared edges, tiny triangles, source-face skipping and stable identities explicitly; accepting seams or wrong surfaces is not a radiance tolerance.
- Define and justify numeric image tolerances before judging comparisons. Do not require bit-identical CPU/GPU noise where arithmetic differs. Maintain reproducible seed/pixel/sample mapping within the GPU backend and test repeat visits and batch boundaries. Validate accumulation precision over long runs against the CPU double-precision reference; do not silently lower supported sample limits.
- Report selected/active backend, device, requested/actual grid, captured generation/age and sample count. Separate GPU execution timestamps from host elapsed time, upload/build, readback and presentation costs. Hardware primitive-test counters that are unavailable must say unavailable, not zero or a fabricated CPU-equivalent value.
- Glass, mirrors/rough materials, area-light MIS, volumes, advanced camera optics and temporal reconstruction are outside S1.1. Reject requests for unsupported features with a reason and CPU option. P4 adaptive resolution is not required on GPU in S1.1: fixed grid is explicit and labeled, while CPU adaptation continues to work. No silent depth, resolution or material reduction is allowed.

#### Milestones and status

The inventory above is completed investigation. The table distinguishes delivered code and automated verification from the separate native-window human acceptance step.

| ID | User can now... | Prerequisite | Status / evidence |
| --- | --- | --- | --- |
| S1.1 | Play the textured landscape using an explicitly selected GPU backend and compare it with CPU rendering | Verified hardware/API inventory; scope agreed above | Implemented and automatically verified; [S1.1 results](benchmarks/s1/README.md); native-window human acceptance pending |
| S1.2 | Play the same supported game with device-resident presentation, preserving navigation and diagnostics | S1.1 evidence supports continued investment; presentation approach selected | Authorized October 27; pending implementation and verification |
| S1.1a | Explore the existing GPU game with less image-conversion overhead at identical quality | Existing GPU backend | Implemented; verification results below; human acceptance pending |
| S1.1b | Choose a standard internal render resolution or retain custom dimensions in game settings | Existing settings panel | Implemented; settings tests and game gate passed; human acceptance pending |
| S1.1c | Play at 1080p with GPU color conversion and one-quarter-sized routine readback | S1.1a and S1.1b | Implemented; real-device, capture, game and completion gates passed; human acceptance pending |
| S1.1d | See fresh images painted without waiting for the UI timer, and inspect fresh paint rate | S1.1c | Implemented; game, GPU UI and completion checks passed; native-window acceptance pending |
| S1.3 | Explore demanding scenes with a selectable frame-budget mode that adjusts GPU internal resolution and reconstructs output | S1.2 | Authorized; lower internal resolutions accepted for the player's 4K display; implementation pending |
| S1.4 | Play combat, atmosphere and fog scenes with fewer frame-time spikes at the selected quality | S1.2; measured remaining bottlenecks | Proposed; specific optimization selected from evidence |
| S1.4a | Explore with optional lower-resolution atmosphere while keeping geometry sharp | S1.1c; existing fog reconstruction | Implemented; GPU quality/lifecycle, game and completion checks passed; human visual/performance acceptance pending |

**S1.1 — Play and compare the GPU landscape.** Extend the existing `./game.ps1` entry point with an explicit backend option; the command is `./game.ps1 -Backend gpu`. With no option it must retain CPU behavior; provide `-Backend cpu` for repeatable comparisons. Use `-Width 320 -Height 200` or `-Width 960 -Height 600` to select the fixed tracing grid independently of the window size. Runtime backend switching is not required; relaunching is sufficient.

User test: launch CPU and GPU separately on the landscape, navigate using existing controls, stop and observe refinement, edit the supported sun/sky controls, restart accumulation, lose/regain window focus and close. GPU mode must show textured geometry and matching lighting behavior, keep input responsive, publish coherent movement images, and refine the final view at the selected grid. Invalid initialization or content containing an unsupported material or camera mode must show the reason and a documented CPU relaunch action. A failed initialization must leave no background GPU work running.

This delivery includes the complete Java-to-shader path, scene compilation/cache, session lifecycle, bounded GPU work, readback, existing-window presentation, controls, diagnostics and reproducible comparisons. Automated checks cover supported transport, numeric and seam cases, repeatable sampling, batch equivalence, edit/restart invalidation, leases, motion publication, suspend/resume, shutdown and injected capability/device failures. CPU regression tests must remain green. Missing GPU hardware skips explicitly marked device tests; it must not count as GPU verification. Hands-on game checks are required separately from window-free replay results.

Performance evaluation uses the frozen `benchmarks/game/camera-route.csv` and landscape, depth two, seed one, one sample per batch, temporal off and adaptation off. Compare fixed 320x200 and native 960x600 on both backends. Record window dimensions separately. Use thirty seconds warmup per backend and at least three measured route replays, alternating order without simultaneous sessions. Record source fingerprint, JDK, driver/device, dependency/shader versions, CPU worker/tile settings, power/load conditions, actual grids and all transport settings. Separate cold shader/scene preparation from warm tracing. Compare stationary error over multiple seeds against a sufficiently converged CPU reference at equal elapsed time; record reference spp, chosen error metric and numeric tolerances with the evidence.

Report median/p95 completed-image intervals, snapshot-to-display age, fresh images/second, stationary quality, full pipeline timing, upload/build/readback, allocations and peak host/device memory. Distinguish headless publication timing from actual window submission; neither is physical input-to-photon latency. Human acceptance includes native-window responsiveness and visible artifacts. Functional completion requires the behavior and tests above plus a reproducible comparison report, even if the result shows no useful speedup. The historical 200 ms p95 check remains a regression check, not a smoothness target. These are historical S1.1 acceptance conditions. The October 27 request authorizes continued investment through S1.2, with targets and incremental acceptance defined below. An unfavorable result may close the experiment as evaluated/deferred without promoting GPU to default.

**S1.2 — Display GPU images directly.** Preserve S1.1's game and CPU option while presenting the GPU image without routine full-image GPU-to-CPU readback. Include GPU filtering, exposure/tone mapping, sRGB encoding, window presentation, input/focus handling and diagnostic/UI composition. Preserve the CPU reference display semantics, including linear-space filtering before tone mapping. Explicit export/reference capture may still read back. Keep any GPU image alive until presentation completion as well as reader release.

User test: use the same GPU game entry point and fixed-grid controls, follow the same route, inspect diagnostics, stop/refine, change supported lighting, switch window focus and close. Navigation and displayed scene behavior remain intact. Compare S1.1 readback and direct presentation at matching settings and image quality, measuring transfer removal and complete display cost, age and interval p95. Accept the change only if it produces a repeatable end-to-end benefit without new correctness, lifecycle or input regressions. Selection of a Vulkan window/surface integration is a bounded engineering investigation inside this outcome; it must preserve the actual game's controls and UI. If integration cost or measurements do not justify the change, retain S1.1 and record why.

#### Deferred scope, verification and next decisions

Broader engine parity is deferred, not a hidden prerequisite for S1.1. Before calling GPU a general replacement, specify further user-testable slices and cover every existing transport/camera/history feature, including nested media, absorption, Fresnel/IOR weights, rough materials, area-light MIS, volume visibility, orthographic cameras and temporal modes. The October 24 follow-up adds perspective depth of field and grouped sample-game settings on CPU and GPU; see the [support and verification notes](benchmarks/s1/README.md#october-24-follow-up-settings-panel-and-gpu-depth-of-field). It preserves exact zero-aperture behavior and does not add GPU temporal reuse or adaptive resolution. Retain the Java reference throughout. Other GPU vendors/platforms need their own capability and correctness runs; initial acceptance is on the inventoried Windows machine and does not establish cross-vendor support.

No blocking user decisions remain for the agreed S1.1 scope. Vulkan/LWJGL and double-precision accumulation are implemented; the best kernel organization remains an evidence-led optimization choice. The threshold for further investment is deliberately evidence-led rather than an invented FPS commitment. Direct GPU presentation is authorized by the October 27 request; full engine parity remains separate scope.

Run `./verify.ps1`, relevant game checks and new GPU tests for implementation changes; document selected-suite limits and inspect failures. Automated verification must remain window-free and must not steal focus. Record milestone results and commands in `tracker.md` and `benchmarks/s1/README.md` when implemented, with raw settings/results beside them. Keep milestone status here authoritative and link evidence rather than duplicating completion claims. Changes to this specification require independent document review; implementation changes require the checks above.


#### October 27, 2026: high-resolution game performance program

Audience: the player evaluating the sample game and maintainers implementing its renderer.
The user authorized implementation, durable specification tracking, and notifications when
playable increments are ready. This program supersedes the earlier condition that S1.2
needed separate authorization. The existing CPU performance phases remain available but
are not the current delivery priority.

**Intent and evidence.** Make exploration and combat feel responsive on the RTX 3080
12 GB, targeting at least 60 fresh displayed images per second and preferably 120+.
A frame budget is 16.67 ms at 60 FPS and 8.33 ms at 120 FPS. Repeatedly displaying the
same publication does not count as a fresh frame. The historical
[fog lighting-grid experiment](benchmarks/atmosphere/fog-grid/README.md) measured
45–47 ms of headless frame work at 1600x766 with its fastest tested settings, including
roughly 11 ms of CPU conversion. These measurements exclude native window presentation
and do not describe current combat performance. At the start of this program, the Swing repaint timer requested
updates every 16 ms; S1.1d below removes that wait for fresh images. The initial investigation found a 1600-pixel per-axis software limit;
the subsequent 1080p delivery below raises it to 1920.

**Scope and quality.** Optimize the existing Vulkan game backend and preserve the CPU
reference, controls, captures, diagnostics, optics, fog and supported scene behavior.
Begin with changes that preserve displayed bytes and raw radiance. The first slice parallelizes conversion of large GPU images across at most four strips on the existing process-wide worker pool; small images retain serial conversion. All strips finish before the calling image lease can be released, including after interruption. CPU rendering retains its existing per-pixel conversion behavior. Device-resident
presentation, improved traversal, GPU adaptive resolution/reconstruction and cheaper
dynamic-scene/fog work are authorized areas of investigation and implementation.
Do not silently reduce path depth, disable effects, change exposure, or call an enlarged
low-resolution image native rendering. Changes that approximate lighting or reuse history
must be selectable and retain a reference mode. No new GPU vendor or general engine parity
promise is part of this program.

S1.1a is useful independently; it does not complete S1.2 direct presentation.

**S1.1a user test and acceptance.** Launch `./game.ps1 -Backend gpu -Scene world
-Width 1600 -Height 1000`, move and turn, release the pointer with Escape, change exposure,
stop to refine, save an F8 capture, switch focus away/back and close. Geometry, lighting,
pixel orientation and controls must match the reference. Measure a paired conversion
comparison on the same leased GPU image and matched end-to-end scene runs; retain this
slice only with a repeatable benefit and no raw-image or lifecycle regression. It is useful
independently of direct presentation but does not claim the final FPS target.


**S1.1a measured result (October 27).** On a frozen 1600x1001 GPU landscape image,
three alternating paired rounds compared the former conversion loop with the new one,
including creation of the AWT image in both. Forty paired warmups preceded three rounds
of forty pairs. Median conversion times were 26.84/26.90/24.20 ms before and
8.32/8.39/7.67 ms after: approximately 3.2 times faster for this stage. The serial bulk
prototype did not improve performance and was superseded by bounded parallel conversion.
The timings are host-load-dependent and do not establish displayed FPS or a whole-game
speedup. Byte comparisons cover five exposures, orientation, special float values, held
image stability, invalid destinations, closed leases and interruption. The CPU path is
checked separately. Raw sampling and the production shader are unchanged; a deferred-normal
shader experiment passed device checks but was removed because stable end-to-end performance
evidence was unavailable.

Evidence is in [paired conversion results](benchmarks/s1/high-resolution/copy-gpu.csv),
[CPU compatibility results](benchmarks/s1/high-resolution/copy-cpu.csv) and
[host-variable baseline](benchmarks/s1/high-resolution/before-fog.csv). The baseline fog
sweep varied widely and is retained only to explain why it cannot support a speedup claim.
The [paired live-game routes](benchmarks/s1/high-resolution/route.csv) replay a three-second
landscape camera path at 1600x1000, depth 2, one sample per batch and fixed resolution,
after two seconds of warmup per renderer. Three pairs alternate conversion order and drain
the previous GPU owner before starting the next. Parallel/reference fresh-copy rates were
68.0/20.7, 26.3/10.7 and 39.7/34.0 images per second. Host variability was large, and the
last pair's p95 interval regressed despite higher throughput. These short headless runs
support continued investigation, not a stable whole-game speedup or a 60 FPS floor.
Combat, fog and native-window target acceptance remain outstanding.
Reproduce the paired check after `./gpu-build.ps1`: compile
`tools/gpu/DisplayCopyCheck.java` into `out/gpu/classes` with Java 23 and the
`out/game/classes;out/gpu/classes;out/gpu/lib/*` classpath, then run
`game.DisplayCopyCheck gpu 1600` on that classpath with `--enable-preview` and
`-Djava.awt.headless=true`. `./gpu-check.ps1` includes the GPU parallel and CPU compatibility
checks at smaller sizes. `./verify.ps1` and `./game-check.ps1` passed; full GPU checks are
recorded in the retained verification log. Run PowerShell 7 for the readability gate:
Windows PowerShell 5.1 miscompares the existing baseline on this machine. No baseline
allowances were expanded. Native-window smoothness remains for the player to evaluate.
Compile `tools/gpu/DisplayRouteBenchmark.java` alongside `DisplayCopyCheck.java` and run
`game.DisplayRouteBenchmark` on the same classpath/options to repeat the live-game pairs.
The [GPU checks](benchmarks/s1/high-resolution/gpu-check.log),
[completion gate](benchmarks/s1/high-resolution/verify.log), and
[game checks](benchmarks/s1/high-resolution/game-check.log) retain the automated outcomes.

**S1.1c — Faster 1080p image delivery (October 28).** The normal GPU game path now
applies exposure, Reinhard tone mapping and the existing sRGB lookup on the GPU. It reads
back packed display pixels: four bytes per pixel plus a four-byte fog counter, versus the
previous sixteen bytes per pixel. The existing Swing window receives the same top-down
RGB bytes. This is a usable intermediate delivery, not completion of S1.2: packed images
still cross to the CPU, and the 16 ms Swing repaint timer remains.

Three bounded publication slots retain raw RGB snapshots on the device until requested
by a raw-pixel reader or needed to recolor an already completed image after an exposure edit.
Reads run on the GPU owner, never the input thread. A held image remains stable across newer
frames and resolution changes; normal session shutdown preserves raw data for outstanding
leases before retiring the device. Exposure edits retain accumulation. F8 snapshots and F9
history exports still save the displayed image and replay information through the existing
capture system. CPU launches retain their prior path. The renderer's diagnostics identify
`display GPU packed`; the JVM property `-Dgame.referenceDisplay=true` selects the prior
parallel CPU conversion for comparisons.

User test: launch `./game.ps1 -Backend gpu -Scene world -Width 1920 -Height 1080` from
the repository root, move and turn, then use Escape to change exposure and select 720p and
1080p. Stop to refine, save F8 and F9 captures, switch focus away/back, and close. Colors,
orientation, camera framing, captures and controls must remain intact. The faster path is
automatic for GPU launches; no settings switch is needed. Human smoothness acceptance,
combat/fog performance and the final 60/120 FPS targets remain open.

The [paired 1080p route](benchmarks/s1/compact-display/route.csv) uses the same landscape
camera path as S1.1a: depth 2, one sample per batch, two seconds of warmup and three seconds
of measured movement per renderer, with three alternating pairs. The previous GPU owner
retires before the next renderer starts. GPU-packed/CPU-parallel rates were 128.3/41.3,
132.3/36.7 and 136.7/40.7 completed CPU images per second. Median image-copy cost fell from
6.03–7.69 ms to 1.35–1.53 ms. The new path's p95 intervals were 17.79–18.27 ms, versus
36.72–41.60 ms before; 10.2–14.4% of its intervals still exceeded 16.67 ms. These short,
headless measurements show a repeatable delivery improvement, not 120 displayed FPS or a
60 FPS floor. They exclude Swing painting, physical input delivery and monitor scanout.
[Settings and source hashes](benchmarks/s1/compact-display/metadata.json) bind the evidence
to the measured implementation.

Automated checks compare exact displayed bytes, including random float bit patterns,
NaN/infinities, subnormals and six exposures; check fog reuse counts, held raw and packed
images, resize, three-slot backpressure and reading retained images after normal close;
and save/reload both GPU capture kinds. Explicit double arithmetic in the display shader
preserves CPU rounding and subnormal behavior; transport and raw accumulation are unchanged.
The [encoding check](benchmarks/s1/compact-display/encoding-check.log),
[1080p lifecycle/capture check](benchmarks/s1/compact-display/compact-check.log),
[full GPU gate](benchmarks/s1/compact-display/gpu-check.log),
[game gate](benchmarks/s1/compact-display/game-check.log), and
[completion gate](benchmarks/s1/compact-display/verify.log) passed without expanding the
readability baseline. Native-window behavior remains a human check.

Reproduce after `./gpu-build.ps1` using Java 23 with `--enable-preview`,
`-Djava.awt.headless=true`, and classpath `out/gpu/classes;out/game/classes;out/gpu/lib/*`.
Compile `tools/gpu/CompactDisplayBenchmark.java` into `out/gpu/classes` with that classpath,
then run `game.CompactDisplayBenchmark`; it alternates both presentation paths itself.
`./gpu-check.ps1` builds and runs the encoding and lifecycle checks automatically.

**S1.1d — Paint fresh images immediately (October 28).** The game now requests a
Swing repaint whenever the renderer publishes a new display image, outside the controls
monitor. Swing coalesces dirty regions when its event thread is busy, so slow painting does
not create an image queue. The existing 16 ms timer remains for controls, overlays and idle
updates; it no longer limits how often fresh images can request painting. Closing the panel
or entering its failure state detaches the wakeup. A callback already in flight may finish.

The footer reports `fresh paints/s (2s)`: distinct display images painted during the previous
two seconds, divided by two. Repainting a cached image does not count. The first two seconds
include startup, and the rate falls toward zero when refinement finishes or rendering stops.
This measures game-image painting, not monitor refresh, scanout or physical input latency.
It is separate from renderer completion throughput. This increment retains compact CPU
readback and Swing painting; S1.2's device-resident window presentation remains unfinished.

User test: launch `./game.ps1 -Backend gpu -Width 1920 -Height 1080`, move and turn, and
watch the new footer rate. Stop with refinement disabled: cached redraws must not keep the
rate high. Change resolution and exposure, open settings, save a capture, switch focus away
and back, then close. Controls, image content and capture behavior must be preserved.
Compare smoothness with atmosphere off and on; no atmosphere quality setting changes in
this delivery. Native-window acceptance and the 60/120 FPS targets remain open.

The [paired paint benchmark](benchmarks/s1/presentation-pacing/presentation.csv) paints the
real game panel and HUD on the Swing event thread into a 3840x2160 offscreen image, with
1920x1080 internal rendering and atmosphere off. Three alternating pairs use two seconds
of warmup and three seconds of the same moving landscape route, draining each GPU owner
before the next run. Immediate/timer-only fresh-paint rates were 62.7/33.7, 78.3/33.3 and
81.7/33.0 per second. Immediate-paint p95 intervals were 20.96–27.77 ms versus
34.73–36.67 ms with the timer. Copy-to-paint p95 latency results were mixed, so these runs
support a throughput improvement, not a uniform latency improvement or a 60 FPS floor.
They exercise real Swing painting but exclude native window submission and monitor scanout.
The JVM property `-Dgame.timerPresentation=true` restores timer-only repaint requests for
comparison; it does not change tracing quality. The benchmark selects both modes itself.

**Atmosphere observation and follow-up.** The player reported a noticeable slowdown with
atmosphere enabled. The [paired atmosphere measurement](benchmarks/s1/presentation-pacing/atmosphere.csv)
keeps the compact GPU path fixed and toggles the clear-air atmosphere, with fog off, on the
same headless 1080p route. It measured 87.3–99.0 completed images/s with atmosphere and
134.7–144.7 without. Median GPU-job host wall time rose from 4.68–5.23 ms to 7.12–7.59 ms;
image-copy medians stayed around 1.4–1.6 ms. Job wall time includes GPU preparation,
submission, waiting and compact readback, not just shader execution. The shader performs
32-step atmospheric scattering integration along traced segments, which is a concrete
candidate for investigation. These results isolate atmosphere's cost on the current path;
they are not a historical before/after regression test. Cache or integration changes need
their own cost/quality comparison before adoption. No effect is disabled or approximated
by this delivery. Track this separately from direct presentation under S1.4.

[Measurement settings](benchmarks/s1/presentation-pacing/metadata.json) record both methods.
To reproduce, run `./gpu-build.ps1`, compile `tools/gpu/PresentationBenchmark.java` and
`tools/gpu/AtmosphereRouteBenchmark.java` into `out/gpu/classes`, then run
`game.PresentationBenchmark` and `game.AtmosphereRouteBenchmark`. Use Java 23,
`--enable-preview`, `-Djava.awt.headless=true`, and classpath
`out/gpu/classes;out/game/classes;out/gpu/lib/*` for both. Run each benchmark separately.
The [game gate](benchmarks/s1/presentation-pacing/game-check.log),
[GPU UI check](benchmarks/s1/presentation-pacing/gpu-ui.log),
[completion gate](benchmarks/s1/presentation-pacing/verify.log), and
[focused tests](benchmarks/s1/presentation-pacing/focused-test.log) passed. Tests cover fresh
repaint requests from the renderer thread, detachment while rendering continues, cached-frame
exclusion, idle expiry and bounded rate history. Native presentation remains a human check.

**S1.2 acceptance extension.** Preserve the user test above and all existing settings,
input and F8/F9 capture entry points while keeping normal display images on the GPU.
Bound in-flight images and never overwrite an image still being presented. Exposure changes
must not reset raw accumulation. Resize, minimize/restore, focus loss, initialization failure
and close must remain responsive. Preserve explicit CPU launch and report GPU failures;
never silently substitute CPU rendering. Presentation pacing must support a 120+ Hz display
without a fixed 16 ms repaint ceiling. Report GPU execution, transfers, CPU work, fresh window
submissions and image age separately. Native-window acceptance requires a human playthrough.

**S1.1b — Standard render resolutions.** The player uses a 4K monitor and accepts lower
internal rendering resolutions. In Escape → Render, a Render resolution dropdown offers
360p (640x360), 480p (854x480), 540p (960x540), 720p HD (1280x720), 900p (1600x900),
1080p (1920x1080), and Custom. Selecting a preset changes both dimensions together. Custom retains the current
dimensions and allows editing the existing width/height controls. Merely opening or refreshing
settings must not change resolution. Existing non-preset dimensions display Custom.
Preset selection retains camera framing, optics, transport and existing adaptive-mode settings;
CPU adaptive mode may sample below the selected requested size as before. The image is scaled
to the game window; these options do not change the monitor mode or claim native 4K tracing.
The UI states the shared 1920-pixel per-axis render limit. The former 1600 cap was inherited
from the CPU viewport's software validation, not a hardware limit of the RTX 3080. Its exact
original numeric rationale is not documented. The 1080p extension raises the engine, game
navigation/capture, custom controls, console and launch-command bounds consistently while
retaining existing default resolutions. Requests above 1920 per axis remain rejected before
changing live state. Both CPU and GPU accept 1920x1080. 1440p and native 4K rendering still
need a separately verified limit increase; automatic monitor-native sizing is not added.

User test: launch `./game.ps1 -Backend gpu -Scene world`, press Escape, open Render, choose
720p and then 1080p, and resume movement. Inspect the rendered size and scene detail; camera
framing and controls must remain intact. Choose Custom, edit width/height, reopen settings,
then choose 540p. Custom dimensions must persist until explicitly replaced, and the selected
preset must agree with the width/height fields. The same control is available for CPU.
Automated settings tests cover all presets, initial Custom state, refresh without mutation,
atomic paired dimensions and retained camera state; game and completion gates cover regressions.
`./game.ps1 -Backend gpu -Width 1920 -Height 1080` also selects Full HD at launch.
Verification includes real CPU/GPU 1080p publication, display conversion, held-image stability,
restart and close, navigation capture round trips, and rejection of invalid dimensions.
This increases available image detail; it does not claim 60 FPS at 1080p. At equal samples and
depth, 1080p has 44% more pixels than 900p, so it normally requires more rendering work.

**S1.3 user test and acceptance.** Select a 60 or 120 FPS budget in game settings, move
through ordinary and expensive views, then stop. Show output size and actual internal
render size separately; restore configured stationary quality without stale history.
The full-quality fixed-resolution mode must remain available. Reject history after cuts,
scene/optics changes and newly revealed surfaces. Compare moving images against reference
captures for ghosting, disappearing detail and fog artifacts. This milestone's quality
tradeoff is accepted in principle for the player's 4K display, with visual quality still
subject to hands-on evaluation.

**S1.4a - Optional faster atmosphere in the ruins (October 28).** The player reports
low-to-mid 30s FPS in the ruins at 1080p with atmosphere enabled, and near-target performance
without it. This observation is not a controlled benchmark. The next increment makes the
existing GPU primary-fog reconstruction available for atmosphere without local fog. It can
ship before S1.2's direct GPU presentation; the broader S1.4 workload remains unfinished.

In Escape -> Lighting, below Atmosphere enabled, the Atmosphere / fog resolution selector
offers Full, Half and Quarter. The Fog page exposes the same shared setting. Full remains
the default and restores the original integration. Half and Quarter reduce each dimension
of the primary air grid, not the geometry grid: 1080p geometry remains 1920x1080. Surface
shading and bounced-ray air integration retain full quality. Interpolation rejects incompatible
surface identity, depth, normals, sharp lighting changes and solar-disc pixels; rejected pixels
use direct integration. Approximate air can soften fine gradients or change during movement,
so these modes remain opt-in pending visual evaluation. Existing saved Half/Quarter fog
settings now also apply when atmosphere is enabled without fog. The stored setting retains
its existing `fogDownsample` name for capture compatibility.

CPU rendering, finite-aperture cameras and ineligible fog retain Full integration. Fog's
existing eligibility checks remain in place even when atmosphere is enabled. With neither
atmosphere nor fog enabled, there is no reconstruction work. Changing quality restarts
accumulation; switching to Full releases the extra reconstruction buffers. At 1080p the
extra guide/coarse buffers require about 87 MiB for Half and 69 MiB for Quarter. This uses
current-frame spatial reconstruction only; it adds no temporal history or delayed lighting.

User test: launch `./game.ps1 -Backend gpu -Scene world -Width 1920 -Height 1080`, fly to
the ruins, enable Atmosphere in Lighting, and compare Full, Half and Quarter along the same
route. Start with Half. Inspect pillar edges, the horizon, sunlight and haze while moving;
compare the two-second fresh-paint counter after each mode settles. Switch back to Full to
recover exact integration. Change the shared selector on the Fog page and confirm Lighting
shows the same choice. Opening settings alone must not change quality. Human acceptance
requires both a useful native-window improvement and acceptable moving-image quality.

Automated verification compares Full/Half/Quarter radiance at odd dimensions under clear
and hazy air at sunrise, morning, noon and sunset, plus clear-air 1080p at depths 0 and 8 in the ruins. It
checks finite nonnegative radiance, root-mean-square error <=0.004, and every channel's
absolute error <=0.015 + 5% of its reference. Full restoration is exact; lens/no-air and
unsupported-fog fallback, resize, accumulation reset and held-image stability are covered.
The 1080p Quarter comparison reused 1,806,029 of 2,073,600 pixels, with linear-radiance
root-mean-square error 0.00000263 and maximum channel error 0.000152. These are numerical
checks of specific views, not proof of perceptual equivalence for every atmosphere setting.

Matched headless runs use AdventureWorld's ruins, clear air at 09:00, no local fog, 1080p,
depth 2, one sample per batch and a moving camera. Each mode warms for two seconds, runs
for three seconds, and repeats three times in alternating order. Completed-image rates
were 116-126/s Full, 139-142/s Half and 142-147/s Quarter: approximately 13% and 18%
improvement respectively using the median rate of each mode. Median GPU-job host time
fell from 7.47-7.67 ms to 6.68-6.81 ms (Half) and 6.43-6.83 ms (Quarter). This metric
includes preparation, GPU work, waiting and compact readback. It excludes game image copying.
The rates exclude native-window painting and do not reproduce the player's reported FPS.
The playable world additionally enables combat and raises depth to 8. A second matched
route with `enableShooter()` includes animated scene updates, but no firing, and freezes
the clock at 09:00 for comparison. Completed-image rates were 40.7-42.7/s Full,
47.0-51.3/s Half and 48.7-51.7/s Quarter. Full p95 intervals were 29.9-31.9 ms;
Half and Quarter reduced them to 27.1-28.2 ms and 27.2-27.6 ms respectively.
Nevertheless, 54-74% of measured intervals exceeded 16.67 ms, with worst intervals
29-52 ms across the modes. This does not meet a 60 FPS minimum.

Real GamePanel painting into a reusable 4K offscreen image reduced that benefit:
35.7-39.3 fresh paints/s Full, 37.3-41.3 Half and 36.0-41.0 Quarter. Median rates across
three runs improved only about 3% for either reduced mode. Paint p95 intervals remained
34-42 ms, with 80-88% over 16.67 ms and worst intervals 41-54 ms. Native window
submission and scanout remain excluded. The atmospheric transport saving is real, but
presentation and animated-scene costs still need work; this slice is not a claim that the
reported slowdown is solved. Half is the first hands-on comparison because Quarter gives
little additional end-to-end speed in this workload. S1.2 and remaining S1.4 profiling stay open.

Raw measurements, exact fixture source, quality results and verification logs are retained in
[ruins atmosphere evidence](benchmarks/s1/ruins-atmosphere/metadata.json).

**S1.4 user test and acceptance.** Repeat the exploration route with fog, a moving sun,
and active combat. Confirm moving enemies, weapon animation, shadows and lighting remain
current. Measure hierarchy updates, fog and frame-time tails before choosing optimizations;
reuse unchanged geometry and avoid per-frame allocation where evidence supports it.
Keep a change only when repeated matched runs improve its targeted workload without a
material regression in the ordinary route. If a hypothesis fails, record the result rather
than declaring a delivery complete.

**Measurement protocol.** Record source revision/fingerprint, device/driver, grid/output
dimensions, depth, batch samples, optics, fog modes, seed and pacing. Use identical settings
and camera routes for paired baseline/candidate runs, warm up before timing, and alternate
order across at least three measured runs. Include exploration, the saved ruins-fog view
(or a recorded replacement when the private capture is absent), and moving combat.
Record median/p95/p99 fresh-image intervals, missed frame deadlines, capture-to-submission
age and stationary quality. Headless tests must be labeled as such. Do not combine
overlapping pipeline stage timings as though they were sequential, or multiply predicted
optimization gains. A 60 FPS average is insufficient evidence of a minimum: report deadline
misses and worst intervals as well. Final acceptance workload and allowed miss rate remain
open; no 60/120 guarantee is claimed until those conditions and measurements exist.

**Open decisions and assumptions.** The player confirmed a 4K monitor and accepts lower
internal resolutions, including the proposed optional performance mode. Existing 1600-wide
grids remain the first comparison workloads. Probe the actual monitor mode and refresh during
S1.2 integration rather than assuming a 120 Hz display. Final workload, deadline-miss allowance
and reconstruction quality still need evaluation before declaring the FPS goal met; they do
not block implementation. Quality-preserving improvements remain enabled; new approximate
rendering modes remain opt-in pending evaluation.

**Verification and handoff.** Run `./verify.ps1`, `./game-check.ps1`, GPU device checks and
focused image/lease tests for affected behavior. Keep automated runs window-free and
console-free. Store raw measurements under `benchmarks/s1/` with their settings and link
them from this specification. Distinguish implementation, automated verification and human
acceptance in the status rows. Notify the player with the exact launch and things to inspect
when each usable increment is ready; a notification does not mark human acceptance complete.

### S2 Native CPU rendering backend

Evaluate a representative native intersection or render batch, potentially using Embree, only when it answers a measured bottleneck or supplies a needed capability. Compare against the improved Java implementation on identical scenes and quality settings, including native-call/data conversion overhead. Avoid crossing the language boundary for individual rays when batching is possible.

Acceptance: quantify end-to-end benefit, packaging burden, memory ownership, diagnostics, and feature parity. Keep the editor and orchestration in Java unless there is independent evidence for moving them. An FPS number alone is not a language-migration criterion. A scalar rewrite with unchanged algorithms is not the intended deliverable.

## How we choose the next phase

After each phase, record the achieved effect and remaining bottleneck. Use the following decisions rather than treating every phase as mandatory:

- If tracing dominates and workers scale well, finish P1/P2 and then target measured intersection or sampling costs.
- If display work dominates at low resolution, prioritize P3 before additional transport work.
- If raw throughput is acceptable but motion is poor, investigate P2/P4 and then reconstruction.
- Following hands-on P5.2 evaluation, defer further temporal performance work for the current one-spp motion workload. Profile with temporal off and prioritize measured raw tracing costs in P7 through P10, P15 or P13. Cleaner one-spp output alone does not justify a performance claim.
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
