# Camera models and controls specification

Status: C1–C5 implemented and automatically verified on 2026-10-06. The user reported that the viewport looked good after both deliveries and approved committing and pushing the work. Implementation evidence, query measurements, previews and manual test commands are in [benchmarks/camera/README.md](benchmarks/camera/README.md).

This specification captures the agreed camera refactor and user-facing behavior. An implementer should deliver it in the stages below, preserving the existing renderer throughout. Follow [AGENTS.md](AGENTS.md) for build, testing, concurrency, and documentation requirements. Existing transport and performance specifications remain applicable: [RenderingRequirements.md](RenderingRequirements.md) and [PerformanceRequirements.md](PerformanceRequirements.md).

## Outcome and scope

Make camera ray generation an explicit abstraction supporting the existing ideal pinhole perspective camera, an orthographic camera, and an ideal thin-lens perspective camera with depth of field. Preserve navigation, scene transport, progressive refinement, asynchronous previews, deterministic sampling, and resolution-independent framing.

The user-facing mode names are `perspective`, `orthographic`, and `lens`. Internally, perspective is a pinhole model. Both perspective and orthographic modes have unlimited geometric depth of field. Lens mode models a circular aperture and a perpendicular focus plane, without diffraction or detailed optical aberrations.

The original C1–C3 delivery is a camera refactor, not a transport rewrite. Real lens assemblies, physical sensor sizes, photographic focal-length/f-number controls, lens distortion, diffraction, chromatic aberration, click-to-focus, bookmarks, and front/side/top shortcuts remain deferred. Continuous autofocus with parameterized screen selection is now specified in C5; named-subject/image-feature tracking remains deferred. No new persistence format is required. C4–C5 explicitly supersede the original mode/aperture restrictions and manual-only focus behavior where stated; all other compatibility requirements remain in force.

## User stories and controls

| Story | Proposed console control | Required result |
| --- | --- | --- |
| Keep my existing view working | `view camera mode perspective` | Default mode; preserve current preset images, perspective, and unlimited depth of field. |
| Change how much of the scene I see without moving | `view camera fov <degrees>` | Change vertical field of view in perspective/lens modes; horizontal coverage follows the established viewport aspect policy. |
| Compare sizes without perspective distortion | `view camera mode orthographic` | Parallel rays; equal-size objects at different depths retain equal projected sizes. Lighting and occlusion remain active. |
| Zoom an orthographic view | `view camera height <scene-units>` | Set the visible vertical span; derive horizontal span from aspect. |
| Focus on a subject | `view camera mode lens`, `view camera focus distance <scene-units>` | Set focus-plane distance measured along camera forward from the camera position. |
| Control foreground/background blur | `view camera aperture <radius>` | Set circular aperture radius in scene units; larger radii increase defocus; zero uses the exact pinhole path. |
| Focus without estimating distance | `view camera focus center` | Use the first surface hit by the central reference ray to set focus-plane distance; a miss leaves focus unchanged and reports why. |
| Understand my current view | `view camera status` | Report mode, pose, relevant projection settings, focus/aperture, and effective temporal availability with any fallback reason. |
| Recover the preset camera | `view camera reset` | Restore the active preset's default camera and clear transient navigation input, without resetting edited scene objects/materials or tracing resolution. |

Successful edits print concise confirmations with units. `view status` includes a concise camera summary. Add camera group and leaf help using the existing equivalent help routes (`help view camera`, `view camera help`, and leaf equivalents). Do not retain the current hard-coded "Reset camera" response for all camera operations.

Validation decisions for this delivery:

- Vertical FOV must be finite and between 1 and 179 degrees inclusive.
- Orthographic height and focus distance must be finite and strictly positive; aperture radius must be finite and nonnegative. Reject values whose derived camera geometry is nonfinite, degenerate, or numerically unrepresentable. Errors leave all state unchanged.
- FOV applies in perspective/lens modes; height applies in orthographic mode; aperture applies in lens mode. Reject wrong-mode edits with guidance rather than silently changing an inactive setting.
- Focus controls are available in every mode because focus distance also supplies the reference distance for initial projection conversion. Explain that they do not introduce blur outside lens mode.
- A syntactically valid no-op must not restart accumulation. Changes to inactive optical settings, such as focus distance in perspective mode, do not change current image identity.

## Navigation, switching, and defaults

Preserve WASD, Space/Ctrl, mouse look without clicking, normalized 3-unit/second movement, pitch limits, held-key handling, and the elapsed-time cap. Preserve clearing transient input on console opening, camera reset, scene switching, and window focus loss. Movement changes pose, not FOV, orthographic height, aperture, or focus distance. The focus plane follows the camera at the selected distance; it does not track a world-space subject.

Forward movement in orthographic mode changes viewpoint position and which surfaces lie ahead, but does not magnify objects. Zooming changes FOV or orthographic height without translating the camera. Resolution and sample-count edits never change framing or optics.

