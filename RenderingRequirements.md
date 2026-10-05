# Viewport rendering requirements and implementation plan

This document defines the next viewport renderer: configurable light bounces, progressive convergence, reflective and transmissive materials, and richer scenes. The implementation is divided into six playable phases so each change can be inspected in the viewport before the next starts. Phases 1–3 are implemented; phases 4–6 remain planned.

The first four phases deliver the main material playground. The last two extend it to larger meshes and light scattering inside translucent objects. Each phase should land separately, with its demo, controls, tests, and documentation together. Split a phase further if needed, but avoid a large infrastructure-only prerequisite commit.

## Starting point before phase 1

- The active viewport uses `BackwardRayTracer`, not the older forward `RayTracer`.
- Each frame traces a 1600 by 1600 sensor, downsamples to the 800 by 800 display, and draws a grayscale image. The current default scene contains one triangle and one point light.
- Direct lighting sums angle-dependent contributions from visible point lights. There are no indirect bounces, material assignments, or distance-based light falloff in this path. Brightness is normalized to the frame maximum.
- Geometry consists of individual triangles and rectangles. The scalar tracing loop prepares geometry once per frame and reuses hit storage. Its shadow optimization skips the originating flat primitive.
- F3 shows frame and runtime metrics; F4 enables statistical JFR trace sampling. Keep both available throughout this work.
- `ViewportState.maxBounces` and its scalar accumulator belong to the earlier design. Their presence does not mean the active viewport already supports path depth or progressive RGB rendering.

## Required behavior

### Light transport and bounce count

The viewport must support a user-controlled maximum number N of continuation events after the first camera hit. N equals zero means direct lighting only. N equals one permits a path from camera to surface A to surface B to a light. Evaluate emission and applicable direct lighting at the final allowed vertex; reaching the limit stops further continuation, not the light evaluation there.

Reflection and refraction both consume continuation depth. Shadow visibility queries do not. Once volume scattering exists, a scattering event also consumes continuation depth; crossing a boundary without a scattering interaction does not consume an additional event beyond an actual surface reflection/refraction. A glass entry and glass exit can therefore consume two events. Demonstration presets must use enough depth to show transmission through a solid.

Use one sampled continuation per interaction rather than an exponentially branching tree. Track accumulated linear RGB radiance and RGB throughput in an iterative loop. Material sampling must return a direction and correctly weighted contribution, with probability information and event type sufficient for light sampling. Preserve the allocation-conscious tracing approach; do not allocate a chain of Ray, Vec3, or hit objects per path.

Point lights require explicit direct-light sampling. Their infinitesimal position is not something random continuation rays can practically discover. Direct lighting must use the material's scattering model; do not apply diffuse cosine shading indiscriminately to mirrors or glass. Any later probabilistic early termination must compensate its survival probability so it does not darken the result.

### Progressive rendering and display

Accumulate independent, reproducible-with-a-seed path samples in linear RGB. Display their average, with sample count and convergence status visible. Accumulation storage and sample counts must stay bounded in memory and avoid practical count overflow or uncontrolled loss of precision in long sessions.

Replace per-frame maximum normalization with this order:

1. Average linear radiance samples.
2. Apply a consistent user-controlled exposure multiplier, preferably presented in stops.
3. Apply a documented tone-mapping curve and encode the result for the display, such as sRGB.

Decode UI color values appropriately before using them as linear reflectance or emission. Keep accumulation separate from display buffers, console drawing, and performance overlays. Exposure and tone-map changes redisplay the same accumulated data without restarting sampling.

Reset accumulation when the camera, geometry, material, light, environment, sensor resolution, or transport settings such as maximum depth change. Do not reset for overlay visibility, opening the console, exposure, or samples-per-frame changes that preserve the estimator. Provide an explicit restart action. Apply state changes coherently between rendering batches so old and new scene samples cannot mix.

The viewport must remain interactive while converging. Provide sample-batch and resolution controls, process input between bounded batches, and report the actual settings. No silent resolution reduction or FPS promise on unspecified hardware. Rendering can continue indefinitely or stop at a user-selected sample target, with an explicit paused/complete status.

### Materials and boundaries

Objects must reference editable material definitions. Required material families are colored diffuse, ideal mirror, smooth dielectric, rough reflective, rough transmissive, and emissive. Parameters should have meaningful names and validated ranges: reflectance/color, roughness, IOR, absorption, emission, and eventually scattering. A generic translucency slider may drive a preset, but must not conflate these different effects internally.

