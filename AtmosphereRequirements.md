# Atmosphere, haze and local volumetric scattering requirements

Status: A1 and A2 implemented with automatic verification; native human acceptance is pending.
Updated: 2026-10-25
Audience: Sample-game players and developers building and evaluating outdoor lighting and fog.

## Intent and current situation

The sample game's daylight should emerge from light traveling through air: a blue daytime sky,
warm sunrise/sunset illumination, gradual twilight and distant terrain fading into haze. Players
must also be able to explore low-lying mist and local fog with visible shafts of sunlight through
trees and ruins. These effects must respond coherently to the existing day/night clock and to
editable atmospheric properties.

Prerequisite: P1 and P2 of [RendererParityRequirements.md](RendererParityRequirements.md),
updated 2026-10-25, were implemented and automatically verified by commit `2109caf`.
[P1 evidence](benchmarks/parity/README.md) covers the shared inputs, capability validation and
CPU/GPU comparison gate; [P2 evidence](benchmarks/parity/P2.md) covers fatal reports and bounded
shutdown without automatic fallback. Their native human acceptance remains pending; the
implementation and automated contracts are the prerequisite used here.
This document assumes its renderer contracts, common-feature policy, verification and failure
handling are already in place. It does not add parity milestones or re-specify backend management.

Baseline at commit `72d481f`, inspected on 2026-10-25:

- [Sky](src/engine/Sky.java) is a vertical color gradient and optional camera-visible disc.
  [DayNightCycle](src/game/DayNightCycle.java) manually blends colors/intensity as time advances.
  Neither represents air between the camera and the scene.
- [Volume](src/engine/Volume.java) implements homogeneous scalar scattering and a
  Henyey-Greenstein angular distribution. Material absorption is RGB. This supports existing
  enclosed material volumes, not an altitude-dependent atmosphere.
- [WorldSnapshot](src/engine/WorldSnapshot.java) currently rejects directional-sun/sky
  combinations with non-diffuse materials, including those volumes.
- The game already supplies a polygon terrain, trees and ruins, fly controls, focus controls,
  a clock and sky/sun/moon presentation. These provide the entry point and test scenery.

This specification extends outdoor lighting beyond [SampleGameRequirements.md](SampleGameRequirements.md).
The material-volume behavior in [RenderingRequirements.md](RenderingRequirements.md) remains a
separate supported feature with its existing restrictions unless explicitly extended here.

## Scope and decisions

Included: an altitude-dependent global atmosphere; physically motivated sun attenuation and sky
scattering; distant haze; editable local fog regions; spatially varying fog density; sunlight
visibility inside fog; and useful game presets/controls for these effects.

Excluded: weather simulation, condensation/humidity dynamics, wind-driven fluid simulation,
cloud formation/rendering, rain, astronomical lunar phases/eclipses, spectral rendering,
volumetric caustics and physically general combinations with glass/reflective surfaces.
Opaque diffuse scenery is the required interaction set. Preserve the existing night/moon experience;
new astronomical accuracy is not required. General engine persistence/editor authoring for these
new features is deferred; reproducible benchmark settings and live game controls are required.

The requested outcome is simulated optical appearance. A fog preset does not claim to simulate
the meteorological process that created it. RGB wavelength approximations are acceptable.

Engineering direction, subject to measured validation: use precomputed lookup textures for
global atmospheric light transport, with a separate integration method for local fog. Lookup
textures store solutions indexed by altitude and light/view direction; they are not painted sky
images. A different numerical method is acceptable if it satisfies the same correctness,
interaction and performance requirements. Do not force local fog into a table that assumes a
horizontally uniform atmosphere.

## Optical model and shared constraints

### Global atmosphere

Rayleigh scattering represents air molecules redirecting shorter wavelengths more strongly.
Mie-like aerosol scattering represents the directional glow and haze from larger suspended
particles. Absorption removes light rather than redirecting it. Specify separate RGB scattering
and absorption coefficients, altitude-dependent density profiles and angular distributions.
Use Rayleigh's angular distribution for molecular scattering; an explicitly documented
approximation is acceptable for aerosols. Do not implement warm sunsets solely by changing
an RGB sun-color preset.

