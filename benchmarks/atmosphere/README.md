# A1: global scattering atmosphere

Status: Implemented and automatically verified; native playthrough and visual acceptance remain pending.
Audience: Developers reproducing the measurements and players evaluating the sample game's outdoor lighting.

## Try it

Run `./game.ps1 -Scene world -Backend gpu -Width 480 -Height 300` from the repository root.
Open **Lighting**, enable **Atmosphere enabled**, and keep exposure fixed at 1 initially.
Pause the clock and compare hours 12, 17.8 and 18.2. Turn toward the sun as well as away from it.
The atmosphere follows the clock even when it is paused. Its sun remains below the horizon during twilight;
the moon has a separate direction and illumination. Run the clock across 6 and 18 to inspect both transitions.

**Clear air / reset atmosphere** enables the default air. **Hazy morning air (keeps time)** enables a
low aerosol layer; set time to 6.3 separately and fly upward to leave the layer. Neither button changes
camera, exposure, path depth, clock time or scene geometry. Camera reset retains air settings.
Uncheck **Atmosphere enabled** to restore the legacy gradient/disc lighting, including saved manual lighting
edits. The lower Sun/Sky controls edit that legacy lighting; they do not recolor the atmospheric sun.
Atmosphere is off at startup. CPU is selected explicitly with `-Backend cpu`; there is no automatic fallback.

A1 supplies global haze. Local fog banks, procedural density variation and geometry-shaped light shafts
remain A2 work. A tree casts a surface shadow now, but does not cast a volumetric air shadow in A1.

## Inputs and units

`engine.Atmosphere.Parameters` is immutable and validated before application. `Sky.atmosphere` carries it
with a separate signed solar direction and top-of-atmosphere RGB irradiance. The game uses irradiance
(3, 3, 3), independent of time. Color changes emerge from transport rather than a sunset color preset.

| Input | Default / exposed range | Meaning |
| --- | --- | --- |
| World mapping | 20 metres per scene unit; ground reference Y = -2 | Local +Y is altitude; independent of mesh dimensions |
| Planet / air extent | Radius 6371 km; air extends 80 km above ground | Curved horizon and planet occlusion of sunlight |
| Molecular scattering | (0.0058, 0.0135, 0.0331) per km at ground | Rayleigh phase; density falls exponentially with 8 km scale height |
| Molecular density control | 1; 0..4 | Multiplies molecular scattering, retaining independent absorption |
| Aerosol scattering | (0.004, 0.004, 0.004) per km at ground | Henyey–Greenstein phase with direction parameter g = 0.76 |
| Aerosol density control | 1; 0..10 | Multiplies aerosol scattering |
| Aerosol scale height control | 1.2 km; 0.1..5 km | Exponential vertical falloff; hazy preset uses scale 8 and height 0.35 km |
| RGB absorption control | (0.0003, 0.0006, 0.0001) per km; each channel 0..0.2 | Removes light; follows the molecular altitude profile |
| Solar disc | Angular radius 0.266 degrees | Irradiance divided by projected solid angle gives disc radiance |

The engine also validates mapping 0.1..1000 metres/unit, ground Y -10000..10000, radius 1000..10000 km,
extent 20..200 km, molecular height 1..20 km, each coefficient 0..0.2 per km, g -0.9..0.9 and solar
irradiance 0..100 per channel. These advanced inputs are available through the API, not additional game widgets.
Invalid/NaN edits throw an explanatory exception without replacing active inputs. Set both scattering scales
and absorption to zero for vacuum: transmission is exactly 1 and scattered radiance exactly 0; the sun and
separate night background still exist. Disabled atmosphere additionally restores the artistic daytime sky.

This is a local world model. Each ray starts at altitude derived from Y and follows a spherical column;
horizontal world positions share the local vertical. Origins below the reference ground or above the air
extent clamp to those altitude limits. This is not an orbital or planet-wide navigation model.

## Transport and energy accounting