Smooth dielectrics need Snell refraction, angle-dependent Fresnel reflection/transmission, total internal reflection, and correctly weighted sampling. Colored transmission must attenuate by distance traveled inside the medium, per color channel, rather than by an arbitrary tint at each crossing. The ideal zero-roughness limit must remain supported.

Hit data must retain geometric orientation, entering/exiting status, primitive identity, object or instance identity, material identity, and interior-medium information. A face-forward shading normal cannot replace the original geometric normal. Track media through closed boundaries, including rays starting inside an object and nested nonintersecting solids. Arbitrarily overlapping media may be excluded initially, but the supported boundary model must be documented.

Offset spawned rays onto the correct side of a surface. Exiting rays must be allowed to hit another part of the same sphere or mesh. The existing rule that skips the source triangle/rectangle must not become a blanket rule skipping a whole object, nor be applied blindly to curved primitives.

Visibility must distinguish opaque blockage, transmission, and volume attenuation as applicable. A straight shadow segment cannot generally stand in for a path bent through glass. The initial dielectric phase may omit reliable direct caustic rendering, but must show reflected/refracted views and absorption correctly and describe that limitation. Do not label straight-through tinted shadows as physically correct refractive caustics.

### Geometry and lighting

Retain triangles and rectangles; add spheres and closed boxes, then reusable closed triangle meshes with transforms. Instances need translation, rotation, and scale, with correct normals and comparable world-space hit distances. Provide a floor and visible colored reference objects behind glass so material behavior can be judged.

Point lights remain available with editable position, color, and intensity. Establish and document one consistent intensity/distance convention, including inverse-square falloff for the physical lighting mode. Preserve the old triangle scene as a selectable diagnostic preset, with any intentional brightness-model changes documented.

Add rectangular area lights and emissive surfaces with a consistent sampling model. Prevent double-counting paths that can be found through both explicit light sampling and continuation rays; use multiple importance sampling or a documented correct alternative. Mirror and glass delta events need appropriate treatment. Larger emitter size should produce softer shadows under a clearly stated fixed-power or fixed-radiance convention.

Acceleration must preserve the same closest-hit and bounded visibility results as a brute-force reference. Use a BVH when introducing larger meshes; retain the reference path for tests and diagnostic comparisons.

## Common viewport controls and completion gate

Every phase must expose its new behavior without a source edit or restart. Existing viewport console commands and key bindings are acceptable; a complete graphical inspector is not a prerequisite. Controls must display their current values and have a discoverable help entry. Provide named scene presets, object/material selection by readable name, relevant parameter editing, camera reset, and scene reset.

Each phase must include:

- A named demo preset and a short hands-on checklist showing the new effect.
- Deterministic numeric tests where possible, seeded statistical/image checks where necessary, and regression checks for earlier presets. Do not assert exact noisy images across unseeded runs.
- A repeatable headless benchmark with dimensions, depth, sample count, sampling state, and scene recorded. Report median/p95, throughput, allocations, and tracing counters; do not compare different quality settings as though they were equivalent.
- An updated F3/F4 view as the algorithm evolves: distinguish primary, continuation, and visibility rays; show samples per pixel, configured depth, and relevant primitive/volume work. Label sampled CPU attribution separately from measured wall time.
- Continued operation of the texture editor, scene switching, console, and camera controls.

## Phased implementation

| Phase | Visible result | Main controls to try |
| --- | --- | --- |
| 1 | Colored sphere and box on a floor | Object transform, base color, light position/intensity, exposure |
| 2 | Reflections and diffuse color bleeding converge over time | Material, bounce count, samples per frame, resolution, restart |
| 3 | Clear and tinted glass bends the background | IOR, absorption, object thickness/size, camera inside/outside |
| 4 | Rough reflections, frosted glass, and soft shadows | Roughness, emitter size, emission, point/area light preset |
| 5 | A larger mesh scene stays navigable | Mesh preset, instance transforms, BVH/reference mode |
| 6 | A cloudy object scatters light internally | Scattering density, absorption, anisotropy, thickness |

### Phase 1 Material and object playground

Build the editable scene/material representation, RGB direct lighting, sphere intersection, a closed box made from triangles, simple instance transforms, and fixed exposure/display mapping. Include a floor, colored background objects, and a movable point light. Keep tracing deterministic and direct-only in this phase; closed objects and their outward boundaries must already be usable by later dielectric work.