Mode changes preserve pose. Perspective and lens modes share perspective framing/FOV; lens mode additionally remembers its aperture. Orthographic mode remembers its own height. Remember settings for the lifetime of the viewport, until camera reset or preset loading resets them. Returning to an initialized mode restores its saved settings; do not continually recompute and overwrite them.

On first entry into orthographic mode, match perspective scale at the reference distance `d` using `height = 2 * d * tan(verticalFov / 2)` for centered standard cameras. The reference distance is the current focus distance and is reported in the switch confirmation. Exact composition across all depths is impossible; help should say that matching applies at this distance only. A generalized legacy off-center/skewed plane must retain its geometry in the camera representation; do not silently normalize it during extraction. If conversion of such a camera cannot preserve its framing, reject that conversion with an explicit explanation in this first delivery.

Existing presets start in perspective mode with exactly their existing camera geometry. Proposed new defaults: focus distance 5 scene units and aperture radius 0 (so entering lens mode alone does not unexpectedly blur the view). Orthographic height starts uninitialized and is derived on first entry. Reset restores these settings, the preset pose/framing, and uninitialized orthographic height. Preserve special preset viewpoints, including `glass-inside`; audit the existing reset implementation rather than assuming it already handles every preset correctly.

Preset loading and full `view reset` retain their broader existing scene-reset semantics while initializing camera defaults. Camera-only reset must not gain those broader effects.

## Camera architecture

Separate immutable camera description/pose from image resolution and sampling. Compile that description into immutable per-job camera data outside the input/state monitor. The tracer must obtain ray origin and direction from the camera instead of constructing all rays from a shared eye.

A conceptual API is `sampleRay(imageU, imageV, apertureU, apertureV, result)`. Exact Java types/names are implementation choices. Requirements:

- Image coordinates identify continuous samples over the image rectangle. The camera does not own pixel counts or accumulation.
- The result contains origin, normalized direction, and a contribution weight if required by the model. Use worker-local reusable storage; no per-ray objects or shared mutable camera scratch.
- Pinhole/orthographic models ignore aperture samples. The initial three models can use unit contribution weight under the exposure convention below. Keep the boundary extensible without implementing hypothetical lens transport now.
- Preserve the full current eye/rectangle parameterization, including off-center and nonorthogonal sensor edges, in the pinhole adapter. Extract existing arithmetic without algebraic reordering that changes float results.
- Keep a deterministic reference ray operation for guide geometry, picking, and center focus. Do not make stochastic aperture sampling an implicit part of picking.
- Share camera operations between blocking and asynchronous tracing. Existing numeric ray-level adapters retain their behavior.

### Ray models

For the existing plane `P(u,v) = origin + u * edge1 + v * edge2` and eye `E`, perspective emits `(E, normalize(P-E))`, exactly as today. Preserve the current primary pixel-center diagnostic at path depth zero and subpixel jitter at positive depth.

For standard orthographic cameras, place the image rectangle in the plane through the camera position, oriented by camera right/up. Emit rays from positions across that rectangle in the common forward direction. The rectangle's location defines the starting boundary: geometry behind ray origins is not visible. Do not introduce an arbitrary distant eye or inherit the old sensor-plane offset as a hidden near clip.

For lens cameras, intersect the reference perspective ray with the plane at forward distance `focusDistance` to obtain focus point `F`. Uniformly sample a disk of `apertureRadius` in the right/up plane through the camera position to obtain origin `L`. Emit `(L, normalize(F-L))`. Focus distance is axial distance, not the slanted distance along each ray. Aperture zero bypasses this calculation and takes the exact pinhole path.

The initial lens estimator averages radiance over the aperture with normalized sampling: aperture changes blur without automatically changing exposure. Focus changes sharpness without focus breathing. This intentionally omits physical exposure/vignetting changes. Document this convention rather than claiming a full photographic camera simulation.

At path depth zero, retain image-plane pixel centers but still sample a finite aperture across successive samples; depth zero must not silently disable lens blur. Existing area-light sampling remains independent.

### Center focus

Use a center reference ray through the optical axis, or the image center for a legacy off-center camera. In lens mode, use the aperture-center reference ray. Intersect existing prepared scene geometry; choose the first surface, including glass or emitters, without following refraction or selecting a random volume-scattering event. Convert the hit to axial distance with a dot product against camera forward. Reject nonpositive/invalid distances. Report the resulting distance, or explain a miss without changing state.

Perform potentially expensive intersection work outside the state monitor. Capture the camera and scene revision, then publish the focus edit only if they still match; otherwise report that the view changed and ask the user to retry. Count focus queries separately from transport diagnostics or exclude them from those diagnostics.

## Sampling and render lifecycle

Preserve exact existing pinhole RGB samples and camera seed hashing in the compatibility path. Merely extracting the abstraction or selecting a zero-aperture lens must not perturb subpixel, BSDF, or light sample streams. Use a separate deterministic aperture sampling domain keyed by seed, captured camera, pixel, and sample; do not consume extra values from existing transport streams.

New camera models must reproduce results across worker counts, tile sizes, batches, cancellation/resume, and visits to identical captured settings. Include all active image-affecting camera parameters in estimator identity and camera sampling identity. Separate remembered inactive UI settings from that identity. Perspective and zero-aperture lens must produce the same raw samples for equivalent framing.