Model the finite atmosphere and ground/planet horizon sufficiently to compute sunlight's longer
path near the horizon and the ground blocking sunlight below it. Choose and document world-unit
to-meter conversion, reference altitude, atmosphere extent, density scale heights and ground
reference. These are engine inputs, not values secretly tied to terrain mesh dimensions.
Default Earth-like parameters are engineering defaults, not a promise of geophysical accuracy.

The model must support a continuous signed sun elevation through sunrise and sunset. The existing
clock's replacement of the below-horizon sun by an above-horizon moon must not erase the solar
direction needed for twilight. The moon's visible direction and illumination remain separate
from the solar atmosphere calculation. Do not require moonlight to have accurate lunar phases.

Compute the following from compatible atmospheric parameters:

- Background radiance as a function of view direction, solar direction and observer altitude.
- Attenuation/color of sunlight reaching surfaces, and the visible solar disc's attenuation.
- Diffuse sky illumination reaching the terrain, preserving energy accounting.
- Transmittance and in-scattered light between the camera and a surface hit: distant objects
  lose contrast and acquire atmospheric haze; nearby objects should not get a uniform color wash.

Transmittance is the fraction of light remaining along a segment. For a surface result, compose
`observed radiance = transmittance * surface radiance + in-scattered radiance` for the camera
segment. Apply compatible segment treatment to supported indirect transport; never apply the
same extinction twice or add an unrelated ambient term. The solar disc and direct-light sampling
must not count the same solar energy twice.

Include a documented multiple-scattering approximation sufficient for smooth twilight and
reasonable shadow fill. Multiple scattering means light redirected more than once before it
reaches the viewer. A single-scattering reference remains useful for tests; it is not sufficient
evidence that twilight appearance is complete. Ground curvature support does not imply orbital
flight or a planetary-scale game world.

### Local air regions and god rays

A local fog region is a bounded field of participating air, independent of a solid's surface
material or refractive boundary. The camera may enter, leave or start inside it. It must support
RGB scattering/absorption, a bounded scattering-direction parameter, a base density, vertical
falloff and a smooth spatial density variation. An adjustable deterministic procedural density
field is sufficient; arbitrary imported 3D textures and moving fog are not required.

Use a documented world/local coordinate convention so moving/scaling a region carries its density
pattern predictably. Density is finite and nonnegative. Where regions overlap, add their local
scattering/absorption coefficients; account for each component's scattering-direction distribution
without double-counting the background atmosphere. Integrate the combined medium across the
visible segment rather than alpha-blending whole region images in scene-list order.

Sunlight reaching points inside the fog must account for both intervening air extinction and
opaque scene occlusion. A god ray is a visible lit portion of air beside shadowed air; adding
density without this visibility test is insufficient. Use the same scene geometry/transforms
as surface shadows. Moving a tree/occluder or changing sun direction updates the shafts.
Shadows must not stop at arbitrary fog-tile boundaries. At least single scattering with correct
extinction and visibility is required for local fog; high-density multiple scattering is deferred
and its approximation limits must be documented with the supported parameter range.

### Controls, consistency and lifecycle

Expose global atmosphere enable/reset, molecular density multiplier, aerosol density multiplier,
aerosol altitude falloff and absorption controls with units or clearly named dimensionless scales.
Expose local fog enable/reset and region placement/extent, density, height falloff, scattering
direction and spatial variation controls. Exact ranges and widgets are engineering choices;
validate finite values and document supported limits before exposing them. Reject an invalid edit
atomically with a useful message.

Provide at least clear-air, hazy morning and local-mist demonstration presets. Presets specify
all properties needed to reproduce the effect; they do not alter exposure, camera optics or path
depth without showing that change. Keep manual exposure initially so changing exposure cannot
mask a lighting error. Atmosphere reset must not reset camera/scene edits; camera reset must not
change the atmospheric settings. Time changes continue to use the existing clock controls.