Acceptance: recolor the sphere, move/rotate/scale the box, move the light, and adjust exposure live. Moving a bright object into view must not change the exposure of unrelated objects. Sphere rays from inside return the exit hit. Transformed objects and normals behave correctly. The triangle diagnostic preset remains available.

Implemented in `DirectRgbTracer`, with immutable named instances/materials, cached prepared geometry, and a conservative bounds check for the box's 12 triangles. This is a single bounds test per multi-primitive object; the mesh BVH remains for phase 5. Sphere/ellipsoid intersections preserve the world ray parameter; geometric normals, front-face status, object/material names, and primitive indices are retained. Only the originating flat primitive is skipped for visibility. A sphere can block a shadow from its own interior.

The color pipeline is linear diffuse reflectance × linear light color × intensity × cosine / (π × distance²), followed by box filtering, exposure multiplier 2^stops, Reinhard `c/(1+c)`, and sRGB encoding. Hex colors are interpreted as sRGB and decoded. Intensity is a scalar radiant intensity per steradian in scene units; no ambient contribution is added. Flat surfaces shade both sides. Positive scales of at least 0.01 are supported, with scale then X/Y/Z Euler rotation in degrees then translation; nonuniform sphere scaling makes an ellipsoid. Glass/medium behavior remains for later phases.

#### Hands-on phase 1 checklist

Start the app, press F12 to reach the viewport, then `/` to open its console. Commands return current settings; Esc closes the console to inspect the result. The footer points to `view help`. Existing WASDQE movement, R camera reset, F3 metrics, and F4 sampled CPU attribution remain available.

1. Run `view help` and `view status`. For faster editing, explicitly choose `view resolution 400`; `view resolution 1600` restores the original sensor quality.
2. Run `view select sphere`, then `view color 00cc88`. The sphere changes color immediately.
3. Run `view select box`, `view rotate 15 45 0`, `view scale 0.8 1.2 0.8`, and `view move 1 -0.3 6`. Coordinates are absolute, not deltas. Try `view material gold` to assign an existing material; color edits affect all objects sharing that material name.
4. Run `view light position 2 4 3`, `view light intensity 240`, and optionally `view light color ffd8aa`. Shadows move and illumination changes with color and distance.
5. Run `view exposure 1` or `view exposure -1`. Move/recolor an object and check that unrelated pixels retain their exposure. `view camera reset` resets just the camera; `view reset` reloads the current preset, camera, light, materials, and exposure, preserving the explicitly chosen sensor resolution.
6. Run `view preset triangle` for the original single-triangle geometry, then `view preset playground` to return. Triangle brightness intentionally differs from the former per-frame normalization: this preset now uses inverse-square lighting and fixed exposure, with intensity 80.

Verification includes analytic RGB/inverse-square checks, inside/outside sphere and box rays, rotated nonuniform scales, 2,000 seeded box rays against independent world-space triangle intersections, bounded and self-shadowing checks, deterministic repeated images, command validation, and buffer resizing. The test preview is `out/cli/material-playground.png`. Existing scalar renderer, resampling, geometry, profiling, DI, input, and texture editor suites also remain part of regression verification.

The headless benchmark warms 50 frames and measures 100, displaying to 800×800 while excluding AWT presentation, overlay drawing, and scheduler idle. All phase 1 runs use depth 0 and one deterministic sample per sensor pixel per frame. A local 1600×1600 playground run measured 211.71 ms median / 222.00 ms p95 after the bounds/sphere fast paths (457.04 / 531.32 ms before them); the explicitly selected 400×400 version measured 21.30 / 23.91 ms. These are different quality settings. The new RGB triangle measured 53.61 / 56.21 ms distant and 82.42 / 89.30 ms close; RGB lighting/display adds work compared with the preceding scalar renderer. Unchanged traces allocate about 0.7 KiB; display resampling buffers allocate separately. F3/F4 and the benchmark report actual primitive tests, visibility queries, ray throughput, and trace allocation. Live JFR sampling of the full playground succeeded (213.53 / 216.73 ms with sampling enabled); inlined lighting work can appear in generation/other. Timings vary by machine and load.

### Phase 2 Progressive paths and indirect bounces

