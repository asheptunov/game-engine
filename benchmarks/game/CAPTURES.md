# Capturing and reproducing sample-game problems

The sample game can export a current snapshot or the preceding 30 seconds of diagnostic history. These captures are self-contained folders: a developer or coding agent can inspect settings, restore the world, render a saved view, or run the recorded movement as a regression test. They do not require regenerating the built-in landscape.

## Save and share

Run the game with the current build. Press **F8** for a snapshot or **F9** for recent history. The **Capture** settings tab has matching buttons. Keyboard shortcuts work while a settings widget has focus, and held keys do not repeatedly export. Capturing does not release the pointer or pause movement.

The game writes a uniquely named folder under `out/game/captures/`. Wait for the on-screen **Saved capture:** message; it gives the absolute path and also appears in `out/game/game-stdout.log`. Give that folder path to an agent working in this repository. To transfer a capture elsewhere, zip the entire folder and share it; extract it before using the commands below. Add a short description of the unexpected behavior and what you expected instead. Nothing is uploaded automatically.

Only one export runs at a time. Serialization and image encoding run on a background writer. A failed export reports an error without crashing gameplay. A folder containing `INCOMPLETE` is unfinished and cannot be loaded; keep the game open until saving finishes. A second request during export is rejected rather than queued.

## What is saved

| Content | Meaning |
| --- | --- |
| `summary.txt` | Human-readable build, backend, camera, character, controls, world, displayed-image information and history counts. |
| `capture.properties` | Versioned structured current state and separate displayed-image state. Includes camera direction/optics, character feet/previous position/velocity/grounding, held inputs, movement mode, physics remainder, render settings/seed, exposure, clock, atmospheric properties, fog controls and backend status. |
| `scene-N.properties.gz` | Embedded geometry, instance transforms, materials and opaque texture pixels. Geometry assets are shared within each saved scene. No external asset paths are needed. |
| `displayed.png` | The last rendered image painted by the game panel, without UI overlays or letterboxing. Absent if nothing has been painted yet. |
| `timeline.properties.gz` | Recorded movement updates and world/settings observations. Empty history lists for F8. |
| `history-N.png` | Selected completed renderer images, at most one every four seconds. They may not have been painted by the window. Each has camera, rendering context, sample count and timing metadata in the timeline. |
| `sha256.txt` | File-integrity checksums. Loading rejects altered, missing, or incomplete data. |

The current simulation camera can be ahead of the displayed image. The two are intentionally distinct. Displayed-image metadata refers to the corresponding render generation, not the camera position at export time. If the bounded generation-context cache no longer knows that generation, its world/settings are marked unavailable; its image is still saved and the render command rejects that missing context instead of guessing.

History retains up to 30 seconds of monotonic timestamps, 8,192 changed movement updates, 2,048 world/settings observations, and eight selected images. Capacity pressure can shorten the retained interval; the timeline reports capacity drops cumulatively for the run. Movement records include the first retained checkpoint, effective held inputs/look direction/control changes, actual elapsed time, collision-scene identity, and expected results. Updates that change no navigation state are omitted. World/settings observations are recorded on edits and render updates; history is not a video or an operating-system input log. Image timing metrics are observations of software rendering, not monitor scanout measurements.

The format supports the sample game's scene instances, including polygon meshes, analytic spheres and cube textures. Legacy renderer primitives are rejected explicitly. Walking replay still requires polygon collision geometry. Capture sections together are limited to 96 MiB of decoded structured data, bundles to 256 MiB of checked files on load, and geometry versions to 128. Ordinary landscape captures are well below these bounds. The recorder can retain eight full-resolution images (about 78 MiB of pixel payload at the maximum 1600-square grid), plus bounded state and scene references.

## Inspect, test, render, or reopen

Run these PowerShell commands from the repository root, replacing the example folder with the saved path. They use the existing Java 23 build and do not open a window except for `game.ps1`.

