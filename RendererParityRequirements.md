# CPU/GPU renderer parity requirements

Status: P1 implemented and automatically verified; human playthrough pending. P2 not started.
Updated: 2026-10-25
Audience: Sample-game players, engine consumers, and developers implementing and verifying rendering changes.

## Intent and current situation

The sample game must remain usable on a CPU when a machine cannot run its GPU renderer.
Choosing a backend must preserve supported scene features and rendering semantics. Developers
need repeatable evidence that changes to either renderer have not broken the other.
This means manual backend selection, not automatic recovery from a failed GPU.

Baseline at commit `72d481f`, inspected on 2026-10-25:

- [RenderEngine](src/engine/RenderEngine.java) selects CPU or GPU explicitly. The default
  overload uses CPU without loading native GPU classes.
- Both backends consume immutable `WorldSnapshot`, `RenderView`, and `RenderSettings`
  values and implement [RenderSession](src/engine/RenderSession.java) and
  [RenderImage](src/engine/RenderImage.java). Image pixels remain valid only while their lease is held.
- CPU transport is implemented in Java; GPU transport is a separate
  [GLSL shader](assets/gpu/diffuse.comp). Shared interfaces do not enforce matching mathematics.
- [GpuKernel](src/gpu/GpuKernel.java) rejects unsupported inputs. Its supported transport is
  narrower than the CPU renderer's. Some game controls currently branch directly on backend name.
- [GpuCheck](tools/gpu/GpuCheck.java) compares raw linear RGB images and tests lifecycle
  behavior. The last local run passed 51 checks on an NVIDIA GeForce RTX 3080. This is evidence
  for those cases and that device, not certification of every input or GPU.
- [verify.ps1](verify.ps1) does not run [gpu-check.ps1](gpu-check.ps1). Device checks are separate.
- GPU worker failures currently retain a message, while the game can suspend rendering and
  remain open. There is no complete fatal-error reporting contract or automatic fallback.

This document governs parity and fatal renderer diagnostics. It supplements the GPU experiment
in [PerformanceRequirements.md](PerformanceRequirements.md) and the application boundary in
[EngineRequirements.md](EngineRequirements.md). Its fatal-error behavior supersedes the older
sample-game behavior that could leave a failed renderer suspended in an open window.

## Scope and decisions

Agreed scope is the existing common game feature set, with mandatory parity for future game
features. Porting every existing CPU feature to GPU is excluded. CPU-only features remain usable
and explicitly identified; they do not become supported on GPU by being exposed in a shared API.

The common game profile is a named, documented set of inputs supported by both backends. Its
initial requirements are:

| Area | Required common behavior |
| --- | --- |
| Worlds | Current `world`, `landscape`, and `gallery` game scenes; shared polygon meshes, transforms, opaque diffuse colors and existing canonical-cube textures |
| Lighting | Zero or one directional light; gradient sky; visible sun/moon disc; day/night updates; existing diffuse sky illumination |
| Camera | Perspective projection, field of view, pinhole and finite aperture, manual focus and existing center autofocus |
| Transport | Depth 0 through 2, deterministic seed semantics, progressive accumulation, samples per batch and target, fixed grids, acceleration enabled |
| Controls and output | Exposure, pause/resume, restart, resizing, scene/camera/lighting edits, navigation and focus lifecycle; existing color conversion and image orientation |
| Ownership | Immutable snapshots, coherent captured-camera previews, image leases, bounded publication storage, cancellation and shutdown |

The profile applies to the worlds as the game constructs them, including its replacement of
the old block-world point lights with directional lighting. It does not promise that every
intermediate asset-builder snapshot is a supported GPU scene.

Existing CPU-only depth ranges, orthographic cameras, point/area lights, reflective and
transmissive materials, material volumes, adaptive resolution and temporal reconstruction
are outside this initial profile. Their existing support restrictions still apply on CPU.
Worker count, tile layout and acceleration diagnostics may be backend-specific execution controls.
The profile does not require identical scheduling, performance, noise at every pixel, or diagnostic counters.

No automatic backend selection, failover, in-session backend switch, or silent feature removal
is included. Keep the existing CPU startup default and explicit `-Backend cpu` / `-Backend gpu`
selection. A user can manually relaunch on CPU. Requested settings must never be quietly changed
to make unsupported GPU input appear supported.

