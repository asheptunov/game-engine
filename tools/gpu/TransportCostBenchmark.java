package gpu;

import engine.*;

import game.LightingQualityScene;

import java.nio.file.*;
import java.util.*;

/** Diagnostic ablations: deliberately incomplete lighting, never a playable quality preset. */
public final class TransportCostBenchmark {
    private static final String AIR =
            "airSegment(o.y,d,found ? hit.t : 1e30,!found && depth==0,32,light,transmission);";
    private static final String END =
            "        if (depth == grid.z) break;\n        if (glassIor == -2)";

    public static void main(String[] args) throws Exception {
        // shaderc initializes LWJGL before VulkanDevice can select its normal stack capacity.
        org.lwjgl.system.Configuration.STACK_SIZE.set(512);
        Path directory = Path.of(args[0]);
        Files.createDirectories(directory);
        String source =
                Files.readString(Path.of(args.length > 1 ? args[1] : "assets/gpu/diffuse.comp"))
                        .replace("\r\n", "\n");
        if (!source.contains(AIR) || !source.contains(END))
            throw new IllegalArgumentException("Diagnostic anchors changed; review the ablations");
        var variants = new LinkedHashMap<String, String>();
        boolean integrated = source.contains("bool surviveDiffuse(");
        variants.put("reference", source);
        variants.put("no-atmosphere-integral", source.replace(AIR, AIR.replace(",32,", ",0,")));
        variants.put(
                "primary-atmosphere-only",
                source.replace(AIR, AIR.replace(",32,", ",depth==0?32:0,")));
        String counted =
                integrated
                        ? source
                        : source.replace(
                                "float firstBounceDistance=65504;",
                                "float firstBounceDistance=65504; int diffuseEvents=0;");
        for (int cap : new int[] {1, 2})
            variants.put(
                    "diffuse-" + cap,
                    counted.replace(
                            END,
                            "        if ("
                                    + (integrated ? "diffuseEvents" : "++diffuseEvents")
                                    + " >= "
                                    + cap
                                    + ") break;\n"
                                    + END));
        variants.put("roulette-2", integrated ? source : roulette(source));
        if (args.length > 2) variants.keySet().retainAll(Arrays.asList(args[2].split(",")));
        for (var variant : variants.entrySet()) {
            Path shader = directory.resolve(variant.getKey() + ".comp");
            Files.writeString(shader, variant.getValue());
            CompileShader.main(
                    new String[] {
                        shader.toString(), directory.resolve(variant.getKey() + ".spv").toString()
                    });
        }
        var frames = LightingQualityScene.frames();
        var names = new ArrayList<>(variants.keySet());
        try (var csv = Files.newBufferedWriter(directory.resolve("frames.csv"))) {
            csv.write(
                    "round,variant,state,split,frame,gpuMs,hostMs,reconstructionMs,visibilityMs\n");
            System.out.println(
                    "round,variant,state,split,medianGpuMs,p95GpuMs,medianHostMs,medianReconstructionMs,medianVisibilityMs");
            for (int round = 0; round < 2; round++) {
                if (round == 1) Collections.reverse(names);
                for (String name : names) {
                    try (var kernel = new GpuKernel(() -> {}, directory.resolve(name + ".spv"))) {
                        for (int state : new int[] {7, 11})
                            for (boolean split : new boolean[] {false, true}) {
                                var scene = frames.get(state);
                                var settings =
                                        RenderSettings.defaults()
                                                .withPathDepth(8)
                                                .withTemporal(true, 1)
                                                .withDenoiser(Denoiser.NRD_RELAX)
                                                .withPathRoulette(name.equals("roulette-2"))
                                                .withLightingScale(split ? .5 : 1);
                                var raw = new GpuKernel.Snapshot();
                                var shown = new GpuKernel.Snapshot();
                                int[] packed = new int[split ? 1920 * 1080 : 960 * 540];
                                double[][] values = new double[5][40];
                                kernel.temporal.reset();
                                for (int frame = -20; frame < 40; frame++) {
                                    var view =
                                            ReconstructionBenchmark.moved(
                                                    scene.view().camera(), 960, 540, frame + 20);
                                    var output =
                                            new RenderView(
                                                    view.camera(),
                                                    split ? 1920 : 960,
                                                    split ? 1080 : 540);
                                    kernel.trace(
                                            scene.world(),
                                            view,
                                            settings,
                                            0,
                                            new GpuKernel.Destination(
                                                    null, packed, raw, 1, shown, output));
                                    if (frame < 0) continue;
                                    values[0][frame] = kernel.executeNanos / 1e6;
                                    values[1][frame] = kernel.hostNanos / 1e6;
                                    values[2][frame] = kernel.reconstructionNanos / 1e6;
                                    values[3][frame] = kernel.visibilityNanos / 1e6;
                                    csv.write(
                                            String.format(
                                                    Locale.ROOT,
                                                    "%d,%s,%d,%s,%d,%.6f,%.6f,%.6f,%.6f%n",
                                                    round,
                                                    name,
                                                    state,
                                                    split,
                                                    frame,
                                                    values[0][frame],
                                                    values[1][frame],
                                                    values[2][frame],
                                                    values[3][frame]));
                                }
                                for (var value : values) Arrays.sort(value);
                                System.out.printf(
                                        Locale.ROOT,
                                        "%d,%s,%d,%s,%.4f,%.4f,%.4f,%.4f,%.4f%n",
                                        round,
                                        name,
                                        state,
                                        split,
                                        values[0][19],
                                        values[0][37],
                                        values[1][19],
                                        values[2][19],
                                        values[3][19]);
                                raw.source.close();
                                shown.source.close();
                            }
                    }
                }
            }
        }
    }

    private static String roulette(String source) {
        String helper =
                """
// Independent domain retains the existing light/material random stream.
bool surviveDiffuse(inout vec3 weight, float etaScale, int events, uint depth, uint64_t key) {
    if(events<2) return true;
    vec3 importance=weight*etaScale;
    float p=clamp(max(max(importance.x,importance.y),importance.z),.05,1.0);
    float r=float(mix64(key ^ mix64(uint64_t(depth)+0xd1b54a32d192ed03UL)) >> 40)*(1.0/16777216.0);
    if(r>=p) return false;
    weight/=p;
    return true;
}
""";
        return source.replace("struct Hit {", helper + "\nstruct Hit {")
                .replace(
                        "float u = grid.z",
                        "uint64_t rouletteKey=randomState;\n    float u = grid.z")
                .replace(
                        "float firstBounceDistance=65504;",
                        "float firstBounceDistance=65504; float etaScale=1; int diffuseEvents=0;")
                .replace("weight*=eta*eta;", "weight*=eta*eta; etaScale/=eta*eta;")
                .replace(END, "        diffuseEvents++;\n" + END)
                .replace(
                        "if(all(equal(weight,vec3(0)))) break;",
                        "if(all(equal(weight,vec3(0)))) break;\n"
                            + "           "
                            + " if(!surviveDiffuse(weight,etaScale,diffuseEvents,depth,rouletteKey))"
                            + " break;")
                .replace(
                        "if (all(equal(weight,vec3(0)))) break;",
                        "if (all(equal(weight,vec3(0)))) break;\n"
                            + "       "
                            + " if(!surviveDiffuse(weight,etaScale,diffuseEvents,depth,rouletteKey))"
                            + " break;");
    }
}