Add iterative path transport, diffuse hemisphere sampling, ideal mirror reflection, and progressive linear RGB accumulation. Wire the active renderer's depth setting explicitly rather than relying on the older forward tracer. Add a corner or room preset where a colored wall visibly illuminates a neutral surface, plus a mirror sphere that reflects another object.

Acceptance: N equals zero shows only direct lighting; increasing N reveals indirect color bleeding and longer reflected paths. A stationary image visibly becomes less noisy as samples accumulate. Camera and material edits restart convergence without ghosting; exposure and overlay changes do not. Repeating a seeded run reproduces the result. Changing samples per frame changes convergence speed without changing the expected brightness.

Implemented by extending `DirectRgbTracer` with an iterative path loop and RGB throughput. `ViewportState.pathDepth` is the active continuation limit, independent of the older forward tracer's `maxBounces`. Diffuse directions use a cosine hemisphere distribution: BRDF × cosine / PDF reduces to RGB reflectance. Ideal mirrors use the reflected incoming direction and RGB reflectance as their delta-event weight. `Material.Sample` provides direction, RGB weight, event type and probability (solid-angle density for diffuse, discrete mass for mirror). Each path evaluates explicit point lighting at every diffuse vertex, including the final vertex; a mirror receives no diffuse highlight. Misses see a black environment, and all geometry remains opaque to visibility rays. Emission, glass, area lights and caustic improvements belong to later phases.

Progressive storage holds a double-precision online mean per RGB sensor pixel and a float display buffer, independent of exposure and overlays. Its size is fixed for the selected resolution (about 88 MiB for these buffers at 1600², excluding legacy/display storage). Counts stop at one billion spp, or a smaller selected target, to keep sessions bounded. Seeded pixel/sample-local streams give identical averages across batch sizes. Depth >0 jitters primary rays within pixels; depth 0 retains the exact phase 1 pixel-center diagnostic, so its repeated samples have no stochastic noise.

The renderer compares immutable snapshots before each batch, resetting on camera, scene, geometry, material, light, resolution, depth, seed or explicit restart changes. Exposure, console/overlay visibility, pause/resume, sample targets and samples per batch preserve the estimator. Scene edits also clear the image while paused. All viewport edits use the state monitor between render batches. Batches request 1–8 full sensor samples; a 50 ms budget is checked after each sample, so a single high-resolution/deep sample can exceed the budget. Resolution remains explicit, with no automatic quality reduction. Footer/F3 show total spp and converging/paused/complete status; F3/F4 distinguish primary, continuation and visibility work. F4's generation/transport/mean category also includes scattering work that does not appear in intersection, lighting or visibility stacks.

#### Hands-on phase 2 checklist

1. In the viewport console, run `view resolution 200`, then `view preset bounce-room`. This preserves your explicit resolution and starts at depth 3. Close the console with Esc and watch the noisy image refine. The mirror sphere reflects the gold sphere, floor and colored walls; the open front of the room reflects black.
2. Compare `view depth 0`, `view depth 1` and `view depth 3`. Depth 0 makes the ideal mirror black. Depth 1 reveals directly lit objects in reflection and the first diffuse color bleeding; more depth adds longer paths. Each depth edit restarts sampling.
3. Run `view select mirror-sphere`, then `view type diffuse` and `view type mirror`. Try `view color ffd8aa` for a tinted mirror; color edits preserve its scattering type. Types/colors edit shared material definitions, as in phase 1.
4. Run `view samples 4`, `view target 128`, and `view restart`. The requested batch size changes convergence speed; the 50 ms budget may produce fewer samples in a frame. `view status` shows requested settings and total spp, while F3 shows the actual batch. Completion stops tracing at the target. `view target 0` resumes continuous sampling up to the safety cap.
5. Run `view pause`, then `view exposure 1`. The same accumulated image brightens and spp stays constant. Toggle F3/F4 or open/close the console; these retain samples. `view resume` continues. Move the camera with WASDQE, move an object, or change the light: convergence restarts without blending the old scene into the new one.
6. Run `view seed 42`, `view target 128`, `view restart`. Restarting with unchanged scene/depth/seed reproduces the same sample sequence. Use `view resolution 400` or `1600` to explicitly increase quality; each change restarts sampling. `view preset triangle` and `playground` keep their depth-0 diagnostics available.