`AtmosphereTransport` integrates 32 quadratically spaced view intervals, resolving nearby dense air.
Each interval integrates constant local extinction analytically, using a stable small-optical-depth formula.
The molecular and aerosol densities contribute separately to RGB extinction and their angular scattering
functions. Sunlight traverses the same air before illuminating a surface or scattering toward the camera.
The planet blocks sunlight, and the finite solar disc's visible area smooths direct illumination through
sunrise/sunset. Scene geometry still provides ordinary solar surface shadows.

For every camera or diffuse continuation segment, the tracer adds in-scattered light weighted by the
incoming path throughput, then multiplies throughput by segment transmittance. It subsequently evaluates
the surface or background. This implements `observed = transmission * farther radiance + scattered radiance`
without applying extinction twice. Diffuse paths sample sky illumination through the existing cosine-weighted
surface sampler. There is no added surface ambient term. The visible solar disc contributes only on camera
escape rays; direct sunlight accounts for its energy at surfaces. Additional directional light, including
the moon, is attenuated by the same air. The game retains a fading artistic night background and moon disc;
it adds no artistic daytime gradient when atmosphere is enabled.

Multiple scattering uses an explicitly approximate two-stream closure. An upper layer one molecular scale
height above each integration point intercepts attenuated sunlight. Its isotropic returning contribution
uses local scattering albedo (scattering/extinction), vertical optical depth, downward attenuation and the
geometric gain `1 / (1 - albedo/2)`. This sums idealized successive isotropic orders with half escaping each
order. It vanishes with zero scattering and adds smooth twilight/shadow fill. It is not a full angular
multiple-scattering solution: it omits ground-albedo feedback, detailed aerosol multiple scattering and
spectral/ozone structure. The high-resolution numerical reference checks integration of this same closure;
it does not establish geophysical accuracy of the approximation. Single-scattering mode remains available
in the numerical test controls.

The required material set is nonemissive opaque diffuse scenery with distant directional sources. Atmosphere
with point lights, emissive surface lights, mirrors or dielectric/material volumes is rejected explicitly.
Those existing features remain unchanged with atmosphere disabled. CPU temporal reconstruction/budgets retain
the requested settings but are inactive with atmospheric transport; raw accumulation remains available.

