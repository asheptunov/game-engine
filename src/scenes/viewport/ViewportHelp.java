package scenes.viewport;

import java.util.Map;
import misc.monads.Result;

/** Help follows command paths; parent pages list children rather than expanding them. */
final class ViewportHelp {
    static final String INDEX = "View commands (use view <command> help):\n"
            + "  Scene     preset, reset, camera, status\n"
            + "  Sampling  resolution, depth, samples, seed, target, restart, pause, resume\n"
            + "  Tracing   workers, tile, interactive\n"
            + "  Display   exposure, acceleration, temporal\n"
            + "  Objects   select, copy, remove, move, rotate, scale, mesh\n"
            + "  Material  material, color, type, ior, absorption, roughness, emission\n"
            + "  Volume    scattering, anisotropy\n"
            + "  Lighting  light\n"
            + "Example: view light help -> view light position help";
    private static String page(String usage, String details) { return "Usage: view " + usage + "\n  " + details; }
    private static final Map<String, String> PAGES = Map.ofEntries(
            Map.entry("status", page("status", "Show full scene, camera sampling, objects, materials and lights status.")),
            Map.entry("preset", page("preset <name>", "Presets: playground, triangle, bounce-room, glass, glass-inside,\n  rough-room, mesh-room, volume-room. Keeps your resolution.")),
            Map.entry("reset", page("reset", "Reload the current preset, retaining resolution.")),
            Map.entry("camera", "Camera commands:\n  reset  Restore the starting camera.\nUse view camera reset help."),
            Map.entry("camera reset", page("camera reset", "Restore camera position and orientation; restart samples.")),
            Map.entry("resolution", "Usage: view resolution <width> <height>\n"
                    + "       view resolution native | half | quarter | <factor>x\n"
                    + "       view resolution <size>\n"
                    + "  Width and height: 64..1600 each. One size selects a square grid.\n"
                    + "  Scales use window dimensions, rounded to nearest pixels.\n"
                    + "  Changes sampling density, not camera field of view or window size.\n"
                    + "Examples:\n  view resolution 800 500\n  view resolution 0.5x"),
            Map.entry("depth", page("depth <0..32>", "Maximum path continuations; 0 gives direct lighting only.")),
            Map.entry("temporal", page("temporal on | off", "Diffuse-only presentation history, off by default. Off displays raw radiance immediately.\n  Raw spp stay unchanged. Reject edges, cuts, edits, mirrors/glass and volume scenes.\n  F3 shows reused/eligible pixels, mean history blend confidence and reconstruction cost.")),
            Map.entry("interactive", "Interactive resolution commands (off by default):\n"
                    + "  on | off   Reduce sensor work during camera movement\n"
                    + "  target <ms>   Best-effort update budget, default 16.67ms\n"
                    + "  min <width> <height>   Minimum grid bounds, default quarter\n"
                    + "Maximum and stationary grid: view resolution. Aspect and depth stay fixed.\n"
                    + "Returns to requested quality after 350ms without camera changes.\n"
                    + "Minimum bounds are capped by the requested grid; both axes retain its aspect."),
            Map.entry("interactive on", page("interactive on", "Enable automatic resolution during motion; spatial detail is reduced.")),
            Map.entry("interactive off", page("interactive off", "Restore requested resolution at the next job boundary.")),
            Map.entry("interactive target", page("interactive target <1..1000 ms>", "Best-effort budget for completed moving images; not a promised FPS.")),
            Map.entry("interactive min", page("interactive min <width> <height>", "64..1600 per axis. Fit requested aspect above these bounds, capped at requested resolution.")),
            Map.entry("samples", page("samples <1..8>", "Maximum samples per background batch; retains accumulated samples.\n  The display can repeat the previous complete image while tracing.")),
            Map.entry("workers", page("workers <count>", "Persistent tracing workers: 1..min(32, available CPUs). Retains samples.")),
            Map.entry("tile", page("tile <1..256>", "Square tracing tile size in pixels; default 32. Retains samples.\n  Smaller tiles allow earlier cancellation after scene edits or pause.\n  Camera motion finishes the active pass as a preview.")),
            Map.entry("seed", page("seed <integer>", "Set the deterministic sampling seed; restarts accumulation.")),
            Map.entry("target", page("target <spp>", "Stop at this sample count; 0 means continuous sampling.")),
            Map.entry("restart", page("restart", "Clear accumulated samples and start again.")),
            Map.entry("pause", page("pause", "Pause accumulation.")),
            Map.entry("resume", page("resume", "Resume accumulation up to the sample target.")),
            Map.entry("exposure", page("exposure <-16..16>", "Display exposure in stops; retains samples.")),
            Map.entry("acceleration", page("acceleration bvh | brute", "Select traversal mode; restart samples for seeded comparison.")),
            Map.entry("select", page("select <object>", "Select an object for edits. Use view status to list objects.")),
            Map.entry("copy", page("copy <unique-name>", "Copy selected object three units along +Z and select it. Maximum 128 objects.")),
            Map.entry("remove", page("remove <object>", "Remove an object; at least one must remain.")),
            Map.entry("move", page("move <x> <y> <z>", "Set selected object's absolute position.")),
            Map.entry("rotate", page("rotate <x> <y> <z>", "Set selected object's absolute rotation in degrees.")),
            Map.entry("scale", page("scale <x> <y> <z>", "Set selected object's absolute scale; all components must be positive.")),
            Map.entry("mesh", "Mesh commands:\n  detail  Regenerate a shared procedural mesh.\nUse view mesh detail help."),
            Map.entry("mesh detail", page("mesh detail <4..64>", "Regenerate all instances sharing the selected procedural mesh.")),
            Map.entry("material", page("material <name>", "Assign an existing material to the selected object. List names with view status.")),
            Map.entry("color", page("color <RRGGBB>", "Set selected material's sRGB color. Shared material edits affect all users.")),
            Map.entry("type", page("type diffuse | mirror | dielectric", "Set selected shared material's type. Glass requires a closed sphere, box or mesh.")),
            Map.entry("ior", page("ior <1..3>", "Set selected shared material's index of refraction.")),
            Map.entry("absorption", page("absorption <r> <g> <b>", "Absorption coefficients: 0..100 per world unit; edits the shared material.")),
            Map.entry("roughness", page("roughness <0..1>", "Mirror/glass roughness; 0 is ideal. Edits the shared material.")),
            Map.entry("emission", page("emission <r> <g> <b>", "Linear radiance: 0..10000. Rectangle materials only.")),
            Map.entry("scattering", page("scattering <0..100>", "Density per world unit; dielectric sphere/box interiors only.")),
            Map.entry("anisotropy", page("anisotropy <-0.95..0.95>", "Volume scattering direction bias; 0 is isotropic.")),
            Map.entry("light", "Light commands:\n  position   Set position\n  color      Set sRGB color\n  intensity  Set strength\n  size       Set area emitter dimensions\nUse view light <command> help."),
            Map.entry("light position", page("light position <x> <y> <z>", "Set the active point light or area emitter's position.")),
            Map.entry("light color", page("light color <RRGGBB>", "Set the active point light or area emitter's sRGB color.")),
            Map.entry("light intensity", page("light intensity <value>", "Set point-light intensity or area-emitter peak radiance.")),
            Map.entry("light size", page("light size <width> <depth>", "Area emitters only; positive dimensions. Larger area emits more power at fixed radiance."))
    );

    static Result<String, String> help(String... path) {
        if (path.length == 0) return Result.success(INDEX);
        String key = String.join(" ", path);
        String page = PAGES.get(key);
        return page == null ? Result.failure("Unknown view help topic: " + key + "; use view help") : Result.success(page);
    }

    static String usage(String[] args) {
        if (args.length < 2) return "Use view help.";
        String key = args[1];
        if ((key.equals("light") || key.equals("camera") || key.equals("mesh") || key.equals("interactive")) && args.length > 2
                && PAGES.containsKey(key + " " + args[2])) key += " " + args[2];
        return PAGES.getOrDefault(key, "Use view help.");
    }
}
