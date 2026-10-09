# Sample game requirements

Status: G1 and G2 are implemented with automated checks passing; human window/input and texture QA is pending. G3 and G4 are proposed. See [the game QA guide](benchmarks/game/README.md) for launch instructions, evidence, and the hands-on test.

Build a standalone block-world exploration application using the public ray-tracing engine. The first complete sample lets a user fly around a small landscape of textured cubes under sunlight and a blue sky. It also demonstrates that a new application can use the engine without depending on the existing viewport or scene editor.

Deliver the work as the vertical milestones below. Each milestone includes its application behavior, necessary engine support, launch instructions, and verification. A milestone must produce a usable application; engine infrastructure alone does not complete it. Follow [AGENTS.md](AGENTS.md), [EngineRequirements.md](EngineRequirements.md), [RenderingRequirements.md](RenderingRequirements.md), and [PerformanceRequirements.md](PerformanceRequirements.md) for existing architecture and correctness requirements.

## Scope and decisions

- This is a finite exploration sample inspired by block-building games. Use original textures; do not copy Minecraft assets.
- One block is one world unit on each axis. Represent blocks as ordinary objects initially, sharing geometry and materials.
- Navigation is free flight. Collision, gravity, jumping, block placement/removal, inventory, multiplayer, and gameplay objectives are outside G1 through G4.
- The game owns block coordinates and types. It converts those values to engine scene instances. This separation must allow later replacement of the object representation without rewriting game rules.
- Game save/load, terrain editing, chunk storage, streaming, and combining exposed block faces into larger meshes are deferred. They are candidate future milestones, not hidden prerequisites.
- Sun direction is fixed during normal play. Diagnostic presets may change it; a time-of-day simulation and visible sun disk are deferred.
- Use a separate executable and window. Do not replace startup behavior in the existing application or add the game as another viewport preset.

The initial world dimensions and performance settings below are proposed starting values. The implementation must report measured results before treating those values as validated defaults. No open decision prevents starting G1; later gameplay and storage designs remain unspecified deliberately.

## Milestones and delivery status

Milestones are cumulative. Each depends on the preceding milestone, and all earlier user tests must continue to pass. Keep identifiers stable when adding work; insert a decimal milestone or append a new identifier rather than renumbering delivered work.

| Milestone | User can test | Engine work delivered with it | Status |
| --- | --- | --- | --- |
| G1 Explore a cube scene | Launch a separate application and fly around colored cubes | Reuse existing public camera, rendering, and input APIs | Implemented; human QA pending |
| G2 Explore textured blocks | Inspect crisp grass, dirt, stone, and wood textures on cube faces | Immutable texture data and diffuse cube texture mapping | Implemented; human QA pending |
| G3 Explore under sunlight and sky | Observe sun shadows, change sun presets, and compare sky illumination | Directional lights and optional sky radiance | Proposed |
| G4 Explore the sample landscape | Navigate a small coherent world with measured responsiveness | Integrate existing resolution adaptation; add acceleration only if measurements justify it | Proposed |

## Shared application and engine requirements

Place application code under `src/game/` and image assets under `assets/game/`. The intended entry point is `game.SampleGameMain`. Provide `./game.ps1` from the repository root to build and launch the sample using Java 23 with preview features. Keep the launch command valid after every milestone. It is an explicit interactive launch; automated checks must run without opening windows or stealing focus.

Use `RenderEngine`, `RenderSession`, `WorldSnapshot`, `SceneInstance`, `Camera`, and `RenderView` through their public APIs. Reuse `engine.input` and the platform AWT input adapter where appropriate. The game must not import `scenes.viewport` or `editor`; the engine must not import the game. Existing viewport-specific camera controls are not an application dependency to inherit.

The application owns its window, input state, navigation policy, block definitions, asset decoding, and display overlay. The engine owns transport, immutable render input, worker scheduling, accumulation, and image leases. Decode images outside the engine and give it immutable pixels. Rendering workers must never read mutable game state. Copy or convert pixels while a render-image lease is held, and release the lease before retaining the resulting display image.

Keep texture sampling allocation-free per ray. Preserve geometry caching, worker-local scratch, cancellation, and coherent captured-camera previews during movement. Texture, light, and sky changes invalidate affected accumulation and history; returning to an old setting must not revive stale publications. Existing scenes retain their appearance and behavior when new features are absent.

Each milestone delivery must include exact launch and user-test instructions, automated results, and representative screenshots in `benchmarks/game/README.md`. Record implementation progress in `tracker.md` and update the status table here. Distinguish automated verification from a human playthrough; do not claim a human test passed before the user performs it. The implementation agent runs `./verify.ps1` and additional tests relevant to that milestone.

## G1 Explore a cube scene