Verification: `scenes.viewport.ProgressivePathTest` checks cosine density/direction/weight, analytic diffuse enclosure brightness across depths, mirror weighting and final-vertex lighting, bounded mirror loops, exact seeded batch equivalence, target/pause behavior, and invalidation. In a seeded room, the 64-spp image's squared error is substantially below the 1-spp image's relative to a 512-spp reference, with measurable colored indirect light on the neutral floor. It writes 64² convergence comparisons and 320² previews at `out/cli/bounce-room-preview-1.png` and `bounce-room-preview-128.png`. Headless integration verifies exposure/overlay and scene switching; `ViewportKeyBindingsTest` exercises console/camera controls with a non-headless toolkit and no window. Phase 1 and the existing scalar, geometry, profiling and editor regressions remain green.

The benchmark accepts `bounce-room size=200 depth=3 samples=1 seed=1` (also `details` for JFR). It warms 50 frames, measures 100, and displays at 800² without AWT presentation/overlay drawing/scheduler idle. It records actual primary/continuation/visibility rays, their primitive tests, accumulated spp, requested/actual batch spp, seed, throughput and trace allocation. Compare matched settings; different depths/resolutions represent different quality and work.

Local phase 2 measurements with seed 1 and one spp/batch: room depth 3 at 200² measured 30.07 ms median / 47.77 ms p95; at 400² it measured 96.04 / 144.23 ms. Room depth 0 at 400² measured 23.79 / 47.11 ms; the triangle diagnostic at 1600² measured 61.65 / 101.17 ms. The final 400² depth-3 trace cast 160,000 primary, 412,934 continuation and 473,509 visibility rays, testing 1,120,000 primary / 2,890,538 continuation / 2,749,683 shadow primitives. Its throughput was 12.48 million total rays/s and unchanged trace allocation about 1 KiB. Initial mean/buffer allocation and display resampling are separate. Timings vary with JIT and load; depth-3 paths intentionally do substantially more work than direct-only frames. These measurements do not imply equivalent quality across sizes/depths or include presentation.

Live F4/JFR capture with normal JDK access succeeded on the 400² depth-3 workload (108.74 / 144.48 ms): 69 generation/transport/mean, 242 intersection and 153 visibility samples. Lighting was inlined into other attributed work. The restricted Windows account reported JFR unavailable; the overlay retained its existing unavailable status behavior, and retrying with normal account access produced an active capture.

### Phase 3 Smooth glass and absorption

Add smooth dielectric sampling, IOR transitions, Fresnel, total internal reflection, and distance-based RGB absorption. Extend ray state with the supported medium tracking. Provide a glass sphere and box in front of a colored pattern; include a preset suitable for entering the glass with the camera. Account explicitly for glass boundary interactions in the depth setting.

Acceptance: IOR near the surrounding medium's IOR gives little or no bending; increasing the contrast visibly changes refraction. Grazing reflections grow stronger, thicker tinted glass absorbs more, and exit surfaces are not skipped. Test entering/exiting orientation, total internal reflection, nested media, and numerical offsets. The demo and help state that focused refractive caustics are not yet a guaranteed feature.

Implemented with smooth `DIELECTRIC` materials, exact unpolarized Fresnel, Snell refraction and total internal reflection. Each boundary samples one reflection or transmission. Fresnel coefficients cancel against their branch probabilities; transmitted radiance throughput also carries `(incident IOR / exit IOR)^2`. Entry and exit each consume a continuation, so two are required to see through a solid. The glass presets default to depth 8. Glass receives no diffuse point-light highlight, and its base color does not tint a crossing.

IOR is validated in 1–3. Absorption is a linear RGB coefficient in 0–100 inverse scene units: each channel is multiplied by `exp(-coefficient * distance)`. Zero is clear; increasing the red coefficient removes more red, producing a cyan transmission. Absorption applies to camera/continuation segments and unobstructed point-light segments inside the same medium. The inner medium replaces the outer medium while inside a nested solid; their coefficients are not added. Changing type or color preserves the other material parameters; shared edits are validated together before publication.

Supported glass boundaries are single spheres (including transformed ellipsoids) and the existing closed box triangulation, with positive transforms. Open triangles/rectangles are rejected as glass. Media use prepared object identity rather than material name or individual triangle identity. Camera containment is initialized once per batch from oriented exit intersections, ordering enclosing solids from outermost to innermost. Transmission pushes/pops the medium stack; reflection leaves it intact. The original geometric normal controls orientation and the correct side of the 0.001 scene-unit spawn offset. Spheres and box exits remain eligible for subsequent hits.

