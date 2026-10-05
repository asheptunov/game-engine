# Renderer performance specification

Status: P0 and P1 implemented. P2 onward remain proposed. Measurements and commands are in [P0/P1 results](benchmarks/p0-p1/README.md).

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

Code inspection found:

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
| P5 | Temporal sample reuse | Potentially high quality per second during motion; low confidence for glass | P2 and history validation data |
| P6 | Spatial denoising | Potentially high perceived quality at low spp; medium confidence | Auxiliary surface data and raw-image reference |
| P7 | Top-level object BVH | Medium for current rooms, high as object count grows; medium confidence | P0 |
| P8 | Near-first BVH traversal | Medium in intersection-heavy scenes; medium confidence | Existing BVH; integrate P7 if present |
| P9 | Better BVH construction | Medium for larger meshes, low for simple rooms; medium confidence | P0 |
| P10 | Compact geometry and BVH storage | Medium if cache stalls dominate; low confidence until profiled | P0; preferably settle P7 to P9 first |
| P11 | Adaptive sampling | Medium quality per second for stationary views; medium confidence | Per-pixel sample accounting |
| P12 | Improved sample sequences | Medium quality per second; medium confidence | P0 quality benchmark |
| P13 | Russian roulette | Low to medium for current presets, higher for long lossy paths; medium confidence | P0 quality benchmark |
| P14 | Shared mesh acceleration | Low for current simple rooms, high memory/build benefit with many mesh copies | P0 |
| P15 | Analytic solid intersections | Low to medium for scenes with boxes; medium confidence | Preserve boundary and identity rules |
| P16 | Explicit SIMD kernels | Uncertain medium throughput benefit; low confidence before layout work | P10 and kernel profiling |
| S1 | GPU backend | Highest potential throughput ceiling; feasibility and achieved gain unverified | P0, hardware inventory, backend contract |
| S2 | Native CPU backend | Uncertain gain; evaluate only against optimized Java | P0 and representative Java baseline |

Start with P0 through P3. Review that result before committing to the later phases. P4 is an optional interactive mode. P5 and P6 are substantial reconstruction work and may be deferred in favor of P7 if preserving raw sample output is the immediate priority.

## Shared completion requirements

Each phase includes controls, documentation, relevant tests, and a before/after result in `tracker.md`. P0 and P1 were delivered together at the user's request; later phases are pending. An experiment that shows no useful improvement may be marked evaluated and deferred, with evidence; do not retain complexity solely because it was planned.

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
from the local scaling measurements. P2 still owns responsive input/cancellation.

Divide the sensor into tiles and dynamically assign them to a persistent, bounded platform-thread pool. Begin by benchmarking 16 by 16 and 32 by 32 tiles and worker counts such as 1, 2, 4, 6, 10, 14, and 20 where available. Tile size and worker count are experiments, not fixed promises. Avoid one task per pixel and per-ray atomics.

Workers share an immutable render snapshot and prepared geometry. Each owns reusable hit records, sampler, material samples, medium stacks, visibility scratch, and counters. A pixel has one writer for a sample pass. Preserve the existing seed/pixel/sample mapping and per-pixel accumulation order. Reduce statistics at a synchronization boundary. Shut down workers cleanly and avoid duplicating pools on scene switches.

Initially publish complete sample passes so existing uniform spp semantics remain valid. Any partial-work discard must leave committed accumulation coherent; use staging or equivalent ownership. P2 adds cancellation. A minimal snapshot is required here even if the coordinator temporarily retains existing input locking.

Acceptance: one-worker output agrees with the reference; several worker counts and tile sizes produce the same fixed-spp output; pause/restart/resolution/scene changes are safe. Show scaling curves, CPU usage, total allocations, and p95 frame time. Select a default from measured throughput and interaction headroom rather than logical processor count alone.

## P2 Responsive rendering and cancellation

Separate input/state editing, tracing, and presentation. Acquire the state monitor only to apply edits or capture/publish coherent state; perform expensive tracing and image conversion outside it. Maintain an immutable snapshot and monotonically increasing render generation. Camera or transport changes invalidate old work. Exposure and overlays retain radiance samples.

