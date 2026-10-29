# Sample game performance and image quality review

October 29, 2026. For the RayTracingEngine developer deciding the next renderer investment.

The target is **120 genuinely rendered frames per second at 1920×1080 output on the RTX 3080 12 GB**, accepting some visual approximations. This permits 8.33 ms between fresh frames. Rendering fewer source pixels is acceptable if reconstruction preserves useful detail; repeated or generated frames do not satisfy the target. The recommendation is to build a real-time rendering pipeline around persistent lighting information and GPU presentation, while retaining the current renderer as a reference. Further whole-image resolution reduction alone will continue the existing loss of detail.

This is an architectural review of the current working tree, including its uncommitted GPU/game changes, and published implementation guidance. The timing evidence below comes from existing repository experiments and captures. No new performance experiment was run for this review, so none of the proposed gains is a measured speedup or a promise of 120 FPS. The next work should be bounded experiments before a broad rewrite.

## What is already implemented and why the budget loses detail

The game already has Vulkan hardware ray queries, shared mesh acceleration structures, reusable scene buffers, asynchronous rendering, packed GPU display conversion, experimental direct GPU presentation, and two center-detail sampling approaches. The old CPU viewport description does not describe this workload. Its CPU temporal reconstruction is also not a GPU game feature: [GpuRenderSession](../../src/gpu/GpuRenderSession.java) publishes no temporal reconstruction and resets accumulation when its world/camera/render key changes.

The movement FPS setting controls a resolution budget, not a guaranteed presentation rate. [GpuResolutionPolicy](../../src/gpu/GpuResolutionPolicy.java) estimates cost per pixel and chooses a whole-image scale, reserving 20% of the requested time for other work. Approximately, its desired pixel count is `0.8 × time budget / cost per pixel`. Raising the requested resolution therefore often produces a smaller scale and a similar actual pixel count. Discrete scale choices, cooldowns, minimum dimensions and non-pixel costs complicate this approximation.

Center-detail sampling then reduces the number of paths within that selected grid. Its smooth reconstruction uses current surface guides and a spatial filter; it has no history that recovers detail from earlier views. Lowering the whole grid also lowers its protected center. A higher output size cannot recreate an enemy silhouette or leaf that no ray sampled.

Existing evidence gives useful bounds:

| Evidence | Result | Interpretation |
| --- | --- | --- |
| [1080p fixed-camera experiment](../s1/foveated/smooth/README.md), depth 8, Full atmosphere | Uniform: 13.02 ms GPU / 15.42 ms host. Smooth center detail: 8.65 / 10.93 ms. | Both exceed 8.33 ms before live presentation. These are older scene measurements, not the current forest's FPS. |
| [Direct presentation comparison](../s1/direct-presentation/README.md) | About 50–53 fresh native submissions/s versus 57 offscreen Swing paints/s. | Avoiding readback did not by itself fix the pipeline. Hidden-surface timing cannot settle visible-window behavior. |
| Local capture at 2026-10-29 13:51:40 UTC, ending `0c27bbc2-e5b3-472e-b5ac-0ab6028da370` | Displayed image 720×405, one sample, depth 8, atmosphere enabled, smooth center detail; controls selected a 60 FPS budget. | Confirms that low actual detail occurs in a real saved game state. This single snapshot does not establish a sustained rate or represent the user's 120 FPS setting. |

The capture is under `out/game/captures/` and is not a portable benchmark dependency. Its image visibly contains substantial noise in vegetation. Noise reduction and silhouette stability are separate problems; both matter for aiming and recognizing motion.

## Highest priority changes

### 1. Give the real-time GPU mode an efficient ray traversal contract

In [diffuse.comp](../../assets/gpu/diffuse.comp), `nearest()` deliberately never confirms triangle candidates. It tracks the closest hit in shader code to reproduce deterministic CPU tie order. Vulkan's normal closest-hit process shortens the ray interval after a confirmed hit; this implementation prevents that mechanism. It also computes hit attributes during traversal for successively nearer candidates. [Vulkan traversal specification](https://docs.vulkan.org/spec/latest/chapters/raytraversal.html)