Camera changes restart accumulation. Preserve the existing asynchronous rule: an active camera-only pass may finish as a coherent preview of its captured camera, but never contributes to a different camera's accumulation. No camera-generation backlog; no cancellation starvation during sustained movement. Lens/projection edits must never mix estimates from different cameras. Retain publication leases, bounded buffers, pause/target behavior, scene suspension, and close lifecycle.

P4 resolution adaptation remains available in all modes. Pixel dimensions, requested/actual grids, and batch maxima stay independent of the camera model. FOV, aperture, and focus stay fixed when the grid adapts.

### Starting media

Before C1–C3, the worker computed initial medium membership once at the eye. The delivered implementation now checks membership per varying ray origin. Reusing the eye's membership is only valid when all ray origins share it. Orthographic and finite-aperture cameras can place origins in different glass/volume interiors.

Determine starting media for each generated origin unless a sound optimization establishes common membership. Never silently reuse the center-eye medium for all origins. Preserve nested-medium ordering, absorption/scattering semantics, and current restrictions on intersecting/touching boundaries. Use worker-local reusable medium scratch; do not add per-ray allocation. Cover rays whose origins are outside, inside, and distributed across supported boundaries.

## Projection and temporal reconstruction

Ray generation is mandatory; temporal projection is a separate optional camera capability. It needs world-to-image projection, reconstruction from guide depth, pixel footprint at depth, and camera-cut compatibility. Define guide depth consistently as distance along a normalized reference ray from that ray's origin. Orthographic origins differ per pixel, so eye-relative reconstruction is invalid.

Move pinhole-specific guide rays and the private pinhole projection logic out of their current assumptions. Preserve existing pinhole temporal results. Add correct orthographic projection, reconstruction, footprint, camera-cut checks, center/corner guides, and visibility validation. Orthographic footprint is independent of depth; existing perspective formulas cannot simply be reused.

Finite-aperture lens mode falls back to raw rendering in this delivery. A surface point has an aperture-dependent image footprint, which existing single-surface guides do not model. Keep the user's requested temporal setting, but show effective availability and the reason in status/F3. Zero-aperture lens mode may use the pinhole capability. Clear history on incompatible camera/optics changes; toggling aperture or mode must not resurrect stale publications.

Disable P5.2 temporal-dependent motion budgets whenever temporal reconstruction is effectively unavailable, restoring requested quality under the existing transition rules. Independent P4 behavior remains available. Existing scattering and unsupported-pixel fallbacks still apply in all supported projection modes.

## Implementation map

Inspect these entry points; this is not an exhaustive file list:

- [DirectRgbTracer.java](src/scenes/viewport/DirectRgbTracer.java): snapshot, camera seed, primary rays, guide rays, starting media, worker scratch.
- [ViewportState.java](src/scenes/viewport/ViewportState.java): render keys, immutable job capture, camera state, aspect policy, effective temporal/budget eligibility.
- [CameraControls.java](src/scenes/viewport/CameraControls.java): pose movement and look without pinhole-only geometry assumptions.
- [TemporalReconstruction.java](src/scenes/viewport/TemporalReconstruction.java): projection, depth reconstruction, footprints, cut tests.
- [ViewportCommand.java](src/scenes/viewport/ViewportCommand.java), shared console help, and [ScenePresets.java](src/scenes/viewport/ScenePresets.java): controls, feedback, defaults, reset.
- [Viewport.java](src/scenes/viewport/Viewport.java), asynchronous tracing, render metadata, profiling overlays, benchmarks, and tests: audit every use of eye/sensor and camera-derived identity.

Do not leave a second independently mutable eye/sensor state beside the new camera description. Compatibility accessors, if necessary during migration, must derive from one authoritative state.

## Delivery stages and acceptance

### C1: Extract and preserve perspective

Introduce the camera boundary, immutable state, and projection capability while leaving the default view unchanged. Add status/FOV controls and migrate navigation, guide rays, snapshots, seeds, and keys. Capture deterministic baselines before modifying primary ray arithmetic.

Acceptance: exact raw RGB equality against pre-refactor pinhole fixtures at depth zero and stochastic depths, including glass/volume cases; equivalent rays for legacy skewed/off-center planes; unchanged seeded streams across workers/batches; existing pinhole temporal and asynchronous lifecycle tests pass. FOV edits preserve pose; resolution edits preserve framing; camera reset is camera-only and respects preset defaults.

### C2: Orthographic rendering

Add mode switching, remembered settings, height controls, projection conversion, origin-dependent media, and orthographic temporal support.

Acceptance: all ray directions agree while origins span the specified rectangle; objects of equal size at different depths have equal projected extents; forward movement does not magnify; changing height scales framing predictably; first-entry matching agrees at the reference plane; returning to a mode restores its settings. Test world/image round trips, depth reconstruction, depth-independent footprints, camera cuts, history rejection, and inside/outside starting media. Exercise asynchronous previews and P4 transitions in orthographic mode.

