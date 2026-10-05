# FAQ

Knowledge base for humans. Only humans put terms or ask questions here. Both humans and LLMs can contribute to answers.
If a term or question is not followed by a definition (`TN: <definition>` / `QN <question>\nAN<answer>`),
another human or LLM is welcome and encouraged to answer it.
Answers should be concise, and will sometimes trigger other questions.
This is okay - threads are allowed, which is why questions and terms are enumerated.

## Glossary

T1 RGB resampling

T1: Changing an image's pixel grid while processing its red, green, and blue light values. For example, converting a 1600×1000 traced image to a 1440×900 display combines overlapping source pixels; enlarging a small image distributes its values over more display pixels. It does not trace new rays or recover missing detail.

T2 Tone mapping

T2: Compressing scene brightness into the range a display can show. Our renderer uses Reinhard mapping, `c / (1 + c)`, after exposure: a linear value of 1 becomes 0.5, and 9 becomes 0.9. This preserves differences between bright values instead of clipping all of them to white. Encoding the result as sRGB is a separate step.

T3 Resampling indices and weights

T3: Indices identify which source pixels contribute to a destination pixel; weights say how much each contributes. A destination pixel might receive 25% of source A and 75% of source B. With our fixed box filter, these relationships depend on grid dimensions, not image content, so we can calculate them once when the resolution changes.

T4 Linear-space filtering

T4: Averaging values that are proportional to light, before tone mapping and sRGB encoding. Equal contributions from linear black (0) and white (1) average to 0.5. Averaging their encoded display values instead produces a different, generally darker result. In our pipeline, resampling therefore operates on linear radiance.

T5 Near-first traversal

T5: When a ray could enter both branches of a BVH, test the nearer bounding box's branch first. Finding a close surface lets us skip farther boxes that cannot contain a closer hit. Box order is only a prediction: we still test any other branch that could beat the current hit.

T6 Surface-area-heuristic BVH construction

T6: Choosing how to split geometry by estimating the resulting ray-test cost. Smaller child bounding-box surface areas generally imply fewer rays entering them; primitive counts estimate the work inside. We compare candidate splits using both, rather than simply dividing the primitive count in half. It costs more to build but can produce a faster tree to traverse.

T7 disocclusion

T7: A previously hidden surface becoming visible, such as the wall revealed when you move past a sphere. The previous image has no valid sample of that newly visible patch, so temporal reuse must reject unrelated old pixels there and gather new information.

T8 MIS

T8: Multiple importance sampling combines different ways of finding light paths, such as aiming toward a light and sampling a material's scattering direction. Probability-based weights favor the technique suited to that path and prevent double-counting their estimates. Our surface renderer already uses it for area lights. [Explanation](https://pbr-book.org/4ed/Light_Transport_I_Surface_Reflection/A_Better_Path_Tracer).

T9 continuation

T9: Following a path beyond its current interaction: another reflection, refraction, or volume-scattering direction. The camera ray begins the path; continuation rays extend it. Shadow rays separately ask whether a light is visible and do not consume continuation depth. In this renderer, glass entry and exit can each consume one continuation.

## Q&A

Q1 Can you explain what the "hit records, sampler, medium stack, visibility scratch, and counters" are that we are giving each parallel worker thread?

A1: They are reusable working memory for tracing a path:

- **Hit record:** the intersected object/face, position, distance, surface normal, and whether we hit its front or back.
- **Sampler:** the current pseudorandom-number generator state used to choose ray positions and directions.
- **Medium stack:** the nested interiors currently containing the path, such as air → glass → an inner solid. The innermost medium determines IOR, absorption, and scattering.
- **Visibility scratch:** temporary hit records, crossed-media lists, distances, and transmission values used when testing a connection to a light.
- **Counters:** accumulated statistics such as ray counts, primitive tests, and glass transmission events.

Each worker reuses its own storage across paths. Sharing mutable storage would let threads overwrite one another; allocating it for every ray would create unnecessary garbage. Immutable scene geometry can still be shared.

Q2 Can you explain the "seed/pixel/sample-based random stream"?

A2: Each path starts a reproducible pseudorandom sequence from three identifiers: the chosen seed, the pixel's index (`y * width + x`), and that pixel's sample number. The sequence supplies choices such as subpixel jitter and bounce directions. The same three identifiers produce the same choices, regardless of which worker runs the path or when it runs. One path taking extra bounces does not consume another path's random numbers. This makes parallel scheduling and different batch sizes reproducible, provided the sampling algorithm stays the same. Changing resolution changes pixel indexing and starts new accumulation.

Q3 why is there a monitor around state in Viewport.java? is there parallelization around that component?

A3: There is concurrency already: AWT delivers input on its event thread while the rendering loop reads and updates scene state on another thread. `synchronized (state)` prevents an input edit from changing the camera or scene halfway through rendering and makes updates visible between threads. It does not parallelize tracing. The lock currently covers the whole viewport render, so input waits for expensive work. Immutable snapshots would preserve consistency while allowing rendering and input to proceed independently.

Q4 why does the tracer have a budget if it's only checked after it's done?

