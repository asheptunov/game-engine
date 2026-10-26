# A2: editable ruins mist and shadowed sunlight

A2 adds bounded local fog to the CPU and GPU renderers. Fog is independent of solid materials
and of the global atmosphere toggle. The sample game exposes two regions around the existing
ruins and a removable, movable screen for testing shafts of sunlight. Automated checks and
measurements are described below; a native playthrough remains a human acceptance step.

## Try the ruins

Launch `./game.ps1 -Scene world -Backend gpu -Width 480 -Height 300`. Release the pointer with
Escape and open the **Fog** tab. Select **Ruins mist (07:30)**, then **View ruins (moves camera)**.
The first button enables local fog, restores its demonstration parameters, adds the test screen,
sets the shared clock to 07:30 and pauses it. It preserves global-atmosphere settings, exposure,
camera optics and path depth. The separate view button moves the camera to (8, 4, 18), looking
across the columns toward the screen. The region is centered at (20, 3, 28).

Enable the global atmosphere in Lighting if desired. Compare the air around the columns and
screen with **Close sunlight gap** off and on. Move **Screen shift Z** to move the gap and its
shadow. The screen consists of two limestone-colored opaque slabs at X=28, spanning Y=1..11;
the gap spans Z=26..30 before shifting. Closing it inserts a third slab. These are ordinary
scene instances queried by both surface and fog shadow rays.

Fly through and above the mist, or move the region around the camera. Adjust density, height
falloff and variation separately. Enable **Second overlapping region** and edit either region
with **Edit region (1 or 2)**. Reverse their order: their combined image should remain identical.
Disable the second region, then all fog. **Reset local fog** disables both regions, restores
their parameters and removes the demonstration screen; it preserves the clock, global air,
original scenery and camera. Camera reset leaves fog settings alone. The clock and the
Morning/Noon/Evening buttons remain the same shared controls with either sky mode.

The default fog has half extents (13, 5, 12), yaw 0 degrees, density 1, height falloff 1.5,
scattering (0.035, 0.035, 0.035) per world unit, absorption (0.002, 0.002, 0.002) per world unit,
Henyey-Greenstein direction parameter 0.65, variation 0.45, frequency 2 and edge feather 0.2.
The second region uses the same properties centered at (25, 3, 28). Exposure and optics are
not part of this preset. The fixed evidence views use depth 2, seed 1 and exposure 1.

## Inputs and optical calculation

`engine.FogRegion` is immutable. Its box is translated, rotated about world Y, and scaled by
its half extents. The density pattern uses normalized box-local coordinates, so translating,
rotating or scaling the box carries the pattern with it. Height falloff follows local Y.
Scattering and absorption coefficients use inverse **world units**, independently of the global
atmosphere's kilometer coefficients; neither is a painted opacity or a surface material.

Density is the product of a base scale, exponential vertical falloff, smooth boundary feather
and deterministic spatial variation. The variation is a product of three shifted sine waves,
remapped to 0..1 and mixed with uniform density. Height falloff is the exponential exponent
across the complete box height. Feather uses the maximum absolute local coordinate and a
smooth cubic transition to zero at each face. Feather 0 deliberately gives a sharp physical
box boundary for homogeneous reference tests; path length still varies continuously at entry.

Validated engine and UI ranges are:

| Input | Supported range |
| --- | --- |
| Center coordinates | -10000..10000 world units |
| Half extent per axis | 0.25..50 world units |
| Yaw | -180..180 degrees |
| RGB scattering or absorption | 0..0.2 per world unit, independently per channel |
| Base density | 0..4 |
| Height falloff | 0..8 across normalized box height |
| Scattering direction parameter | -0.9..0.9; positive favors forward scattering |
| Spatial variation | 0..1 |
| Pattern frequency | 0..8 in normalized local coordinates |
| Edge feather | 0..0.49 of a half extent |
| Region count | Engine: eight; game editor: two |
| Demonstration screen shift | -10..10 world units along Z |

Nonfinite or out-of-range values fail before replacing the world. An incompatible scene also
rejects the fog preset without changing its enable state or time. Participating air supports
opaque diffuse scenery and distant directional lighting; it does not extend existing material
volumes or introduce refractive fog boundaries. Point lights and emissive surfaces remain
unsupported with participating air.

`Sky.fog` carries canonical, immutable active region definitions into `WorldSnapshot` and render
keys. Zero-density/zero-coefficient regions are omitted, restoring the exact original path when
no participating air remains. Canonical ordering makes overlap sums independent of input order.
At each integration point the renderer adds every region's scattering and absorption coefficients
and retains each region's own angular scattering distribution. It never composites region images.

`FogTransport` splits a visible ray at every intersected box entry/exit and uses 24 midpoint cells
per interval containing fog. Light transmittance uses 24 midpoint cells for each intersected
region. Each cell integrates constant coefficients with the exponential solution, including a
stable small-extinction limit. View and sun ray endpoints are clipped to the actual box boundaries;
there are no screen-space fog tiles. Shadow rays use the same prepared geometry as surface shadows,
including the thin-screen test. Each incident directional source is attenuated by intervening fog
and, when enabled, the global atmosphere. Surface sunlight receives the same local extinction.