Deliver a window containing a small gallery of differently colored cubes at varied heights, lit with existing point lights. Include recognizable near and far landmarks so movement and depth are easy to judge. Use existing engine functionality; textures, directional light, and sky are not required for this milestone.

The window starts with a visible pointer and instructions. Clicking the rendered view captures mouse look. Escape releases the pointer and clears held movement. While captured, WASD moves relative to camera heading, Space/Ctrl moves vertically, and mouse movement controls yaw and pitch. Limit pitch to avoid flipping. Normalize diagonal movement and integrate movement by elapsed time, independent of key repeat or rendering speed. Start at three world units per second. R restores the starting camera and clears held movement.

Focus loss releases the pointer, clears movement, and suspends rendering; regaining focus resumes rendering but requires a click to recapture. Resizing preserves vertical field of view and updates horizontal framing to the display aspect. Closing releases the render session and application update resources. Expose a small overlay containing capture state, controls, and requested versus displayed tracing dimensions.

User test, using `./game.ps1`:

1. Click the view, fly between and around cubes, look upward/downward, and move diagonally. Motion is controllable and is not limited by the pointer reaching the window edge.
2. Release movement keys and observe the camera stop. Press Escape and confirm the desktop pointer is usable; click to resume.
3. Hold a movement key while switching to another application. Return and confirm that movement has stopped and the pointer remains free.
4. Resize the window, reset the camera with R, and close/relaunch. There are no stuck keys, stretched geometry, or surviving game update threads after close.

Automated acceptance covers navigation timing and normalization, pitch limits, transient-input clearing, session suspension/close, and compilation of the game against the independently built engine rather than viewport/editor classes. Continuous camera edits must still produce complete captured-camera previews.

## G2 Explore textured blocks

Replace gallery colors with shared grass/dirt, stone, and wood materials using original pixel-art assets. Retain the G1 point lights. Include an elevated grass block so its underside is visible, and a rotated/scaled demonstration cube so texture attachment can be checked by eye. Missing or invalid assets produce an actionable error naming the asset instead of an unexplained blank scene.

Add immutable texture data and optional diffuse texture bindings to the engine material path. For cubes, map local face coordinates to a two-dimensional image; specify top, bottom, and side images separately. Use nearest-neighbor sampling, which selects a single texel rather than blending adjacent texels. Convert asset sRGB colors to linear reflectance before lighting. Document face orientation and edge handling in the implementation so asymmetric patterns do not rotate or mirror accidentally.

Mapping is relative to the object, not world position: translating, rotating, or scaling a block carries its texture with it. Texture reflectance modulates both direct and bounced diffuse lighting. This milestone does not require arbitrary mesh texture coordinates, transparent textures, normal maps, or textured mirror/glass materials. Unsupported bindings must fail explicitly rather than render incorrectly.

Preserve texture bindings when `MaterialAsset` and `SceneSnapshot` copy materials. Game persistence remains deferred. Until the scene file format supports these bindings, `SceneFiles.save` must reject a textured document before replacing its destination file. Existing untextured save/load remains supported; no save operation may silently strip textures.

User test, using the same launch command:

1. Inspect grass tops, dirt sides and bottoms, stone, and wood at close range. Pixel art is recognizable, opaque, and has the intended face orientation.
2. Fly below the elevated cube and around the rotated/scaled cube. Patterns stay attached to each face without swimming through world coordinates.
3. Reset the camera and compare the repeated block materials. Shared assets look consistent across objects.

Automated acceptance covers immutable pixel ownership, color-space conversion, face orientation and boundaries, transformed mapping, direct and bounced texture contribution, material-copy preservation, and rejection of unsupported persistence without modifying an existing destination. Retain the untextured rendering reference tests.

## G3 Explore under sunlight and sky

Illuminate the textured gallery with a directional sun and a blue vertical-gradient sky. A directional light represents a source far enough away that all incoming rays are parallel and illumination does not decrease across the scene. It has direction, linear RGB color, and strength; it casts shadow rays toward infinity. Existing point lights must continue to work.

Add optional sky radiance to world input, defaulting to black for existing scenes. On a missed ray, evaluate sky color from its direction and multiply by accumulated path throughput, the fraction of light retained through earlier interactions. The same sky must appear behind objects and illuminate diffuse surfaces through continuation rays. Start the game with two continuation bounces; do not add an unrelated constant ambient term to disguise unlit shadows.

Expose a small lighting panel when the pointer is released. Provide morning, noon, and evening sun-direction presets plus a sky on/off control; these are diagnostic settings, not a day/night simulation. R resets only the camera, leaving lighting choices intact. Put directional lights and sky in game-created `WorldSnapshot` values; adding editable scene-document components and persistence for them is deferred.

The engine must carry these values through validation, render-session state, immutable snapshots, and render identity. Define and test the light-direction sign convention. For combinations of new lighting with existing mirror, dielectric, or volume transport, implement correct support or reject unsupported combinations explicitly at the public API boundary. Never silently omit a configured light. Existing worlds without these new features must retain their established behavior.