Solids may be disjoint or strictly nested, with nonintersecting boundaries. Arbitrarily overlapping, touching/coincident boundaries and features/gaps smaller than the numerical offset are outside this initial model; editable transforms do not enforce this relationship. Arbitrary closed meshes come in phase 5. The current point-light visibility query treats glass as a blocker: it does not pretend that a straight tinted shadow is a bent refractive path. Reflected/refracted views and distance absorption work; focused refractive caustics and direct illumination from lights across a glass boundary are not guaranteed. Misses still see a black environment.

#### Hands-on phase 3 checklist

1. Press F12 for the viewport, `/` for its console, then run `view resolution 200` and `view preset glass`. Close the console with Esc. A clear sphere and tinted rotated box sit in front of alternating red/blue stripes. The floor includes blocked direct shadows under the current visibility model.
2. Run `view select glass-sphere`, `view ior 1`, then `view ior 1.5` or `view ior 2`. IOR 1 largely removes the sphere boundary's optical effect; increasing contrast bends the pattern and strengthens reflections. Glass can also be selected through `view type dielectric` on a supported closed object.
3. Run `view select glass-box`, `view absorption 0 0 0`, then `view absorption 0.8 0.15 0.04`. Red attenuates fastest. Compare `view scale 0.8 1 0.3` and `view scale 0.8 1 1` for thickness. Scaling is absolute and resets accumulation.
4. Compare `view depth 0`, `view depth 1`, `view depth 2`, and `view depth 8`. Depth 0/1 cannot transmit all the way through a solid; additional depth includes entry, exit, and longer reflected/indirect paths. `view target 128`, `view samples 4`, and `view restart` provide a reproducible finite convergence run.
5. Run `view preset glass-inside` for a camera at the clear sphere's center; WASDQE lets you cross its boundary. `view camera reset` returns to the standard outside camera; `view reset` restores the preset's inside camera. F3 retains sample/depth/runtime metrics; F4 adds exact glass reflection/transmission and medium segment counts alongside statistical CPU attribution. `view status` reports selected IOR and absorption.

`scenes.viewport.DielectricPathTest` covers normal/grazing Fresnel, sampled branch frequencies, Snell directions, total internal reflection, depth termination, entry/exit, inside cameras, nested IOR and absorption, box/ellipsoid boundaries, point-light attenuation, blocked glass visibility, validation, atomic shared edits, invalidation and seeded batch equivalence. A 64² glass image at 64 spp has about 1.3% of the first sample's squared error against a 512-spp reference. Visually checked previews: `out/cli/glass-preview-64.png`, `glass-ior-1-preview.png`, and `glass-inside-preview-64.png` (240²). Earlier renderer, geometry, profiling, DI/input and texture-editor regressions pass.

Headless benchmark settings: seed 1, max/actual one spp per batch, 50 warmup/100 measured frames, 150 total spp, display 800², no overlay/AWT/scheduler. Glass depth 8 at 200² measured 30.36 ms median / 49.29 ms p95; at 400² 93.76 / 100.97. Inside glass depth 8 at 200² measured 38.69 / 48.88. The 400² last frame traced 160,000 primary, 274,842 continuation and 166,952 visibility queries; primitive tests were 2,155,116 / 4,192,164 / 1,721,315 respectively, with 18,537 glass reflections, 89,718 transmissions and 59,421 medium segments. Last-frame all-ray throughput was 6.69 Mrays/s; unchanged tracing allocated 1,368 bytes per batch. Bounds, stacks and hit scratch are reused throughout a batch; no per-path object chains are allocated. Different scenes/depths/resolutions represent different work. Regression benchmarks: bounce-room depth 3 at 200² 29.49 / 36.22 ms; triangle depth 0 at 1600² 63.26 / 87.10 ms. Timings vary with load.

### Phase 4 Rough surfaces and extended lights

Add rough reflective and rough dielectric sampling and evaluation, an emissive rectangular area light, and consistent direct-light sampling for the new source. Use a preset with polished/rough material pairs and an object casting a shadow onto the floor. Keep this phase focused on surface scattering; foggy interiors belong to phase 6.

