package scenes.viewport;

import misc.monads.Result;

import java.util.Map;

/** Help follows command paths; parent pages list children rather than expanding them. */
final class ViewportHelp {
    static final String INDEX =
            "View commands (use view <command> help):\n"
                + "  Scene     preset, reset, camera, status\n"
                + "  Sampling  resolution, depth, samples, seed, target, restart, pause, resume\n"
                + "  Tracing   workers, tile, interactive\n"
                + "  Display   exposure, acceleration, temporal\n"
                + "  Objects   select, copy, remove, move, rotate, scale, mesh\n"
                + "  Material  material, color, type, ior, absorption, roughness, emission\n"
                + "  Volume    scattering, anisotropy\n"
                + "  Lighting  light\n"
                + "Example: view light help -> view light position help";

    private static String page(String usage, String details) {
        return "Usage: view " + usage + "\n  " + details;
    }

    private static final Map<String, String> PAGES =
            Map.ofEntries(
                    Map.entry(
                            "status",
                            page(
                                    "status",
                                    "Show full scene, camera sampling, objects, materials and"
                                            + " lights status.")),
                    Map.entry(
                            "preset",
                            page(
                                    "preset <name>",
                                    "Presets: playground, triangle, bounce-room, glass,"
                                            + " glass-inside,\n"
                                            + "  rough-room, mesh-room, volume-room. Keeps your"
                                            + " resolution.")),
                    Map.entry(
                            "reset",
                            page("reset", "Reload the current preset, retaining resolution.")),
                    Map.entry(
                            "camera",
                            "Camera commands:\n"
                                + "  projection perspective | orthographic\n"
                                + "  mode perspective | orthographic | lens (shortcuts)\n"
                                + "  fov <degrees> | height <units>\n"
                                + "  focus distance | center | mode | source | pull | transition |"
                                + " auto\n"
                                + "  aperture <radius>\n"
                                + "  status | reset\n"
                                + "Use view camera <command> help."),
                    Map.entry(
                            "camera reset",
                            page(
                                    "camera reset",
                                    "Restore camera position and orientation; restart samples.")),
                    Map.entry(
                            "camera status",
                            page(
                                    "camera status",
                                    "Show mode, pose, optics and effective temporal"
                                            + " availability.")),
                    Map.entry(
                            "camera mode",
                            page(
                                    "camera mode perspective | orthographic | lens",
                                    "Shortcuts: perspective/orthographic close the aperture; lens"
                                        + " restores perspective and remembered radius.\n"
                                        + "  Canonical projection changes retain active aperture."
                                        + " First orthographic entry matches at current focus;"
                                        + " later visits restore height.")),
                    Map.entry(
                            "camera projection",
                            page(
                                    "camera projection perspective | orthographic",
                                    "Change projection while preserving pose, aperture and focus"
                                        + " policy.\n"
                                        + "  Orthographic aperture bundles have parallel reference"
                                        + " rays and preserved mean framing.")),
                    Map.entry(
                            "camera fov",
                            page(
                                    "camera fov <1..179 degrees>",
                                    "Vertical field of view in perspective/lens mode. Changes"
                                            + " framing without moving.")),
                    Map.entry(
                            "camera height",
                            page(
                                    "camera height <units>",
                                    "Positive vertical span in orthographic mode. Forward movement"
                                            + " does not magnify.")),
                    Map.entry(
                            "camera aperture",
                            page(
                                    "camera aperture <radius>",
                                    "Nonnegative radius in scene units in either projection. Zero"
                                        + " exactly preserves the reference path.\n"
                                        + "  Larger radii increase defocus; normalized aperture"
                                        + " averaging preserves exposure.\n"
                                        + "  Finite aperture displays raw rendering and disables"
                                        + " temporal-dependent budgets.")),
                    Map.entry(
                            "camera focus",
                            "Focus commands:\n"
                                    + "  distance <units> | center (immediate manual)\n"
                                    + "  mode manual | auto\n"
                                    + "  source screen <u> <v>\n"
                                    + "  pull distance <units> | center | source\n"
                                    + "  transition duration <ms> | curve linear/smooth\n"
                                    + "  auto delay <ms> | tolerance <percent>\n"
                                    + "Use view camera focus <command> help."),
                    Map.entry(
                            "camera focus distance",
                            page(
                                    "camera focus distance <units>",
                                    "Positive axial distance to the perpendicular focus plane. Also"
                                        + " sets first orthographic scale matching.\n"
                                        + "  No focus breathing or physical photographic exposure"
                                        + " model.")),
                    Map.entry(
                            "camera focus center",
                            page(
                                    "camera focus center",
                                    "Immediate manual focus on first surface at (0.5,0.5),"
                                        + " regardless of configured source.\n"
                                        + "  Live query is asynchronous; status shows success, miss"
                                        + " or stale result. Failures retain focus policy/pull.")),
                    Map.entry(
                            "camera focus mode",
                            page(
                                    "camera focus mode manual | auto",
                                    "Auto repeatedly measures the selected screen point, including"
                                            + " glass. Manual freezes realized focus.\n"
                                            + "  Pause, hidden scene and window focus loss suspend"
                                            + " acquisition; console opening does not.")),
                    Map.entry(
                            "camera focus source",
                            page(
                                    "camera focus source screen <u> <v>",
                                    "Finite normalized 0..1 coordinates: u=0 left, v=0 bottom;"
                                        + " default (0.5,0.5).\n"
                                        + "  Independent of resolution. Manual selection edits hold"
                                        + " focus; auto edits freeze and reacquire.")),
                    Map.entry(
                            "camera focus pull",
                            page(
                                    "camera focus pull distance <units> | center | source",
                                    "Take manual control and transition to a distance, center hit,"
                                        + " or configured-source hit.\n"
                                        + "  Miss/stale queries leave policy and pull unchanged;"
                                        + " live query result appears in status.")),
                    Map.entry(
                            "camera focus pull distance",
                            page(
                                    "camera focus pull distance <units>",
                                    "Positive axial target; reciprocal-distance interpolation with"
                                            + " configured duration and curve.")),
                    Map.entry(
                            "camera focus pull center",
                            page(
                                    "camera focus pull center",
                                    "Pull to the first surface at (0.5,0.5), independent of"
                                            + " configured source.")),
                    Map.entry(
                            "camera focus pull source",
                            page(
                                    "camera focus pull source",
                                    "Pull once to the configured screen source; take manual control"
                                            + " only on success.")),
                    Map.entry(
                            "camera focus transition",
                            "Focus transition commands:\n"
                                    + "  duration <0..10000 ms> (default 300; zero snaps)\n"
                                    + "  curve linear | smooth (default smooth)"),
                    Map.entry(
                            "camera focus transition duration",
                            page(
                                    "camera focus transition duration <0..10000 ms>",
                                    "Static targets arrive exactly after active duration."
                                        + " Retargeting restarts from the current value.\n"
                                        + "  Policy edits restart an active pull; pause excludes"
                                        + " inactive time.")),
                    Map.entry(
                            "camera focus transition curve",
                            page(
                                    "camera focus transition curve linear | smooth",
                                    "Interpolate reciprocal distance: linear or smooth"
                                        + " ease-in/ease-out. No velocity continuity guarantee on"
                                        + " retarget.")),
                    Map.entry(
                            "camera focus auto",
                            "Autofocus acquisition commands:\n"
                                    + "  delay <0..2000 ms> (default 150)\n"
                                    + "  tolerance <0..25 percent> (default 1)"),
                    Map.entry(
                            "camera focus auto delay",
                            page(
                                    "camera focus auto delay <0..2000 ms>",
                                    "Consistent new-subject dwell before accepting. Same-subject"
                                            + " depth motion does not restart dwell.")),
                    Map.entry(
                            "camera focus auto tolerance",
                            page(
                                    "camera focus auto tolerance <0..25 percent>",
                                    "Suppress small reciprocal-depth changes against the accepted"
                                        + " target; zero disables suppression.\n"
                                        + "  Miss freezes focus; reacquisition uses normal dwell."
                                        + " Queries are limited to 20Hz with bounded staleness.")),
                    Map.entry(
                            "resolution",
                            "Usage: view resolution <width> <height>\n"
                                + "       view resolution native | half | quarter | <factor>x\n"
                                + "       view resolution <size>\n"
                                + "  Width and height: 64..1600 each. One size selects a square"
                                + " grid.\n"
                                + "  Scales use window dimensions, rounded to nearest pixels.\n"
                                + "  Changes sampling density, not camera field of view or window"
                                + " size.\n"
                                + "Examples:\n"
                                + "  view resolution 800 500\n"
                                + "  view resolution 0.5x"),
                    Map.entry(
                            "depth",
                            page(
                                    "depth <0..32>",
                                    "Maximum path continuations; 0 gives direct lighting only.")),
                    Map.entry(
                            "temporal",
                            page(
                                    "temporal on | off",
                                    "Diffuse-only presentation history, off by default. Off"
                                        + " displays raw radiance immediately.\n"
                                        + "  Raw spp stay unchanged unless the separate motion"
                                        + " budget is enabled.\n"
                                        + "  Reject edges, cuts, edits, mirrors/glass and volume"
                                        + " scenes.\n"
                                        + "  F3 shows history blend and cost. Use view temporal"
                                        + " budget help for tracing reductions.")),
                    Map.entry(
                            "temporal budget",
                            "Temporal motion budget (off by default; requires temporal on):\n"
                                + "  view temporal budget on | off\n"
                                + "  view temporal budget samples <1..8>   Moving batch cap,"
                                + " default 1\n"
                                + "  view temporal budget scale <0.25..1>  Moving grid cap, default"
                                + " 1\n"
                                + "Caps work only during motion; restores requested grid/batch"
                                + " after 350ms.\n"
                                + "Without compatible history, requested samples seed the image (P4"
                                + " still caps at 1).\n"
                                + "Scale reduces detail; history does not upscale it. Axes stay at"
                                + " least 64 pixels.\n"
                                + "With interactive on, its bounds apply and moving batches already"
                                + " cap at 1.\n"
                                + "Volume/no-diffuse scenes bypass this budget. Glass/mirror pixels"
                                + " remain raw.\n"
                                + "At one sample and scale 1 there is no tracing reduction or"
                                + " promised FPS gain."),
                    Map.entry(
                            "temporal budget on",
                            page(
                                    "temporal budget on",
                                    "Enable the separate motion tracing cap; requires temporal on"
                                            + " and a supported scene.")),
                    Map.entry(
                            "temporal budget off",
                            page(
                                    "temporal budget off",
                                    "Restore configured motion work at the next job boundary; P4"
                                            + " remains independent.")),
                    Map.entry(
                            "temporal budget samples",
                            page(
                                    "temporal budget samples <1..8>",
                                    "Fresh samples per moving batch are capped by this and view"
                                            + " samples. Default 1; never below 1.")),
                    Map.entry(
                            "temporal budget scale",
                            page(
                                    "temporal budget scale <0.25..1>",
                                    "Default 1 (full grid). Lower values explicitly trade sharpness"
                                        + " for speed; retain aspect and 64-pixel axes. P4 minimum"
                                        + " bounds apply when interactive is on.")),
                    Map.entry(
                            "interactive",
                            "Interactive resolution commands (off by default):\n"
                                + "  on | off   Reduce sensor work during camera movement\n"
                                + "  target <ms>   Best-effort update budget, default 16.67ms\n"
                                + "  min <width> <height>   Minimum grid bounds, default quarter\n"
                                + "Maximum and stationary grid: view resolution. Aspect and depth"
                                + " stay fixed.\n"
                                + "Returns to requested quality after 350ms without camera"
                                + " changes.\n"
                                + "Minimum bounds are capped by the requested grid; both axes"
                                + " retain its aspect."),
                    Map.entry(
                            "interactive on",
                            page(
                                    "interactive on",
                                    "Enable automatic resolution during motion; spatial detail is"
                                            + " reduced.")),
                    Map.entry(
                            "interactive off",
                            page(
                                    "interactive off",
                                    "Restore requested resolution at the next job boundary.")),
                    Map.entry(
                            "interactive target",
                            page(
                                    "interactive target <1..1000 ms>",
                                    "Best-effort budget for completed moving images; not a promised"
                                            + " FPS.")),
                    Map.entry(
                            "interactive min",
                            page(
                                    "interactive min <width> <height>",
                                    "64..1600 per axis. Fit requested aspect above these bounds,"
                                            + " capped at requested resolution.")),
                    Map.entry(
                            "samples",
                            page(
                                    "samples <1..8>",
                                    "Maximum samples per background batch; retains accumulated"
                                            + " samples.\n"
                                            + "  The display can repeat the previous complete image"
                                            + " while tracing.")),
                    Map.entry(
                            "workers",
                            page(
                                    "workers <count>",
                                    "Persistent tracing workers: 1..min(32, available CPUs)."
                                            + " Retains samples.")),
                    Map.entry(
                            "tile",
                            page(
                                    "tile <1..256>",
                                    "Square tracing tile size in pixels; default 32. Retains"
                                        + " samples.\n"
                                        + "  Smaller tiles allow earlier cancellation after scene"
                                        + " edits or pause.\n"
                                        + "  Camera motion finishes the active pass as a"
                                        + " preview.")),
                    Map.entry(
                            "seed",
                            page(
                                    "seed <integer>",
                                    "Set the deterministic sampling seed; restarts accumulation.")),
                    Map.entry(
                            "target",
                            page(
                                    "target <spp>",
                                    "Stop at this sample count; 0 means continuous sampling.")),
                    Map.entry(
                            "restart",
                            page("restart", "Clear accumulated samples and start again.")),
                    Map.entry("pause", page("pause", "Pause accumulation.")),
                    Map.entry(
                            "resume",
                            page("resume", "Resume accumulation up to the sample target.")),
                    Map.entry(
                            "exposure",
                            page(
                                    "exposure <-16..16>",
                                    "Display exposure in stops; retains samples.")),
                    Map.entry(
                            "acceleration",
                            page(
                                    "acceleration bvh | brute",
                                    "Select traversal mode; restart samples for seeded"
                                            + " comparison.")),
                    Map.entry(
                            "select",
                            page(
                                    "select <object>",
                                    "Select an object for edits. Use view status to list"
                                            + " objects.")),
                    Map.entry(
                            "copy",
                            page(
                                    "copy <unique-name>",
                                    "Copy selected object three units along +Z and select it."
                                            + " Maximum 128 objects.")),
                    Map.entry(
                            "remove",
                            page("remove <object>", "Remove an object; at least one must remain.")),
                    Map.entry(
                            "move",
                            page("move <x> <y> <z>", "Set selected object's absolute position.")),
                    Map.entry(
                            "rotate",
                            page(
                                    "rotate <x> <y> <z>",
                                    "Set selected object's absolute rotation in degrees.")),
                    Map.entry(
                            "scale",
                            page(
                                    "scale <x> <y> <z>",
                                    "Set selected object's absolute scale; all components must be"
                                            + " positive.")),
                    Map.entry(
                            "mesh",
                            "Mesh commands:\n"
                                    + "  detail  Regenerate a shared procedural mesh.\n"
                                    + "Use view mesh detail help."),
                    Map.entry(
                            "mesh detail",
                            page(
                                    "mesh detail <4..64>",
                                    "Regenerate all instances sharing the selected procedural"
                                            + " mesh.")),
                    Map.entry(
                            "material",
                            page(
                                    "material <name>",
                                    "Assign an existing material to the selected object. List names"
                                            + " with view status.")),
                    Map.entry(
                            "color",
                            page(
                                    "color <RRGGBB>",
                                    "Set selected material's sRGB color. Shared material edits"
                                            + " affect all users.")),
                    Map.entry(
                            "type",
                            page(
                                    "type diffuse | mirror | dielectric",
                                    "Set selected shared material's type. Glass requires a closed"
                                            + " sphere, box or mesh.")),
                    Map.entry(
                            "ior",
                            page(
                                    "ior <1..3>",
                                    "Set selected shared material's index of refraction.")),
                    Map.entry(
                            "absorption",
                            page(
                                    "absorption <r> <g> <b>",
                                    "Absorption coefficients: 0..100 per world unit; edits the"
                                            + " shared material.")),
                    Map.entry(
                            "roughness",
                            page(
                                    "roughness <0..1>",
                                    "Mirror/glass roughness; 0 is ideal. Edits the shared"
                                            + " material.")),
                    Map.entry(
                            "emission",
                            page(
                                    "emission <r> <g> <b>",
                                    "Linear radiance: 0..10000. Rectangle materials only.")),
                    Map.entry(
                            "scattering",
                            page(
                                    "scattering <0..100>",
                                    "Density per world unit; dielectric sphere/box interiors"
                                            + " only.")),
                    Map.entry(
                            "anisotropy",
                            page(
                                    "anisotropy <-0.95..0.95>",
                                    "Volume scattering direction bias; 0 is isotropic.")),
                    Map.entry(
                            "light",
                            "Light commands:\n"
                                    + "  position   Set position\n"
                                    + "  color      Set sRGB color\n"
                                    + "  intensity  Set strength\n"
                                    + "  size       Set area emitter dimensions\n"
                                    + "Use view light <command> help."),
                    Map.entry(
                            "light position",
                            page(
                                    "light position <x> <y> <z>",
                                    "Set the active point light or area emitter's position.")),
                    Map.entry(
                            "light color",
                            page(
                                    "light color <RRGGBB>",
                                    "Set the active point light or area emitter's sRGB color.")),
                    Map.entry(
                            "light intensity",
                            page(
                                    "light intensity <value>",
                                    "Set point-light intensity or area-emitter peak radiance.")),
                    Map.entry(
                            "light size",
                            page(
                                    "light size <width> <depth>",
                                    "Area emitters only; positive dimensions. Larger area emits"
                                            + " more power at fixed radiance.")));

    static Result<String, String> help(String... path) {
        if (path.length == 0) return Result.success(INDEX);
        String key = String.join(" ", path);
        String page = PAGES.get(key);
        return page == null
                ? Result.failure("Unknown view help topic: " + key + "; use view help")
                : Result.success(page);
    }

    static String usage(String[] args) {
        if (args.length < 2) return "Use view help.";
        String key = args[1];
        if ((key.equals("light")
                        || key.equals("camera")
                        || key.equals("mesh")
                        || key.equals("interactive")
                        || key.equals("temporal"))
                && args.length > 2
                && PAGES.containsKey(key + " " + args[2])) key += " " + args[2];
        return PAGES.getOrDefault(key, "Use view help.");
    }
}