```powershell
./capture.ps1 -Capture 'out/game/captures/EXAMPLE' -Action inspect
./capture.ps1 -Capture 'out/game/captures/EXAMPLE' -Action replay
./capture.ps1 -Capture 'out/game/captures/EXAMPLE' -Action render -View displayed -Backend cpu -Samples 1
./capture.ps1 -Capture 'out/game/captures/EXAMPLE' -Action render -View current -Backend gpu -Samples 16 -Output out/game/reproduced.png
./game.ps1 -Capture 'out/game/captures/EXAMPLE' -Backend gpu
```

`capture.ps1` builds before running. Use `-NoBuild` only when the compiled classes already match the source. Its command log is `out/game/capture-command.log`. Rendering defaults to CPU, the current state, one sample per pixel, and `out/game/capture-render.png`. GPU selection is explicit and requires the existing GPU setup and a supported device; there is no automatic backend fallback. A render has a 120-second timeout.

Opening with `game.ps1 -Capture` restores the current embedded world, camera, physical body, optics and controls. For safe inspection it releases held inputs, clears the sub-tick remainder, stops the day/night cycle and autofocus, and unpauses rendering. The recorded body velocity and grounded state are retained. Click the view to resume movement; re-enable clock/autofocus in settings if desired. The saved window size and tracing grid are restored. This does not replay history in the live window.

`replay` uses the same `GameNavigation.advance` and fixed 120 Hz character physics code as gameplay. It applies recorded input/control checkpoints and collision-scene changes, advances with recorded elapsed times, and checks every resulting navigation/physics state. The first mismatch exits nonzero with its update index, timestamp, expected body/position, and actual state. No-motion captures report **NO MOTION**, not a replay pass. Input/control checkpoints are explicitly restored between simulation updates; this tests movement integration and collisions, not AWT event delivery, asynchronous autofocus acquisition, or the implementation of a settings button. A changed physics implementation can intentionally cause replay divergence; that is useful regression evidence.

Fresh rendering uses saved scene, view, lighting, exposure and transport inputs, but starts a new accumulation. It disables adaptive resolution and temporal reconstruction, uses one sample per batch, and renders the requested sample count. It does not restore GPU scheduling, progressive means, denoising history or driver state. The original screenshot is the evidence of what was shown; exact image reproduction requires compatible code/backend and matching sampling conditions. Build revision and source fingerprint are recorded, so use the matching build first when investigating a report. Engine validation still applies on the receiving machine, including supported features and CPU worker limits.

## Using a capture from a test or an agent

Start with `summary.txt` and `capture.properties`, then use `inspect` to validate the checksums and load embedded content. Read `displayed.png` for the reported visual result. Use `replay` for movement failures and fresh rendering for appearance failures. Keep the original folder intact; put experimental images or reduced fixtures elsewhere. The loader ignores extra files that are not listed in the checksum manifest.

The public Java entry points are `GameCapture.load(Path)`, `CaptureReplay.replay(Loaded)` and `CaptureReplay.render(Loaded, output, backend, displayed, samples)`. `Loaded.world(World)` reconstructs a saved world. `manifest().current()` and `manifest().displayed()` expose the two snapshot states; `timeline()` exposes motion, world/settings observations and historical image contexts. These values can supply a focused test without driving Swing or regenerating a preset. All nested data uses fixed schemas selected by code; input files cannot name arbitrary classes or execute code.

`GameCaptureTest` checks embedded texture/geometry round trips, separate current/displayed states, CPU image reproduction, physics replay, deliberately introduced divergence, full-window bounds, failed exports, corrupted/incomplete bundles, and window-wide shortcut registration. It is included in `./game-check.ps1` and therefore in `./parity-check.ps1`. The displayed-image test also accepts `-Dcapture.backend=gpu` when run with the GPU classpath. Native key delivery and a human playthrough remain separate manual checks.