With global atmosphere disabled, retain the legacy gradient/disc mode. Local fog can be enabled
independently for isolated testing with the existing directional light. Disabling both restores
the legacy render path. Zero local density is exactly transparent. A zero-density global
atmosphere has no atmospheric scattering/extinction; it need not reproduce the artistic gradient.

Update medium/light state through immutable snapshots. Cache reuse must include all influential
parameters and reject stale asynchronous results after edits, reset or disabling an effect.
Do expensive preparation outside input/state locks. Keep navigation responsive and complete
captured-state previews publishing while the clock moves. No atmosphere cache or fog history may
grow without bound as time advances. Preserve focus/navigation, image ownership, suspension,
resume and shutdown behavior.

## User scenarios and required behavior

| Scenario / action | Required observable result |
| --- | --- |
| Advance from noon through sunset at fixed exposure | Sky hue varies with viewing direction; sun and illuminated ground warm consistently; twilight continues smoothly after sunset |
| Increase aerosol density or descend toward the haze layer | Distant terrain loses contrast; near terrain remains clearer; sunlight/sky changes reflect the same air properties |
| Fly into and out of a local mist bank | Fog appears over the correct distance, including from inside; no rectangular screen overlay or abrupt region-face artifact |
| View sunlight through the forest or ruins in mist | Geometry creates dark air shadows next to lit shafts; rotating the view alone does not move those shadows in world space |
| Change fog placement, density or sun direction during rendering | A coherent new result replaces the previous one; no stale fog/lighting blend survives |
| Disable both effects | Existing game appearance and performance path return without a restart |

## Milestones and status

| ID | User can now... | Prerequisites | Status / evidence |
| --- | --- | --- | --- |
| A1 | Explore a world lit by a scattering atmosphere through golden hour, twilight and altitude-dependent haze | Completed parity specification | Implemented; numerical, surface, lifecycle and CPU/GPU checks plus measured scenes in [A1 evidence](benchmarks/atmosphere/README.md). Native human acceptance pending |
| A2 | Explore editable local mist with spatial density variation and sun shafts through scenery | A1 | Implemented; numerical, ruins imagery, lifecycle and CPU/GPU evidence in [A2 fog evidence](benchmarks/atmosphere/FOG.md). Native human acceptance pending |

### A1 — Explore physically motivated golden hour and atmospheric haze

**User test:**

1. Launch `./game.ps1 -Scene world -Backend gpu -Width 480 -Height 300`, open Lighting and enable
   the new atmosphere. Set fixed exposure and pause the clock at noon.
2. Inspect the sky toward/away from the sun and compare near/far terrain. Step through afternoon,
   sunset and below-horizon twilight, then run the clock continuously across sunrise and sunset.
3. Apply the hazy-morning preset, vary aerosol density/falloff and fly above the layer. Observe
   altitude-dependent haze and consistent surface lighting, without local fog being required.
4. Disable the atmosphere and confirm the legacy sky returns. Exercise edits while moving,
   focus loss/resume and close.

**Work included:** Atmospheric inputs and transport, required reference calculations, sky/direct
lighting/aerial-perspective integration, solar clock semantics, cache lifecycle, live controls,
presets and reproducible quality/performance scenes. These are one usable delivery.

**Acceptance:** Compare representative zenith, horizon and solar directions against a converged
numerical reference at noon, low sun and twilight. Verify wavelength ordering of Rayleigh
scattering, nonnegative finite radiance, transmittance in [0,1], zero-density limits and increasing
optical depth with path length in a uniform test medium. Check the solar disc's direction against
surface shadows. Sweep time across both horizons to catch discontinuities and wrong moon/solar
state. Compare near/far and low/high camera views. Include surface-lighting checks, not just sky
screenshots, and retain legacy images with effects disabled.