### C3: Thin lens and focus

Add finite-aperture rays, independent aperture sampling, center focus, effective temporal fallback, and help/status integration.

Acceptance: aperture zero is exactly equivalent to perspective raw output; rays for one image sample converge at the intended focus-plane point; disk samples cover area uniformly; out-of-focus points spread more with larger aperture; focus-plane detail remains sharp. Verify axial center-focus distance on off-axis hits, miss behavior, stale-query rejection, and first-surface glass behavior. Use analytic ray checks and statistical image tolerances, not only subjective screenshots.

Confirm stable mean brightness on a uniform-radiance test scene as aperture changes, no focus breathing, depth-zero aperture sampling, deterministic worker/tile/batch agreement, and correct varying-origin medium membership. Verify immediate raw fallback, no old history revival, temporal-budget restoration, and pinhole temporal resumption at zero aperture.

### Verification and completion evidence

Build with the repository's Java 23 preview workflow. Run the new focused camera suites plus existing affected suites: `CameraSamplingTest`, `ViewportKeyBindingsTest`, `ProgressivePathTest`, `DielectricPathTest`, `VolumePathTest`, `ParallelTraceTest`, `ResponsiveTraceTest`, `InteractiveResolutionTest`, `TemporalReconstructionTest`, `TemporalBudgetTest`, `ProfilingIntegrationTest`, and `ui.console.ConsoleInteractionTest` (use their actual package-qualified names). Run additional suites when touched code warrants them. Inspect harness output because failures do not necessarily produce a nonzero exit status. Use non-headless AWT only where required; automated checks must not open windows.

Record matching-setting before/after pinhole timing and allocations; explain material regressions. Confirm no per-ray camera allocation. Include reproducible perspective, orthographic, and lens preview commands/images plus a short manual check of navigation, command feedback, mode restoration, and blur controls. Do not claim physical input/display behavior from headless tests.

Update `AGENTS.md`, relevant help, and `tracker.md` when functionality lands, distinguishing implemented behavior from remaining stages. Check existing FAQ entries for newly inaccurate or unanswered material, preserving human questions; do not invent FAQ questions. Completion requires all three stages and their integration checks, not just the new camera interface.

## Follow-up: C4–C5 (implemented)

These stages extend C1–C3 with independent projection/aperture and continuously evaluated, parameterized focus selection. Center is the default selection. The requirements below describe delivered behavior; they supersede the original restrictions above.

### Evaluation of the C1–C3 implementation

The renderer already has the needed integration boundary: [Camera.java](src/scenes/viewport/Camera.java) compiles an immutable description into primary/reference rays; [DirectRgbTracer.java](src/scenes/viewport/DirectRgbTracer.java) consumes those rays with reusable scratch and checks varying-origin media. [CameraProjection.java](src/scenes/viewport/CameraProjection.java) supplies orthographic/pinhole temporal geometry. Sampling, estimator keys, P4 motion detection, and asynchronous preview publication already identify captured cameras.

The remaining coupling is concentrated in `Camera.Mode`, `withMode`, `withAperture`, `effectiveMode`, `identity`, `variableOrigin`, `temporalSupported`, and the branches in `Compiled.sample`. The three modes combine two choices: spatial projection and aperture behavior. C4 is therefore a localized camera/state/UI/key refactor plus a new orthographic aperture ray model and its tests; it does not require changing BSDFs or path transport.

Autofocus has a larger lifecycle cost. Before C5, `CameraFocus` created `PreparedObject` data on every one-shot query and linearly checked every primitive; `ViewportCommand` immediately wrote the resulting distance. Calling that routine every frame would repeat preparation, block input delivery, and restart samples unnecessarily. C5 now uses a reusable geometry/BVH cache, a bounded [FocusQueryService](src/scenes/viewport/FocusQueryService.java), stable subject identities and a separately clocked [FocusController](src/scenes/viewport/FocusController.java). The difficult work is avoiding stale-result feedback, jitter and render starvation while retaining eventual stationary convergence.