Acceptance: roughness broadens reflections and blurs the view through frosted glass; the smooth limit matches phase 3. Increasing emitter size softens shadows according to the stated power convention. Sampling a light explicitly and hitting it through continuation must not double brightness. Test probability/weight consistency and energy behavior, with special handling for ideal mirror/refraction events.

### Phase 5 Closed meshes and acceleration

Add reusable indexed triangle meshes with stable object/primitive identity, instances, and BVH traversal. Supply at least one procedural closed mesh with adjustable tessellation and a multi-instance demo; an external model loader is optional. Preserve material and medium boundaries across triangles of the same object. Build/refit or rebuild acceleration data on edits, not on every unchanged frame.

Acceptance: increase mesh detail and add instances while observing primitive-test counts and frame times. Switching between BVH and brute force preserves hits and seeded images within the agreed numerical tolerance. Rays travel through both sides of a glass mesh, and one triangle can shadow another triangle of the same object. Nonuniform scale and object edits update bounds and normals correctly.

### Phase 6 Volumetric translucency

Add a homogeneous participating medium inside a closed sphere or box: sample distances to scattering events, accumulate absorption/transmittance, and sample scattering directions with an adjustable phase function. Include direct-light visibility through the medium. Start with constant density and supported closed boundaries; heterogeneous density fields and specialized skin models are outside this phase.

Acceptance: the object can transition from clear absorbing glass to a cloudy or milky interior by changing scattering density. Thickness and absorption alter transmission; anisotropy changes the preferred scattering direction. Zero scattering reduces to the preceding absorption-only behavior. Multiple scattering can brighten regions that a surface-only shader leaves dark. Surface and volume depth limits terminate correctly, with medium tests and seeded convergence checks.

## Scope boundaries and implementation choices

The phases do not require a mesh importer, a full scene editor, texture authoring integration, GPU rendering, denoising, depth of field, a bidirectional/caustic solver, arbitrary intersecting media, or heterogeneous volumes. These can follow without preventing the demos above. Exposure remains manual initially; adaptive quality and automatic exposure must not silently change the comparison conditions.

Material formulas, the tone mapper, and BVH construction details can be selected during the relevant phase, provided their conventions, supported limits, and verification are documented. UI sliders are welcome but optional; the required capability is live, discoverable editing in the viewport. Parallel tracing may be added when profiling justifies it, with worker-local scratch storage and reproducible sampling; it is not a prerequisite for phase 2.

## Code entry points

- [DirectRgbTracer](src/scenes/viewport/DirectRgbTracer.java): active primary-ray generation, iterative RGB paths/throughput, progressive averaging, closest-hit selection, lighting, and visibility. Extend this path for subsequent phases. [BackwardRayTracer](src/scenes/viewport/BackwardRayTracer.java) retains the scalar reference/diagnostic implementation.
- [SceneInstance](src/scenes/viewport/SceneInstance.java), [Material](src/scenes/viewport/Material.java), [Transform](src/scenes/viewport/Transform.java), and [PreparedPrimitive](src/scenes/viewport/PreparedPrimitive.java): editable scene representation and cached geometry. [ViewportCommand](src/scenes/viewport/ViewportCommand.java) owns live console controls; [ScenePresets](src/scenes/viewport/ScenePresets.java) defines the demo/reset state.
- [TraceSurface](src/scenes/viewport/TraceSurface.java) and [SceneObject](src/scenes/viewport/objects/SceneObject.java): prepared geometry and the current sealed primitive boundary. Revisit exhaustive cases and self-intersection assumptions when adding spheres and meshes.
- [ViewportState](src/scenes/viewport/ViewportState.java): scene and camera state. Define coherent edits, transport settings, and accumulation invalidation here or in a dedicated render-state component.
- [Viewport](src/scenes/viewport/Viewport.java), [Resampler](src/scenes/viewport/Resampler.java), and [DisplayMapping](src/scenes/viewport/DisplayMapping.java): frame rendering, linear RGB sensor-to-display conversion, and fixed exposure/tone mapping.
- [profiling](src/profiling) and [MainModule](src/MainModule.java): keep instrumentation shared and wiring explicit rather than distributing profiler logic through materials.
- [ViewportBenchmark](tst/ViewportBenchmark.java) and [tracing regressions](tst/scenes/viewport/BackwardRayTracerRegressionTest.java): extend the current benchmark and independent reference checks as new transport behavior lands.

Record completed phases in [tracker.md](tracker.md); keep the broader roadmap in [Goals.md](Goals.md).
