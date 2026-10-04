# Viewport rendering requirements and implementation plan

This document defines the next viewport renderer: configurable light bounces, progressive convergence, reflective and transmissive materials, and richer scenes. The implementation is divided into six playable phases so each change can be inspected in the viewport before the next starts. Phase 1 is implemented; phases 2–6 remain planned.

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

### Phase 3 Smooth glass and absorption

Add smooth dielectric sampling, IOR transitions, Fresnel, total internal reflection, and distance-based RGB absorption. Extend ray state with the supported medium tracking. Provide a glass sphere and box in front of a colored pattern; include a preset suitable for entering the glass with the camera. Account explicitly for glass boundary interactions in the depth setting.

Acceptance: IOR near the surrounding medium's IOR gives little or no bending; increasing the contrast visibly changes refraction. Grazing reflections grow stronger, thicker tinted glass absorbs more, and exit surfaces are not skipped. Test entering/exiting orientation, total internal reflection, nested media, and numerical offsets. The demo and help state that focused refractive caustics are not yet a guaranteed feature.

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

- [DirectRgbTracer](src/scenes/viewport/DirectRgbTracer.java): active phase 1 primary-ray generation, closest-hit selection, RGB lighting, and visibility. Extend this path for subsequent phases. [BackwardRayTracer](src/scenes/viewport/BackwardRayTracer.java) retains the scalar reference/diagnostic implementation.
- [SceneInstance](src/scenes/viewport/SceneInstance.java), [Material](src/scenes/viewport/Material.java), [Transform](src/scenes/viewport/Transform.java), and [PreparedPrimitive](src/scenes/viewport/PreparedPrimitive.java): editable scene representation and cached geometry. [ViewportCommand](src/scenes/viewport/ViewportCommand.java) owns live console controls; [ScenePresets](src/scenes/viewport/ScenePresets.java) defines the demo/reset state.
- [TraceSurface](src/scenes/viewport/TraceSurface.java) and [SceneObject](src/scenes/viewport/objects/SceneObject.java): prepared geometry and the current sealed primitive boundary. Revisit exhaustive cases and self-intersection assumptions when adding spheres and meshes.
- [ViewportState](src/scenes/viewport/ViewportState.java): scene and camera state. Define coherent edits, transport settings, and accumulation invalidation here or in a dedicated render-state component.
- [Viewport](src/scenes/viewport/Viewport.java), [Resampler](src/scenes/viewport/Resampler.java), and [DisplayMapping](src/scenes/viewport/DisplayMapping.java): frame rendering, linear RGB sensor-to-display conversion, and fixed exposure/tone mapping.
- [profiling](src/profiling) and [MainModule](src/MainModule.java): keep instrumentation shared and wiring explicit rather than distributing profiler logic through materials.
- [ViewportBenchmark](tst/ViewportBenchmark.java) and [tracing regressions](tst/scenes/viewport/BackwardRayTracerRegressionTest.java): extend the current benchmark and independent reference checks as new transport behavior lands.

Record completed phases in [tracker.md](tracker.md); keep the broader roadmap in [Goals.md](Goals.md).