PBRT independently exposes aperture radius and focus distance for both perspective and orthographic cameras; this establishes that orthographic defocus is a supported camera-model choice, rather than a conceptual contradiction. See its [camera parameters](https://pbrt.org/fileformat-v4#cameras) and [projective-camera construction](https://pbr-book.org/4ed/Cameras_and_Film/Projective_Camera_Models). The specific orthographic model below is our design choice, with explicit framing invariants; do not assume that copying another renderer's finite-aperture construction preserves those invariants.

### C4: Independent projection and aperture

Replace the combined mode discriminator in authoritative camera state with independent projection (`perspective` or `orthographic`) and effective optics (focus distance and circular aperture radius). Keep pose, the full remembered legacy perspective plane, and remembered orthographic height. Compatibility/UI memory may remember a disabled aperture separately, but only effective optics belong to estimator identity. Compile ray generation from projection plus optics. This boundary should permit future aperture distributions or physical cameras without adding unrelated behavior to path transport.

Canonical controls:

| Story | Control | Result |
| --- | --- | --- |
| Change projection without losing my optics | `view camera projection perspective\|orthographic` | Preserve pose, aperture, current focus, focus-control settings, and remembered framing. First orthographic entry still matches scale at the current realized focus distance. |
| Add defocus to either projection | `view camera aperture <radius>` | Available in both projections; nonnegative finite scene-unit radius. Zero disables geometric defocus and preserves the exact zero-aperture path. |
| Zoom without changing focus | Existing `fov` / `height` controls | FOV remains perspective-only; height remains orthographic-only. Neither is a focus control. |
| Understand independent choices | `view camera status` | Report projection, effective aperture, current axial focus, and effective temporal availability; any remembered disabled radius must be labelled inactive. |

Preserve existing `view camera mode` commands as documented compatibility shortcuts. `mode perspective` selects perspective with zero effective aperture; `mode orthographic` selects orthographic with zero effective aperture; `mode lens` selects perspective with the remembered lens aperture, including a deliberately configured zero. Repeated shortcuts remain no-ops when their effective settings already match. Aperture edits in either projection update the shared remembered aperture. Turning aperture off through a mode shortcut must not overwrite that remembered radius. New `projection` commands retain the effective aperture; explain this difference in mode/projection help. Do not reinterpret existing `mode perspective` or `mode orthographic` as preserving an active lens, since that would change their delivered behavior.

Both projections share focus and aperture rather than acquiring separate, hidden focus settings. Projection changes never continually rederive orthographic height, and autofocus changes never zoom the orthographic view. Presets/reset restore perspective, zero aperture, focus distance 5 and the original framing defaults. Retain current validation and explicit conversion rejection for legacy off-center/skewed cameras where finite optics or orthographic conversion cannot preserve their representation.

#### Orthographic aperture model and invariants

Use an ideal model with parallel reference rays and translated ray bundles. This is an explicit approximation inspired by telecentric imaging; it is not a simulation of a real telecentric lens assembly. Let `O(u,v)` be the existing orthographic origin in the plane through camera position, `N` its normalized forward, `d > 0` the realized axial focus distance, and `B` a uniform disk offset of radius `a` along orthonormal camera right/up:

```text
reference origin    = O(u,v)
reference direction = N
focus point         = O(u,v) + d * N
sampled origin      = O(u,v) + B
sampled direction   = normalize(d * N - B)
```

Every bundle converges at its own focus-plane point. Bundles are translated copies of one another, so mean orthographic magnification is independent of depth and focus distance. Individual aperture rays have angular spread; requiring every sampled ray to remain parallel would prevent this focus mechanism. For a perpendicular plane at axial depth `z`, the ray footprint around the reference intersection has radius `a * abs(1 - z / d)`. This analytic footprint is an acceptance oracle, not a new display blur/filter pass.

Sample the pupil relative to each image sample's own orthographic origin. Sending all orthographic image samples from a single pupil near the eye towards separate focus points would introduce perspective-like ray bundles and would not satisfy our depth-independent framing contract. The per-sample translated pupils describe this ideal estimator, not a physical common aperture. Keep normalized unit-weight aperture averaging and the existing exposure convention.

Radius zero must bypass all focus-point and aperture arithmetic and reproduce delivered orthographic ray float bits and RGB exactly. Preserve the delivered perspective finite-aperture arithmetic and independent aperture random stream exactly. Use an explicit versioned sampling-domain identifier for the new orthographic finite-aperture estimator, without perturbing any existing estimator's seed hashes. Include effective projection/aperture and active realized focus in keys. Remembered UI values and focus-controller policy are excluded. With aperture zero, focus changes in either projection leave raw image identity unchanged.

Per-origin media remain mandatory for all orthographic rays and for any nonzero aperture. Temporal reconstruction and temporal-dependent motion budgets are unavailable whenever effective aperture is nonzero, in either projection. Keep requested settings, explain fallback, and preserve the independent P4 policy. Zero-aperture orthographic temporal geometry remains exactly as delivered. Clear incompatible history without converting every optics edit into a hard tracing cancellation.

### C5: Parameterized autofocus and focus pulls

Keep two distinct operations: **acquisition** chooses a target distance; **transition** moves the realized focus towards it. The immutable camera snapshot contains only the realized focus used to generate that job's rays. A viewport-owned controller contains source policy, candidate/accepted target metadata, timing and transition state. Never put target distance or mutable autofocus policy into a worker camera or raw estimator key.

Screen-point autofocus repeatedly measures the first geometric surface through a configured normalized image location using the aperture-center reference ray, including glass and emitters. Its default location is image center. It does not follow refraction, sample volume events, or recognize/track a named subject. When a different subject moves under the selected point, it may acquire that subject after the configured delay. A focus plane remains perpendicular to camera forward and is specified by axial distance in scene units.

#### Parameterized selection boundary

Make selection an explicit immutable `FocusTargetSource` (exact Java naming is an implementation choice), independent of manual/auto policy and transition timing. A normalized screen point is one source, with `u` and `v` finite in `[0,1]`; center is the parameter value `(0.5,0.5)`, not a special hard-coded autofocus ray. Use the renderer's image coordinates: `u=0` left, `v=0` bottom. Report this convention in help; any future screen-pixel/mouse coordinates must be converted at the UI boundary. Coordinates stay independent of requested/actual tracing resolution, image filtering and P4 adaptation.

Introduce a resolver boundary from a captured source plus camera/scene/query context to a focus measurement. Screen-point resolution generates that point's deterministic reference ray and returns the first hit with source provenance, world hit, subject identity and timestamps. Axial target distance is derived against the appropriate camera pose; off-center selection never uses slanted ray length as focus distance. A distance command is an explicit distance target and requires no ray query. Keep immutable source parameters and any scene-query implementation outside the transition interpolator.

Design the request/result boundary so future world-point, named-object/feature, or timeline-driven providers can submit the same target measurement/intent without rewriting focus interpolation, image identity, or publication. Only normalized screen selection and explicit axial distance are implemented in C5. A future animation can change selection and transition policy independently, or supply explicit targets; this delivery does not implement a movie/timeline system. Use distinct request/source identity and control revision so results from one selection cannot satisfy a later selection accidentally.

Existing `focus center` and `focus pull center` always use `(0.5,0.5)`, regardless of the configured source. `focus pull source` uses the configured source once. Continuous `focus mode auto` uses that source repeatedly. This makes selection reusable without changing the delivered meaning of `center`.

#### User stories, controls and defaults

| Story | Control | Result |
| --- | --- | --- |
| Keep the surface under my selected view point focused | `view camera focus mode auto` | Enable continuous acquisition from the configured source, using the configured delay and transition. Works with either projection and any aperture. |
| Select focus independently of how it transitions | `view camera focus source screen <u> <v>` | Set normalized screen-point selection; default `(0.5,0.5)`. Preserve manual/auto mode and realized focus; changing it in auto invalidates acquisition and reacquires. |
| Hold focus while I reframe | `view camera focus mode manual` | Freeze the current realized distance; cancel acquisition and any active pull. |
| Set focus immediately | Existing `view camera focus distance <units>` | Switch to manual and set the exact validated distance immediately, preserving delivered semantics. |
| Focus here once | Existing `view camera focus center` | On a valid hit, switch to manual and set immediately; a miss/stale query leaves distance, mode and transition unchanged. |
| Deliberately pull focus to a distance or visible surface | `view camera focus pull distance <units>` / `view camera focus pull center` / `view camera focus pull source` | On successful validation/acquisition, switch to manual and start the configured transition; screen-query misses/stale results leave all focus state unchanged. |
| Choose how quickly focus moves | `view camera focus transition duration <ms>` | Finite 0..10000 ms; default 300 ms. A fixed accepted target is reached in this duration. Zero means immediate. |
| Choose the feel of a pull | `view camera focus transition curve linear\|smooth` | Default `smooth`; linear progression or smooth ease-in/ease-out. |
| Ignore brief interruptions by passing objects | `view camera focus auto delay <ms>` | Finite 0..2000 ms; default 150 ms of consistent subject identity before a new subject is accepted. |
| Prevent small depth changes continually restarting a pull | `view camera focus auto tolerance <percent>` | Finite 0..25%; default 1%. Ignore small target changes using the reciprocal-distance rule below. Zero disables this suppression. |
| See what autofocus is doing | `view camera status` and F3 | Current/target distances, manual/auto policy, source type/coordinates, waiting/pulling/settled/lost/suspended state, and last accepted measurement age. Distinguish requested focus from the shown image's captured focus while rendering lags. |

Duration replaces a separate world-units-per-second speed control; exposing both would create conflicting arrival-time semantics. Curves are a small named set, not user-authored functions. Do not expose query frequency or arbitrary solver coefficients. Reset/preset loading restores manual focus, screen source `(0.5,0.5)`, cancels queries/candidates/pulls, and restores the defaults above. Console help must distinguish commands that take manual control from changing selection or an autofocus policy setting. A manual-mode source edit does not start a pull; an auto-mode source edit freezes realized focus, cancels the old candidate/pull, and begins normal acquisition from the new source. Equal source parameters are a no-op.

Live one-shot `center`/`pull center`/`pull source` commands also use the asynchronous query service: the console acknowledges a queued request and `view camera status` reports its eventual result. Only a successful unchanged-view measurement takes manual control. A miss, stale request or intervening selection edit leaves the existing focus policy/transition unchanged. Headless command adapters retain synchronous results outside the state monitor for compatibility; no live AWT/display query blocks on intersection work.

#### Acquisition and resistance to jitter

Perform selected-point queries at a maximum internal rate of 20 Hz while the viewport is active, including when the aperture is zero. At zero aperture this can prepare focus for later opening the aperture; it must not restart rendering or trigger P4 motion merely because a controller target changed. Query cadence is independent of display FPS, tracing spp, temporal eligibility and tracing-grid adaptation.

Use a bounded asynchronous query service with at most one query in flight per viewport and one latest desired view, not a FIFO of camera positions. Geometry preparation and intersections run outside both the input/state monitor and the AWT/display threads. Do not perform autofocus work inside a tracing job, depend on eligible temporal guides, or consume transport/aperture random streams. Reuse immutable prepared geometry across queries until scene geometry/transforms change, and use existing bounds/BVH traversal where applicable. Keep query scratch/counters separate from transport and guide diagnostics. Do not share the tracer's mutable workers or cache unsafely; extracting a shared immutable prepared-scene/query layer is an acceptable implementation route.

Measurements contain capture/completion times, scene/query revisions, hit position, axial distance, and stable subject identity. Identity should persist across camera-only queries and adjacent faces of the same scene instance; fresh `PreparedObject` allocation addresses or per-frame surface counters are insufficient. A geometry replacement/removal invalidates affected identity. New geometry/transform revisions invalidate stale scene measurements even if an instance name is reused.

Delay applies to acquiring a **different subject**, including initial acquisition and reacquisition after loss. Candidate identity must remain consistent for the delay; a conflicting hit or miss resets its dwell. Depth may change continuously while that same subject moves: do not restart the delay on every depth change, or forward movement would prevent acquisition indefinitely. After acceptance, meaningful depth changes on the same subject update its target without another acquisition delay. Hold the previous accepted target while waiting on a different candidate.

For a same-subject measurement, use `q = 1 / distance` and suppress retargeting when `abs(qMeasured - qAccepted) / max(qMeasured, qAccepted) <= tolerance / 100`. Compare against the last **accepted target**, not the previous measurement or current interpolated focus; many small changes must eventually accumulate into an accepted update. Suppression controls target updates, not convergence to an already accepted target. Do not use depth tolerance to merge identities of unrelated foreground/background objects.

A miss or invalid/nonpositive/unrepresentable measurement never focuses at infinity or a preset fallback. Mark autofocus lost, freeze the current realized focus, cancel the pull and candidate, and require normal reacquisition. Record errors/status without repeated console messages. Keep the geometric first-surface rule even when a transparent foreground might not be the user's intended subject; subject-selection alternatives are deferred.

#### Freshness without rejecting every focus update

Increment a dedicated query/control epoch on manual takeover, autofocus source changes, camera reset, preset/scene changes, suspension/close, and projection/framing changes. Compare scene revisions separately. Ignore the controller's own realized-focus writes and aperture-only edits in this query epoch: the reference ray does not depend on focus/aperture. Otherwise autofocus would invalidate its own work continually.

One-shot screen queries retain the existing strict unchanged-view publication check, also checking selected source/request identity. Continuous autofocus may accept bounded camera-motion lag: a measurement is eligible only if its query epoch, selected source and scene revision still match, capture age is at most 100 ms, and its hit reprojects within 2% of image width and height around the currently configured screen point, ahead of the current camera. Recompute axial target distance from the stored world hit and the current pose. Reject outdated/out-of-selection hits without treating rejection as an actual geometric miss; hold current focus and request the latest view. This permits useful results during translation or slow look motion without accepting an arbitrary old aim.

Reference world-to-image projection for this check is geometric and must work even when finite-aperture **temporal** projection is disabled. Do not couple autofocus freshness to `temporalSupported()`. A bounded old observation can still lag an intervening occluder; delay, age checks and the next query limit that lag, and status must report age. Do not claim current-view visibility without a current query. If query cost exceeds the freshness window, hold focus and report delayed/stale acquisition instead of queueing views or loosening validation silently.

#### Transition definition

Interpolate reciprocal distance `q = 1 / d`, a focus-power coordinate that gives more useful control near the camera than linear interpolation in raw world distance. Calculate interpolation in double precision; validate each realized float distance before publication.

For a segment from `q0` to `q1`, normalize elapsed monotonic time to `t` in `[0,1]`. Use `q(t) = q0 + (q1-q0) * c(t)`, with `c(t)=t` for linear and `c(t)=3*t*t-2*t*t*t` for smooth. At the endpoint, assign the exact accepted target float and stop updating; never leave an asymptotic tail that prevents sample accumulation. Duration 0 assigns the target immediately. A constant target completes after the configured active elapsed duration, regardless of display FPS.

An accepted new target during a pull starts a new segment from the current evaluated value, preserving position continuity and preventing overshoot; it gets the full configured duration. Smooth restarts ease in/out for that segment and do not promise velocity continuity under continual retargeting. Moving targets can keep a pull active and cause lag; duration is a static-target arrival time, not a bound on tracking lag. Changing curve/duration during a pull first evaluates the old segment, then restarts from that value towards its existing target under the new policy. Reissuing an identical accepted target does not restart the clock.

Use an injected monotonic clock and explicit update method for deterministic tests. Integrate with a live viewport tick that advances pose and realized focus before render-key capture; blocking snapshot tracing must not mutate the focus controller or advance it once per spp. On render pause, scene suspension, window focus loss or close, freeze active focus time and stop acquisition; discard in-flight results. Resume without counting inactive elapsed time, reacquire with normal delay, and do not apply a large catch-up jump. Console opening clears navigation input but does not itself disable autofocus. Distinguish suspension reasons rather than attaching autofocus blindly to every `suspendInput()` call.

#### Rendering feedback and publication

Apply only a changed realized float distance to authoritative camera state. At zero effective aperture, those writes do not change estimator/seed identity, temporal history or P4 motion. At nonzero aperture, each changed realized distance is a camera-only generation change: restart accumulation for that camera, let the active pass finish as a captured preview, and capture the latest focus next. Never mix intermediate focus distances in one accumulation or cancel every in-progress pass.

P4 may lower tracing resolution during a focus pull with finite aperture, using its existing policy; it cannot change optics or normalized query coordinates. Temporal reconstruction and its dependent budgets remain unavailable with finite aperture, regardless of focus mode. Preserve leases and bounded buffers. Stopping focus motion must restore requested quality after the existing quiet interval and allow progressive refinement/target completion. Query/controller updates must not depend on having reached a render sample target; acquiring a new focus can legitimately start a new generation after the prior one completed.

Store captured projection/aperture/realized-focus metadata with completed images for requested-versus-shown reporting. Controller settings, candidates, targets, timestamps and query epochs are not raw estimator identity. A scripted series of realized camera snapshots must render exactly the same raw samples regardless of whether manual commands or autofocus produced it. Headless controller tests use a supplied time/measurement sequence; live wall-clock autofocus schedules need not be identical across machines.

### Follow-up delivery and acceptance gates

Implement C4 before C5. Before changing code, capture delivered perspective finite-aperture and orthographic zero-aperture raw fixtures to complement the existing original pinhole hashes. Within C5, land the reusable query/transition boundary and manual pull controls before enabling continuous acquisition. Each stage must retain all C1–C3 suites. Update the specification status, live help, `AGENTS.md`, tracker and verification guide only when the corresponding behavior lands.

**C4 acceptance:** all four projection/aperture combinations work; delivered perspective zero/finite-aperture and orthographic zero-aperture fixtures remain exact; zero-radius no-op/key equivalence holds; mode aliases and remembered optics preserve existing behavior. Test translated orthographic bundles, uniform disk coverage, focus-plane convergence, analytic blur footprint versus depth, equal mean magnification at different depths, no breathing as focus changes, stable uniform-radiance exposure, depth-zero sampling, media across/nested boundaries, worker/tile/batch/cancellation reproducibility, captured async previews, P4 and temporal fallback/restoration. Include an orthographic foreground/background focus preview and a numeric test that would fail for a single common pupil.

**C5 controller/query acceptance:** test synthetic monotonic timestamps for both curves, reciprocal-distance progression, exact endpoints, duration zero, retarget continuity, no overshoot, identical-target no-op, policy edits, and pause/resume. Test initial/new-subject dwell, same-subject motion during dwell, sub-threshold cumulative changes, target reversals, adjacent mesh faces, first-surface glass, misses/loss/reacquisition, invalid distances, strict one-shot staleness, bounded autofocus pose lag, scene/control/source revision rejection, and no self-invalidation from autofocus focus writes. Verify manual takeover on distance/center/pull, freezing on manual mode, and unchanged state on failed screen-query operations. Cover off-center axial focus, coordinate bounds/validation, selected-point reprojection, resolution independence, source changes mid-query/mid-pull, and center aliases retaining their meaning after source edits. A fake alternate target provider must drive the same controller without depending on ray selection, demonstrating the extension boundary without shipping object tracking or animation.

**C5 integration acceptance:** queries do not block input/state/display/tracing, repeatedly rebuild geometry, issue per-pixel queries, grow queues, or reuse transport scratch/counters. Use fake services/latches to test edits/close during slow queries without flaky timing assertions. During sustained finite-aperture autofocus, require completed coherent previews and zero mixed-camera accumulation. After a stationary target is acquired, require focus to settle exactly, query results to stop optical writes, requested grid restoration, and continued raw convergence to sample target. At zero aperture, enabling autofocus and changing targets must preserve current RGB means/spp, seed identity and eligible temporal history. Cover both projections, P4, target-complete/paused states, console interaction, scene switches, focus loss and close.

Measure autofocus query cost and preparation counts for playground and mesh-room with matching geometry, separating queries from trace time/allocations. Verify one in-flight query and bounded memory under rapid edits; record stale-result rates during motion and overload. No fixed FPS or focus-lag promise follows from the 20 Hz maximum query cadence. Include scripted controller replay evidence and human tests of foreground interruptions, slow/fast/smooth pulls, manual freeze/reframe, and orthographic autofocus. Human GUI behavior remains a separate acceptance step from automated replay.

Deferred beyond C5: named-object tracking, feature recognition, configurable regions or click-target UI, world-point/timeline provider implementations, focus-plane overlays, physical exposure/telecentric lens design, custom transition curves, and capture/exposure-time integration while focus moves. A trace pass captures one realized camera, not a shutter interval; motion blur and optical focus movement during an exposure require a separate specification.