Check cancellation and time budgets between tiles. Stop scheduling obsolete tiles promptly; in-flight tiles may finish but must not publish into a newer generation. Publish through explicitly owned buffers so presentation never reads storage being modified. Discard incomplete passes initially; showing partial passes later requires valid per-pixel counts and clear stale-pixel handling.

Keep input responsive while a pass is unfinished. A 16.67 ms display budget does not imply a complete fresh sensor sample fits inside it. Report image age and completed work, not just presentation FPS. Bound queued generations and memory; repeated mouse motion must not accumulate a backlog.

Acceptance: rapid camera movement, resolution changes, preset switches, pause/resume, console interaction, and window focus loss cannot mix generations, race buffers, or deadlock. Measure input latency and wasted obsolete work. Aim for p95 input handling within one 60 Hz interval on the baseline host; separately report time to a visible camera update. Long individual tiles must be visible in diagnostics and motivate smaller tiles.

## P3 Reuse and fuse display buffers

Reuse display storage across unchanged dimensions. Fuse RGB resampling, exposure/tone mapping, encoding, and output where practical. Cache resampling indices and weights when dimensions change. Bypass resampling for equal grids; for integer enlargement, encode one source pixel and replicate the encoded value. For averaging, filter linear radiance before nonlinear display mapping.

Avoid clears and checkerboard draws only where full opaque viewport coverage makes them redundant. Reduce channel packing/copying into the reusable AWT image where compatible with editor and overlay composition. Redisplay cached output when neither radiance nor presentation settings changed. Keep publication ownership consistent with P2.

Acceptance: compare equal-size, integer enlargement, integer reduction, and fractional resampling against reference images, including orientation, exposure, clipping, console and overlays. Show lower whole-frame allocation and display stage cost. No display-sized allocation should recur in steady state; dimension changes may allocate. Do not compromise the texture editor's alpha behavior.

## P4 Dynamic resolution while moving

Add an explicitly enabled interactive resolution mode with a target update time, minimum and maximum sensor dimensions, and displayed actual resolution. Reduce sensor work during sustained movement; restore requested quality after movement settles. Use hysteresis so dimensions do not oscillate. Preserve aspect ratio and field of view. Existing fixed-resolution commands remain authoritative when automatic mode is off.

Resolution changes start coherent accumulation; old grids cannot be silently treated as samples of the new grid. Do not simultaneously reduce depth unless a separate explicit quality option is introduced. This phase intentionally trades spatial detail for interaction speed.

Acceptance: a repeatable camera path shows reduced time to visible updates, bounded resolution changes, and stable return to requested resolution. Report actual sampled dimensions alongside FPS. The target is best effort if even the minimum grid exceeds budget.

## P5 Temporal sample reuse

Reproject prior image information into the new camera view instead of always discarding all useful history. Add the auxiliary data needed to validate correspondence, such as first-hit depth, normal, and stable surface identity. Reject newly revealed surfaces, mismatched depth/normals/identity, incompatible scene edits, and unsuitable history.

Begin with opaque diffuse surfaces and keep a raw reference mode. Reflected and refracted views are not reliably validated by the first visible surface alone; initially reject or conservatively limit their history. Volumes and mixed subpixel surfaces require similarly explicit fallback behavior. Keep reconstruction history separate from unbiased raw accumulation and report history confidence separately from raw spp.

Acceptance: motion sequences include disocclusion, camera cuts, changing lights/materials, thin geometry, glass, mirrors, and volumes. Measure ghosting/trailing as well as error at equal frame time. History memory is bounded; disabling the feature restores raw output. Expansion beyond diffuse surfaces requires new evidence rather than applying the same rule everywhere.

## P6 Spatial denoising

Add an optional spatial filter for noisy radiance, guided where useful by depth, normal, and albedo. Start with a bounded-cost edge-aware filter and benchmark its own cost before adopting a heavier implementation. Operate on a separate presentation result, retaining raw samples and a raw-image toggle. This phase can work without temporal reuse and may precede P5 if simpler.

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