The implemented A1 numerical criterion uses 512 view steps and 384 sun steps as its reference,
with a separate 256-by-192 calculation to check convergence. Each reference radiance channel
must change by no more than `0.0005 + 0.01 * reference` when resolution increases. Production
radiance error must be at most `0.015 + 0.05 * reference` per channel, and transmittance absolute
error at most 0.015. The solar disc is tested separately from angular sky comparisons.
These limits were established before optimization and are retained in
[thresholds.json](benchmarks/atmosphere/thresholds.json); the numerical comparison grid and
measured results are in [A1 evidence](benchmarks/atmosphere/README.md).

**Boundaries:** Local fog banks and tree-shaped atmospheric shafts are delivered in A2. Global
haze in A1 does not claim to include local-geometry volumetric shadows. The A1 demo must not depend
on A2 for attractive golden-hour lighting or distant haze.

### A2 — Explore local fog and shadowed light shafts

**User test:**

1. Launch the world and select the local-mist preset, which places fog around existing trees/ruins
   and sets a reproducible low sun. The preset description identifies any changed clock setting.
2. Fly outside, through and above the region; inspect shafts across the sun direction and from
   within the fog. Adjust density, height falloff and spatial variation independently.
3. Use the demo's occluder toggle/movement control to open and close a gap in the sunlight.
   Lit shafts must follow the gap rather than remain painted into the fog.
4. Overlap two regions, reorder their definitions through the test/demo controls and confirm the
   same result. Disable one, then all local fog, while retaining the global atmosphere.

**Work included:** Region inputs, supported density fields, combined-medium integration, sunlight
visibility/extinction, camera-inside handling, controls and a reproducible occluder demonstration.

**Acceptance:** Homogeneous-region transmittance matches `exp(-extinction * distance)` within
declared numerical tolerance. Converged single-scattering tests check brightness as well as
visibility. Validate overlap order independence, independent absorption/scattering controls,
region transforms, camera entry/exit, thin occluders and density-zero identity. Sweep integration
quality to demonstrate convergence; screenshots alone cannot establish correct optical depth.
Confirm that removing the occluder removes its air shadow and that fog does not add a second
unattenuated sun contribution. Existing material-volume tests remain intact.

## Quality, performance and verification

Use the same fixed cameras and parameters for every comparison. Record 480x300, depth 2,
fixed seed/exposure, and both single-frame and stationary-refinement results for noon, low sun,
twilight and the forest/ruins fog demo. Record hardware/runtime, integration/cache settings,
warm-up, repetitions, median/p95 fresh-image interval, snapshot age, preparation cost and peak
memory. Measure cold initialization separately from steady clock motion and stationary rendering.
The route must include camera entry/exit through fog and a continuous sunrise/sunset sweep.

No numerical FPS or overhead budget has been agreed. Performance acceptance requires repeatable
measurements, no publication starvation during continuous edits, bounded memory, and a human
playthrough judging the quality/responsiveness tradeoff. Establish the image-error thresholds
against converged references during each milestone's first implementation slice, record them
before optimization, and do not loosen them to disguise artifacts. This numerical calibration is
an implementation task, not permission to omit the required physical limiting-case tests.

Keep the milestone table authoritative. Save reference settings, images, metrics, exact commands
and test coverage under `benchmarks/atmosphere/` when implemented. The existing repository gate
and relevant rendering/game checks remain required. Distinguish automatically verified behavior
from human acceptance. A1 and A2 implementation and automated evidence are recorded above; human acceptance is not claimed.

No blocking product questions remain. Numerical integration, lookup-table layout, cache sizes and
supported UI ranges are bounded engineering choices to validate against these outcomes. If the
chosen method cannot meet the optical behavior or usable interaction, report that limitation
instead of silently reducing the feature scope.

Reference: [Bruneton's atmospheric scattering model and implementation](https://ebruneton.github.io/precomputed_atmospheric_scattering/index.html)
provides an example of altitude-dependent Rayleigh/aerosol scattering, multiple scattering and
precomputed transport. It is technical background, not a requirement to adopt that implementation.
