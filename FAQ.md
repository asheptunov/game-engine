# FAQ

Knowledge base for humans. Only humans ask questions here. Both humans and LLMs can contribute to answers.
Question format is as follows:
```
QN: <question>

AN: <answer>
```
If a question is not followed by an answer, another human or LLM is welcome and encouraged to answer it.
Answers should be concise, and will sometimes trigger other questions. This is okay - threads are allowed,
which is why questions are enumerated. A follow-up question will follow a recursive numbering scheme `QX.Y.Z...`,
and may be answered like any other question, with the benefit of that thread's surrounding context.

## Q&A

Q1: Define RGB resampling

A1: Changing an image's pixel grid while processing its red, green, and blue light values. For example, converting a 1600×1000 traced image to a 1440×900 display combines overlapping source pixels; enlarging a small image distributes its values over more display pixels. It does not trace new rays or recover missing detail.

Q2: Define tone mapping

A2: Compressing scene brightness into the range a display can show. Our renderer uses Reinhard mapping, `c / (1 + c)`, after exposure: a linear value of 1 becomes 0.5, and 9 becomes 0.9. This preserves differences between bright values instead of clipping all of them to white. Encoding the result as sRGB is a separate step.

Q3: Define resampling indices and weights

A3: Indices identify which source pixels contribute to a destination pixel; weights say how much each contributes. A destination pixel might receive 25% of source A and 75% of source B. With our fixed box filter, these relationships depend on grid dimensions, not image content, so we can calculate them once when the resolution changes.

Q4: Define linear-space filtering

A4: Averaging values that are proportional to light, before tone mapping and sRGB encoding. Equal contributions from linear black (0) and white (1) average to 0.5. Averaging their encoded display values instead produces a different, generally darker result. In our pipeline, resampling therefore operates on linear radiance.

Q5: Define near-first traversal

A5: When a ray could enter both branches of a BVH, test the nearer bounding box's branch first. Finding a close surface lets us skip farther boxes that cannot contain a closer hit. Box order is only a prediction: we still test any other branch that could beat the current hit.

Q6: Define surface-area-heuristic BVH construction

A6: Choosing how to split geometry by estimating the resulting ray-test cost. Smaller child bounding-box surface areas generally imply fewer rays entering them; primitive counts estimate the work inside. We compare candidate splits using both, rather than simply dividing the primitive count in half. It costs more to build but can produce a faster tree to traverse.

Q7: Define disocclusion

A8: A previously hidden surface becoming visible, such as the wall revealed when you move past a sphere. The previous image has no valid sample of that newly visible patch, so temporal reuse must reject unrelated old pixels there and gather new information.

Q9: Define MIS

A9: Multiple importance sampling combines different ways of finding light paths, such as aiming toward a light and sampling a material's scattering direction. Probability-based weights favor the technique suited to that path and prevent double-counting their estimates. Our surface renderer already uses it for area lights. [Explanation](https://pbr-book.org/4ed/Light_Transport_I_Surface_Reflection/A_Better_Path_Tracer).

Q10: Define continuation

A10: Following a path beyond its current interaction: another reflection, refraction, or volume-scattering direction. The camera ray begins the path; continuation rays extend it. Shadow rays separately ask whether a light is visible and do not consume continuation depth. In this renderer, glass entry and exit can each consume one continuation.

Q11: Can you explain what the "hit records, sampler, medium stack, visibility scratch, and counters" are that we are giving each parallel worker thread?

A11: They are reusable working memory for tracing a path:

- **Hit record:** the intersected object/face, position, distance, surface normal, and whether we hit its front or back.
- **Sampler:** the current pseudorandom-number generator state used to choose ray positions and directions.
- **Medium stack:** the nested interiors currently containing the path, such as air → glass → an inner solid. The innermost medium determines IOR, absorption, and scattering.
- **Visibility scratch:** temporary hit records, crossed-media lists, distances, and transmission values used when testing a connection to a light.
- **Counters:** accumulated statistics such as ray counts, primitive tests, and glass transmission events.