## User scenarios and required behavior

| Starting state / trigger | Required observable result |
| --- | --- |
| Player launches a common-profile scene on CPU or GPU | Same geometry, material meaning, camera framing, lighting and controls; differences are bounded by documented numerical/stochastic tolerances |
| Engine consumer inspects backend support | Structured capabilities and input validation identify unsupported features and combinations, with the offending setting or object and reason |
| Player views settings | Unsupported controls are disabled with a reason from capabilities; supported controls do not disappear because of hard-coded backend-name assumptions |
| User submits an invalid interactive edit | Edit is rejected before publication; the previous valid state remains usable. This ordinary validation failure is not a renderer crash |
| Explicit launch cannot initialize its renderer, or rendering suffers a fatal execution error | Game writes a diagnostic report, releases the pointer, exits with a nonzero status and does not switch renderer |
| Developer changes CPU, GPU, shared input, or game rendering behavior | A documented parity check reports comparable outputs and behavior, or an actionable mismatch; missing device coverage cannot appear as a pass |

## Shared constraints

### Contracts and drift prevention

Capabilities must distinguish hardware availability, implemented features, supported combinations,
and numeric limits. A list of independent feature flags is insufficient when, for example, a
particular lighting/material combination is rejected. Core APIs and settings UI must consume the
same support decisions. Concrete API names and representation are implementation choices.

Retain the public session/image boundary. Do not duplicate game scenes or controls for the two
renderers. Share immutable data, validation, constants and reference calculations where practical;
do not require Java and GLSL to come from one generated source. Independently test important
physical invariants so identical bugs in both implementations cannot pass solely by agreeing.

Use one backend-parameterized conformance suite for session behavior. It must exercise creation,
updates, invalid-input atomicity, completion signals, acquired-image stability, released-image
rejection, accumulation reset/retention, pause/resume, suspension, close and late completions.
Identical inputs and sample counts must retain existing deterministic and batch-equivalence
guarantees within each backend. Cross-backend comparison may use tolerances for floating-point
arithmetic and stochastic sampling; bitwise image identity is not required.

Compare raw linear radiance before display mapping and separately compare displayed orientation,
exposure and color conversion. Each fixture records scene, camera, seed, depth, dimensions, sample
count, output metric and threshold. Reuse existing tolerances as initial regression limits.
New fixtures must justify thresholds with reference calculations or converged measurements.
Include targeted pixels/regions and maximum-error checks where a whole-image average could hide
a missing small feature. Do not widen tolerances simply to accept a regression.

The documented completion gate must include CPU conformance, game checks and device comparisons.
On a machine without a usable GPU, device tests produce an explicit SKIP with reason and a partial
verification summary. A change affecting shared/game/GPU rendering is not fully verified until
device checks pass on an available supported machine at the same revision. Local GPU execution is
sufficient initially; adding hosted CI infrastructure or certifying every vendor is not required.
Record hardware, driver and runtime versions with results.

Future game features extend this profile, capability validation, shared behavior tests and
image/reference fixtures before they are declared complete. Explicitly CPU-only research features
may remain outside the game profile. The atmosphere specification is a future extension governed
by this rule; atmosphere implementation is not a prerequisite for this parity project.

### Fatal failure and post-mortem diagnostics

In the standalone game, an unrecoverable renderer initialization or execution failure is fatal.
Log the first causal failure, notify the application, and terminate with a nonzero exit status.
Bound cleanup so a failed GPU or worker cannot keep the game indefinitely alive. Cleanup errors
are secondary and must not replace the original cause. Engine libraries expose typed failure
information and never terminate the host process themselves.

Write a unique timestamped report under `out/game/crashes/`, alongside the existing launcher
stdout/stderr logs. Print its absolute path to stderr. The report must include:

- Failure phase, timestamp, complete exception/cause stack and available native error codes.
- Selected backend, application revision/build identity, OS/JVM, GPU/device/driver information
  when obtainable, and which initialization steps completed.
- Last requested and last completed render generation, camera, dimensions, seed, sample counts,
  transport/display settings, scene identity and relevant scene-content fingerprints.
- Recent bounded rendering/control events, resource/lifecycle state and available memory/timing
  diagnostics; distinguish known values from unavailable information.