Benchmark a committed closest-hit path with opaque geometry where valid, and an any-occluder path for shadows. Shadows already return at the first acceptable candidate, so the same missed-pruning diagnosis does not apply equally to them. Keep specialized candidate filtering where source-face rejection or actual cutout materials require it. NVIDIA likewise recommends opaque geometry to avoid unnecessary shader interruptions during traversal. [RTX best practices](https://developer.nvidia.com/blog/best-practices-using-nvidia-rtx-ray-tracing/)

This is a high-priority hypothesis because it affects every primary and continuation ray. The magnitude is unknown. Exact tie selection on coincident triangles can change, so validate touching faces, seams, thin leaves, glass and self-intersections. Preserve physical visibility and stable object IDs without forcing every production ray to duplicate reference traversal order.

The same separation should cover numeric precision. The current GPU path stores double-precision running means and uses double arithmetic in display conversion for CPU rounding parity. Test bounded FP32 real-time histories and ordinary GPU color conversion, retaining precise long-run accumulation in reference mode. Do not attribute all GPU cost to these operations without profiling, and do not silently reduce the reference sample limit.

### 2. Build motion-aware lighting reconstruction

The GPU renderer needs persistent history across camera movement and moving enemies. Add surface data for each rendered view: depth, normal, material properties, stable object identity and motion from both the camera and objects. Motion vectors identify where the same surface appeared in the preceding frame. Retain previous transforms and invalidate locally when visibility or lighting changes; avoid treating a moving rifle as a reason to discard the entire image's useful lighting.

Separate noisy diffuse and specular lighting from surface color where the chosen denoiser requires it. This lets the system smooth lighting while retaining texture detail. NVIDIA's NRD uses surface guides and supports Vulkan integration; adopting it still requires these inputs and host integration. It is not a filter that can be dropped onto the current final RGB buffer. [NRD integration documentation](https://github.com/NVIDIA-RTX/NRD)

Compare two coherent reconstruction stacks: NRD plus a temporal upscaler, and DLSS Ray Reconstruction with its required guide buffers and reconstruction path. Do not simply stack both denoisers. Upscaling combines lower-resolution images with history and motion to reconstruct the output; it does not make arbitrarily tiny inputs equivalent to native rendering. [DLSS developer overview](https://developer.nvidia.com/rtx/dlss), [FSR temporal integration](https://gpuopen.com/manuals/fidelityfx_sdk/techniques/super-resolution-temporal/)

Glass scopes, moving weapons, thin vegetation and finite aperture need explicit treatment. First-surface motion alone does not describe a scene viewed through refraction. Use appropriate layers/guides or deliberately simplify those effects in the performance preset. A pinhole gameplay preset is a useful first comparison against the current aperture of 0.02. Denoising cost must be included in the 8.33 ms budget; its performance value comes from allowing fewer lighting paths at acceptable quality.

The earlier CPU temporal experiments do not settle this decision. They added conservative guide queries and image processing to paths that still often traced one fresh sample per pixel. The proposed GPU pipeline must obtain useful guides efficiently and actually reduce lighting work or improve equal-time quality.

### 3. Preserve visibility detail while reducing lighting work

Distinguish three resolutions: output pixels, visible-surface detail, and lighting samples. The current whole-grid budget reduces all of them together. A more useful FPS tradeoff is sharp geometry and textures with approximate indirect lighting.

Evaluate a hybrid renderer: rasterize the first visible opaque surface into a geometry buffer, then ray trace shadows, selected reflections/refractions and sparse indirect illumination. An alternative keeps a cheap full-resolution primary-ray pass and traces fewer continuations. Rasterization can preserve enemy and foliage silhouettes efficiently, while the path tracer still supplies the lighting that matters. Neither option automatically solves glass or thin translucent materials.

Prefer this hybrid experiment for the stated competitive responsiveness target. Retain a fully path-traced option if that is an important product goal. The current NVIDIA RTXPT project provides both a pure path tracer and an introductory deferred-rendering route, showing that these are legitimate alternative architectures. [RTXPT implementation](https://github.com/NVIDIA-RTX/RTXPT)

Keep mild center-detail sampling as an optional further tradeoff. Fixed screen-center allocation is not eye tracking, and peripheral enemy visibility matters in an FPS. It should follow reliable reconstruction rather than substitute for it.

### 4. Reuse indirect lighting in world space

Repeatedly tracing long diffuse paths through the same forest spends work rediscovering lighting that changes slowly. A radiance cache stores a spatial estimate of that lighting. Sparse update paths refresh it; visible paths can stop after a short prefix and use the cached estimate for the remaining indirect contribution.

Prototype a spatial hash radiance cache or probe-based diffuse lighting before a more complex neural cache. SHaRC provides an existing update/resolve/query design for this purpose. Its update and resolve passes have costs, and limited spatial detail can cause light leaking or delayed response. Dynamic enemies, sun changes and newly exposed areas need tests. [SHaRC integration guide](https://raw.githubusercontent.com/NVIDIA-RTX/RTXGI/main/Docs/SharcGuide.md)

Treat diffuse bounces and glass transmission separately. The current shooter enables depth of at least eight; reducing a single global depth can break a scope that needs several boundary crossings. A performance preset can allow enough glass events while limiting or caching diffuse continuation. This is a more useful tradeoff than shrinking every object on screen.

### 5. Replace repeated atmosphere integration with reusable approximations

The Full atmosphere path calls a 32-step atmosphere integral along path segments, including continuations. Optical-depth tables already exist, and the repository has coarse primary fog and lighting-grid experiments; atmosphere optimization therefore needs to extend existing work rather than assume no caching exists.

Evaluate a sky-radiance lookup table, a camera-relative aerial-perspective volume and cheaper secondary-ray atmosphere queries. These approximate the integrated light and extinction, avoiding repeated full integration where appropriate. Preserve a high-quality reference for sunsets, horizons, shafts and glass views. Hillaire's production atmosphere method and Unreal's implementation use such tables to evaluate expensive components at lower resolution. [Atmosphere paper](https://sebh.github.io/publications/egsr2020.pdf), [Unreal sky atmosphere](https://dev.epicgames.com/documentation/unreal-engine/sky-atmosphere-component-in-unreal-engine)

This is especially relevant to the current outdoor game. Measure atmosphere, surface tracing and local fog separately; switching all atmosphere off is a diagnostic experiment, not the proposed final appearance.

### 6. Make GPU submission and presentation a frame pipeline

[VulkanDevice.submit()](../../src/gpu/VulkanDevice.java) waits for a fence after every submission. Scene builds and trace/display work therefore contain host round trips. The native presenter already exists but remains experimental. The Swing path still reads back packed scene pixels, while native presentation removes routine scene-image readback.

Build a bounded GPU frame pipeline with explicit dependencies between scene preparation, visibility, lighting, reconstruction and presentation. Keep scene images and histories on the GPU, overlap independent preparation where profitable, and pace work against the latest camera. Limit queued frames so throughput does not improve at the cost of input latency. Measure visible-window presentation before promoting the native path.

Do not blame a hard 60 Hz repaint cap: [GamePanel](../../src/game/GamePanel.java) already requests repaint on image completion as well as using its 16 ms UI timer. Diagnose actual scheduling gaps and stalls.

[GpuScene](../../src/gpu/GpuScene.java) reuses mesh structures and top-level build storage but still rebuilds the top-level hierarchy when instances move. Test instance packing, submission batching and update/refit versus rebuild with traversal quality included. A full rebuild is not inherently wrong: RTXPT also rebuilds its top-level hierarchy each frame. [RTXPT design](https://raw.githubusercontent.com/NVIDIA-RTX/RTXPT/main/Docs/Design.md)

## Further techniques worth evaluating

| Technique | Current gap and likely value | Priority |
| --- | --- | --- |
| Better sample sequences | GPU paths use hashed pseudorandom draws. Test scrambled low-discrepancy samples and appropriate spatiotemporal blue-noise domains with the chosen reconstruction. Keep frame/sample sequencing separate from stationary accumulation resets. | Alongside reconstruction; quality per ray, not a guaranteed tracing speedup. |
| Russian roulette | The GPU continues paths until miss, exact zero weight or the depth cap. Probabilistically terminate low-contribution paths and compensate surviving weights. This reduces expected work but increases variance and may leave divergent GPU lanes idle. | Early controlled experiment, preserving glass weights and reference brightness. |
| Texture and geometry filtering | Surface textures use nearest texel lookup without mip selection. Ray-footprint-aware mipmaps, filtered textures and distance-dependent geometry detail can reduce shimmer and traversal cost. | High quality value for foliage and distant terrain; test thin silhouettes. |
| Environment/light importance sampling | The game explicitly samples its sun, while continuation rays sample the material. Better sampling of bright sky regions can reduce noise. | Before a complex many-light system for the current sun-dominated scene. |
| ReSTIR | Reuses selected lighting samples between nearby pixels and frames. Direct-light reuse matters more with many lights; GI/path variants could help vegetation and indirect lighting but add storage, validation and passes. | Evaluate after motion guides, denoising and basic sampling; not a universal first fix. |
| Wavefront execution and material specialization | Separating path stages can improve GPU occupancy but introduces queues, dispatches and memory traffic. | Choose using shader occupancy, register and stall measurements. A single large shader is not automatically the wrong design. |

Sources for these technique comparisons: [GPU path-tracing tradeoffs in PBRT](https://www.pbr-book.org/4ed/Wavefront_Rendering_on_GPUs/Mapping_Path_Tracing_to_the_GPU), [spatiotemporal sampling](https://developer.nvidia.com/blog/rendering-in-real-time-with-spatiotemporal-blue-noise-textures-part-1/), [RTXPT features](https://github.com/NVIDIA-RTX/RTXPT), and [RTXDI direct, indirect and path reuse](https://raw.githubusercontent.com/NVIDIA-RTX/RTXDI/main/README.md). RTXPT's current design uses a unified tracer and selects different lighting strategies with different denoisers; copying every available feature would not reproduce its architecture. Hardware-specific execution reordering is optional and must not be counted as an assumed RTX 3080 gain.

## Recommended order and decision points

1. **Establish the live 120 FPS baseline.** Record the current forest/combat route at fixed 1080p and under the existing budget. Include rapid turns, walking, aiming through glass, firing, enemy movement and atmosphere. Collect GPU timestamps by pass, fresh presented-frame intervals, actual dimensions and camera age. Use GPU profiling for shader stalls and CPU profiling for scheduling; the existing Java Flight Recorder recording cannot explain GPU instruction cost by itself.
2. **Test efficient GPU primitives.** Compare committed traversal, real-time precision and atmosphere approximations independently. Retain only improvements with valid visibility and acceptable error. This determines how much raw budget remains before reconstruction.
3. **Build one useful reconstruction slice.** Produce surface/motion guides, denoise diffuse lighting and reconstruct 1080p output from a fixed lower-resolution input. Compare that with native visibility plus sparse lighting. Judge moving vegetation and enemies, not just stationary screenshots. Select the denoising stack using total cost and artifacts on the RTX 3080.
4. **Add cached indirect illumination and finish native frame pacing.** These become part of the selected pipeline, with budgeted update work and bounded history. Then tune automatic quality using separate lighting, atmosphere and resolution controls.
5. **Choose the extent of replacement from those results.** If integration and frame ownership dominate, replace the renderer backend with a native C++/Vulkan or D3D12 module, potentially borrowing an established sample framework. Java can retain game logic, controls and content. Rewriting the game language alone will not change GPU path cost. If a custom renderer stops being a goal, moving the sample game to an established engine is the broader alternative, with significant gameplay/content migration.

The preferred direction is a hybrid real-time game renderer with sharp visibility, short or cached lighting paths, temporal reconstruction and direct GPU presentation. A pure path-tracing architecture remains viable as an experiment, but is a higher-risk route to this specific latency target. No existing measurement establishes either route at 120 FPS in this forest.

For acceptance, propose three runs of a repeatable 60-second visible-window route after loading and warmup. Each run must average at least 120 fresh rendered presentations per second, calculated as distinct rendered images presented during the measured window divided by its actual elapsed seconds. Also require a 95th-percentile fresh-frame interval at or below 8.33 ms; this pacing check does not replace the average-rate check. Report the 99th percentile, stalls and camera age separately. Generated frames and repeated presentations remain separate counters.

Before adaptive-quality acceptance runs, the developer should provide matched motion clips at several fixed visibility resolutions and reconstruction settings. The player chooses the lowest acceptable detail, considering enemy recognition, leaf silhouettes and scope readability. Record that resolution and those settings in the benchmark configuration as a floor shared by every comparison; the controller must not cross it to meet the FPS target. Until that choice is made, report fixed-resolution tradeoffs without claiming quality acceptance. Compare against converged reference images and inspect motion for ghosting, newly exposed surfaces, brightness shifts and scope artifacts.

The older CPU-first priority order in [PerformanceRequirements.md](../../PerformanceRequirements.md) should be revised when an experiment is selected. Its correctness reference remains valuable, while exact traversal ties, precise display rounding and full per-segment atmosphere need not dictate every operation of a separately validated real-time mode.