User test:

1. Launch the gallery and fly around its raised blocks. A blue sky is visible; blocks cast coherent shadows on other blocks and the ground.
2. Release the pointer and switch sun presets. Shadow direction changes, and the newly rendered lighting replaces the previous result without stale blends.
3. Turn off the sky. The background becomes black and surfaces shielded from direct sun become darker after refinement. Turn it back on and observe fill return.
4. Move closer to a block without changing lighting. Its surface lighting does not brighten merely because the camera approached it.

Automated acceptance additionally verifies distance-independent sun illumination at separated identical surfaces, shadow occlusion, sky directions, and lighting invalidation. For an isolated diffuse surface under a constant sky with no other lights, converged outgoing radiance must match reflectance times sky radiance within a documented statistical tolerance.

## G4 Explore the sample landscape

Make a coherent finite landscape the default: start with a roughly 12 by 12 block ground footprint, short stepped terrain, a block-built tree, stone features, and a small wood/stone structure. Spawn above ground looking toward recognizable landmarks. Every block remains an ordinary scene instance using shared unit-cube geometry and materials. Retain the gallery through `./game.ps1 -Scene gallery` so earlier visual checks remain reproducible.

Keep the game-owned source data as a list of block coordinates and types. Do not make the renderer's prepared primitives the authoritative world model. Do not introduce a chunk format or compressed save format in this milestone.

Start at a requested tracing resolution of 320 by 200 for a matching window aspect, adjusting one dimension when the window aspect changes. Integrate the existing adaptive-resolution option and let the user turn it off for comparison. The overlay distinguishes requested and actually displayed tracing dimensions. Camera motion may reduce resolution; stopping restores requested quality and stationary sampling refines the image. No setting may silently reduce path depth to meet a speed target.

Provide a replayable 30-second camera route in `benchmarks/game/camera-route.csv`, containing time in seconds, camera position, yaw, and pitch. During G4 implementation, choose and record exact coordinates from the finished landscape: start at spawn at 0 seconds, pass the tree at 8 seconds, the structure at 16 seconds, and open ground at 25 seconds, then hold the final pose until 30 seconds. Interpolate position and unwrapped angles linearly between these points. The automated runner must use elapsed time rather than rendered-frame count so slower rendering does not slow the route. Freeze the fixture before collecting comparison results and publish the replay command in the game README.

Run one complete unmeasured route for warmup, reset the camera, and measure three route replays sequentially on the development machine. Record CPU, JDK, worker count, block count, window/tracing dimensions, depth, sample settings, and adaptive-resolution state. Count fresh rendered publications rather than repeated display redraws. For each replay, measure intervals during the first 25 seconds, including the wait from movement start to the first new image and the hold from the last image to movement end. The provisional target is a 95th-percentile interval of at most 200 ms in each replay. Use the last five seconds to check restoration and refinement separately. Report freshness as software image freshness, not physical input-to-monitor latency.

The current tracer checks objects in a flat list. If the target is missed and profiling attributes at least half of tracing time to object traversal, add an object-level bounding volume hierarchy, a tree of bounds that skips groups of objects. Coordinate this work with P7 in [PerformanceRequirements.md](PerformanceRequirements.md). Otherwise address the measured bottleneck. If the target still cannot be met, report results and propose an explicit scope or target revision; do not silently shrink the world or mark the performance requirement passed. Headless measurements and the live user experience must be reported separately.

User test:

1. Launch the default world, fly from the spawn to the tree and structure, and inspect textured surfaces and sun shadows from several heights.
2. Move continuously for at least 30 seconds. New coherent images continue to arrive, input remains usable, and the camera does not freeze waiting for a render pass.
3. Stop moving. Requested resolution returns and image noise decreases as more samples accumulate. Toggle adaptive resolution and compare movement quality using the overlay.
4. Repeat the G1 capture, focus-loss, reset, resize, and close checks in the full world; then launch the gallery and repeat its texture and lighting checks.

Automated acceptance includes deterministic world construction, shared assets, sustained camera previews, stationary refinement, adaptive-resolution recovery, and the recorded freshness benchmark. If a bounding hierarchy is added, compare seeded images and shadow results against the existing brute-force traversal.

## Adding future milestones

The next candidates are selecting/placing/removing blocks, saving and reopening a world, walking with collision, and scaling terrain through chunks. Their order and detailed behavior remain open. Adding one does not retroactively expand G1 through G4.

Each new milestone must state the user-visible outcome, prerequisites, application and engine changes, exact user test, automated acceptance, and deferred behavior. Include a runnable delivery and update the milestone table. A storage or acceleration change must be demonstrated through a user task such as reopening an edited world or exploring a larger landscape, rather than delivered only as invisible infrastructure.