- Reproduction parameters for built-in scenes, including their current day/night and lighting
  state. For arbitrary in-memory scenes, explicitly state when content was not captured and the
  report alone cannot reproduce the scene. Do not claim a fingerprint is a scene export.

Do not depend on a blocking GPU query to produce the report. Keep enough context on the host
before risky work. If the report directory cannot be written, emit the report to stderr and
identify the failed path. Do not dump unrelated environment variables or credentials. Configure
the JVM fatal-error log path and associate available native/JVM crash logs with the run; a hard
OS termination, power loss or corrupted process can prevent final reporting, so maintain a
small startup/run-context record before initializing the renderer. Full memory dumps are not required.

## Milestones and status

| ID | User can now... | Prerequisites | Status / evidence |
| --- | --- | --- | --- |
| P1 | Select either backend for the game with truthful support information, and verify matching behavior through one documented check workflow | Existing renderer baseline | Implemented; automated checks passed 2026-10-25. [Evidence and human test](benchmarks/parity/README.md); human acceptance pending |
| P2 | Diagnose a fatal renderer failure from an exited game and a retained report, without an unexplained frozen window | P1 | Proposed; not implemented or human-accepted |

### P1 — Select and verify equivalent game rendering

**User test:**

1. Run `./game.ps1 -Scene world -Backend cpu -Width 480 -Height 300`, then repeat with `-Backend gpu`.
2. Freeze the clock at the same hour; match exposure, seed, depth, aperture and sample target.
   Explore, stop/refine, change focus and lighting, resize, suspend by changing window focus, then close.
3. Observe matching features and controls. CPU-only settings are clearly identified; rejected
   input preserves the previous scene rather than partially applying an edit.
4. Run the documented combined verification entry point. Its summary identifies passed, failed
   and skipped backends and links fixture outputs and differences.

**Work included:** Capabilities, common-profile definition, UI integration, shared conformance
fixtures, CPU/GPU comparisons, negative support cases and integration into the verification workflow.

**Acceptance:** All common-profile cases pass on CPU and the available supported GPU. Deliberately
perturb a shader or fixture result in an isolated test and confirm the check detects the mismatch.
Test combinations as well as individual flags, small visible discs, mesh seams, extreme supported
camera values and edits during active rendering. CPU-only regression suites still pass. CPU
launch/build must not require installed Vulkan libraries or an available GPU.

**Performance:** Record before/after results on the same machine for a fixed 480x300 world route,
depth 2, fixed seed, with motion followed by stationary refinement. Report median/p95 fresh-image
interval, snapshot age, initialization time, memory and allocations. Reporting/capability work
must not introduce synchronous file I/O into normal frame production or new unbounded buffers.
No FPS equivalence or speedup target is imposed; investigate repeatable regressions beyond measured
run-to-run variation before accepting the milestone.

### P2 — Obtain useful fatal-renderer reports

**User test:** Use a documented, explicitly test-only fault-injection entry point in a child
game process. Trigger GPU initialization failure, GPU execution failure and CPU worker failure.
For each, observe nonzero termination and inspect the report path emitted to stderr. Relaunch
the same built-in scene manually on CPU; no automatic switch should have occurred in the failed run.

**Work included:** Preserve causal errors across worker boundaries, application-level fatal handling,
bounded shutdown, structured context/event capture, report writing and JVM crash-log configuration.

**Acceptance:** Automated child-process tests assert report contents, original cause retention,
nonzero exit, no new renderer startup and bounded shutdown. Choose and document a finite shutdown
deadline in the fault tests; it must not depend on the failed worker returning. Cover report-write
failure, absent device information, failure before the first frame and repeated failure signals.
Window-free automated checks must not open dialogs or steal focus. Manual verification confirms
that the real game releases mouse capture and exits. Validation errors remain recoverable as in P1.

## Verification and tracking

No blocking product decisions remain. Implementation may choose capability API names, report
serialization and the combined check command, provided their behavior above is preserved.
Keep milestone status authoritative in this document. Record commands, revisions, device details,
raw metrics and fixture images in an implementation evidence directory under `benchmarks/parity/`.
Run the repository verification gate and relevant game/device checks. Automated evidence and a
human playthrough are separate acceptance records; neither should be claimed before it occurs.