The model draws on the optical structure described in [Bruneton's atmospheric scattering documentation](https://ebruneton.github.io/precomputed_atmospheric_scattering/).
It is a separate, simplified RGB implementation, not a port of that full precomputed multiple-scattering model.

## Preparation, memory and invalidation

`AtmosphereTable` stores molecular/aerosol column lengths on a 96-altitude × 192-direction grid (144 KiB).
Altitude and direction coordinates concentrate resolution at the ground and curved horizon. Each entry is
computed with 192 numerical sun-path intervals. CPU and GPU use bilinear interpolation of the same immutable
table. Scattering/absorption coefficients and solar direction are applied after lookup, so changing the clock,
camera or coefficient multipliers does not rebuild it. Changes to planet extent/radius or density scale heights do.

One process-wide cache entry shares the latest table. Each tracing worker or GPU session retains its current
table, so simultaneous sessions with different air do not rebuild tables on every ray. Old tables live only as
long as those bounded owners use them. There is no cache indexed by advancing time. The GPU has one 144 KiB
table payload in its parameter buffer, including when the atmosphere is disabled; disabled rendering bypasses
atmospheric integration. Construction happens in tracing/preparation outside game input/state locks.

Immutable sky/world equality includes all air and solar inputs. Revisions invalidate accumulation after edits,
including undo. The clock updates lighting after completed publications, retaining the existing coherent
preview policy during movement. Image leases, suspension/resume, autofocus and fatal failure handling retain
the renderer contract. Atmospheric input is included in the existing crash report's sky snapshot.

## Verification and reproduction

Run from the repository root with the pinned JDK/GPU dependencies installed:

```powershell
./parity-check.ps1
./atmosphere-check.ps1 -Measurements
```

The second command runs game/atmosphere checks and requires both CPU and Vulkan GPU execution; a missing GPU
is a failure, not substituted CPU coverage. Without `-Measurements`, it renders compact 64×64 fixtures.
With measurements it writes fixed 480×300, depth-2, seed-1, exposure-1 world images at 1 and 4 samples per pixel
for noon (12), low sun (17.8), twilight (18.2), and hazy morning (6.3). The same camera/inputs are used on both
backends. Scene CSV elapsed times include setup, polling and evidence writing; use the separate motion route
for fresh-image performance. Generated raw float reference files remain in `out/atmosphere`; retained PNGs,
CSV files, logs and source/runtime metadata are under [evidence](evidence/).

[thresholds.json](thresholds.json) records error limits chosen before lookup optimization. Sky checks compare
zenith, horizon, low-elevation and solar directions, toward/away azimuths, low/high observers, clear/hazy air,
and noon/low-sun/twilight. Production is compared to 512 view × 384 sun integration; a 256×192 reference must
also converge. The numerical bound is absolute error 0.015 plus 5% of reference radiance per channel;
transmission absolute error is at most 0.015. CPU/GPU image limits are RMSE 0.004 and maximum channel error 0.05.

`AtmosphereNumerics` also checks Rayleigh wavelength ordering, finite nonnegative output, bounded transmission,
vacuum, the analytic short-uniform-segment Beer law, distance/altitude behavior, attenuated sun color, solar-disc
direction and multiple-scattering contribution. A saved 8002-state sweep crosses both horizons, including
signed solar and opposite moon state. `AtmospherePathTest` checks surface brightness against converged segment
transport, solar geometry shadows, diffuse sky illumination against hemisphere quadrature and exact legacy
restoration. `AtmosphereLifecycleTest` checks invalid edits, reset independence, in-flight changes, stable leases,
current-generation pixels against an isolated session, and suspend/resume. Shared conformance runs its session
checks and an atmosphere image on both backends. The unchanged legacy fixture matrix remains in the parity gate.

The route runs four times per backend: one warmup, three measured repetitions, eight seconds each. The first
six seconds move the camera through the aerosol layer while a 30-second day crosses sunrise; at three seconds
the clock is deliberately moved to 17.6, then runs continuously across sunset. The last two seconds hold camera
and time for refinement. It records cold first-image latency, median/p95 fresh-image interval, snapshot age,
heap high-water sampling, trace allocations when available, table preparation/payload and backend diagnostics.
This excludes physical monitor/input latency. No numeric FPS target has been agreed; native human acceptance
of appearance, controls, focus loss/resume, pointer release and responsiveness is still required.

## Recorded results

The retained run used Windows 11, JDK 23.0.1, the reported Intel Family 6 Model 183 CPU with 14 tracing
workers, and an NVIDIA GeForce RTX 3080. These are headless measurements, not native display FPS.
Across the three warmed repetitions at 480×300:

| Backend | Median fresh-image interval (range across runs) | p95 interval | p95 snapshot age |
| --- | --- | --- | --- |
| CPU | 83.5–93.1 ms | 174.1–221.2 ms | 169.8–194.5 ms |
| GPU | 1.28–1.29 ms | 1.60–1.87 ms | 3.10–3.70 ms |

The first cold route image took 785 ms on CPU and 692 ms on GPU, including world/session initialization.
The retained CPU table preparation was 73 ms for 144 KiB. Sampled heap high-water marks reached about
151 MB (CPU process) and 335 MB (GPU process); the final GPU status reports a 10.6 MiB device-resource peak.
Heap sampling is not total process resident memory. GPU trace-allocated-byte fields are unsupported zero
placeholders, not evidence of zero allocation. Clock motion published throughout both horizon crossings,
and the final stationary holds continued accumulating samples.

The worst tested production/reference sky channel error was 0.006931, with the two high-resolution
references differing by at most 0.000108. In the 480×300 CPU/GPU matrix the largest image RMSE was 0.000018
and maximum channel error 0.008805, below the predeclared limits. Inspect the retained PNGs at fixed exposure:
twilight is deliberately dimmer than noon. Numerical checks support the transport/lifecycle implementation;
choosing whether its approximate twilight and haze look good in motion remains the native human acceptance step.
Per-publication route rows can be regenerated in `out/atmosphere/route`; retained route summaries contain the
quantiles above, runtime settings and table preparation metadata.