Each worker reuses its own storage across paths. Sharing mutable storage would let threads overwrite one another; allocating it for every ray would create unnecessary garbage. Immutable scene geometry can still be shared.

Q12: Can you explain the "seed/pixel/sample-based random stream"?

A12: Each camera path starts a reproducible pseudorandom sequence from the chosen seed, captured camera position/sensor, pixel index (`y * width + x`), and sample number. These supply choices such as subpixel jitter and bounce directions. We now mix the camera into the seed so movement changes the noise instead of repeatedly using the same screen-anchored sample-zero grain. An identical captured view/seed/grid/sample reproduces the same choices regardless of workers, scheduling or earlier camera visits. Stationary samples advance normally and average away noise; cached redraws do not introduce new grain. One path taking extra bounces does not consume another path's random numbers. Resolution changes pixel indexing and restarts accumulation. The ray-level numeric test adapter retains its camera-independent stream.

Q13: why is there a monitor around state in Viewport.java? is there parallelization around that component?

A13: AWT delivers input on its event thread while the display loop and trace coordinator run on other threads. The monitor protects brief edits, snapshot capture, and publication of matching sample counts. P2 performs tracing and image conversion outside that lock, so input can proceed while workers trace. Workers use a fixed snapshot. Camera motion lets the active pass finish as a coherent, slightly older preview; the next job captures the latest camera. Scene or resolution edits still cancel between tiles. Preview samples do not count toward a different camera's accumulation. The display leases a completed image that remains immutable until the lease is released, and console drawing snapshots its text before drawing.

Q14: why does the tracer have a budget if it's only checked after it's done?

A14: The 50 ms limit still stops a batch between complete image samples. P2 additionally checks cancellation and a 16.67 ms work-slice budget between tiles. Workers finish their current tiles, then return; the coordinator can schedule another slice of the same unfinished pass. A complete pass may span many slices, and a long tile can exceed the slice budget. Camera motion ends the batch after its active pass completes, allowing previews to update during sustained movement; cancelling every mouse event would prevent any pass from finishing. Scene/resolution edits and pause still cancel incomplete passes. These are cooperative limits, not a promise of a fresh image every 16.67 ms; input no longer waits on the pass or image conversion.

Q15: is how does rendering snapshots asynchronously "enable automatic resolution reduction during movement and refinement when stationary"

A15: It lets input and display continue while an expensive pass finishes. P4 now adds the separate policy: `view interactive on` chooses a smaller grid during camera movement using measured completed-image cost, then restores the requested grid after 350 ms without camera changes. Grid changes wait for completed jobs so continuous movement still publishes coherent previews. Each grid starts its own accumulation; stationary rendering then adds samples to reduce noise. Path depth and camera field of view remain unchanged. Automatic mode is off by default, and F3 shows the sampled dimensions alongside FPS. See [InteractiveResolution.java](src/scenes/viewport/InteractiveResolution.java).

Q16: what is top-level BVH over objects and how is it different from how we're using a BVH now?

A16: Currently, a ray checks each object's bounds in a flat list. For an object with enough primitives, it then uses that object's BVH to find relevant faces. A top-level BVH groups the objects themselves: missing a group box can skip many objects at once. Think of first finding the relevant buildings, then the relevant rooms inside each building. With only a few objects, the extra hierarchy may cost more than a short linear scan.

Q17: can you explain the Russian roulette technique, specifically how it is applied to path tracing? how do you know if a path is low-contribution? how do you compensate for other paths?