A4: One `trace()` call can perform several complete image samples, according to `samplesPerFrame`. The 50 ms check runs after each sample, before starting the next. If a sample takes 10 ms, it can stop a large batch after roughly five samples. If the first sample takes 600 ms, it cannot interrupt it; with one sample per frame it saves no work. It is a soft batch limit, not a frame deadline. Tile-level checks would provide smaller interruption points. The state lock is released only after the remaining viewport work finishes too.

Q5 is how does rendering snapshots asynchronously "enable automatic resolution reduction during movement and refinement when stationary"

A5: It makes the policy responsive; it does not implement the policy itself. Input can mark a camera change immediately, cancel stale work, and request a new snapshot with a smaller sensor grid. A separate controller detects movement, chooses resolution, and restores the requested grid after movement settles. While stationary, we accumulate more samples to reduce noise. A resolution change restarts raw accumulation. We could implement automatic resolution with the current synchronous renderer, but reacting would still wait for the current expensive frame to finish.

Q6 what is top-level BVH over objects and how is it different from how we're using a BVH now?

A6: Currently, a ray checks each object's bounds in a flat list. For an object with enough primitives, it then uses that object's BVH to find relevant faces. A top-level BVH groups the objects themselves: missing a group box can skip many objects at once. Think of first finding the relevant buildings, then the relevant rooms inside each building. With only a few objects, the extra hierarchy may cost more than a short linear scan.

Q7 can you explain the Russian roulette technique, specifically how it is applied to path tracing? how do you know if a path is low-contribution? how do you compensate for other paths?

A7: Track **throughput**, the running RGB multiplier for light found farther along the path. Small throughput suggests low future contribution, but cannot predict an unusually bright light. After a few bounces, choose a survival probability `p` from throughput. Stop with probability `1 - p`; otherwise divide future throughput by `p`. With `p = 0.2`, surviving paths carry 5× the weight: `0.2 × 5 = 1` on average. Already collected light is unchanged. We do not modify other paths; compensation occurs statistically across repeated samples. This can save work but increase noise. Our renderer does not implement it yet. [Explanation](https://pbr-book.org/4ed/Light_Transport_I_Surface_Reflection/A_Better_Path_Tracer).

Q8 what are IOR-aware roulette weights in the context of Russian roulette tracing?

A8: IOR means index of refraction. Our glass transmission weight includes `(incidentIOR / exitIOR)²`: entering IOR 1.5 glass from air multiplies throughput by about 0.444; exiting reverses that factor. This temporary reduction is not absorption. For roulette decisions, track a compensating product of `(exitIOR / incidentIOR)²` across transmissions, so glass paths are not needlessly killed inside. Keep the physical throughput unchanged except for roulette survival compensation. This is proposed work; the transmission factors already exist in [Material.java](src/scenes/viewport/Material.java).

Q9 in the Adaptive sampling technique, how could you know that a pixel is noisy to spend more time on it? conversely, how can you know that a region is converged? don't you send a uniform # of rays per pixel? or is this some heuristic, like how much is a pixel changing per sample with no scene changes, and keep rendering the ones with high variance?

A9: Yes, that is the idea, with statistical safeguards. Today we send the same number of primary path samples per pixel, although paths can use different numbers of rays. Adaptive sampling would track each pixel's sample count, mean, and variance. For independent samples, uncertainty in the mean is estimated by `sqrt(variance / count)`. Pixels above an error threshold get more work; quieter ones get less. This estimates convergence rather than proving it: several dark samples can miss a rare bright path. Require a minimum sample count and periodic revisits, and validate the stopping policy for bias. Scene changes invalidate the statistics. Looking only at the latest change in the average is unreliable because that change naturally shrinks as the count grows.

Q10 what is "stratified or low-discrepancy sampling"? how does it reduce noise?

A10: Independent random samples can accidentally cluster, leaving gaps. **Stratified sampling** divides a domain into cells and samples each: for 16 pixel samples, choose one random position in each cell of a 4×4 grid. **Low-discrepancy sequences** such as Sobol or Halton distribute samples more evenly across many regions and sample counts; scrambling adds controlled randomness. Better coverage often reduces error for the same number of paths. This applies to light positions and scattering choices as well as pixel positions. It does not guarantee improvement for every scene, especially across discontinuities. [Stratification](https://pbr-book.org/4ed/Sampling_and_Reconstruction/Stratified_Sampler), [low-discrepancy sampling](https://pbr-book.org/4ed/Sampling_and_Reconstruction/Halton_Sampler).

Q11 what is Temporal reuse and denoising?

A11: **Temporal reuse** uses information from earlier frames. Our stationary accumulation already averages past samples at an unchanged camera; the proposed extension maps useful history into a changed view and rejects history that no longer matches. **Denoising** estimates a cleaner image from noisy samples, often using nearby pixels plus depth, normals, and surface color to avoid blurring across boundaries. It can also use temporal history. Both can make a low-sample image more useful, but can introduce blur or ghosting. Glass and mirrors are difficult because the first visible surface does not identify the reflected or refracted scene. Keep raw samples separate and provide a raw-image view.