Inside fog, one short atmospheric integration supplies effective atmospheric extinction and
in-scattering for each cell; those coefficients combine with the local terms before integrating.
Outside fog, the original A1 atmospheric segment calculation remains in use. This is a local,
world-scale approximation to A1's spherical transport, not planetary volumetric ray marching.
Fog attenuates the distant sky and disc through the view segment. The explicit sun contributes
once to local single scattering; the visible disc is not sampled again as a fog emitter.
A1's approximate atmospheric multiple-scattering fill remains; A2 does not solve multiple
scattering inside dense local fog or cast local-geometry shadows into the entire global atmosphere.

Use the default thin mist for interpretable shafts. Large density/coefficient settings are finite
and supported as edits but produce an optically thick single-scattering approximation, which can
be too dark and lacks diffuse multiple-scattering fill. Very narrow moving shadows and high spatial
frequencies can reveal fixed-step quadrature error. Stationary surface refinement does not increase
the deterministic fog integration resolution. Imported density textures, moving fluids and weather
simulation are outside A2.

## Verification and evidence

Run `./fog-check.ps1 -Measurements` for numerical checks, the game suites, CPU/GPU images and the
motion route. Run `./parity-check.ps1` for repository checks, shared backend conformance and fatal
failure/lifecycle checks. GPU unavailability is a failure for this evidence command, not a fallback.
`fog-thresholds.json` records the numerical limits chosen before optimization.

`FogNumerics` compares homogeneous transmission and single-scattering brightness with independent
exponential formulas; checks individual scattering/absorption and overlapping phase functions;
verifies ordering, translation/rotation/scaling, inside/outside/exit and zero-density behavior;
and compares spatially varying fog at 24 steps with 192 and 384 steps. `FogScenes` checks that a
0.02-unit-thick opaque screen removes the air illumination, removing it restores the exact result,
edits preserve held image leases, the final generation is reproducible, and suspension followed
by disabling fog produces the correct new result. Both backends run those scene checks.
Game tests exercise the actual Fog controls, camera/exposure retention and atomic rejection.
The existing material-volume suites remain in the repository gate.

The fixed 480x300 ruins comparisons cover 07:30, noon, 17:48 and 18:12, with the gap open/closed,
at one and four samples per pixel. Linear CPU/GPU root-mean-square error must remain at most 0.004,
and maximum channel error at most 0.05. Images use identical cameras, seed, depth and exposure.
Numerical logs, CSVs, previews and runtime measurements are saved in `fog-evidence/`.

`FogBenchmark` runs four 32-second routes per backend; run 0 warms up the implementation, followed
by three measured repetitions. The first 24 seconds move through and out of the region while
crossing sunrise, then sunset. The switch between the two clock sweeps at 12 seconds is explicit;
each horizon crossing is continuous. The final eight seconds hold camera and time for refinement.
The original eight-second A1 route was too short to collect representative CPU fog intervals,
so this route uses a longer duration without weakening numerical image thresholds.
It records time to first image, median/p95 publication intervals, p95 snapshot age, sampled heap,
reported trace allocations and final sample count. Device initialization/preparation and peak
GPU payload are included in backend status; the global table payload/preparation is recorded
separately. Fog has fixed worker scratch and eight-region storage, with no time-keyed cache.

Measured on an RTX 3080 and Intel Family 6 Model 183 CPU, Java 23.0.1, Windows 11:

| Warmed route runs 1..3 | CPU | GPU |
| --- | --- | --- |
| Median fresh-image interval | 310.6..315.5 ms | 2.65..2.93 ms |
| p95 fresh-image interval | 2288..2369 ms | 8.27..12.86 ms |
| p95 captured-image age | 2204..2272 ms | 9.34..14.36 ms |
| First image, per-session | 465..547 ms | 208..296 ms |
| Final stationary samples | 45..48 | 3072..3417 |

Cold run-0 first images took 997 ms on CPU and 1448 ms on GPU. Individual warmed fixed-view
GPU traces took 2.47..5.70 ms; CPU traces took about 1.77..2.63 seconds, with a 4.33-second
first-pass outlier. A trace duration and a publication interval measure different parts of the
pipeline. The moving route includes views outside the region, so its median is not a worst-case
fog cost. These measurements establish ongoing publication, not acceptable CPU interactivity.

The maximum linear CPU/GPU image error was 0.03125 and maximum root-mean-square error 0.00010658.
Homogeneous analytical error stayed below 5.6e-7; variable-density transmission error was below
2.9e-5, radiance error below 1.2e-6, and reference radiance change below 3.1e-9. All satisfy the
predeclared thresholds. Sampled heap peaks reached 229 MB on CPU and 385 MB on GPU across the
repeated runs; these include JVM allocations awaiting collection, not just retained fog storage.
See the runtime files for device initialization/preparation, GPU memory and full settings.
The final repository/parity gate output is retained in `fog-evidence/`.
Headless publication measurements exclude a native window and human input. Human acceptance of
appearance and responsiveness remains pending; in particular, CPU fog is much slower than GPU fog.