A17: Track **throughput**, the running RGB multiplier for light found farther along the path. Small throughput suggests low future contribution, but cannot predict an unusually bright light. After a few bounces, choose a survival probability `p` from throughput. Stop with probability `1 - p`; otherwise divide future throughput by `p`. With `p = 0.2`, surviving paths carry 5× the weight: `0.2 × 5 = 1` on average. Already collected light is unchanged. We do not modify other paths; compensation occurs statistically across repeated samples. This can save work but increase noise. Our renderer does not implement it yet. [Explanation](https://pbr-book.org/4ed/Light_Transport_I_Surface_Reflection/A_Better_Path_Tracer).

Q18: what are IOR-aware roulette weights in the context of Russian roulette tracing?

A18: IOR means index of refraction. Our glass transmission weight includes `(incidentIOR / exitIOR)²`: entering IOR 1.5 glass from air multiplies throughput by about 0.444; exiting reverses that factor. This temporary reduction is not absorption. For roulette decisions, track a compensating product of `(exitIOR / incidentIOR)²` across transmissions, so glass paths are not needlessly killed inside. Keep the physical throughput unchanged except for roulette survival compensation. This is proposed work; the transmission factors already exist in [Material.java](src/scenes/viewport/Material.java).

Q19: in the Adaptive sampling technique, how could you know that a pixel is noisy to spend more time on it? conversely, how can you know that a region is converged? don't you send a uniform # of rays per pixel? or is this some heuristic, like how much is a pixel changing per sample with no scene changes, and keep rendering the ones with high variance?

A19: Yes, that is the idea, with statistical safeguards. Today we send the same number of primary path samples per pixel, although paths can use different numbers of rays. Adaptive sampling would track each pixel's sample count, mean, and variance. For independent samples, uncertainty in the mean is estimated by `sqrt(variance / count)`. Pixels above an error threshold get more work; quieter ones get less. This estimates convergence rather than proving it: several dark samples can miss a rare bright path. Require a minimum sample count and periodic revisits, and validate the stopping policy for bias. Scene changes invalidate the statistics. Looking only at the latest change in the average is unreliable because that change naturally shrinks as the count grows.

Q20: what is "stratified or low-discrepancy sampling"? how does it reduce noise?

A20: Independent random samples can accidentally cluster, leaving gaps. **Stratified sampling** divides a domain into cells and samples each: for 16 pixel samples, choose one random position in each cell of a 4×4 grid. **Low-discrepancy sequences** such as Sobol or Halton distribute samples more evenly across many regions and sample counts; scrambling adds controlled randomness. Better coverage often reduces error for the same number of paths. This applies to light positions and scattering choices as well as pixel positions. It does not guarantee improvement for every scene, especially across discontinuities. [Stratification](https://pbr-book.org/4ed/Sampling_and_Reconstruction/Stratified_Sampler), [low-discrepancy sampling](https://pbr-book.org/4ed/Sampling_and_Reconstruction/Halton_Sampler).

Q21: what is Temporal reuse and denoising?

A21: **Temporal reuse** uses information from earlier frames. P5 now adds optional `view temporal on`: map previous diffuse-surface information into a changed camera view, validate depth/normals/surface identity, then blend it with fresh radiance. Edges, cuts, scene edits, mirrors/glass and volumes reject history. It remains separate from raw accumulation; `view temporal off` immediately displays raw output. F3's history blend percentage describes reused presentation weight, not extra raw spp or a probability of correctness. **Denoising**, still proposed in P6, estimates a cleaner image using neighboring pixels and surface information. Both can reduce noise but introduce blur or ghosting, and their processing cost can outweigh the benefit. See [P5 measurements](benchmarks/p5/README.md).

Q22: Define integer enlargement

A22: Enlarging by a whole-number factor in both dimensions, such as 360×225 to 1440×900 at 4×. Each source pixel covers a 4×4 display block. With our box filter, all sixteen display pixels receive the same source value, so we can encode that value once and copy it across the block. P3 implements this in `DisplayConverter`.

Q23: Define "filter linear radiance"

A23: Combine neighboring light values while they are still proportional to physical brightness, before tone mapping and sRGB encoding. For example, equal contributions of radiance 0 and 4 average to 2; then we tone-map 2. Tone-mapping the inputs first and averaging their display values produces a different result. Both the reference resampler and P3's fused display converter filter linear radiance before mapping and encoding.
